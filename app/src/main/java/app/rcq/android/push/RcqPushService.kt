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
            "msg" -> Push.showMessage(applicationContext, json)
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
