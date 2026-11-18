package com.chaosnoteviewer.account

import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseSettingsTest {
    private val key = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
    private val state = mutableMapOf<String, String>()
    private val store = object : StateStore {
        override suspend fun getState(key: String) = state[key] ?: ""
        override suspend fun setState(key: String, value: String) { state[key] = value }
    }
    private var resets = 0
    private val settings = FirebaseSettings(store, onProjectChanged = { resets++ })

    @Test fun freshInstallShowsTheSetup() = runTest {
        settings.load()
        assertNull(settings.config.value)
        assertEquals(false, settings.setupSeen.value)
    }

    @Test fun skippingIsRemembered() = runTest {
        settings.load()
        settings.skipSetup()
        val again = FirebaseSettings(store)
        again.load()
        assertEquals(true, again.setupSeen.value)
        assertNull(again.config.value)
    }

    @Test fun saveChecksAndKeeps() = runTest {
        settings.load()
        assertNotNull(settings.save("nope", "my-notes"))
        assertNull(settings.config.value)
        assertNull(settings.save(" $key ", " my-notes "))
        assertEquals(FirebaseConfig(key, "my-notes"), settings.config.value)
        assertEquals(true, settings.setupSeen.value)
        val again = FirebaseSettings(store)
        again.load()
        assertEquals(FirebaseConfig(key, "my-notes"), again.config.value)
        assertEquals(0, resets)
    }

    // another project: its cloud copy is new; the same project again (after turning sync off) continues
    @Test fun onlyAnotherProjectStartsTheCloudCopyAgain() = runTest {
        settings.load()
        settings.save(key, "first-project")
        settings.remove()
        assertNull(settings.config.value)
        settings.save(key, "first-project")
        assertEquals(0, resets)
        settings.save(key, "second-project")
        assertEquals(1, resets)
    }

    // updating from a version with the project built in: nothing to reset, the user enters the same project
    @Test fun firstProjectAfterAnUpdateResetsNothing() = runTest {
        state[StateKeys.UID] = "uid-1"
        settings.load()
        assertEquals(false, settings.setupSeen.value)
        settings.save(key, "chaosnoteviewer")
        assertEquals(0, resets)
    }

    @Test fun emulatorBuildIsFixed() = runTest {
        val emu = FirebaseSettings(store, fixed = FirebaseConfig.EMULATOR)
        emu.load()
        assertFalse(emu.editable)
        assertEquals(FirebaseConfig.EMULATOR, emu.config.value)
        assertTrue(emu.setupSeen.value == true)
        assertNull(emu.save(key, "other"))
        assertEquals(FirebaseConfig.EMULATOR, emu.config.value)
    }
}
