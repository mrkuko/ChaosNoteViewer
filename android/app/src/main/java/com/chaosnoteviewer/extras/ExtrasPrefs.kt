package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore

// The switches of the reminders and of the background sync, kept on this device (sync_state) and off until the user turns them on
// (turning one on asks for the notification permission).
class ExtrasPrefs(private val store: StateStore) {
    suspend fun reminders() = store.getState(StateKeys.REMINDERS) == "1"
    // background sync is on unless the user turned it off
    suspend fun backgroundSync() = store.getState(StateKeys.BG_SYNC) != "0"
    suspend fun setBackgroundSync(on: Boolean) = store.setState(StateKeys.BG_SYNC, if (on) "1" else "0")
    suspend fun setReminders(on: Boolean) = store.setState(StateKeys.REMINDERS, if (on) "1" else "0")
}
