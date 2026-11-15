package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.crypto.KeyText
import com.chaosnoteviewer.crypto.NoteCrypto
import com.chaosnoteviewer.data.KeyFlowStore

// What the user has to do about the encryption key.
enum class KeyPhase {
    Unknown,    // could not decide yet (offline), try again later
    Ready,      // key known and confirmed, sync may run
    Save,       // a new key was made: the user must see it and confirm (sync waits)
    Enter,      // the cloud has a key this device doesn't know (or the key changed): ask for it
}

// The encryption key of the cloud copy: created on the first device, shown to the user, typed in on the others.
// The key itself is NEVER in the cloud. The cloud only holds the "key check": a known text encrypted with the
// key, which proves that a typed key is the right one. Same flow as desktop KeyFlow.js.
class KeyFlow(
    private val storage: KeyStorage,
    private val remote: RemoteStore,
    private val store: KeyFlowStore,
) {
    companion object {
        const val CHECK_TEXT = "chaosnoteviewer key check"
        private const val CHECK_AAD = "keycheck"

        fun makeCheck(key: ByteArray) = NoteCrypto.encrypt(key, CHECK_TEXT, CHECK_AAD)
        fun keyMatches(key: ByteArray, check: String) = NoteCrypto.decrypt(key, check, CHECK_AAD) == CHECK_TEXT
    }

    data class Begin(val phase: KeyPhase, val mismatch: Boolean = false, val error: SyncException? = null)
    data class Entered(val ok: Boolean, val message: String = "")

    // Decides what the user has to do.
    suspend fun begin(uid: String, retried: Boolean = false): Begin {
        val stored = storage.load()
        val confirmed = storage.isConfirmed()
        val check = try {
            remote.fetchKeyCheck(uid)
        } catch (e: SyncException) {
            // offline or similar: a confirmed key keeps working, the sync will report the real problem
            return Begin(if (stored != null && confirmed) KeyPhase.Ready else KeyPhase.Unknown, error = e)
        }
        if (check != null) {
            return if (stored != null && keyMatches(stored, check))
                Begin(if (confirmed) KeyPhase.Ready else KeyPhase.Save)
            else
                Begin(KeyPhase.Enter, mismatch = stored != null)
        }
        // no key check in the cloud: publish ours, or make a key when this is the first device
        val key = stored ?: NoteCrypto.newKey()
        val created = try {
            remote.createKeyCheck(uid, makeCheck(key))
        } catch (e: SyncException) {
            return Begin(KeyPhase.Unknown, error = e)
        }
        if (!created) {
            // another device was first: look at its key
            if (!retried) return begin(uid, retried = true)
            return Begin(KeyPhase.Unknown, error = SyncException("EXISTS", "Another device created the key first."))
        }
        if (stored == null) {
            storage.save(key, confirmed = false)
            store.markAllDirty()                       // anything uploaded before without a key goes up encrypted
        }
        return Begin(if (stored != null && confirmed) KeyPhase.Ready else KeyPhase.Save)
    }

    // The user typed a key. SyncException when the cloud can't be asked.
    suspend fun enter(uid: String, text: String): Entered {
        val key = KeyText.parse(text)
            ?: return Entered(false, "That is not a valid key. Check it for typing mistakes: it has 56 letters and digits.")
        val check = remote.fetchKeyCheck(uid)
            ?: return Entered(false, "There is no key in the cloud any more. Close this and try again.")
        if (!keyMatches(key, check))
            return Entered(false, "That key does not match the notes in the cloud.")
        storage.save(key, confirmed = true)
        return Entered(true)
    }

    // The key shown to the user, four lines; "" when there is none
    suspend fun keyBlock(): String = storage.load()?.let(KeyText::formatBlock).orEmpty()
    suspend fun keyText(): String = storage.load()?.let(KeyText::format).orEmpty()

    suspend fun confirmSaved() = storage.confirm()

    // Key reset for a lost key: delete everything in the cloud, make a new key, upload what is on this device
    // again. The old key is replaced only after the cloud was wiped, and the check is created last: if that
    // fails, begin() recreates it from the stored key next time.
    suspend fun reset(uid: String) {
        val key = NoteCrypto.newKey()
        remote.wipe(uid)
        storage.save(key, confirmed = false)
        store.forgetRemote()
        if (!remote.createKeyCheck(uid, makeCheck(key)))
            throw SyncException("EXISTS", "Another device created the key first.")
    }
}
