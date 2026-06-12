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
    // the sing-box proxy via client() when bypass is on.) GitHub
    // releases/latest/download/ always tracks the newest published release.
    private val MANIFEST_URLS = listOf(
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
                    val mirror = obj.get("mirror_base")?.asString?.let { it + url.substringAfterLast('/') }
                    Update(vc, obj.get("versionName")?.asString ?: "$vc", obj.get("notes")?.asString.orEmpty(), url, mirror)
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
    fun shareApk(context: Context): Boolean = runCatching {
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
