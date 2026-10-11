package app.rcq.android.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decrypted copies a wipe takes with the account: played voice notes,
 * opened and shared files. Not the update package waiting to be installed,
 * and nothing else in the cache.
 */
class PlaintextFilesTest {

    @Test fun decryptedVoiceAndFilesGo() {
        assertTrue(PlaintextFiles.isPlaintext("", "voice-3f2a.m4a"))
        assertTrue(PlaintextFiles.isPlaintext("", "voice-out.m4a"))
        assertTrue(PlaintextFiles.isPlaintext("files", "audio-3f2a.ogg"))
        assertTrue(PlaintextFiles.isPlaintext("files", "contract.pdf"))
        assertTrue(PlaintextFiles.isPlaintext("files", "somebody.apk"))
        assertTrue(PlaintextFiles.isPlaintext("share", "photo.jpg"))
    }

    @Test fun theUpdateAndTheRestOfTheCacheStay() {
        assertFalse(PlaintextFiles.isPlaintext("files", "rcq-update-214.apk"))
        assertFalse(PlaintextFiles.isPlaintext("files", "RCQ-0.214.apk"))
        assertFalse(PlaintextFiles.isPlaintext("", "island-catalogue.json"))
        assertFalse(PlaintextFiles.isPlaintext("media", "abc"))
    }
}
