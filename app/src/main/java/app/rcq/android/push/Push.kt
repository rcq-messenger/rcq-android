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
 * STAGE 1 scope: background MESSAGE notifications. The notification shows the
 * server-provided generic title/body/group_name only — NO background
 * decryption (decrypting a v=2 or sender-keys envelope out of band would
 * advance the ratchet and make the live WS/offline-queue copy undecryptable,
 * losing the message). The real sender + text arrive when the app opens and
 * drains the offline queue. Incoming-call wakes ({type:"call"}) are Stage 2.
 */
object Push {
    const val CHANNEL_MESSAGES = "rcq_messages"
    const val CHANNEL_CALLS = "rcq_calls"
    const val CHANNEL_CALLS_RING = "rcq_calls_ring"
    private const val CALL_NOTIF_ID = 0x2C01

    /** Intent extras a message notification tap carries into [MainActivity]
     *  so it can open the right thread (and switch to the right account). */
    const val EXTRA_OPEN_GROUP_ID = "open_group_id"
    const val EXTRA_OPEN_TO_UIN = "open_to_uin"
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
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    ctx.getString(R.string.push_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = ctx.getString(R.string.push_channel_messages_desc) },
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
     *  The id must match the one `showMessage` posts under: per group, and one
     *  shared "dm" for every 1:1 (a sealed wake does not reveal the sender, so
     *  they collapse). ⚠ That means opening ONE direct chat clears the wake for
     *  all of them. It is already a single "New message" with nobody's name on
     *  it, so there is nothing per-sender to preserve, and a stuck notification
     *  is the worse of the two.
     */
    fun clearThreadNotification(ctx: Context, groupId: Int?) {
        val id = (groupId?.toString() ?: "dm").hashCode()
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
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
        prefs.edit().putLong(K_FSI_VERSION, version).putBoolean(K_FSI_GRANTED, granted).apply()
        // Same build as last time, or a first run we have no history for.
        if (seenVersion < 0 || seenVersion == version) return false
        return hadIt && !granted
    }

    private const val FSI_PREFS = "rcq_fsi"
    private const val K_FSI_VERSION = "version"
    private const val K_FSI_GRANTED = "granted"

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
                    RcqApi("https://$host").apply { setToken(token) }.setPushToken(endpoint)
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
        // Defense in depth: never wake for a group the TARGET account muted, even
        // if the server's muted_group_ids sync was stale (the v0.63 class of bug).
        // The wake carries to_uin, so on a multi-account device we consult the
        // account it is FOR — checking only the active account let a sibling
        // account's muted group keep buzzing the phone.
        // The 1:1 sealed wake hides the sender, so peer mute stays a server gate.
        if (groupId != null) {
            // Our own post echoing back through a sibling account on this same
            // device (the anonymous legacy group path the server can't filter).
            if (isOwnEcho(groupId)) return
            val acctId = toUin?.let { u ->
                AccountManager.accounts.value.firstOrNull { SecureStore(ctx, it.id).uin == u }?.id
            } ?: app.rcq.android.data.AccountManager.activeId.value
            val thread = app.rcq.android.data.LocalStores.groupThread(groupId)
            if (acctId != null) {
                // Fully muted (NONE): never wake.
                if (app.rcq.android.data.LocalStores.isMutedFor(acctId, thread)) return
                // Mentions-only: the user wants a banner ONLY when @mentioned. Android
                // does no in-push decrypt (unlike the iOS NSE), so it can't confirm a
                // mention from a sealed/gmsg wake — stay quiet rather than spam every
                // message (the "muted RCQ Beta still pushes" report). Real mentions are
                // still seen in-app; precise server-side mention gating is a follow-up.
                if (app.rcq.android.data.LocalStores.isMentionsOnlyFor(acctId, thread)) return
            }
        }
        val title = groupName ?: str("title") ?: ctx.getString(R.string.app_name)
        val body = str("body") ?: ctx.getString(
            if (isGroup) R.string.push_new_group_message else R.string.push_new_message,
        )

        // Distinct groups get their own notification; all 1:1 pushes collapse
        // into one "New message" (the sealed wake doesn't reveal the sender).
        val id = (str("group_id") ?: "dm").hashCode()
        val tap = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Extras don't participate in Intent.filterEquals, so without a
            // per-thread data URI every notification would share one
            // PendingIntent and FLAG_UPDATE_CURRENT would clobber older
            // notifications' extras with the newest thread. The rcq://notif
            // authority matches no VIEW filter and the intent is explicit,
            // so this never collides with real deep links.
            data = android.net.Uri.parse("rcq://notif/${groupId ?: "dm"}/${toUin ?: 0}")
            if (groupId != null) putExtra(EXTRA_OPEN_GROUP_ID, groupId)
            if (toUin != null) putExtra(EXTRA_OPEN_TO_UIN, toUin)
            if (str("notif_kind") == "report_reply") putExtra(EXTRA_OPEN_REPORTS, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, id, tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, notif) }
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
        IncomingCallStore.offer(
            IncomingCallStore.Pending(
                callId = callId,
                fromUin = fromUin,
                nickname = nickname,
                media = str("media") ?: "video",
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
        // On Android 14+ USE_FULL_SCREEN_INTENT is special-access and may be
        // ungranted, so setFullScreenIntent silently degrades to a heads-up. Use
        // the audible ring channel in that case so the call still rings instead
        // of being a silent dropped call; the silent channel only when the FSI
        // can actually launch IncomingCallActivity (which rings via Ringer).
        val fsiOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            android.util.Log.w("RCQpush", "incoming call: notifications disabled — call UI cannot be shown")
        }
        val notif = NotificationCompat.Builder(ctx, if (fsiOk) CHANNEL_CALLS else CHANNEL_CALLS_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(ctx.getString(R.string.call_incoming))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(CALL_NOTIF_ID, notif) }
    }

    fun cancelCallNotification(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(CALL_NOTIF_ID) }
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
