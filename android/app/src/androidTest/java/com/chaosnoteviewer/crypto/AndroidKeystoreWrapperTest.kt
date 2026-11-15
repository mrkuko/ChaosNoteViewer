package com.chaosnoteviewer.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

// The real Android Keystore of this device (and the platform's ChaCha20-Poly1305).
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreWrapperTest {
    private val alias = "chaosnoteviewer_test_wrap"
    private val wrapper = AndroidKeystoreWrapper(alias)

    @After fun removeKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test fun wrapAndUnwrap() {
        val key = NoteCrypto.newKey()
        val blob = wrapper.wrap(key)
        assertTrue(blob.startsWith("ks1."))
        assertFalse(blob.contains(KeyText.format(key)))
        assertArrayEquals(key, wrapper.unwrap(blob))
    }

    @Test fun everyWrapGetsItsOwnIv() {
        val key = NoteCrypto.newKey()
        assertNotEquals(wrapper.wrap(key), wrapper.wrap(key))
    }

    @Test fun garbageIsNotAKey() {
        assertNull(wrapper.unwrap(""))
        assertNull(wrapper.unwrap("ks1."))
        assertNull(wrapper.unwrap("ks1.@@@"))
        assertNull(wrapper.unwrap("fake.00"))
        assertNull(wrapper.unwrap("ks1." + java.util.Base64.getEncoder().encodeToString(ByteArray(40))))
    }

    @Test fun aTamperedBlobIsRejected() {
        val blob = wrapper.wrap(NoteCrypto.newKey())
        val raw = java.util.Base64.getDecoder().decode(blob.removePrefix("ks1."))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(wrapper.unwrap("ks1." + java.util.Base64.getEncoder().encodeToString(raw)))
    }

    @Test fun aBlobOfAnotherKeystoreKeyIsRejected() {
        val blob = wrapper.wrap(NoteCrypto.newKey())
        removeKey()                                   // like a restore on another phone: the wrapping key is gone
        val fresh = AndroidKeystoreWrapper(alias)
        assertNull(fresh.unwrap(blob))
    }

    @Test fun storageWithTheRealKeystore() = runTest {
        val state = MemoryState()
        val storage = KeyStorage(state, wrapper)
        val key = NoteCrypto.newKey()
        storage.save(key, confirmed = true)
        assertArrayEquals(key, storage.load())
        assertEquals(true, storage.isConfirmed())
    }
}

