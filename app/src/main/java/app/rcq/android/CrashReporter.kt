package app.rcq.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash capture for the website-distributed APK (no Play Store, so no Play
 * crash reporting). Installs a default uncaught-exception handler that writes
 * the stack trace + device/app info to a file, then chains to the previous
 * handler so the system still tears the process down normally. On the next
 * launch [Session.start] auto-submits the saved report through the regular
 * bug-report channel and clears it, so a crash needs no adb and no user steps.
 *
 * Caveat: this catches JVM Throwables, including OutOfMemoryError. A pure
 * NATIVE crash (SIGSEGV/SIGABRT in C/C++, e.g. some ImageDecoder paths) bypasses
 * the JVM and is NOT captured here — those still need `adb logcat` or a device
 * bug report.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val CRUMB = "launch_crumb.txt"
    private const val MAX = 16_000

    // Native-crash breadcrumb. The handler above catches JVM crashes; a pure
    // native crash (SIGSEGV/SIGABRT, e.g. some ImageDecoder paths) leaves
    // nothing. So we drop a breadcrumb at each startup checkpoint via [crumb];
    // if the NEXT launch finds one still set (the app never reached
    // [launchComplete]), the previous launch died during startup — almost
    // certainly natively — and [checkPreviousLaunch] synthesises a report
    // naming the last stage reached.
    //
    // The heuristic only holds while the app is in the FOREGROUND danger
    // window, so [launchComplete] fires from three places and the earliest
    // wins: HomeScreen composed + 3s; 8s after MainActivity.onCreate (entries
    // that never compose home, e.g. notification straight into a chat); and
    // the app going to background (a kill while backgrounded is a normal OS
    // reclaim, not a crash). Arming happens in MainActivity.onCreate — never
    // in Application.onCreate, which also runs for headless process warmups.
    @Volatile private var crumbOff = false

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appCtx, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No prior handler (effectively never on Android) — make sure the
                // process still dies instead of hanging in a broken state.
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val report = buildString {
            append("RCQ crash\n")
            append("app: ${BuildConfig.VERSION_NAME} (vc ${BuildConfig.VERSION_CODE})\n")
            append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("thread: ${thread.name}\n\n")
            append(sw.toString())
        }
        File(context.filesDir, FILE).writeText(report.take(MAX))
    }

    /** Record the current startup stage (native-crash breadcrumb). Cheap; a
     *  small file written at a handful of checkpoints. No-op once running. */
    fun crumb(context: Context, stage: String) {
        if (crumbOff) return
        runCatching { File(context.applicationContext.filesDir, CRUMB).writeText(stage) }
    }

    /** Past the startup danger zone — stop tracking and clear the breadcrumb so
     *  a normal later kill (swipe-away, OS reclaim) isn't reported as a crash. */
    fun launchComplete(context: Context) {
        crumbOff = true
        runCatching { File(context.applicationContext.filesDir, CRUMB).delete() }
    }

    /** Call FIRST in Application.onCreate. If the previous launch left a
     *  breadcrumb (never reached [launchComplete]) AND there's no JVM crash
     *  report (which would be richer), synthesise a "suspected native crash"
     *  report naming the last stage — [Session.start]'s existing path submits
     *  it. This is how the launch-time native crash gets diagnosed with no adb. */
    fun checkPreviousLaunch(context: Context) {
        val appCtx = context.applicationContext
        val crumbFile = File(appCtx.filesDir, CRUMB)
        val stage = runCatching { if (crumbFile.exists()) crumbFile.readText() else null }.getOrNull()
        runCatching { crumbFile.delete() }
        if (stage.isNullOrBlank()) return
        // A JVM crash already wrote a real stack — don't clobber it.
        if (File(appCtx.filesDir, FILE).exists()) return
        // Android 11+ keeps a tombstone for the previous process exit. When it
        // was a NATIVE crash this names the actual signal + backtrace — far more
        // useful than the breadcrumb, which only says which startup STAGE we
        // reached (e.g. every post-drain crash collapses onto "drain_done").
        val exit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            runCatching { recentExitInfo(appCtx) }.getOrNull() else null
        val report = buildString {
            append("RCQ launch crash (suspected NATIVE — no JVM stack)\n")
            append("app: ${BuildConfig.VERSION_NAME} (vc ${BuildConfig.VERSION_CODE})\n")
            append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("last stage before crash: $stage\n")
            if (!exit.isNullOrBlank()) { append('\n'); append(exit) }
        }
        runCatching { File(appCtx.filesDir, FILE).writeText(report.take(MAX)) }
    }

    /** The OS-recorded reason for the most recent previous process exit
     *  (Android 11+). For a native crash it includes the signal description and
     *  the tombstone backtrace — the actual faulting frame. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun recentExitInfo(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        val reason = when (info.reason) {
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH(JVM)"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            else -> "reason=${info.reason}"
        }
        return buildString {
            append("exit: $reason status=${info.status} importance=${info.importance}\n")
            info.description?.let { append("exit desc: $it\n") }
            if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let {
                        append("--- native tombstone ---\n")
                        append(it.take(10_000))
                    }
            }
        }
    }

    /** The saved crash report from a previous run, or null if none. */
    fun pending(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }
}
