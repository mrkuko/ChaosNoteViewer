package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.SyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncState(
    val signedIn: Boolean = false,
    val phase: KeyPhase = KeyPhase.Unknown,
    // the key was changed on another device (reset): the one stored here doesn't fit the cloud any more
    val mismatch: Boolean = false,
    val keyBusy: Boolean = false,
    val keyError: String = "",
    // the key as four lines while the user must save it (Save) or asked to see it; "" = hidden
    val keyBlock: String = "",
    val syncing: Boolean = false,
    // why the last sync failed, "" when it worked
    val syncError: String = "",
    val lastSyncMs: Long = 0,
    // consecutive failures (0..MAX_FAILURES): the polling slows down while this is above 0
    val failures: Int = 0,
    // notes this device had synced that are gone from the server (deleted on another device)
    val missing: Set<String> = emptySet(),
)

// The sync of the signed-in account: the encryption key flow and the sync itself, started by the user
// (background sync is a later phase). Same behavior as the desktop AppController.
// [remote] is null when this build has no Firebase configuration.
class SyncManager(
    private val store: SyncStore,
    private val remote: RemoteStore?,
    keyStorage: KeyStorage,
    private val now: () -> Long = System::currentTimeMillis,
    // the server says the sign-in is gone for good (not just offline): the account must be signed out
    private val onSessionLost: () -> Unit = {},
) {
    companion object {
        const val MAX_FAILURES = 6
    }

    private val keys = remote?.let { KeyFlow(keyStorage, it, store) }
    private val engine = remote?.let { SyncEngine(store, it) }
    private val storage = keyStorage
    private val lock = Mutex()          // one key task or sync at a time

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private suspend fun uid() = store.getState(StateKeys.UID)

    private fun message(e: SyncException) = e.message.orEmpty() + " (${e.code})"

    // Called when the account signs in or out.
    suspend fun onSignedIn(signedIn: Boolean) {
        if (!signedIn || remote == null) {
            _state.value = SyncState()
            return
        }
        _state.update { it.copy(signedIn = true) }
        startKeyFlow()
    }

    // Decides what the user has to do about the key; starts a sync when everything is ready.
    suspend fun startKeyFlow() {
        val flow = keys ?: return
        if (!_state.value.signedIn) return
        val ready = lock.withLock {
            _state.update { it.copy(keyBusy = true, keyError = "") }
            val result = flow.begin(uid())
            if (result.error?.code == "SESSION_LOST") {
                sessionLost()
                return
            }
            _state.update {
                it.copy(
                    failures = if (result.error != null) minOf(it.failures + 1, MAX_FAILURES) else 0,
                    keyBusy = false,
                    phase = result.phase,
                    mismatch = result.mismatch,
                    keyBlock = if (result.phase == KeyPhase.Save) flow.keyBlock() else "",
                    keyError = result.error?.let(::message).orEmpty(),
                )
            }
            result.error == null && result.phase == KeyPhase.Ready
        }
        if (ready) syncNow()
    }

    // Push my changes, then pull the others'. Does nothing until the key is ready.
    suspend fun syncNow() {
        val engine = engine ?: return
        val s = _state.value
        if (!s.signedIn || s.phase != KeyPhase.Ready || s.syncing) return
        if (!lock.tryLock()) return
        try {
            _state.update { it.copy(syncing = true) }
            val key = storage.load()
            if (key == null) {
                _state.update { it.copy(phase = KeyPhase.Unknown) }
                return
            }
            val uid = uid()
            try {
                engine.run(uid, key)
                _state.update { it.copy(syncError = "", lastSyncMs = now(), failures = 0) }
                try {
                    val missing = engine.reconcile(uid).toSet()
                    _state.update { it.copy(missing = missing) }
                } catch (e: SyncException) {
                    // the next sync looks again
                }
            } catch (e: SyncException) {
                when (e.code) {
                    // the cloud copy was re-encrypted with another key (reset on another device)
                    "BAD_KEY" -> _state.update { it.copy(mismatch = true, phase = KeyPhase.Enter, keyBlock = "") }
                    "SESSION_LOST" -> sessionLost()
                    else -> _state.update { it.copy(syncError = message(e), failures = minOf(it.failures + 1, MAX_FAILURES)) }
                }
            }
        } finally {
            _state.update { it.copy(syncing = false) }
            lock.unlock()
        }
    }

    private fun sessionLost() {
        _state.value = SyncState()
        onSessionLost()
    }

    // For the background: waits for a key task that is running, then does what is due: decide about the key (offline
    // at the start) or sync. Does nothing before sign-in.
    suspend fun syncOrStart() {
        if (!_state.value.signedIn || remote == null) return
        lock.withLock { }
        when (_state.value.phase) {
            KeyPhase.Unknown -> startKeyFlow()
            KeyPhase.Ready -> syncNow()
            else -> Unit              // waiting for the user (save or enter the key)
        }
    }

    // The user typed the key of the other device.
    suspend fun submitKey(text: String) {
        val flow = keys ?: return
        val ok = lock.withLock {
            _state.update { it.copy(keyBusy = true, keyError = "") }
            try {
                val entered = flow.enter(uid(), text)
                if (entered.ok)
                    _state.update { it.copy(keyBusy = false, mismatch = false, phase = KeyPhase.Ready, keyBlock = "") }
                else
                    _state.update { it.copy(keyBusy = false, keyError = entered.message) }
                entered.ok
            } catch (e: SyncException) {
                _state.update { it.copy(keyBusy = false, keyError = message(e)) }
                false
            }
        }
        if (ok) syncNow()
    }

    // The user saw the new key and says they saved it.
    suspend fun confirmKey() {
        val flow = keys ?: return
        flow.confirmSaved()
        _state.update { it.copy(phase = KeyPhase.Ready, keyBlock = "") }
        syncNow()
    }

    // Shows or hides the key while sync is ready.
    suspend fun toggleKeyShown() {
        val flow = keys ?: return
        val shown = _state.value.keyBlock.isNotEmpty()
        val block = if (!shown && _state.value.phase == KeyPhase.Ready) flow.keyBlock() else ""
        _state.update { it.copy(keyBlock = block) }
    }

    // Lost key: wipes the cloud copy, makes a new key, uploads this device's notes again (after the "save" step).
    suspend fun resetKey() {
        val flow = keys ?: return
        var failed: SyncException? = null
        lock.withLock {
            _state.update { it.copy(keyBusy = true, keyError = "") }
            try {
                flow.reset(uid())
                _state.update { it.copy(keyBusy = false, mismatch = false, phase = KeyPhase.Save, missing = emptySet()) }
            } catch (e: SyncException) {
                failed = e
                _state.update { it.copy(keyBusy = false) }
            }
        }
        val error = failed
        if (error != null) {
            startKeyFlow()                      // find out what state the failed reset left behind
            _state.update { it.copy(keyError = message(error)) }
        } else {
            _state.update { it.copy(keyBlock = flow.keyBlock()) }
        }
    }

    // Puts a note back on the server that was deleted there.
    suspend fun reuploadNote(id: String) {
        store.reupload(id)
        _state.update { it.copy(missing = it.missing - id) }
        syncNow()
    }
}
