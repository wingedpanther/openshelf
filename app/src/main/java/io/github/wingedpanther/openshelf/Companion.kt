package io.github.wingedpanther.openshelf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

object Companion {
    private const val FIRST_PORT = 8642
    private const val MAX_UPLOAD = 2_000_000_000L
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: ServerSocket? = null
    private var token = ""

    var url by mutableStateOf<String?>(null)
        private set
    val asked = mutableStateListOf<String>()
    val uploads = mutableStateMapOf<String, App>()

    fun byId(id: String): App? = Catalog.byId(id) ?: Direct.byId(id) ?: uploads[id]

    fun start(ctx: Context): String? {
        url?.let { return it }
        val ip = localIp() ?: return null
        val socket = (FIRST_PORT until FIRST_PORT + 10).firstNotNullOfOrNull { runCatching { ServerSocket(it) }.getOrNull() } ?: return null
        val app = ctx.applicationContext
        token = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
        server = socket
        thread(name = "companion", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use { handle(app, it) } } }
            }
        }
        url = "http://$ip:${socket.localPort}/?t=$token"
        return url
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        url = null
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun handle(ctx: Context, client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 8192) return
            head.append(b.toChar())
        }
        val lines = head.lines()
        val (method, target) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val body = if (method != "POST" || target.startsWith("/api/upload")) "" else if (length in 1..4096) String(ByteArray(length.toInt()).also { var n = 0; while (n < it.size) { val r = input.read(it, n, it.size - n); if (r < 0) break; n += r } }) else ""
        val path = target.substringBefore('?')
        val query = params(target.substringAfter('?', "")) + params(body)
        val out = client.getOutputStream()
        if (path == "/" && method == "GET") return send(out, 200, "text/html; charset=utf-8", ctx.assets.open("companion.html").use { it.readBytes() })
        if (query["t"] != token) return send(out, 403, "application/json", """{"error":"token"}""".toByteArray())
        when (path) {
            "/api/search" -> send(out, 200, "application/json", search(ctx, query["q"].orEmpty()).toString().toByteArray())
            "/api/install" -> send(out, 200, "application/json", install(ctx, query).toString().toByteArray())
            "/api/status" -> send(out, 200, "application/json", status(ctx).toString().toByteArray())
            "/api/upload" -> send(out, 200, "application/json", upload(ctx, input, length).toString().toByteArray())
            "/api/github" -> send(out, 200, "application/json", github(query["token"]).toString().toByteArray())
            else -> send(out, 404, "application/json", """{"error":"not found"}""".toByteArray())
        }
    }

    private fun params(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun entry(ctx: Context, a: App): JSONObject {
        val installed = Device.installedAs(ctx, a)
        val job = Installer.jobs[a.id]
        val state = when {
            job is Job.Downloading -> "downloading ${(job.fraction * 100).toInt()}%"
            job == Job.Checking || job == Job.Installing -> "installing"
            job is Job.Failed -> "failed"
            job is Job.Held -> "held"
            installed != null && installed.versionCode < a.versionCode -> "update"
            installed != null -> "installed"
            !a.runsHere -> "unsupported"
            else -> ""
        }
        return JSONObject().put("id", a.id).put("code", a.code ?: JSONObject.NULL).put("name", a.name).put("note", a.note)
            .put("icon", a.art.icon ?: JSONObject.NULL).put("stars", a.stars).put("state", state)
            .put("size", pickApk(a).size)
    }

    private fun github(token: String?): JSONObject {
        if (token == null) return JSONObject().put("saved", Prefs.githubToken.isNotEmpty())
        val t = token.trim()
        if (t.isEmpty()) {
            main.launch { Prefs.saveGithubToken("") }
            return JSONObject().put("ok", true).put("saved", false)
        }
        val limit = runCatching {
            val conn = java.net.URL("${GitHub.API}rate_limit").openConnection() as java.net.HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Authorization", "Bearer $t")
                if (conn.responseCode == 401) 0 else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .getJSONObject("resources").getJSONObject("core").getInt("limit")
            } finally {
                conn.disconnect()
            }
        }.getOrElse { return JSONObject().put("error", "net") }
        if (limit <= 60) return JSONObject().put("error", "bad")
        main.launch {
            Prefs.saveGithubToken(t)
            GitHub.limitedUntil = 0L
        }
        return JSONObject().put("ok", true).put("saved", true).put("limit", limit)
    }

    private fun search(ctx: Context, q: String): JSONArray {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val codes = Regex("\\d{5}").findAll(q).map { it.value }.toList()
        val hits = when {
            codes.isNotEmpty() -> codes.mapNotNull { Catalog.byCode(it) }
            words.isEmpty() -> Catalog.visible.take(30)
            else -> Catalog.apps.filter { a ->
                val hay = "${a.name} ${a.note} ${a.desc} ${a.room} ${a.repo}".lowercase()
                Catalog.shown(a) && words.all { hay.contains(it) }
            }.sortedWith(compareByDescending<App> { it.name.lowercase().contains(q.lowercase()) }.thenByDescending { it.stars }).take(30)
        }
        return JSONArray(hits.map { entry(ctx, it) })
    }

    private fun install(ctx: Context, p: Map<String, String>): JSONObject {
        val code = p["code"]
        val repo = p["repo"]?.let { Direct.repoOf(it) }
        if (code != null) {
            val app = Catalog.byCode(code) ?: return JSONObject().put("ok", false).put("error", "unknown code")
            main.launch { asked.add(app.id); Installer.start(ctx, app) }
            return JSONObject().put("ok", true).put("name", app.name)
        }
        if (repo != null) {
            main.launch {
                val app = Direct.lookup(repo)
                if (app != null) {
                    asked.add(app.id)
                    Installer.start(ctx, app)
                }
            }
            return JSONObject().put("ok", true).put("name", repo)
        }
        return JSONObject().put("ok", false).put("error", "nothing to install")
    }

    private fun status(ctx: Context): JSONArray =
        JSONArray(asked.distinct().mapNotNull { id -> byId(id)?.let { entry(ctx, it) } })

    private fun upload(ctx: Context, input: InputStream, length: Long): JSONObject {
        fun no(why: String) = JSONObject().put("ok", false).put("error", why)
        if (length <= 0 || length > MAX_UPLOAD) return no("size")
        if (Device.freeBytes(ctx) < spaceNeeded(length, 0)) return no("space")
        val dir = Device.cacheDir(ctx).apply { mkdirs() }
        val incoming = File(dir, "incoming-${System.nanoTime()}.apk")
        var left = length
        runCatching {
            incoming.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
        if (left > 0) { incoming.delete(); return no("cut") }
        val pm = ctx.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(incoming.path, 0) }.getOrNull()
        if (info == null) { incoming.delete(); return no("apk") }
        info.applicationInfo?.apply { sourceDir = incoming.path; publicSourceDir = incoming.path }
        val label = info.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }?.takeIf { it.isNotBlank() } ?: info.packageName
        val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        val app = uploaded(info.packageName, label, code, info.versionName, length)
        val file = File(dir, "${app.id}-$code.apk")
        file.delete()
        if (!incoming.renameTo(file)) { incoming.delete(); return no("cut") }
        main.launch {
            uploads[app.id] = app
            asked.add(app.id)
            Installer.startFile(ctx, app, file)
        }
        return JSONObject().put("ok", true).put("name", label)
    }

    private fun uploaded(pkg: String, label: String, code: Long, versionName: String?, size: Long) = App(
        id = "upload-$pkg", repo = "", name = label, pkg = pkg, versionCode = code, versionName = versionName, minSdk = null,
        room = "Other", note = str(R.string.upload_note), desc = "", maker = "", stars = 0, licence = null, archived = false, streams = false,
        version = versionName ?: code.toString(), date = java.time.LocalDate.now().toString(), certSha256 = null,
        art = AppArt(null, null, null, emptyList()), apks = listOf(Apk("$pkg.apk", "any", "", size)), releases = emptyList(),
        forkOf = null, parentStars = 0,
    )
}
