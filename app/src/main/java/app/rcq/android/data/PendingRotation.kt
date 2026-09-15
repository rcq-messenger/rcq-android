package app.rcq.android.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * A key rotation that has started and not finished (F3 of the 15.09 cross-island
 * spec). Persisted per account in [SecureStore] BEFORE any network call, so a
 * reply lost in flight cannot leave the island holding keys no device has.
 *
 * Introduced in client release C0, before anything writes it, for one reason:
 * the boot and socket guards must already refuse to erase an account that holds
 * one, so the installs in the field have learned that rule a full release before
 * any client can rotate (P0.2).
 *
 * Times are epoch MILLISECONDS, except [HomeProof.ts], which is the unix-seconds
 * value inside the signed rcq-reissue-v1 bytes. Private keys and the seed are
 * standard base64, and the whole record lives only in the encrypted store.
 */
data class PendingRotation(
    val id: String,
    val startedAt: Long,
    /** startedAt + 30 days: after this the app asks, per unfinished island,
     *  whether to delete the copy there or forget it. */
    val deadlineAt: Long,
    val oldIdentityPriv: String,
    /** Kept until every island confirms or is forgotten; null once deleted. */
    val oldSigningPriv: String?,
    val newSeed: String,
    val homeHost: String,
    val homeUin: Int,
    val phase: Phase,
    val homeProof: HomeProof?,
    val targets: List<Target>,
    /** The retired identity key stays decrypt-only until then (done + 14 days). */
    val retiredIdentityUntil: Long?,
    /** Adopted from a rotation made on another device (identity_rotated). */
    val sibling: Boolean,
) {
    enum class Phase {
        @SerializedName("prepared") PREPARED,
        @SerializedName("home_done") HOME_DONE,
        @SerializedName("done") DONE,
    }

    /** The signed home request, kept so a lost reply is answered by resending
     *  the IDENTICAL bytes (same ts and nonce), never a fresh proof. */
    data class HomeProof(val ts: Long, val nonce: String, val sig: String, val lastSentAt: Long?)

    data class Target(
        val kind: Kind,
        val host: String,
        val uin: Int?,
        val status: Status,
        val reason: String?,
        val attempts: Int,
        val lastTryAt: Long?,
        val proof: HomeProof?,
    ) {
        enum class Kind {
            @SerializedName("backup") BACKUP,
            @SerializedName("visited") VISITED,
        }
        enum class Status {
            @SerializedName("pending") PENDING,
            @SerializedName("done") DONE,
            @SerializedName("failed") FAILED,
            @SerializedName("forgotten") FORGOTTEN,
        }
    }

    fun encode(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        /**
         * ⚠⚠ Presence, not validity, is what the erase guards ask. A record that
         * no longer parses (a future field this build cannot read, a truncated
         * write) still means a rotation may be under way, and erasing then
         * throws away the only copy of the keys the island may already hold.
         */
        fun isPresent(raw: String?): Boolean = !raw.isNullOrBlank()

        /** Null when [raw] is missing, not JSON, or lacks a required field. Gson
         *  fills a missing field with null even for a non-null Kotlin type, so
         *  the fields every later step dereferences are checked here once. */
        fun decode(raw: String?): PendingRotation? {
            if (raw.isNullOrBlank()) return null
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            val p = runCatching { gson.fromJson(obj, PendingRotation::class.java) }.getOrNull() ?: return null
            @Suppress("SENSELESS_COMPARISON")
            val complete = p.id != null && p.oldIdentityPriv != null && p.newSeed != null &&
                p.homeHost != null && p.phase != null && p.targets != null &&
                p.targets.all { it != null && it.kind != null && it.host != null && it.status != null }
            return if (complete) p else null
        }
    }
}
