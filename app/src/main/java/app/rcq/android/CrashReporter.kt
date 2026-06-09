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
    // naming the last stage reached. Turned off once the app is safely running
    // so a normal later kill is never mistaken for a crash.
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
        val report = buildString {
            append("RCQ launch crash (suspected NATIVE — no JVM stack)\n")
            append("app: ${BuildConfig.VERSION_NAME} (vc ${BuildConfig.VERSION_CODE})\n")
            append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("last stage before crash: $stage\n")
        }
        runCatching { File(appCtx.filesDir, FILE).writeText(report) }
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
