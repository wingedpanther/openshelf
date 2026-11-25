package io.github.wingedpanther.openshelf

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface Job {
    data class Downloading(val done: Long, val total: Long, val speed: Long = 0) : Job {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        val secondsLeft: Long? get() = if (speed > 0 && total > done) (total - done + speed - 1) / speed else null
    }
    data object Checking : Job
    data object Installing : Job
    data class Failed(val message: String) : Job
    data class Held(val message: String, val cert: String? = null) : Job
}

internal enum class SignatureRule { OK, KEY_CHANGED, SIBLING, OTHER_BUILD }

internal fun signatureRule(catalogCert: String?, fileCerts: Set<String>, installedCerts: Set<String>?, siblingRepo: String?, accepted: String? = null): SignatureRule {
    if (fileCerts.isNotEmpty() && catalogCert != null && catalogCert !in fileCerts && (accepted == null || accepted !in fileCerts)) return SignatureRule.KEY_CHANGED
    if (installedCerts != null && installedCerts.isNotEmpty() && fileCerts.isNotEmpty() && installedCerts.intersect(fileCerts).isEmpty()) {
        return if (siblingRepo != null) SignatureRule.SIBLING else SignatureRule.OTHER_BUILD
    }
    return SignatureRule.OK
}

internal enum class KeyStatus { MATCHES, IGNORED, DIFFERS }

internal fun keyStatus(catalogCert: String?, installedCerts: Set<String>, accepted: String?): KeyStatus = when {
    catalogCert == null || installedCerts.isEmpty() || catalogCert in installedCerts -> KeyStatus.MATCHES
    accepted != null && accepted in installedCerts -> KeyStatus.IGNORED
    else -> KeyStatus.DIFFERS
}

object Installer {
    val jobs = mutableStateMapOf<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun busy(id: String) = jobs[id].let { it is Job.Downloading || it == Job.Checking || it == Job.Installing }

    @Suppress("DEPRECATION")
    fun canInstall(ctx: Context) = if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

    fun openInstallPermission(ctx: Context) {
        val intent = (if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            else Intent(Settings.ACTION_SECURITY_SETTINGS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun start(ctx: Context, app: App) = Lock.guard { begin(ctx, app) }

    fun startFile(ctx: Context, app: App, file: File) = Lock.guard {
        val appCtx = ctx.applicationContext
        if (busy(app.id) || !allowed(ctx, app.id)) return@guard
        jobs[app.id] = Job.Checking
        scope.launch {
            try {
                place(appCtx, app, file, ask = true)
            } catch (e: Exception) {
                jobs[app.id] = Job.Failed(e.message ?: str(R.string.err_generic))
            }
        }
    }

    private fun allowed(ctx: Context, id: String): Boolean {
        if (canInstall(ctx)) return true
        val asked = (jobs[id] as? Job.Failed)?.message == str(R.string.err_allow_install)
        jobs[id] = Job.Failed(if (asked) str(R.string.err_allow_install_stuck, ctx.packageName) else str(R.string.err_allow_install))
        openInstallPermission(ctx)
        return false
    }

    private fun room(ctx: Context, id: String, file: File, size: Long): Boolean {
        val part = File(file.path + ".part")
        val need = spaceNeeded(size, maxOf(file.length(), part.length()))
        if (Device.freeBytes(ctx) < need) {
            val keep = jobs.filter { busy(it.key) }.keys
            Device.cacheDir(ctx).listFiles()
                ?.filter { f -> f != file && f != part && keep.none { f.name.startsWith("$it-") } }
                ?.forEach { it.delete() }
        }
        val free = Device.freeBytes(ctx)
        if (free >= need) return true
        jobs[id] = Job.Failed(str(R.string.err_space, Fmt.size(need), Fmt.size(free)))
        return false
    }

    private fun begin(ctx: Context, app: App) {
        val appCtx = ctx.applicationContext
        if (busy(app.id) || !allowed(ctx, app.id)) return
        val apk = pickApk(app)
        val file = File(Device.cacheDir(appCtx), "${app.id}-${app.version}-${apk.abi}.apk")
        if (!room(appCtx, app.id, file, apk.size)) return
        jobs[app.id] = Job.Downloading(0, apk.size)
        scope.launch {
            try {
                download(appCtx, apk, file) { done, total, speed -> jobs[app.id] = Job.Downloading(done, total, speed) }
                place(appCtx, app, file)
            } catch (e: Exception) {
                jobs[app.id] = Job.Failed(e.message ?: str(R.string.err_generic))
            }
        }
    }

    private suspend fun place(appCtx: Context, app: App, file: File, ask: Boolean = false) {
        jobs[app.id] = Job.Checking
        val target = if (app.pkg.isNotEmpty()) app else withContext(Dispatchers.IO) {
            val pi = appCtx.packageManager.getPackageArchiveInfo(file.path, 0) ?: throw IOException(str(R.string.err_incompatible))
            Device.archiveCerts(appCtx, file).firstOrNull()?.let { direct[app.id] = ManualLink(app.repo, pi.packageName, it, app.version) }
            Direct.found(app.id, pi.packageName, androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi))
            app.copy(pkg = pi.packageName)
        }
        val held = withContext(Dispatchers.IO) { checkSignature(appCtx, target, file) }
        if (held != null) {
            jobs[app.id] = Job.Held(held.first, held.second)
            return
        }
        jobs[app.id] = Job.Installing
        val session = try {
            withContext(Dispatchers.IO) { commit(appCtx, target, file, ask) }
        } catch (e: Exception) {
            if (!handOff(appCtx, app.id, file)) throw e
            null
        }
        if (session != null) {
            current[app.id] = session
            delay(INSTALL_TIMEOUT)
            fun waiting() = current[app.id] == session && session !in answered && jobs[app.id] == Job.Installing
            while (waiting() && !onScreen) delay(RECHECK)
            if (waiting()) {
                runCatching { appCtx.packageManager.packageInstaller.abandonSession(session) }
                failed(app.id, str(R.string.err_install_timeout))
                settled(appCtx, app.id)
            }
        }
    }

    private const val MAX_TRIES = 3
    private const val INSTALL_TIMEOUT = 90_000L
    private const val RECHECK = 5_000L
    var onScreen = false
    private val current = HashMap<String, Int>()
    private val answered = HashSet<Int>()

    fun answered(session: Int) {
        answered.add(session)
    }

    private class NonRetryable(msg: String) : IOException(msg)

    private suspend fun download(ctx: Context, apk: Apk, file: File, progress: (Long, Long, Long) -> Unit) {
        if (file.exists() && file.length() == apk.size) return
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        var attempt = 0
        while (true) {
            try {
                downloadAttempt(ctx, apk, part, progress)
                if (!part.renameTo(file)) throw IOException(str(R.string.err_save))
                return
            } catch (e: Exception) {
                if (++attempt >= MAX_TRIES || e is NonRetryable) throw e
                delay(500L * (1L shl (attempt - 1)))
            }
        }
    }

    private suspend fun downloadAttempt(ctx: Context, apk: Apk, part: File, progress: (Long, Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val have = part.length()
            val conn = (URL(apk.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "OpenShelf (+https://github.com/wingedpanther/openshelf)")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val resume = when {
                    code == HttpURLConnection.HTTP_PARTIAL -> true
                    code in 200..299 -> false
                    code == 416 -> { part.delete(); throw IOException(str(R.string.err_download_http, code)) }
                    code in 500..599 || code == 408 || code == 429 -> throw IOException(str(R.string.err_download_http, code))
                    else -> throw NonRetryable(str(R.string.err_download_http, code))
                }
                val base = if (resume) have else 0L
                if (!resume) part.delete()
                val remaining = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val total = when {
                    remaining > 0 && resume -> base + remaining
                    remaining > 0 -> remaining
                    else -> apk.size
                }
                var done = base
                var lastShown = base
                var shownAt = System.nanoTime()
                var speed = 0.0
                conn.inputStream.use { input ->
                    FileOutputStream(part, resume).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            val seconds = (now - shownAt) / 1e9
                            if (seconds >= 0.5) {
                                val recent = (done - lastShown) / seconds
                                speed = if (speed == 0.0) recent else speed * 0.7 + recent * 0.3
                                lastShown = done
                                shownAt = now
                                val d = done
                                val s = speed.toLong()
                                withContext(Dispatchers.Main) { progress(d, total, s) }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun checkSignature(ctx: Context, app: App, file: File): Pair<String, String?>? {
        val fileCerts = Device.archiveCerts(ctx, file)
        val installed = Device.installed(ctx, app.pkg)
        val sibling = installed?.let { Catalog.sibling(app, it) }
        return when (signatureRule(app.certSha256, fileCerts, installed?.certs, sibling?.repo, Prefs.acceptedKeys[app.pkg])) {
            SignatureRule.OK -> null
            SignatureRule.KEY_CHANGED -> {
                val key = fileCerts.first()
                str(R.string.held_key_changed, app.name) to key.takeIf { c -> signatureRule(app.certSha256, fileCerts, installed?.certs, sibling?.repo, c) == SignatureRule.OK }
            }
            SignatureRule.SIBLING -> str(R.string.held_sibling, app.name, sibling!!.repo) to null
            SignatureRule.OTHER_BUILD -> str(R.string.held_other_build, app.name) to null
        }
    }

    private fun commit(ctx: Context, app: App, file: File, ask: Boolean): Int {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (app.pkg.isNotEmpty()) setAppPackageName(app.pkg)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31 && !ask) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).putExtra("appId", app.id).putExtra("apk", file.path).putExtra("session", sessionId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, sessionId, intent, flags).intentSender)
        }
        return sessionId
    }

    fun handOff(ctx: Context, id: String, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.apks", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
        jobs.remove(id)
    }.isSuccess

    private var confirming: String? = null
    private var confirmingSince = 0L
    private val waiting = ArrayDeque<Intent>()

    fun confirm(ctx: Context, result: Intent) {
        val id = result.getStringExtra("appId") ?: return
        if (confirming != null && confirming != id && System.currentTimeMillis() - confirmingSince < 120_000) {
            waiting.addLast(result)
            return
        }
        confirming = id
        confirmingSince = System.currentTimeMillis()
        val confirm = if (Build.VERSION.SDK_INT >= 33) {
            result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") result.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val shown = confirm != null && runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!shown) {
            val apk = result.getStringExtra("apk")?.let(::File)
            if (apk == null || !handOff(ctx, id, apk)) failed(id, str(R.string.err_blocked))
            settled(ctx, id)
        }
    }

    fun settled(ctx: Context, id: String) {
        if (confirming != id) return
        confirming = null
        waiting.removeFirstOrNull()?.let { confirm(ctx, it) }
    }

    private val direct = HashMap<String, ManualLink>()

    fun finished(ctx: Context, id: String) {
        jobs.remove(id)
        direct.remove(id)?.let { ManualLinks.addInstalled(ctx, it.repo, it.pkg, it.cert, it.tag) }
        Device.tick++
    }

    fun failed(id: String, message: String) {
        jobs[id] = Job.Failed(message)
        Device.tick++
    }

    fun cancelled(id: String) {
        if (jobs[id] == Job.Installing) jobs.remove(id)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("appId") ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Installer.answered(intent.getIntExtra("session", -1))
            Installer.settled(ctx, id)
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Installer.confirm(ctx, intent)
            PackageInstaller.STATUS_SUCCESS -> Installer.finished(ctx.applicationContext, id)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.cancelled(id)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Installer.failed(id, str(R.string.err_conflict))
            PackageInstaller.STATUS_FAILURE_STORAGE -> Installer.failed(id, str(R.string.err_storage))
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Installer.failed(id, str(R.string.err_incompatible))
            else -> Installer.failed(id, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: str(R.string.err_generic))
        }
    }
}
