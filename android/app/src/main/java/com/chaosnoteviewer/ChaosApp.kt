package com.chaosnoteviewer

import android.app.Application
import com.chaosnoteviewer.data.AppDatabase
import com.chaosnoteviewer.data.NotesRepository

// Holds the app-wide objects (no DI framework, the app is small).
class ChaosApp : Application() {
    val database by lazy { AppDatabase.open(this) }
    val notes by lazy { NotesRepository(database) }
}
