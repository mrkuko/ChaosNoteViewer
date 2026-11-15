package com.chaosnoteviewer.crypto

import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyStorageTest {
    private val state = MemoryState()
    private val wrapper = FakeWrapper()
    private val storage = KeyStorage(state, wrapper)

    @Test fun noKeyYet() = runTest {
        assertNull(storage.load())
        assertFalse(storage.isConfirmed())
    }

    @Test fun saveAndLoad() = runTest {
        val key = NoteCrypto.newKey()
        storage.save(key, confirmed = false)
        assertArrayEquals(key, storage.load())
        assertFalse(storage.isConfirmed())
        storage.confirm()
        assertTrue(storage.isConfirmed())
        assertArrayEquals(key, storage.load())
    }

    @Test fun theKeyIsNeverStoredInTheClear() = runTest {
        val key = NoteCrypto.newKey()
        storage.save(key, confirmed = true)
        val stored = state.map[StateKeys.ENC_KEY]!!
        assertFalse(stored.contains(KeyText.format(key)))
        assertFalse(stored.contains(key.joinToString("") { "%02x".format(it) }))
        assertTrue(storage.isConfirmed())
    }

    @Test fun anUnreadableKeyCountsAsNoKey() = runTest {
        storage.save(NoteCrypto.newKey(), confirmed = true)
        wrapper.broken = true                        // e.g. the database was restored on another phone
        assertNull(storage.load())
    }

    @Test fun clearForgetsTheKey() = runTest {
        storage.save(NoteCrypto.newKey(), confirmed = true)
        storage.clear()
        assertNull(storage.load())
        assertFalse(storage.isConfirmed())
    }

    @Test fun savingANewKeyReplacesTheOld() = runTest {
        storage.save(NoteCrypto.newKey(), confirmed = true)
        val second = NoteCrypto.newKey()
        storage.save(second, confirmed = false)
        assertArrayEquals(second, storage.load())
        assertEquals(false, storage.isConfirmed())
    }
}
