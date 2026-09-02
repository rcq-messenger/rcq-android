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
        // Account roster + notification channel made available process-wide,
        // including on headless starts — the UnifiedPush push service reads the
        // roster to register/resolve accounts and posts message notifications.
        app.rcq.android.data.AccountManager.init(this)
        // Local prefs available process-wide, incl. headless push starts: the
        // UnifiedPush service reads the per-account mute set to suppress banners
        // for muted groups (defense in depth over the server's muted_group_ids).
        // Idempotent — MainActivity.onCreate calls it again and it no-ops.
        LocalStores.init(this)
        // Per-host access-token store for closed (masquerade) islands — seed the
        // in-memory map so the OkHttp interceptor can stamp X-RCQ-Auth.
        app.rcq.android.net.AccessTokenStore.init(this)
        // ⚠ The island pin store, BEFORE anything below can build a client:
        // Push.enforceUserDisabled two lines down already dials every account's
        // island, and on a device whose distributor is ntfy nothing else in the
        // process ever loads it. Judged against an empty store, an island
        // trusted through Let's Encrypt for months reads as unknown and an
        // on-path certificate for it is taken as a first use — with the
        // account's bearer token in the request that follows.
        app.rcq.android.net.IslandTrust.init(this)
        app.rcq.android.push.Push.ensureChannels(this)
        // If this device distributes its own pushes, make sure the socket is up.
        // The service is START_STICKY, but a force-stop (or a background start
        // the system refused) leaves it down until something asks again.
        // Honour a user who turned push off BEFORE anything can re-register.
        app.rcq.android.push.Push.enforceUserDisabled(this)
        app.rcq.android.push.Push.resumeEmbedded(this)
        // Optional PIN-lock grace (#10): users asked not to re-enter the PIN on
        // every quick app switch. With a grace > 0 we DON'T lock on background;
        // instead we lock on RETURN if the app was away longer than the grace
        // (Signal-style). elapsedRealtime so it can't be gamed by clock changes.
        var backgroundedAt = 0L
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                runCatching { onForegroundChange?.invoke(false) }
                runCatching { onForegroundChangeSession?.invoke(false) }
                val grace = LocalStores.lockGraceSeconds()
                if (grace <= 0) PanicPinService.lock(applicationContext)
                else backgroundedAt = SystemClock.elapsedRealtime()
                // The user left a working app — anything that kills the
                // process from here on (swipe-away, OEM reaper, memory
                // pressure) is a normal background death, not a launch crash.
                CrashReporter.launchComplete(applicationContext)
            }

            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                runCatching { onForegroundChange?.invoke(true) }
                runCatching { onForegroundChangeSession?.invoke(true) }
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

    companion object {
        /** Whole-app foreground state (ProcessLifecycleOwner). Used by the call
         *  path: a WS-delivered incoming offer that arrives while the app is
         *  backgrounded can't present the in-app call screen (no Activity can be
         *  launched from the background), so it raises the full-screen-intent
         *  surface instead — the same one the push path uses. Defaults false:
         *  the process can start headless (push/work warmups) with no UI. */
        @Volatile
        var foreground: Boolean = false
            private set

        /** Notified on every whole-app foreground flip. Set by CallController,
         *  which has to move a RINGING call between the in-app screen and the
         *  full-screen-intent notification when the person leaves or returns:
         *  polling [foreground] would only tell it the answer after something
         *  else happened to ask. Single slot on purpose — one owner, and a
         *  listener list here would outlive account switches. */
        @Volatile
        var onForegroundChange: ((Boolean) -> Unit)? = null

        /** Session's own foreground hook: the socket probe and the queue
         *  drain (report #807). A SECOND named slot, not a listener list and
         *  not a shared one: 0.151 had Session.start() assign the slot above
         *  and silently STEAL it from CallController, so the ringing handoff
         *  between the in-app screen and the full-screen notification never
         *  fired again. One slot, one owner, each. */
        @Volatile
        var onForegroundChangeSession: ((Boolean) -> Unit)? = null
    }
}
