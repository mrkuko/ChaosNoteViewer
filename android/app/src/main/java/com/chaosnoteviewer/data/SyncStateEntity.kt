package com.chaosnoteviewer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Key/value table `sync_state` (002_sync.sql): account binding, sync cursor and per-device settings.
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

// Reads and writes the key/value table sync_state.
interface StateStore {
    // "" when there is no such key
    suspend fun getState(key: String): String
    suspend fun setState(key: String, value: String)
}

// Keys used in sync_state, the same ones the desktop app uses.
object StateKeys {
    const val UID = "uid"
    const val EMAIL = "email"
    const val REFRESH_TOKEN = "refresh_token"
    const val CURSOR_TS = "cursor_ts"
    const val CURSOR_ID = "cursor_id"
    const val SORT_FIELD = "sort_field"
    const val SORT_DESC = "sort_desc"
    // the encryption key of the cloud copy, wrapped by the Android Keystore (crypto/KeyStorage.kt), and whether
    // the user saw it and confirmed they saved it; same names as on desktop, other content
    const val ENC_KEY = "enc_key"
    const val KEY_CONFIRMED = "key_confirmed"
    // per-device switches (not synced): daily reminder about notes due today or overdue, open-notes count on the icon
    const val REMINDERS = "reminders"
    const val COUNT_BADGE = "count_badge"
    // sync while the app is closed (WorkManager); on unless "0"
    const val BG_SYNC = "bg_sync"
}
