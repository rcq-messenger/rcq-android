package app.rcq.android.net

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.rcq.android.BuildConfig
import app.rcq.android.R
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update for the website-distributed APK (there's no Play Store auto-
 * update). On launch the app fetches a small JSON manifest hosted next to the
 * APKs; if its `versionCode` is newer than this build it offers to download the
 * matching per-ABI APK and hand it to the system package installer. The user
 * still confirms the install (sideload installs always require consent) — they
 * just don't have to hunt down + download the file by hand.
 *
 * Hosting (founder): drop `latest.json` + the per-ABI APKs under
 * `https://rcq.app/android/`. On each release bump `versionCode` (must match the
 * build's) and keep the SAME signing key, or the update can't install in place.
 *
 * Manifest shape:
 *   {
 *     "versionCode": 2,
 *     "versionName": "0.2",
 *     "notes": "What changed",
 *     "notes_i18n": { "en": "What changed", "ru": "Что изменилось" },
 *     "url": "https://rcq.app/android/rcq-universal.apk",
 *     "abis": {
 *       "arm64-v8a":   "https://rcq.app/android/rcq-arm64-v8a.apk",
 *       "armeabi-v7a": "https://rcq.app/android/rcq-armeabi-v7a.apk",
 *       "x86_64":      "https://rcq.app/android/rcq-x86_64.apk"
 *     }
 *   }
 */
object UpdateChecker {
    // Manifest is fetched from rcq.app first, then the GitHub-release mirror as a
    // fallback so a blocked/dead rcq.app doesn't kill updates. (Both also ride
    // the sing-box proxy via client() when the relays are on.) GitHub
    // releases/latest/download/ always tracks the newest published release.
    private val MANIFEST_URLS = listOf(
        // dl.rcq.app first: same files, but behind Cloudflare, which is what
        // makes an update downloadable from a network that throttles transit to
        // our Frankfurt droplet (a tester had to turn on a VPN to get the APK
        // at any usable speed). The direct origin stays as the next candidate
        // for anyone the CDN cannot serve.
        "https://dl.rcq.app/android/latest.json",
        "https://rcq.app/android/latest.json",
        "https://github.com/rcq-messenger/rcq-android/releases/latest/download/latest.json",
    )

    /** [mirrorUrl] = the same APK on the GitHub release (byte-identical signed
     *  file); the downloader alternates hosts per attempt, and Range-resume
     *  continues across them, so one blocked host can't stall an update. */
    data class Update(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String, val mirrorUrl: String? = null)

    /** Process-level download state so the download survives navigating away /
     *  closing the dialog and the UI can show a non-blocking progress bar. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        /** 0f..1f, or -1f while the total size is unknown (no Content-Length). */
        data class Active(val progress: Float) : DownloadState
        data object Failed : DownloadState
    }

    // ── "there is a new version" as a fact the UI can watch ────────────────
    //
    // The app asked the manifest exactly once, at launch, and then sat in the
    // background for days without asking again — so a release published on
    // Tuesday was found whenever the user next cold-started, which for a
    // messenger that lives in the background can be a week. Founder, in #520:
    // "приложение само опрашивает сервер с какой-то периодичностью? Можно
    // сделать, чтобы появление обновки было видно, значок рядом с цветком и
    // щитом".
    //
    // So the check now repeats, and what it finds lights a badge in the home
    // header. The DIALOG still appears at most once per launch — an update is
    // not worth interrupting anyone for, and never over a ringing call.
    private val _pending = MutableStateFlow<Update?>(null)
    val pending: StateFlow<Update?> = _pending.asStateFlow()

    /** Poll no more often than this, whoever asks. */
    private const val MIN_CHECK_GAP_MS = 30L * 60 * 1000
    private var lastCheckAt = 0L

    /** Ask the manifest, remember the answer, tell nobody loudly.
     *  @return the update when there is one, which the caller may use for a
     *  one-per-launch prompt. */
    suspend fun refresh(force: Boolean = false): Update? {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < MIN_CHECK_GAP_MS) return _pending.value
        lastCheckAt = now
        val found = check()
        // ⚠ Only a POSITIVE answer moves the badge. `check()` returns null both
        // for "nothing newer" and for "could not ask" — every manifest host
        // unreachable looks exactly like being up to date. Clearing on that
        // would let one flaky poll erase an update the user had already been
        // told about, which is the opposite of the point.
        if (found != null) _pending.value = found
        return found
    }

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    @Volatile private var currentCall: Call? = null
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /** Start a process-level download (no-op if one is already running). It
     *  keeps going if the user closes the dialog or leaves Settings, and the
     *  system installer launches automatically when it finishes. The UI just
     *  observes [downloadState]. */
    fun startDownload(context: Context, update: Update) {
        if (app.rcq.android.BuildConfig.PLAY_STORE) return
        if (downloadJob?.isActive == true) return
        val appCtx = context.applicationContext
        _downloadState.value = DownloadState.Active(-1f)
        downloadJob = downloadScope.launch {
            val ok = downloadAndInstall(appCtx, update) { _downloadState.value = DownloadState.Active(it) }
            _downloadState.value = if (ok) DownloadState.Idle else DownloadState.Failed
        }
    }

    /** Reset a failed state (e.g. when the user dismisses the error). */
    fun clearDownloadError() {
        if (_downloadState.value is DownloadState.Failed) _downloadState.value = DownloadState.Idle
    }

    /** Delete leftover update APKs we no longer need — anything at or below the
     *  version we are already running (a stale install package). These ~100-200MB
     *  files piled up in the cache (founder: "Кэш 2гб") because the reuse path
     *  keeps a downloaded APK; this prunes the OLD ones. A pending NEWER one is
     *  left for reuse. Call on launch. */
    fun cleanupOldApks(context: Context) {
        runCatching {
            File(context.cacheDir, "files").listFiles { f ->
                f.name.startsWith("rcq-update-") && f.name.endsWith(".apk")
            }?.forEach { f ->
                val vc = f.name.removePrefix("rcq-update-").removeSuffix(".apk").toIntOrNull()
                if (vc == null || vc <= BuildConfig.VERSION_CODE) f.delete()
            }
        }
    }

    /** Stop the in-progress download. The partial file is KEPT on disk, so a
     *  later Download resumes from where it left off (HTTP Range) instead of
     *  starting over — answers "where did my previous download go?". */
    fun cancelDownload() {
        currentCall?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    // Route through the censorship transport when it's engaged (the site may be
    // blocked on the same networks the transport exists to pierce).
    //
    // NB: NO callTimeout — that caps the WHOLE call, and a ~200MB APK pulled
    // through a throttled relay easily exceeds any fixed budget (it was 120s,
    // which aborted the download "at half"). We bound only the connect + the
    // per-read GAP, so a slow-but-progressing stream is never killed; a real
    // stall hits readTimeout and the resume/retry loop in downloadAndInstall
    // picks up where it left off.
    private fun client(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        SingBoxTransport.proxy()?.let { b.proxy(it) }
        return b.build()
    }

    /** The hosted update when it's newer than this build, else null. Tries each
     *  manifest host in turn (rcq.app, then the GitHub mirror). */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        // The Play build updates through Play. Nothing to check, nothing to install.
        if (app.rcq.android.BuildConfig.PLAY_STORE) return@withContext null
        for (manifestUrl in MANIFEST_URLS) {
            val u = runCatching {
                val req = Request.Builder().url(manifestUrl).build()
                client().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val obj = JsonParser.parseString(resp.body!!.string()).asJsonObject
                    val vc = obj.get("versionCode")?.asInt ?: return@use null
                    if (vc <= BuildConfig.VERSION_CODE) return@use null
                    val abis = obj.getAsJsonObject("abis")
                    val abiUrl = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> abis?.get(abi)?.asString }
                    val url = abiUrl ?: obj.get("url")?.asString ?: return@use null
                    // mirror_base + the primary URL's filename = the same APK on
                    // the GitHub release (byte-identical), used as a fallback host.
                    //
                    // ⚠⚠ ONLY ADVERTISE `mirror_base` IN THE MANIFEST IF THAT
                    // RELEASE ACTUALLY CARRIES THE APKs AS GITHUB ASSETS. We
                    // publish releases without assets, so every mirror URL was
                    // answering 404, and because the loop below alternates hosts
                    // per attempt that quietly spent HALF of the eight attempts
                    // (plus their backoff) on a host that could never serve.
                    // The field was dropped from the served manifest on
                    // 2026-09-05; a client reading a manifest without it simply
                    // uses the primary host for every attempt, which is what it
                    // was already doing on the attempts that worked.
                    val mirror = obj.get("mirror_base")?.asString?.let { it + url.substringAfterLast('/') }
                    // Release notes in the reader's language.
                    //
                    // ⚠ The app ships in seven languages and this dialog used to
                    // show ONE string to all of them — whatever the person
                    // writing the manifest happened to type, which in practice
                    // was Russian. Caught on a Chinese-locale emulator being
                    // offered 0.123 in Russian.
                    //
                    // `notes_i18n` is optional and `notes` stays the fallback,
                    // so a manifest without it behaves exactly as before and an
                    // older client reading a manifest WITH it is unaffected —
                    // which matters, because the manifest is shared with every
                    // version already installed out there.
                    val notes = obj.getAsJsonObject("notes_i18n")?.let { m ->
                        val tag = java.util.Locale.getDefault().language.lowercase()
                        (m.get(tag) ?: m.get("en"))?.asString
                    } ?: obj.get("notes")?.asString.orEmpty()
                    Update(vc, obj.get("versionName")?.asString ?: "$vc", notes, url, mirror)
                }
            }.getOrNull()
            if (u != null) return@withContext u
        }
        null
    }

    /** Download the APK to cacheDir/files/ and launch the system installer.
     *  Returns false on any failure (network, write, no installer).
     *  [onProgress] reports 0f..1f as bytes arrive (-1f = indeterminate, when the
     *  server sends no Content-Length) so the UI can show a real download bar
     *  instead of a bare spinner. */
    suspend fun downloadAndInstall(
        context: Context,
        update: Update,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val cs = this
        val dir = File(context.cacheDir, "files").apply { mkdirs() }
        val apk = File(dir, "rcq-update-${update.versionCode}.apk")

        // Reuse an already-finished download: if the user cancelled the system
        // install dialog and re-tapped, the APK is still on disk — install it
        // again rather than re-download the whole thing (tester #40).
        // Candidate hosts (primary + GitHub mirror). The signed APKs are
        // byte-identical, so Range-resume continues across hosts.
        val urls = listOfNotNull(update.apkUrl, update.mirrorUrl)
        if (apk.exists()) {
            val expected = urls.firstNotNullOfOrNull { headContentLength(it).takeIf { n -> n > 0 } } ?: -1L
            if (expected > 0) {
                if (apk.length() == expected) { onProgress(1f); return@withContext install(context, apk) }
                if (apk.length() > expected) apk.delete() // corrupt/overshoot → restart
            }
        }
        onProgress(if (apk.length() > 0) 0f else -1f)

        // Resume-on-failure: each attempt requests only the bytes we don't have
        // yet (HTTP Range), appends to the partial file, and retries on a drop.
        // A flaky relay can never lose the whole download — it just continues.
        // Cancel (tester #39) stops the loop but KEEPS the partial for resume.
        val maxAttempts = 8
        for (attempt in 1..maxAttempts) {
            cs.ensureActive()
            val have = if (apk.exists()) apk.length() else 0L
            // Alternate hosts each attempt so a blocked/dead primary fails over
            // to the mirror (and back) while Range-resume keeps the bytes.
            val dlUrl = urls[(attempt - 1) % urls.size]
            val rb = Request.Builder().url(dlUrl)
            if (have > 0) rb.header("Range", "bytes=$have-")
            val done = try {
                val call = client().newCall(rb.build())
                currentCall = call
                call.execute().use { resp ->
                    if (resp.code == 416 && have > 0) {
                        true // Range past EOF → the file is already complete
                    } else {
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        // 206 = our Range honoured (resume); anything else means
                        // we got the whole body, so start the file over.
                        val resuming = resp.code == 206 && have > 0
                        val body = resp.body!!
                        val total = if (resuming) have + body.contentLength() else body.contentLength()
                        if (!resuming && have > 0) apk.delete()
                        var written = if (resuming) have else 0L
                        FileOutputStream(apk, resuming).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var lastPct = -1
                                while (true) {
                                    cs.ensureActive() // cooperative cancel mid-stream
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    if (total > 0) {
                                        val pct = ((written * 100) / total).toInt()
                                        if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                                    }
                                }
                                out.flush()
                            }
                        }
                        // Complete only if we reached the expected size (a dropped
                        // stream returns EOF early → written < total → we retry).
                        total <= 0L || written >= total
                    }
                }
            } catch (c: CancellationException) {
                throw c // a cancel is not a retryable failure
            } catch (e: Throwable) {
                false
            } finally {
                currentCall = null
            }
            if (done) {
                onProgress(1f)
                return@withContext install(context, apk)
            }
            if (attempt < maxAttempts) delay(1500L * attempt) // backoff, then resume
        }
        false
    }

    /** Content-Length of the APK via a cheap HEAD, or -1 if unknown. */
    private fun headContentLength(url: String): Long = runCatching {
        client().newCall(Request.Builder().url(url).head().build()).execute().use {
            it.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }.getOrDefault(-1L)

    /** Share THIS installed app's own APK so anyone who has RCQ can sideload it
     *  to a friend OFFLINE (Bluetooth / Nearby / file / Telegram). This is the
     *  only answer to the FIRST-install bootstrap when rcq.app is blocked: a new
     *  user can't reach the download or the relays (those live inside the app),
     *  so they get the APK hand-to-hand from someone who already has it. */
    /** Share an invite LINK, for the person who does not have RCQ yet.
     *
     *  [shareApk] below exists for a different problem — installing when
     *  rcq.app is blocked — and it hands over a 100MB file, which is not what
     *  anyone sends a friend to say "join me". Until this there was no other
     *  option in the app, and the server has recorded exactly zero referrals in
     *  the project's life while three quarters of accounts hold no contact at
     *  all. The mechanism to connect an invited pair has been finished and
     *  waiting on the server the whole time; nothing ever called it.
     *
     *  The link is the ordinary profile URL, so it already opens the app on a
     *  device that has it (App Links are verified for rcq.app) and lands on the
     *  download page on one that does not. Registering from it names the
     *  inviter, and the island then makes the two of them contacts. */
    fun shareInvite(context: Context, uin: Int): Boolean = runCatching {
        // /r/ rather than /u/: the page behind it says "you are invited"
        // instead of "add to contacts", which is the right sentence for someone
        // who has never heard of RCQ.
        val link = "https://rcq.app/r/$uin"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.invite_share_text, link))
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.invite_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun shareApk(context: Context): Boolean = runCatching {
        if (app.rcq.android.BuildConfig.PLAY_STORE) return false
        val src = File(context.applicationInfo.sourceDir)
        val dir = File(context.cacheDir, "files").apply { mkdirs() }
        val out = File(dir, "RCQ-${BuildConfig.VERSION_NAME}.apk")
        src.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.share_app))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** Hand the finished APK to the system package installer (the user still
     *  confirms the sideload install). */
    private fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
