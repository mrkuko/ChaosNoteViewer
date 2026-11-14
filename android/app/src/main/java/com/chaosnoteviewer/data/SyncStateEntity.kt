package com.chaosnoteviewer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Key/value table `sync_state` (002_sync.sql): account binding, sync cursor and per-device settings.
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

// Keys used in sync_state, the same ones the desktop app uses.
object StateKeys {
    const val UID = "uid"
    const val EMAIL = "email"
    const val REFRESH_TOKEN = "refresh_token"
    const val CURSOR_TS = "cursor_ts"
    const val CURSOR_ID = "cursor_id"
    const val SORT_FIELD = "sort_field"
    const val SORT_DESC = "sort_desc"
}
