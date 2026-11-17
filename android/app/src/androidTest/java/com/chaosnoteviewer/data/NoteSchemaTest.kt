package com.chaosnoteviewer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// The Room entities must match the canonical schema in core/storage/migrations/ (packaged into the test
// assets by app/build.gradle.kts): build a database by running those SQL files, like the desktop app does,
// then open it with Room. Room validates every table, column, type, NOT NULL, default and index on open
// and throws when anything differs.
@RunWith(AndroidJUnit4::class)
class NoteSchemaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "schema-test.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun statements(file: String): List<String> {
        val sql = InstrumentationRegistry.getInstrumentation().context.assets.open(file)
            .bufferedReader().readText()
        return sql.lines()
            .map { it.substringBefore("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    @Test fun roomAcceptsTheCanonicalSchema() = runTest {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            for (file in listOf("001_notes.sql", "002_sync.sql", "003_starred.sql"))
                statements(file).forEach { db.execSQL(it) }
            assertEquals(3, db.version)
            db.execSQL("INSERT INTO notes (id, title, position, created_at, updated_at) VALUES ('x', 'from desktop', 1, 1, 1)")
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val note = room.notes().get("x")!!          // first query opens and validates
            assertEquals("from desktop", note.title)
            assertEquals("", note.description)
            assertEquals(false, note.completed)
            assertEquals(false, note.starred)
            assertEquals(false, note.dirty)
        } finally {
            room.close()
        }
    }

    // A phone that has the database of the previous version (2) must keep its notes when the app updates: Room
    // runs MIGRATION_2_3, which has to end in exactly the schema 003_starred.sql describes.
    @Test fun aVersion2DatabaseMigratesAndKeepsItsNotes() = runTest {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            for (file in listOf("001_notes.sql", "002_sync.sql"))
                statements(file).forEach { db.execSQL(it) }
            assertEquals(2, db.version)
            db.execSQL("INSERT INTO notes (id, title, completed, position, created_at, updated_at) VALUES ('keep', 'kept', 1, 1, 1, 1)")
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build()
        try {
            val note = room.notes().get("keep")!!
            assertEquals("kept", note.title)
            assertEquals(true, note.completed)
            assertEquals(false, note.starred)               // nothing is starred after the update
        } finally {
            room.close()
        }
    }
}
