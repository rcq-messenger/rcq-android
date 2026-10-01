package app.rcq.android.push

import android.util.Log
import com.google.gson.JsonParser
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush entry point (connector 3.x abstract [PushService]). The
 * distributor (ntfy, …) invokes these callbacks; all real work lives in [Push].
 */
class RcqPushService : PushService() {

    /** A new endpoint URL — persist it and register it with every account's
     *  island so the server can wake this device. */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // The connector re-registers on its own when the process starts, and
        // this device always has a distributor available because RCQ ships one.
        // If the user turned push off, an endpoint arriving here is that
        // machinery talking, not the user — drop it and re-assert the choice.
        if (Push.isUserDisabled(applicationContext)) {
            Push.enforceUserDisabled(applicationContext)
            return
        }
        Push.setEndpoint(applicationContext, endpoint.url)
        Push.registerWithBackend(applicationContext, endpoint.url)
    }

    /** A wake payload arrived (plain JSON we POSTed server-side; `decrypted`
     *  is false since we don't use WebPush encryption — content is raw bytes). */
    override fun onMessage(message: PushMessage, instance: String) {
        val json = runCatching {
            JsonParser.parseString(String(message.content, Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return
        when (json.get("type")?.takeIf { !it.isJsonNull }?.asString) {
            "msg" -> {
                // A wake for a number no account here holds is a removed
                // account's row still on its island (report 1066): no banner,
                // and no drain either, since there is no mailbox here for it.
                val target = Push.wakeTarget(applicationContext, Push.toUinOf(json))
                if (target is Push.WakeTarget.Stranger) {
                    Push.onStrangerWake(applicationContext)
                    return
                }
                Push.showMessage(applicationContext, json, target)
                // The push got through; whether the socket did is exactly
                // what this call finds out (#732/#830 - sound, no message).
                runCatching { app.rcq.android.Session.live?.pushSaysDrain() }
            }
            // {type:"call"} = incoming-call wake (kind:"end" = caller cancelled).
            "call" -> Push.showIncomingCall(applicationContext, json)
            else -> Unit
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w("RCQpush", "UnifiedPush registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        Push.clearEndpoint(applicationContext)
    }
}
