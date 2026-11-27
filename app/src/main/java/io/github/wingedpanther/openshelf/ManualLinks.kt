package io.github.wingedpanther.openshelf

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ManualLink(val repo: String, val pkg: String, val cert: String, val tag: String)

sealed interface LinkResult {
    data class Linked(val link: ManualLink) : LinkResult
    data class Error(val message: String) : LinkResult
}

object ManualLinks {
    private const val PREFS = "manual"
    private const val CACHE_TTL = 6 * 60 * 60 * 1000L

    var runtimeApps by mutableStateOf<List<App>>(emptyList())
        private set

    var links by mutableStateOf<List<ManualLink>>(emptyList())
        private set

    fun init(ctx: Context) {
        links = runCatching {
            val raw = prefs(ctx).getString("links", null) ?: return@runCatching emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ManualLink(o.getString("repo"), o.getString("pkg"), o.getString("cert"), o.getString("tag"))
            }
        }.getOrElse { emptyList() }
    }

    fun has(pkg: String) = links.any { it.pkg == pkg }

    fun byPkg(pkg: String) = links.firstOrNull { it.pkg == pkg }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        links.forEach {
            arr.put(JSONObject().put("repo", it.repo).put("pkg", it.pkg).put("cert", it.cert).put("tag", it.tag))
        }
        prefs(ctx).edit().putString("links", arr.toString()).apply()
    }

    private fun add(ctx: Context, link: ManualLink) {
        links = links.filterNot { it.pkg == link.pkg } + link
        prefs(ctx).edit().remove(cacheKey(link.pkg)).apply()
        persist(ctx)
    }

    fun remove(ctx: Context, pkg: String) {
        links = links.filterNot { it.pkg == pkg }
        prefs(ctx).edit().remove(cacheKey(pkg)).apply()
        persist(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cacheKey(pkg: String) = "cache_$pkg"

    fun parseRepo(input: String): String? {
        val text = input.trim().removeSuffix("/").removeSuffix(".git")
        val match = Regex("(?:https?://)?(?:www\\.)?github\\.com/([^/#?]+)/([^/#?]+)").find(text)
            ?: Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").find(text)
            ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        return if (owner.isBlank() || repo.isBlank()) null else "$owner/$repo"
    }

    suspend fun link(ctx: Context, input: String, expectedPkg: String): LinkResult {
        val repo = parseRepo(input)
            ?: return LinkResult.Error(str(R.string.link_err_url))
        if (links.any { it.repo == repo && it.pkg != expectedPkg }) return LinkResult.Error(str(R.string.link_err_duplicate))
        val rel = GitHub.latest(repo) ?: return LinkResult.Error(if (GitHub.limited) GitHub.limitMessage() else str(R.string.link_err_release))
        if (rel.apks.isEmpty()) return LinkResult.Error(str(R.string.link_err_no_apk))
        val picked = pickApkFromList(rel.apks)
            ?: return LinkResult.Error(str(R.string.link_err_abi))
        val file = File(ctx.cacheDir, "manual-${repo.replace('/', '_')}.apk")
        try {
            downloadTo(picked.url, file)
            val pi = ctx.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: return LinkResult.Error(str(R.string.link_err_no_apk))
            if (pi.packageName != expectedPkg) return LinkResult.Error(str(R.string.link_err_package))
            val fileCerts = Device.archiveCerts(ctx, file)
            val installed = Device.installed(ctx, expectedPkg)
            if (installed == null || installed.certs.intersect(fileCerts).isEmpty()) {
                return LinkResult.Error(str(R.string.link_err_cert))
            }
            val link = ManualLink(repo, expectedPkg, fileCerts.first(), rel.tag)
            add(ctx, link)
            store(ctx, link, rel)
            return LinkResult.Linked(link)
        } catch (e: Exception) {
            return LinkResult.Error(str(R.string.err_generic))
        } finally {
            file.delete()
        }
    }

    internal fun pickApkFromList(apks: List<Apk>, supported: List<String> = android.os.Build.SUPPORTED_ABIS.toList()): Apk? {
        for (abi in supported) {
            apks.firstOrNull { it.abi == abi }?.let { return it }
        }
        return apks.firstOrNull { it.abi == "universal" } ?: apks.firstOrNull { it.abi == "any" }
    }

    private suspend fun downloadTo(url: String, file: File) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val part = File("$file.part")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(file)) throw IOException("save failed")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun apps(ctx: Context): List<App> {
        val result = links.mapNotNull { link ->
            val hit = cached(ctx, link)?.takeIf { System.currentTimeMillis() - it.first < CACHE_TTL }?.second
            val rel = hit ?: GitHub.latest(link.repo)?.also { store(ctx, link, it) } ?: cached(ctx, link)?.second
            rel?.let { buildApp(ctx, link, it) }
        }
        runtimeApps = result
        return result
    }

    fun byId(id: String): App? = runtimeApps.firstOrNull { it.id == id }

    private fun store(ctx: Context, link: ManualLink, rel: ReleaseInfo) {
        val assets = JSONArray()
        rel.apks.forEach {
            assets.put(JSONObject().put("name", it.name).put("abi", it.abi).put("url", it.url).put("size", it.size))
        }
        prefs(ctx).edit().putString(
            cacheKey(link.pkg),
            JSONObject().put("at", System.currentTimeMillis()).put("tag", rel.tag).put("date", rel.date).put("apks", assets).toString(),
        ).apply()
    }

    private fun cached(ctx: Context, link: ManualLink): Pair<Long, ReleaseInfo>? = runCatching {
        val raw = prefs(ctx).getString(cacheKey(link.pkg), null) ?: return null
        val o = JSONObject(raw)
        val assets = o.getJSONArray("apks")
        val apks = (0 until assets.length()).map {
            val a = assets.getJSONObject(it)
            Apk(a.getString("name"), a.getString("abi"), a.getString("url"), a.getLong("size"))
        }
        o.getLong("at") to ReleaseInfo(o.getString("tag"), "", apks, o.optString("date"))
    }.getOrNull()

    private fun buildApp(ctx: Context, link: ManualLink, rel: ReleaseInfo): App {
        val tag = rel.tag
        val installed = Device.installed(ctx, link.pkg)
        val baseCode = installed?.versionCode ?: 0L
        val versionCode = baseCode + if (SelfUpdate.newer(tag.removePrefix("v"), installed?.versionName.orEmpty())) 1 else 0
        return App(
            id = appId(link.repo), repo = link.repo, name = appLabel(ctx, link.pkg), pkg = link.pkg,
            versionCode = versionCode, versionName = tag.removePrefix("v"), minSdk = null,
            room = "Other", note = "", desc = "", maker = link.repo.substringBefore('/'),
            stars = 0, licence = null, archived = false, streams = false,
            version = tag, date = rel.date, certSha256 = link.cert,
            art = AppArt(null, null, null, emptyList()),
            apks = rel.apks, releases = listOf(Release(tag, rel.date)),
            forkOf = null, parentStars = 0,
        )
    }

    fun appId(repo: String) = "manual-${repo.replace('/', '_')}"

    fun addInstalled(ctx: Context, repo: String, pkg: String, cert: String, tag: String) {
        if (Catalog.apps.any { it.pkg == pkg }) return
        add(ctx, ManualLink(repo, pkg, cert, tag))
    }

    private fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0),
        ).toString()
    }.getOrElse { pkg }
}
