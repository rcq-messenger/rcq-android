package app.rcq.android.push.embedded

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The registration half of the embedded distributor: the UnifiedPush broadcast
 * contract, answered in-process.
 *
 * Declaring this receiver with the REGISTER action is also what makes RCQ show
 * up in its own distributor chooser — the connector discovers distributors by
 * querying for receivers of that action, and an app is allowed to be its own.
 *
 * Only our own package is served. A general-purpose distributor would hand out
 * endpoints to any app that asked; this one exists solely to keep RCQ's wakes
 * on RCQ's server, and answering for strangers would mean carrying their
 * traffic (and their metadata) for no reason.
 */
class DistributorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RCQdistributor"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val token = intent.getStringExtra(EmbeddedDistributor.EXTRA_TOKEN)
        when (intent.action) {
            EmbeddedDistributor.ACTION_REGISTER -> {
                val app = intent.getStringExtra(EmbeddedDistributor.EXTRA_APPLICATION)
                if (token.isNullOrBlank()) return
                if (app != null && app != ctx.packageName) {
                    Log.w(TAG, "refusing registration for foreign package $app")
                    fail(ctx, token, "ACTION_REGISTER_FAILED")
                    return
                }
                val topic = EmbeddedDistributor.ensureTopic(ctx, token)
                val endpoint = "${EmbeddedDistributor.PUSH_HOST}/$topic?up=1"
                Log.i(TAG, "registered, endpoint minted")
                ctx.sendBroadcast(
                    Intent(EmbeddedDistributor.ACTION_NEW_ENDPOINT).apply {
                        `package` = ctx.packageName
                        putExtra(EmbeddedDistributor.EXTRA_TOKEN, token)
                        putExtra(EmbeddedDistributor.EXTRA_ENDPOINT, endpoint)
                    },
                )
                EmbeddedDistributor.ensureRunning(ctx)
            }

            EmbeddedDistributor.ACTION_UNREGISTER -> {
                if (token.isNullOrBlank()) return
                EmbeddedDistributor.stop(ctx)
                EmbeddedDistributor.clear(ctx)
                ctx.sendBroadcast(
                    Intent(EmbeddedDistributor.ACTION_UNREGISTERED).apply {
                        `package` = ctx.packageName
                        putExtra(EmbeddedDistributor.EXTRA_TOKEN, token)
                    },
                )
                Log.i(TAG, "unregistered")
            }

            // The app confirms it ingested a wake. We already advance `since`
            // when the message is handed over, so this is a no-op kept for
            // spec-completeness: a distributor that ignores the action entirely
            // makes the app's ack look unanswered.
            EmbeddedDistributor.ACTION_MESSAGE_ACK -> Unit
        }
    }

    private fun fail(ctx: Context, token: String, reason: String) {
        ctx.sendBroadcast(
            Intent(EmbeddedDistributor.ACTION_REGISTRATION_FAILED).apply {
                `package` = ctx.packageName
                putExtra(EmbeddedDistributor.EXTRA_TOKEN, token)
                putExtra(EmbeddedDistributor.EXTRA_REASON, reason)
            },
        )
    }
}

/**
 * Bring the push socket back after a reboot. Without this the device would be
 * silent until the user next opened RCQ by hand, which is the one moment push
 * is least useful. BOOT_COMPLETED is one of the cases where starting a
 * foreground service from the background is still permitted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        EmbeddedDistributor.ensureRunning(context.applicationContext)
    }
}
