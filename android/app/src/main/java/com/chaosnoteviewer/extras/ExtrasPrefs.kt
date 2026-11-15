package com.chaosnoteviewer.extras

import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore

// The switches of the notifications, kept on this device (sync_state) and off until the user turns them on
// (turning one on asks for the notification permission).
class ExtrasPrefs(private val store: StateStore) {
    suspend fun reminders() = store.getState(StateKeys.REMINDERS) == "1"
    suspend fun countBadge() = store.getState(StateKeys.COUNT_BADGE) == "1"
    suspend fun setReminders(on: Boolean) = store.setState(StateKeys.REMINDERS, if (on) "1" else "0")
    suspend fun setCountBadge(on: Boolean) = store.setState(StateKeys.COUNT_BADGE, if (on) "1" else "0")
}
