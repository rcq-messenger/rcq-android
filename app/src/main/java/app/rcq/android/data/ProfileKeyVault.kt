package app.rcq.android.data

import android.util.Base64
import app.rcq.android.crypto.MediaCrypto
import app.rcq.android.crypto.Vault
import app.rcq.android.net.RcqApi

/**
 * My profile key, mirrored through the vault so every install of this account
 * uses the SAME one (docs/profile-key-design.md).
 *
 * The key is what my avatar blob is sealed under. The island no longer holds
 * it - until this it sat in `users.avatar_media_key` next to the uin and the
 * nickname, so a seized island opened every face it held - and my contacts get
 * it in a sealed `pkey` envelope instead.
 *
 * ⚠ Vault-FIRST, mint second. If a phone minted its own key while the browser
 * had already published one, the two installs would hand out different keys and
 * half of one person's contacts would hold a key that opens nothing. Whoever
 * published first wins: the merge below refuses to overwrite a non-empty slot.
 *
 * ⚠ The slot carries the base64 key as RAW UTF-8, not JSON. That is what the
 * web writes (`new TextEncoder().encode(keyB64)`), and the two have to agree
 * byte for byte or neither can read the other's.
 */
object ProfileKeyVault {

    private fun slotOf(identityPriv: ByteArray): String = Vault.slotId(identityPriv, Vault.PKEY)

    /**
     * The key to use for my picture: the local copy, else whatever the vault
     * already holds, else a fresh one published to the vault.
     *
     * Returns null only when there is no key AND the island refused the write,
     * which is the one case where minting locally would risk the split above.
     */
    suspend fun ensureMyKey(api: RcqApi, identityPriv: ByteArray): String? {
        LocalStores.myProfileKey()?.takeIf { it.isNotBlank() }?.let { return it }
        val slot = slotOf(identityPriv)
        val floor = LocalStores.vaultSlotVersion(slot)

        // 1. Adopt what a sibling install already published.
        val cur = runCatching { api.vaultGet(slot) }.getOrNull()
        if (cur != null && cur.version >= floor) {
            val existing = cur.blob
                ?.let { runCatching { Vault.open(identityPriv, slot, cur.version, Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }
                ?.toString(Charsets.UTF_8)
                ?.trim()
            if (!existing.isNullOrBlank()) {
                LocalStores.setMyProfileKey(existing)
                LocalStores.setVaultSlotVersion(slot, cur.version)
                return existing
            }
        }

        // 2. Nothing there: mint and publish. A 409 means somebody else got in
        //    first, so re-read rather than overwrite - their key is now the
        //    one their contacts hold.
        val minted = Base64.encodeToString(MediaCrypto.newKey(), Base64.NO_WRAP)
        val base = cur?.version ?: 0L
        val sealed = Vault.seal(identityPriv, slot, base + 1, minted.toByteArray(Charsets.UTF_8))
        val w = runCatching {
            api.vaultPut(slot, Base64.encodeToString(sealed, Base64.NO_WRAP), base)
        }.getOrNull()
        if (w?.version != null) {
            LocalStores.setMyProfileKey(minted)
            LocalStores.setVaultSlotVersion(slot, w.version!!)
            return minted
        }
        // Lost the race (or no vault on this island). Re-read once: if a rival
        // key is there, adopt it; if the island simply has no vault, fall back
        // to the minted key locally - a picture nobody else can open is still
        // better than refusing to set one.
        val again = runCatching { api.vaultGet(slot) }.getOrNull()
        val theirs = again?.blob
            ?.let { runCatching { Vault.open(identityPriv, slot, again.version, Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }
            ?.toString(Charsets.UTF_8)
            ?.trim()
        if (!theirs.isNullOrBlank()) {
            LocalStores.setMyProfileKey(theirs)
            LocalStores.setVaultSlotVersion(slot, again.version)
            return theirs
        }
        LocalStores.setMyProfileKey(minted)
        return minted
    }
}
