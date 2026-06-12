package app.rcq.android

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.rcq.android.data.LocalStores
import app.rcq.android.security.PanicPinService

/**
 * Application entry point. Owns the app-global panic-PIN lock wiring:
 * initialises the lock state at process start and re-locks whenever the whole
 * app goes to background (ProcessLifecycleOwner ON_STOP, which does NOT fire for
 * in-app activity transitions like the photo picker). The actual teardown is
 * driven off [PanicPinService.locked]: [Session] tears its live state down and
 * [MainActivity] shows the lock screen.
 */
class RcqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // BEFORE anything else: if the previous launch left a startup breadcrumb,
        // it died during startup (suspected native crash) — turn it into a report.
        CrashReporter.checkPreviousLaunch(this)
        // First, so it captures crashes from the rest of init too.
        CrashReporter.install(this)
        // NB the breadcrumb is armed in MainActivity.onCreate, NOT here: the
        // process also starts headless (broadcast/work warmups) where
        // Application.onCreate runs, no UI ever shows, and the OS later
        // reclaims the cached process — arming here turned every such reclaim
        // into a phantom "launch crash" report.
        PanicPinService.initLockState(this)
        // Optional PIN-lock grace (#10): users asked not to re-enter the PIN on
        // every quick app switch. With a grace > 0 we DON'T lock on background;
        // instead we lock on RETURN if the app was away longer than the grace
        // (Signal-style). elapsedRealtime so it can't be gamed by clock changes.
        var backgroundedAt = 0L
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val grace = LocalStores.lockGraceSeconds()
                if (grace <= 0) PanicPinService.lock(applicationContext)
                else backgroundedAt = SystemClock.elapsedRealtime()
                // The user left a working app — anything that kills the
                // process from here on (swipe-away, OEM reaper, memory
                // pressure) is a normal background death, not a launch crash.
                CrashReporter.launchComplete(applicationContext)
            }

            override fun onStart(owner: LifecycleOwner) {
                val grace = LocalStores.lockGraceSeconds()
                if (grace > 0 && backgroundedAt > 0L &&
                    SystemClock.elapsedRealtime() - backgroundedAt >= grace * 1000L
                ) {
                    PanicPinService.lock(applicationContext)
                }
                backgroundedAt = 0L
            }
        })
    }
}
