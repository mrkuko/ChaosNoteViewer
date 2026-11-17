package com.chaosnoteviewer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Version 3 = PRAGMA user_version of the latest core/storage/migrations file. A new migration there
// means a new version here plus a Room Migration that runs the same SQL.
@Database(entities = [NoteEntity::class, SyncStateEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun syncState(): SyncStateDao

    companion object {
        const val FILE_NAME = "notes.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_2_3)
                .build()

        // core/storage/migrations/003_starred.sql (the stars other devices already set are fetched by
        // NotesRepository.repairStarsOnce at the next start)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN starred INTEGER NOT NULL DEFAULT 0 CHECK (starred IN (0, 1))")
            }
        }
    }
}
