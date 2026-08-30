package app.rcq.android.crypto

import android.util.Base64
import app.rcq.android.model.RcqGroup
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealed room identity, the READER half (stage 6 phase 2).
 *
 * The island stores an opaque blob per room; a member holding the room state
 * key (RSK) overlays the sealed name/description/avatar/pin over the open
 * columns, everyone else renders the columns exactly as before. Wire format
 * and the key-distribution story: rcq-docs/group-state-seal-design.md. The
 * web is the writer today; this client learns to read, and to receive the
 * key (inner kinds `gskey`/`gsknack` riding the outer types skdm/sknack -
 * see Session's sealed dispatch).
 *
 * Blob layout `[0x02][key_ver u32 BE][nonce 12][AES-256-GCM ct]` over
 * raw-deflated JSON; the first format put the nonce at offset 0 and carried
 * no version. The OPEN key_ver is the one fact a keyless client needs: which
 * key generation to ask for, and what a replacement mint must exceed.
 */
object GroupState {

    private const val BLOB_V2 = 0x02

    /** The sealed fields, decoded. Absent fields fall back to the columns. */
    data class Sealed(
        val name: String?,
        val description: String?,
        val avatarMediaId: String?,
        val avatarMediaKey: String?,
        val pinnedText: String?,
    )

    /** The key generation a blob was sealed under, readable WITHOUT the key.
     *  Null for the pre-versioned format. */
    fun sealedKeyVer(blobB64: String): Long? = runCatching {
        val b = Base64.decode(blobB64, Base64.DEFAULT)
        if (b.size < 18 || (b[0].toInt() and 0xFF) != BLOB_V2) return null
        ((b[1].toLong() and 0xFF) shl 24) or ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 8) or (b[4].toLong() and 0xFF)
    }.getOrNull()

    /** Decrypt + inflate a blob under [keyB64]. Null on any failure - a key
     *  from before a rotation, a truncated blob - and the caller falls back
     *  to the open columns. */
    fun open(blobB64: String, keyB64: String): Sealed? = runCatching {
        val blob = Base64.decode(blobB64, Base64.DEFAULT)
        if (blob.size < 13) return null
        val off = if ((blob[0].toInt() and 0xFF) == BLOB_V2 && blob.size >= 18) 5 else 0
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Base64.decode(keyB64, Base64.DEFAULT), "AES"),
            GCMParameterSpec(128, blob, off, 12),
        )
        val deflated = cipher.doFinal(blob, off + 12, blob.size - off - 12)
        // Raw deflate: the same nowrap framing the web writes via
        // CompressionStream('deflate-raw') - proven cross-platform in Node.
        val inflater = Inflater(true)
        inflater.setInput(deflated)
        val out = java.io.ByteArrayOutputStream(deflated.size * 4)
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        val obj = JsonParser.parseString(out.toString("UTF-8")).asJsonObject
        if (obj.get("v")?.asInt != 1) return null
        fun str(k: String): String? = obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        Sealed(
            name = str("name"),
            description = str("description"),
            avatarMediaId = str("avatar_media_id"),
            avatarMediaKey = str("avatar_media_key"),
            pinnedText = str("pinned_text"),
        )
    }.getOrNull()

    /** Overlay the sealed identity onto [g] when [keyB64] opens its blob;
     *  the row unchanged otherwise. */
    fun overlay(g: RcqGroup, keyB64: String?): RcqGroup {
        val blob = g.stateBlob ?: return g
        val key = keyB64 ?: return g
        val s = open(blob, key) ?: return g
        return g.copy(
            name = s.name?.takeIf { it.isNotBlank() } ?: g.name,
            description = s.description ?: g.description,
            avatarMediaId = s.avatarMediaId ?: g.avatarMediaId,
            avatarMediaKey = s.avatarMediaKey ?: g.avatarMediaKey,
            pinnedText = s.pinnedText ?: g.pinnedText,
        )
    }

    /** The wire body of a `gskey` envelope, for Session's producer side
     *  (answering a gsknack). */
    fun gskeyJson(gid: Int, ver: Long, keyB64: String): JsonObject = JsonObject().apply {
        addProperty("kind", "gskey")
        addProperty("gid", gid)
        addProperty("ver", ver)
        addProperty("key", keyB64)
    }
}
