package com.chaosnoteviewer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Version 2 = PRAGMA user_version of the latest core/storage/migrations file. A new migration there
// means a new version here plus a Room Migration that runs the same SQL.
@Database(entities = [NoteEntity::class, SyncStateEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun syncState(): SyncStateDao

    companion object {
        const val FILE_NAME = "notes.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME).build()
    }
}
