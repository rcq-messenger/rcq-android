package app.rcq.android.push

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rcq.android.MainActivity
import app.rcq.android.R
import app.rcq.android.call.IncomingCallActivity
import app.rcq.android.call.IncomingCallStore
import app.rcq.android.data.AccountManager
import app.rcq.android.data.SecureStore
import app.rcq.android.net.RcqApi
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

/**
 * UnifiedPush glue for the Android client. The server (rcq-server-ref) POSTs a
 * JSON wake payload to the endpoint URL we register; a distributor app (ntfy,
 * …) relays it to [RcqPushService]. This object owns the small surface around
 * that: persisting the endpoint, registering it with every local account's
 * island, creating the notification channel, and turning a {type:"msg"} wake
 * into a system notification.
 *
 * A message wake carries the sealed envelope in `env`, and [PushEnvelope] opens
 * it when it can be opened without side effects — v=1 only, because decrypting
 * a v=2 or sender-keys envelope out of band would advance the ratchet and make
 * the live WS/offline-queue copy undecryptable, losing the message. When it
 * opens, the notification names the sender, previews the text, sends the tap to
 * that exact thread and can finally tell whether a "mentions only" thread was
 * actually mentioned. When it does not, the wake falls back to the
 * server-provided generic title/body/group_name and the real content arrives
 * when the app opens and drains the offline queue.
 */
object Push {
    // v2 because a channel's sound cannot be changed after it exists: the
    // original "rcq_messages" was created soundless and installs kept the
    // system default. Deleted on first run of this build (see ensureChannels).
    const val CHANNEL_MESSAGES = "rcq_messages_v2"
    private const val CHANNEL_MESSAGES_LEGACY = "rcq_messages"
    const val CHANNEL_CALLS = "rcq_calls"
    const val CHANNEL_CALLS_RING = "rcq_calls_ring"
    /** The live call's controls. Deliberately NOT the ringing channels: those are
     *  IMPORTANCE_HIGH so a full-screen intent can fire, and a high-importance
     *  channel pops a heads-up — which meant the "tap to return" notice slid over
     *  the call screen the user was already looking at. Low importance keeps it in
     *  the shade, which is the only place it is needed. */
    const val CHANNEL_CALL_ONGOING = "rcq_call_ongoing"
    private const val CALL_NOTIF_ID = 0x2C01
    /** ⚠ NOT 0x2C02: that is [app.rcq.android.push.embedded.PushSocketService]'s
     *  foreground-service notification. Shipping the live-call controls on that id
     *  in 0.101 made the two overwrite each other — starting a call replaced the
     *  push socket's notice, ending one cancelled a foreground service's
     *  notification, and the socket re-posting its own wiped the End button back
     *  off the shade. That is why End "немного помогает": it was there only until
     *  the push socket next touched its notice. A notification id is global to the
     *  app; every new one has to be checked against the whole app, not against its
     *  own file. */
    private const val ONGOING_CALL_NOTIF_ID = 0x2C03
    /** Broadcast the ongoing-call notification's End button sends; handled by a
     *  receiver the live CallController registers (no manifest component, since
     *  the only thing that can end a call is the controller that owns it). */
    const val ACTION_HANG_UP = "app.rcq.android.CALL_HANG_UP"
    /** Broadcast the RINGING notification's Decline button sends. Two handlers on
     *  purpose: the live CallController (which can tell the caller they were
     *  declined) and a manifest receiver (which still tears the ring down when the
     *  app was killed and the offer arrived over push, where no controller exists). */
    const val ACTION_DECLINE_CALL = "app.rcq.android.CALL_DECLINE"
    const val EXTRA_CALL_ID = "call_id"

    /** Intent extras a message notification tap carries into [MainActivity]
     *  so it can open the right thread (and switch to the right account). */
    const val EXTRA_OPEN_GROUP_ID = "open_group_id"
    const val EXTRA_OPEN_TO_UIN = "open_to_uin"

    /** The SENDER of a 1:1 message, when the wake's envelope could be opened
     *  ([PushEnvelope]). Absent for a group wake, for a v=2/gmsg envelope we
     *  deliberately leave sealed, and for an un-accepted cross-island sender
     *  whose message goes to the quarantine rather than to a thread. */
    const val EXTRA_OPEN_PEER_UIN = "open_peer_uin"
    /** Set when the wake is "we answered your report": the tap should land on
     *  the reports screen, since that answer is the only reason to open. */
    const val EXTRA_OPEN_REPORTS = "open_reports"

    /** Groups this device posted to a moment ago, keyed by group id →
     *  SystemClock.elapsedRealtime() of the send.
     *
     *  The server already refuses to wake the sending device (it skips every
     *  push token the authenticated sender registered), which covers the
     *  sender-keys broadcast path. The LEGACY per-member group path is
     *  deliberately anonymous though — sealed sender means no `caller` — so
     *  there the server cannot tell that a recipient account lives on the same
     *  phone as the author, and a multi-account user got a banner about their
     *  own post. Telling the server which local accounts share a device would
     *  fix it by de-anonymizing exactly what sealed sender protects, so the
     *  knowledge stays here: a wake for a group we posted to a breath ago is
     *  our own echo. Process-global on purpose — the wake is delivered into
     *  this same process, and a killed process has no recent post to suppress. */
    private val recentOwnGroupPosts = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** Window in which a group wake is treated as the echo of our own post.
     *  Short on purpose: suppressing someone ELSE's message notification is
     *  worse than the spurious self-banner this removes, and the echo comes
     *  back within a second or two of the POST. */
    private const val OWN_POST_ECHO_MS = 5_000L

    /** Called from the group send paths right after the fan-out POST. */
    fun noteOwnGroupPost(groupId: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        recentOwnGroupPosts[groupId] = now
        // Bounded without a sweeper task: drop anything already past the window
        // on each write (a user posts to a handful of groups, not thousands).
        recentOwnGroupPosts.entries.removeAll { now - it.value > OWN_POST_ECHO_MS }
    }

    private fun isOwnEcho(groupId: Int): Boolean {
        val at = recentOwnGroupPosts[groupId] ?: return false
        return android.os.SystemClock.elapsedRealtime() - at <= OWN_POST_ECHO_MS
    }

    private const val PREFS = "rcq_push"
    private const val K_ENDPOINT = "endpoint"

    /** The user turned push OFF and means it. Without this flag the choice did
     *  not survive a restart: the connector re-binds our own PushService on
     *  app start, a distributor is always present (we ARE one), so it
     *  re-registered, minted a fresh topic and came back on — the "I disabled
     *  push, relaunched, it is on again" report. */
    private const val K_USER_DISABLED = "user_disabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Endpoint as a flow so a screen sees the async arrival. `register()` only
     *  ASKS the distributor; the endpoint lands later in
     *  RcqPushService.onNewEndpoint, which is why the Settings block used to
     *  need a second tap to notice it had worked. */
    val endpointFlow: kotlinx.coroutines.flow.MutableStateFlow<String?> =
        kotlinx.coroutines.flow.MutableStateFlow(null)

    fun savedEndpoint(ctx: Context): String? = prefs(ctx).getString(K_ENDPOINT, null)
    fun setEndpoint(ctx: Context, url: String) {
        prefs(ctx).edit().putString(K_ENDPOINT, url).apply()
        endpointFlow.value = url
    }
    fun clearEndpoint(ctx: Context) {
        prefs(ctx).edit().remove(K_ENDPOINT).apply()
        endpointFlow.value = null
    }

    fun isUserDisabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_USER_DISABLED, false)
    private fun setUserDisabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(K_USER_DISABLED, on).apply()
    }

    /** Re-assert "push is off" against anything that re-registered behind the
     *  user's back (the connector's own start-up binding, a stale distributor
     *  registration). Cheap and idempotent; called on app start and whenever an
     *  endpoint shows up while the user has push disabled. */
    fun enforceUserDisabled(ctx: Context) {
        if (!isUserDisabled(ctx)) return
        val stale = savedEndpoint(ctx)
        runCatching { UnifiedPush.unregister(ctx) }
        runCatching { UnifiedPush.removeDistributor(ctx) }
        clearEndpoint(ctx)
        app.rcq.android.push.embedded.EmbeddedDistributor.stop(ctx)
        app.rcq.android.push.embedded.EmbeddedDistributor.clear(ctx)
        if (stale != null) deregisterWithBackend(ctx, stale)
    }

    /** Create the message notification channel. Idempotent; safe from
     *  Application.onCreate (also runs on headless starts). */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // The message channel carries OUR tone, not the phone's default one.
        // Reported: with the app closed or the screen locked the system chime
        // played instead of the "о-оу" everyone expects, because the channel was
        // created without a sound and Android then uses the default. A channel's
        // sound is immutable after creation, so correcting it needs a NEW id and
        // the old one deleted, otherwise every existing install keeps the wrong
        // sound forever.
        nm.deleteNotificationChannel(CHANNEL_MESSAGES_LEGACY)
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    ctx.getString(R.string.push_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.push_channel_messages_desc)
                    val tone = android.net.Uri.parse(
                        "android.resource://${ctx.packageName}/${R.raw.snd_message}",
                    )
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(tone, attrs)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CALLS) == null) {
            nm.createNotificationChannel(
                // High importance so a full-screen-intent fires; silent because
                // IncomingCallActivity drives its own ringtone via Ringer.
                NotificationChannel(
                    CHANNEL_CALLS,
                    ctx.getString(R.string.push_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.push_channel_calls_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CALLS_RING) == null) {
            nm.createNotificationChannel(
                // Audible fallback: used when the full-screen intent can't launch
                // (Android 14+ without USE_FULL_SCREEN_INTENT granted) so the call
                // still RINGS as a heads-up instead of being a silent dropped call.
                NotificationChannel(
                    CHANNEL_CALLS_RING,
                    ctx.getString(R.string.push_channel_calls),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.push_channel_calls_desc)
                    val ring = android.media.RingtoneManager
                        .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(ring, attrs)
                    enableVibration(true)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CALL_ONGOING) == null) {
            nm.createNotificationChannel(
                // A call that is already up. LOW so it sits in the shade instead of
                // sliding a heads-up over the call screen every time the state
                // changes (connecting → in progress) — reported as "беда с экранами
                // вызова и самого звонка".
                NotificationChannel(
                    CHANNEL_CALL_ONGOING,
                    ctx.getString(R.string.push_channel_call_ongoing),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = ctx.getString(R.string.push_channel_call_ongoing_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
    }

    /** Ask the active distributor for a push endpoint, if one is set up.
     *  Non-intrusive: registers against a saved/default distributor and never
     *  forces a picker — a device with no distributor simply gets no push
     *  (degrades to today's foreground-only behaviour). The resulting endpoint
     *  arrives asynchronously in [RcqPushService.onNewEndpoint]. */
    fun registerDistributor(activity: Activity) {
        val ctx = activity.applicationContext
        if (UnifiedPush.getSavedDistributor(ctx) != null) {
            UnifiedPush.register(ctx)
        } else {
            UnifiedPush.tryUseDefaultDistributor(activity) { ok ->
                if (ok) UnifiedPush.register(ctx)
            }
        }
    }

    /** ntfy — the recommended UnifiedPush distributor we point users to. */
    private const val NTFY_PKG = "io.heckel.ntfy"

    enum class PushState { CONNECTED, DISTRIBUTOR_AVAILABLE, NO_DISTRIBUTOR }

    /** Current push-delivery state, for the Notifications settings screen. */
    fun pushState(ctx: Context): PushState = when {
        UnifiedPush.getSavedDistributor(ctx) != null && savedEndpoint(ctx) != null -> PushState.CONNECTED
        UnifiedPush.getDistributors(ctx).isNotEmpty() -> PushState.DISTRIBUTOR_AVAILABLE
        else -> PushState.NO_DISTRIBUTOR
    }

    fun savedDistributor(ctx: Context): String? = UnifiedPush.getSavedDistributor(ctx)

    /** Pick a distributor (if none chosen yet) and register — the Settings
     *  "Enable" action. Returns false if none is available at all.
     *
     *  Our own embedded distributor wins the default pick: it talks to
     *  push.rcq.app, where we control the rate limits, instead of the public
     *  ntfy.sh that was refusing 81% of this server's wakes. The user can still
     *  switch to any installed distributor from the chooser. */
    fun enablePush(ctx: Context): Boolean {
        setUserDisabled(ctx, false)
        if (UnifiedPush.getSavedDistributor(ctx) != null) {
            UnifiedPush.register(ctx)
            return true
        }
        val available = UnifiedPush.getDistributors(ctx)
        val pick = available.firstOrNull { it == ctx.packageName }
            ?: available.firstOrNull()
            ?: return false
        UnifiedPush.saveDistributor(ctx, pick)
        UnifiedPush.register(ctx)
        return true
    }

    /** One-shot: this device already moved off an unreachable distributor.
     *  Without it, a user who deliberately moved BACK to their own distributor
     *  would be dragged onto ours again on the next health report. */
    private const val K_DISTRIBUTOR_HEALED = "distributor_healed"

    /** Is [error] the server saying it could not REACH the push host at all
     *  (as opposed to the host answering something)?
     *
     *  `push_last_error` holds either an HTTP status ("429", "507", "400") or
     *  the name of the transport exception ("ConnectTimeout", "ConnectError").
     *  Only the second kind means the host is unreachable from the server: a
     *  507 is ntfy's ordinary "nobody is subscribed right now", which happens
     *  to every phone that is simply switched off and must never be treated as
     *  a broken distributor.
     */
    internal fun isUnreachableError(error: String?): Boolean =
        !error.isNullOrBlank() && error.trim().toIntOrNull() == null

    /** Should this device move to our embedded distributor, given what the
     *  server says about the endpoint it is registered under?
     *
     *  Since 2026-08-01 the flagship's droplet cannot open a TCP connection to
     *  ntfy.sh at all (IPv4 times out, IPv6 is refused, everything else on the
     *  internet answers) — it looks like the public instance blocked our IP
     *  once the v0.76 rollout raised our POST volume. 732 of 877 Android
     *  endpoints pointed there, so most Android devices stopped being woken
     *  entirely and messages only appeared when the app was next opened. Those
     *  users cannot be told to switch, because telling them would need the very
     *  push that is broken, so the app moves itself.
     *
     *  Deliberately narrow: a self-hosted ntfy that is merely rate-limiting or
     *  has nobody subscribed answers with a STATUS, so it is left alone (three
     *  accounts on record use their own instance and it works). Pure decision
     *  function so it can be tested without a distributor.
     */
    internal fun shouldSwitchToEmbedded(endpointHost: String?, health: RcqApi.PushHealth): Boolean {
        val host = endpointHost?.takeIf { it.isNotBlank() } ?: return false
        val row = health.devices.firstOrNull {
            it.platform == "android-up" && it.host.equals(host, ignoreCase = true)
        } ?: return false
        return isUnreachableError(row.last_error)
    }

    /** Act on [shouldSwitchToEmbedded]: repoint this device at our own
     *  distributor (push.rcq.app) and re-register. Returns true when it moved.
     *  Never fights the user — it runs at most once per install, skips a device
     *  that already uses ours, and skips one where push is switched off. */
    fun healUnreachableDistributor(ctx: Context, health: RcqApi.PushHealth): Boolean {
        if (isUserDisabled(ctx)) return false
        if (prefs(ctx).getBoolean(K_DISTRIBUTOR_HEALED, false)) return false
        val saved = UnifiedPush.getSavedDistributor(ctx) ?: return false
        if (saved == ctx.packageName) return false
        if (!availableDistributors(ctx).contains(ctx.packageName)) return false
        val host = runCatching { android.net.Uri.parse(savedEndpoint(ctx)).host }.getOrNull()
        if (!shouldSwitchToEmbedded(host, health)) return false
        prefs(ctx).edit().putBoolean(K_DISTRIBUTOR_HEALED, true).apply()
        // Drop the dead endpoint first: registering mints a new one, and the
        // server prunes the old row when this account re-registers.
        clearEndpoint(ctx)
        UnifiedPush.saveDistributor(ctx, ctx.packageName)
        UnifiedPush.register(ctx)
        android.util.Log.w("RCQpush", "moved off unreachable distributor $saved -> embedded")
        return true
    }

    /** Re-open the embedded distributor's socket if this device uses it. Called
     *  on app start: the service is START_STICKY, but a force-stop (or a system
     *  that could not honour a background start) leaves it down until something
     *  asks again. No-op for a device on ntfy or with push off. */
    fun resumeEmbedded(ctx: Context) {
        if (UnifiedPush.getSavedDistributor(ctx) == ctx.packageName) {
            app.rcq.android.push.embedded.EmbeddedDistributor.ensureRunning(ctx)
        }
    }

    /** Installed UnifiedPush distributors (package names). */
    fun availableDistributors(ctx: Context): List<String> = UnifiedPush.getDistributors(ctx)

    /** Human-readable label for a distributor package — its app name, falling
     *  back to the last path segment (e.g. "ntfy"). Our own package is named
     *  explicitly: in a chooser listing "RCQ" next to "ntfy", the bare app name
     *  reads like a mistake rather than a choice. */
    fun distributorLabel(ctx: Context, pkg: String): String {
        if (pkg == ctx.packageName) return ctx.getString(R.string.notif_push_builtin)
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
    }

    /** Switch to a SPECIFIC distributor the user picked. Drops the old
     *  registration + endpoint first (so the server stops waking a stale
     *  provider); the new endpoint arrives async in [RcqPushService.onNewEndpoint]. */
    fun chooseDistributor(ctx: Context, pkg: String) {
        setUserDisabled(ctx, false)
        val old = savedEndpoint(ctx)
        runCatching { UnifiedPush.unregister(ctx) }
        if (old != null) deregisterWithBackend(ctx, old)
        clearEndpoint(ctx)
        UnifiedPush.saveDistributor(ctx, pkg)
        UnifiedPush.register(ctx)
    }

    /** Forget the current distributor + endpoint so the user can pick another
     *  (or none). Unregisters, clears the saved choice + our endpoint, and
     *  best-effort removes the token from every island. */
    fun resetDistributor(ctx: Context) {
        setUserDisabled(ctx, true)
        val old = savedEndpoint(ctx)
        runCatching { UnifiedPush.unregister(ctx) }
        runCatching { UnifiedPush.removeDistributor(ctx) }
        clearEndpoint(ctx)
        if (old != null) deregisterWithBackend(ctx, old)
    }

    /** Open the store page for ntfy (Play first, F-Droid web fallback). */
    fun openNtfyInstall(ctx: Context) {
        val play = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$NTFY_PKG"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(play) }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://f-droid.org/packages/$NTFY_PKG/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Take down the message notification for a thread the user has just read
     *  in the app.
     *
     *  A wake that has been acted on is noise: "когда я прочитал сообщение в
     *  группе, не нажимая на пуш, этот пуш должен исчезнуть сам, а сейчас он
     *  продолжает висеть". `setAutoCancel` only covers the tap — reading the
     *  message any other way left it hanging.
     *
     *  The ids must match the ones `showMessage` posts under: per group, per
     *  sender when the envelope could be opened, and one shared "dm" for the
     *  wakes that stayed sealed. A direct chat clears BOTH of its possible ids,
     *  because the same peer can have an identified wake and an anonymous one
     *  (a v=2 message from their iPhone) waiting at the same time. ⚠ Clearing
     *  the shared id still takes down every anonymous 1:1 wake at once — those
     *  carry nobody's name, so there is nothing per-sender to preserve, and a
     *  stuck notification is the worse of the two.
     */
    fun clearThreadNotification(ctx: Context, groupId: Int?, peerUin: Int? = null) {
        val nm = NotificationManagerCompat.from(ctx)
        if (groupId != null) {
            runCatching { nm.cancel(groupId.toString().hashCode()) }
            return
        }
        if (peerUin != null) runCatching { nm.cancel("peer:$peerUin".hashCode()) }
        runCatching { nm.cancel("dm".hashCode()) }
    }

    /** Whether the app can present a full-screen incoming-call UI. On Android 14+
     *  (UPSIDE_DOWN_CAKE) USE_FULL_SCREEN_INTENT is special-access and is NOT
     *  auto-granted to a non-dialer app, so an incoming call silently degrades to
     *  a heads-up notification that's easy to miss. True (always) below 14. */
    fun fullScreenIntentGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)

    /** True when the full-screen-intent grant has gone missing since the last
     *  version of the app that ran on this device.
     *
     *  Stock Android keeps the grant across an in-place update (verified on
     *  API 35: `appops get` still reports `allow` after `install -r`), but some
     *  vendor builds clear special access on every update, and a tester on one
     *  of them had to re-enable it after every release. There is nothing an app
     *  can do to hold onto a permission the system took away — what it can do
     *  is notice, instead of leaving the person to find out by missing a call.
     *
     *  Only reports a LOSS: the very first run after install records the state
     *  and says nothing, so this never doubles as a nag for someone who simply
     *  never granted it. */
    fun fullScreenIntentLostOnUpdate(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val prefs = ctx.applicationContext.getSharedPreferences(FSI_PREFS, Context.MODE_PRIVATE)
        val granted = fullScreenIntentGranted(ctx)
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        val seenVersion = prefs.getLong(K_FSI_VERSION, -1L)
        val hadIt = prefs.getBoolean(K_FSI_GRANTED, false)
        // ⚠ This is a read that WRITES, and that used to be the whole bug. The
        // detection needs the previous version to compare against, so it has to
        // record the current one — after which asking a second time answers
        // "same build, nothing happened". The home screen asks on every entry,
        // so the banner survived exactly one visit: step into settings, come
        // back, and it was gone without the user having answered it (tester,
        // 0.95). Worse than a cosmetic flicker — the one warning that incoming
        // calls had stopped popping full-screen erased itself.
        //
        // So the DETECTION result is latched into its own flag, and only the
        // user clears it: by fixing the grant, or by dismissing.
        val pending = prefs.getBoolean(K_FSI_PENDING, false) ||
            (seenVersion >= 0 && seenVersion != version && hadIt && !granted)
        prefs.edit()
            .putLong(K_FSI_VERSION, version)
            .putBoolean(K_FSI_GRANTED, granted)
            // Getting the grant back answers the banner by itself; there is
            // nothing left to tell the user about.
            .putBoolean(K_FSI_PENDING, pending && !granted)
            .apply()
        return pending && !granted
    }

    /** The user answered the full-screen-intent banner (fixed it or dismissed
     *  it). Until this is called the notice survives navigation, process death
     *  and reboots — it is the only warning that incoming calls stopped popping
     *  full-screen, and it is not worth losing to a stray recomposition. */
    fun clearFullScreenIntentNotice(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(FSI_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_FSI_PENDING, false).apply()
    }

    private const val FSI_PREFS = "rcq_fsi"
    private const val K_FSI_VERSION = "version"
    private const val K_FSI_GRANTED = "granted"
    private const val K_FSI_PENDING = "pending"

    /** Open the system screen where the user grants full-screen-intent access, so
     *  incoming calls pop the full call UI instead of a heads-up. Falls back to
     *  the app's notification settings if the dedicated screen is unavailable. */
    fun openFullScreenIntentSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val pkgUri = android.net.Uri.parse("package:${ctx.packageName}")
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Open the system settings page for the push-service channel, where the
     *  user can block the channel and hide the persistent connection notice
     *  for good — the foreground service keeps running without it (Android
     *  then shows only the Task Manager "active apps" entry). */
    fun openPushServiceChannelSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(
                        android.provider.Settings.EXTRA_CHANNEL_ID,
                        app.rcq.android.push.embedded.PushSocketService.CHANNEL_ID,
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** POST [endpoint] to every local account's island so each can wake this
     *  device. Idempotent server-side (upsert on uin+token). Fire-and-forget;
     *  callable headless (reads per-account creds straight from SecureStore). */
    fun registerWithBackend(ctx: Context, endpoint: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (acct in AccountManager.accounts.value) {
                val store = SecureStore(ctx, acct.id)
                val token = store.token ?: continue
                val host = store.serverHost ?: RcqApi.DEFAULT_HOST
                runCatching {
                    RcqApi("https://$host").apply { setToken(token) }
                        .setPushToken(endpoint, app.rcq.android.net.DeviceId.get(ctx))
                }
            }
        }
    }

    /** DELETE [endpoint] from every local account's island — used when the user
     *  resets or switches the push provider so the server stops trying to wake a
     *  now-dead endpoint. Fire-and-forget, headless-safe. */
    fun deregisterWithBackend(ctx: Context, endpoint: String) {
        CoroutineScope(Dispatchers.IO).launch {
            for (acct in AccountManager.accounts.value) {
                val store = SecureStore(ctx, acct.id)
                val token = store.token ?: continue
                val host = store.serverHost ?: RcqApi.DEFAULT_HOST
                runCatching {
                    RcqApi("https://$host").apply { setToken(token) }.deletePushToken(endpoint)
                }
            }
        }
    }

    /** Build + post a wake notification for a {type:"msg"} push payload. */
    fun showMessage(ctx: Context, json: JsonObject) {
        ensureChannels(ctx)
        fun str(k: String): String? =
            json.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

        // Only REAL messages raise a banner. The server pushes for "message"/"gmsg"
        // (a real message) but also "secscreen" (secure-screen state sync) and
        // "system" envelopes, which carry NO new message — showing "New message"
        // for those is the "ложные уведомления, новых сообщений нет" report.
        // Mirror the iOS NSE, which suppresses these after decrypt. envType is set
        // server-side in the UnifiedPush payload; absent => assume a real message
        // (older server) so nothing legitimate is ever swallowed.
        val envType = str("envType") ?: "message"
        if (envType != "message" && envType != "gmsg") return

        val groupName = str("group_name")
        val groupId = json.get("group_id")?.takeIf { !it.isJsonNull }?.asInt
        val toUin = json.get("to_uin")?.takeIf { !it.isJsonNull }?.asInt
        val isGroup = groupName != null || groupId != null

        // Our own post echoing back through a sibling account on this same
        // device (the anonymous legacy group path the server can't filter).
        if (groupId != null && isOwnEcho(groupId)) return

        // Which local account is this wake FOR? It carries to_uin, so on a
        // multi-account device we consult the account it is addressed to —
        // checking only the active account let a sibling account's muted group
        // keep buzzing the phone.
        val acctId = toUin?.let { u ->
            AccountManager.accounts.value.firstOrNull { SecureStore(ctx, it.id).uin == u }?.id
        } ?: app.rcq.android.data.AccountManager.activeId.value

        // A fully muted group is decided before the envelope is touched at all:
        // there is nothing the plaintext could change about the answer, and the
        // cheapest decrypt is the one we skip.
        if (groupId != null && acctId != null &&
            app.rcq.android.data.LocalStores.isMutedFor(
                acctId,
                app.rcq.android.data.LocalStores.groupThread(groupId),
            )
        ) {
            return
        }

        // Open the envelope the wake carries, when it is one we can open
        // without touching a ratchet — see [PushEnvelope] for why that is only
        // v=1. Everything below degrades to the old generic wake when this is
        // null, so a sealed envelope is never a missing notification.
        val opened = if (envType == "message" && acctId != null) {
            str("env")?.let { runCatching { PushEnvelope.open(ctx, acctId, it) }.getOrNull() }
        } else {
            null
        }
        // A control envelope carries no new message: read receipt, reaction,
        // edit, delete, presence ping. Waking the user for one is the "ложные
        // уведомления, новых сообщений нет" report — envType filters the kinds
        // the server can see, this filters the rest.
        if (opened != null && opened.preview == null) return

        // The peer this wake is about, once we know it. Absent for a group (the
        // sender of a group message is not a thread to open), for an envelope we
        // left sealed, and for an un-accepted cross-island sender (their message
        // is quarantined as a request, so there is no thread to open and no name
        // to reveal).
        val peerUin = opened?.takeIf { !it.quarantined && !isGroup }?.senderUin

        // Defense in depth: never wake for a thread the TARGET account muted,
        // even if the server's muted_group_ids sync was stale (the v0.63 class
        // of bug). Peer mute used to be a server-only gate because the sealed
        // wake hid the sender; with the envelope open it is enforced here too,
        // so a failed push-preferences PUT no longer lets a muted contact buzz.
        if (acctId != null) {
            val thread = when {
                groupId != null -> app.rcq.android.data.LocalStores.groupThread(groupId)
                peerUin != null -> app.rcq.android.data.LocalStores.peerThread(peerUin)
                else -> null
            }
            if (thread != null) {
                // Fully muted (NONE): never wake.
                if (app.rcq.android.data.LocalStores.isMutedFor(acctId, thread)) return
                // Mentions-only: a banner ONLY when actually mentioned. With the
                // envelope open we can finally tell — before, this was a blanket
                // return, which made "Mentions" behave exactly like "None" in the
                // background. A sealed envelope (v=2, gmsg) still stays quiet:
                // guessing would spam every message, the very report this gate
                // came from. iOS does the same in its sender-keys fallback.
                if (app.rcq.android.data.LocalStores.isMentionsOnlyFor(acctId, thread) &&
                    !(opened?.mentionsMe ?: false)
                ) {
                    return
                }
            }
        }

        val title = when {
            groupName != null -> groupName
            opened != null && !opened.quarantined -> opened.senderName
            else -> str("title") ?: ctx.getString(R.string.app_name)
        }
        val body = when {
            opened?.preview == null || opened.quarantined -> str("body") ?: ctx.getString(
                if (isGroup) R.string.push_new_group_message else R.string.push_new_message,
            )
            // In a group the title is the group, so the sender goes in front of
            // the text — same shape as the iOS NSE.
            isGroup -> "${opened.senderName}: ${opened.preview}"
            else -> opened.preview
        }

        // The SAME wake delivered twice must never sound twice. The server
        // retries a publish whose response it lost (~30s later, i.e. outside the
        // burst window below), ntfy replays its 12h cache to a distributor that
        // redials with `since=`, and a device that still has an old distributor
        // registered gets the copy sent there too. All of those carry a
        // byte-identical `env`, so the ciphertext IS the identity of the wake.
        val repeat = str("env")?.let { !SeenWakes.firstTime(it) } ?: false

        post(
            ctx,
            title = title,
            body = body,
            groupId = groupId,
            peerUin = peerUin,
            toUin = toUin,
            openReports = str("notif_kind") == "report_reply",
            forceQuiet = repeat,
        )
    }

    /** Raise the message notification for an envelope that arrived over the
     *  LIVE socket while the app was in the background.
     *
     *  Until this existed the two delivery paths were lopsided: a wake posted a
     *  notification, while a message the running app received itself only
     *  played an in-app tone — audible with the screen off, invisible in the
     *  shade, and impossible to silence from the notification settings. Same id
     *  scheme and same burst window as [showMessage], so whichever path gets
     *  there first alerts and the other one only refreshes the text. */
    fun showLocalMessage(
        ctx: Context,
        title: String,
        body: String,
        groupId: Int?,
        peerUin: Int?,
        toUin: Int?,
    ): Boolean {
        // Someone who blocked our notifications in Android settings would hear
        // NOTHING at all if we simply posted into the void, since the in-app
        // tone is now the foreground's job. Say so, and let the caller chirp
        // like it used to.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        ensureChannels(ctx)
        post(ctx, title, body, groupId, peerUin, toUin, openReports = false, forceQuiet = false)
        return true
    }

    /** Build + post the message notification both delivery paths share. */
    private fun post(
        ctx: Context,
        title: String,
        body: String,
        groupId: Int?,
        peerUin: Int?,
        toUin: Int?,
        openReports: Boolean,
        forceQuiet: Boolean,
    ) {
        // Distinct groups get their own notification, and so does each sender we
        // could identify. Wakes we could not open still collapse into one shared
        // "New message" — there is no sender to separate them by.
        val id = when {
            groupId != null -> groupId.toString().hashCode()
            peerUin != null -> "peer:$peerUin".hashCode()
            else -> "dm".hashCode()
        }
        val tap = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Extras don't participate in Intent.filterEquals, so without a
            // per-thread data URI every notification would share one
            // PendingIntent and FLAG_UPDATE_CURRENT would clobber older
            // notifications' extras with the newest thread. The rcq://notif
            // authority matches no VIEW filter and the intent is explicit,
            // so this never collides with real deep links.
            data = android.net.Uri.parse(
                "rcq://notif/${groupId ?: peerUin?.let { "p$it" } ?: "dm"}/${toUin ?: 0}",
            )
            if (groupId != null) putExtra(EXTRA_OPEN_GROUP_ID, groupId)
            if (toUin != null) putExtra(EXTRA_OPEN_TO_UIN, toUin)
            if (peerUin != null) putExtra(EXTRA_OPEN_PEER_UIN, peerUin)
            if (openReports) putExtra(EXTRA_OPEN_REPORTS, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, id, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Ring once per burst, not once per message. Reported: a night offline
        // then reconnect meant fifty wakes for fifty queued messages, so the
        // phone chimed fifty times for one conversation. The notification
        // already collapses (same id per thread), but every notify() re-alerts
        // unless told otherwise. So the first wake in a thread alerts and
        // anything arriving within the burst window only updates the text; a
        // message later in the day rings again like it should.
        //
        // ⚠ The timestamp is only refreshed when we actually ALERT. Stamping it
        // on every post let a chain of duplicates keep extending the silence, so
        // a real message twenty seconds later went unheard.
        val now = android.os.SystemClock.elapsedRealtime()
        val burstQuiet = synchronized(lastAlertAt) {
            val prev = lastAlertAt[id]
            val within = prev != null && now - prev < BURST_WINDOW_MS
            if (!within) lastAlertAt[id] = now
            if (lastAlertAt.size > 64) lastAlertAt.entries.removeAll { now - it.value > BURST_WINDOW_MS * 4 }
            within
        }
        // The app in front makes its own noise (in-app tone + banner), so a
        // second, louder chime from the system on top of it is the "о-оу
        // несколько раз, тихо и громко" report. The notification is still
        // posted — silently — so the shade holds it once the user leaves.
        // …but only for the account that IS in front. A wake addressed to a
        // SECOND local account has no screen and no in-app tone of its own, so
        // suppressing the system sound left it entirely silent: the message
        // arrived, the shade filled up, and nothing ever made a noise ("пуш без
        // звука, так и должно быть, когда на одном устройстве оба?").
        val forAnotherAccount = toUin != null && runCatching {
            AccountManager.activeId.value?.let { SecureStore(ctx, it).uin } != toUin
        }.getOrDefault(false)
        val foregroundQuiet = app.rcq.android.RcqApp.foreground && !forAnotherAccount
        // Honour the app's own sound switches. The channel carries our tone, so
        // "App sounds: off" or "Message sounds: off" used to silence only half
        // of the paths and the phone kept chiming.
        val settingsQuiet = !app.rcq.android.data.LocalStores.soundMasterOn() ||
            !app.rcq.android.data.LocalStores.soundMessagesOn()
        val quiet = forceQuiet || burstQuiet || foregroundQuiet || settingsQuiet
        val notif = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // ⚠ setOnlyAlertOnce is NOT enough on its own: it suppresses the
            // alert only while the previous notification is still showing, and
            // a tap (autoCancel) or an in-app read takes it down — after which
            // the duplicate posts as "new" and rings. setSilent is absolute.
            .setSilent(quiet)
            .setOnlyAlertOnce(quiet)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, notif) }
    }

    /** Last time each notification id made a sound, for burst coalescing. */
    private val lastAlertAt = HashMap<Int, Long>()
    private const val BURST_WINDOW_MS = 20_000L

    /** Wakes already turned into a sound, keyed by the ciphertext they carry.
     *
     *  A duplicate wake is not hypothetical: the server treats a lost response
     *  as a failed publish and retries at ~6s and ~24s (the second one lands
     *  outside the burst window above), and ntfy hands a reconnecting
     *  distributor everything since the last id it saw. Bounded and in-memory:
     *  a process restart at worst costs one duplicate chime. */
    private object SeenWakes {
        private const val CAP = 64
        private val keys = object : LinkedHashMap<Int, Unit>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Unit>) = size > CAP
        }

        /** True when this ciphertext has not been signalled before. */
        fun firstTime(envB64: String): Boolean = synchronized(keys) {
            keys.put(envB64.hashCode(), Unit) == null
        }
    }

    /** Raise a full-screen incoming-call wake for a {type:"call"} payload, or
     *  dismiss it when kind=="end". The full-screen-intent surfaces
     *  [IncomingCallActivity] over the lock screen; on accept it hands off to
     *  MainActivity which runs the WebRTC answer through the live Session. */
    fun showIncomingCall(ctx: Context, json: JsonObject) {
        fun str(k: String): String? =
            json.get(k)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        val callId = str("call_id") ?: return
        if (str("kind") == "end") { dismissIncomingCall(ctx, callId); return }
        val sdp = str("sdp") ?: return
        val fromUin = json.get("from_uin")?.takeIf { !it.isJsonNull }?.asInt ?: return
        ensureChannels(ctx)
        val nickname = str("nickname") ?: "#$fromUin"
        val media = str("media") ?: "video"
        val video = media == "video"
        IncomingCallStore.offer(
            IncomingCallStore.Pending(
                callId = callId,
                fromUin = fromUin,
                nickname = nickname,
                media = media,
                sdp = sdp,
            ),
        )
        val full = Intent(ctx, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val pi = PendingIntent.getActivity(
            ctx, 1, full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Answer/Decline ON THE NOTIFICATION. With the screen on and unlocked a
        // full-screen intent is only ever a heads-up (see below), and that heads-up
        // used to carry no buttons at all: the phone rang, nothing offered to pick
        // up, and the notice slid away into the shade — "пропадает сам экран вызова,
        // только мелодия играет". Answer goes through IncomingCallActivity because
        // accepting has to bring an Activity forward anyway; Decline is a broadcast
        // so declining never flashes a UI.
        val answerPi = PendingIntent.getActivity(
            ctx, 4,
            Intent(ctx, IncomingCallActivity::class.java)
                .setAction(IncomingCallActivity.ACTION_ANSWER)
                .putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declinePi = PendingIntent.getBroadcast(
            ctx, 5,
            Intent(ACTION_DECLINE_CALL).setPackage(ctx.packageName).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // On Android 14+ USE_FULL_SCREEN_INTENT is special-access and may be
        // ungranted, so setFullScreenIntent silently degrades to a heads-up. Use
        // the audible ring channel in that case so the call still rings instead
        // of being a silent dropped call; the silent channel only when the FSI
        // can actually launch IncomingCallActivity (which rings via Ringer).
        val fsiOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)
        // ⚠ Having the permission is not the same as the activity launching. A
        // full-screen intent only takes over the screen when the device is
        // LOCKED or the display is off; with the screen on and unlocked Android
        // shows a heads-up instead and IncomingCallActivity never starts — so
        // the silent channel meant a silent incoming call. That is the tester's
        // "backgrounded on Android 12: a push appears but it does not ring",
        // and it was never about Android 12: their Android 15 rang only because
        // that device is MISSING the permission, which flipped it to the
        // audible channel. Every version was silent whenever the phone was in
        // the user's hand.
        //
        // So pick the channel by whether the activity will actually run.
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val screenOff = pm?.isInteractive == false
        val locked = km?.isKeyguardLocked == true
        // Racy by a hair (the screen can lock between this check and the post),
        // and the cost of losing that race is a ringtone alongside the Ringer
        // for a moment. The cost of the old behaviour was a call that never
        // made a sound.
        val activityWillRing = fsiOk && (screenOff || locked)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            android.util.Log.w("RCQpush", "incoming call: notifications disabled — call UI cannot be shown")
        }
        val caller = androidx.core.app.Person.Builder().setName(nickname).setImportant(true).build()
        val notif = NotificationCompat.Builder(ctx, if (activityWillRing) CHANNEL_CALLS else CHANNEL_CALLS_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(ctx.getString(if (video) R.string.call_incoming_video else R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            // CallStyle draws the system's own incoming-call layout (big Answer /
            // Decline, caller's name) from API 31, and on older releases the compat
            // shim falls back to the same two actions on an ordinary notification.
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(CALL_NOTIF_ID, notif) }
    }

    fun cancelCallNotification(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(CALL_NOTIF_ID) }
    }

    /** The control surface for a call that is already up.
     *
     *  Android has no CallKit: the only place to hang up is the in-app
     *  CallScreen overlay, which exists only while MainActivity is in front. Any
     *  path that leaves a live call without that Activity leaves the user
     *  connected with no way out — reported on 0.100 after accepting from the
     *  lock screen, where the hand-off back to MainActivity did not land. Rather
     *  than chase each such path, give the call a surface that does not depend on
     *  an Activity at all: tapping returns to the call, and End works from the
     *  shade even if the UI never appears.
     *
     *  Separate id from the incoming-call notification so dismissing the ring
     *  never takes the live call's controls with it. */
    fun showOngoingCall(ctx: Context, callId: String, peer: String, connected: Boolean) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, app.rcq.android.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ACTION_HANG_UP).setPackage(ctx.packageName).putExtra(EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The LOW-importance channel, not the ringing one: on the ringing channel
        // this popped a heads-up across the call screen the user was already
        // looking at, and did it again on every state change.
        val notif = NotificationCompat.Builder(ctx, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peer)
            .setContentText(ctx.getString(if (connected) R.string.call_ongoing else R.string.call_connecting))
            .setSubText(ctx.getString(R.string.call_return))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, ctx.getString(R.string.call_hangup), end)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ONGOING_CALL_NOTIF_ID, notif) }
    }

    fun cancelOngoingCall(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ONGOING_CALL_NOTIF_ID) }
    }

    /** Caller cancelled before pickup ({kind:"end"}): drop the offer, remove the
     *  notification, and tell a showing IncomingCallActivity to finish. */
    fun dismissIncomingCall(ctx: Context, callId: String) {
        IncomingCallStore.clearIf(callId)
        cancelCallNotification(ctx)
        runCatching {
            ctx.sendBroadcast(
                Intent(IncomingCallActivity.ACTION_CANCEL)
                    .setPackage(ctx.packageName)
                    .putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId),
            )
        }
    }
}
