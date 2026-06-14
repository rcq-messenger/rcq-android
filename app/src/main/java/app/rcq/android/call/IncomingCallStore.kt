package app.rcq.android.call

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global handoff for a push-woken incoming call.
 *
 * The UnifiedPush service has no access to the Activity-scoped [app.rcq.android.Session],
 * so it parks the call offer here and raises a full-screen notification.
 * [IncomingCallActivity] reads it to ring on the lock screen; [app.rcq.android.MainActivity]
 * drains it into the live [CallController] once the Session's WS is connected
 * (the only place that can run the WebRTC answer + send replies/fetch TURN).
 *
 * [acceptedCallId] is an observable flow so MainActivity's drain re-fires for a
 * second call even when nothing else changed; accept is bound to a specific
 * call id so a later offer can't be answered under an earlier one's UI.
 */
object IncomingCallStore {
    data class Pending(
        val callId: String,
        val fromUin: Int,
        val nickname: String,
        val media: String,
        val sdp: String,
        /** Monotonic arrival time — the drain drops an offer older than the
         *  caller's ring window so a late unlock doesn't answer a dead call. */
        val ts: Long = SystemClock.elapsedRealtime(),
    )

    @Volatile
    var pending: Pending? = null
        private set

    private val _acceptedCallId = MutableStateFlow<String?>(null)

    /** The call id the user accepted on the full-screen UI, or null. Observable
     *  so the MainActivity drain re-runs per acceptance regardless of WS/lock
     *  state churn. */
    val acceptedCallId: StateFlow<String?> = _acceptedCallId.asStateFlow()

    fun offer(p: Pending) {
        pending = p
        _acceptedCallId.value = null
    }

    /** Mark [callId] accepted — no-op if it isn't the call currently parked
     *  (a newer offer already replaced it). */
    fun accept(callId: String) {
        if (pending?.callId == callId) _acceptedCallId.value = callId
    }

    fun clear() {
        pending = null
        _acceptedCallId.value = null
    }

    /** Drop a specific call (decline / caller cancelled / already handled).
     *  No-op if a newer call already replaced it. */
    fun clearIf(callId: String) {
        if (pending?.callId == callId) clear()
    }
}
