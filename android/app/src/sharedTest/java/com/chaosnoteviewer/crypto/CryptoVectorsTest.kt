package com.chaosnoteviewer.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The shared vectors of core/crypto/vectors.json (made by the desktop implementation) must pass both ways.
// Runs on the PC (JDK crypto) and on the phone (Android's own provider): the output must be the same.
class CryptoVectorsTest {
    private val vectors = readVectors()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test fun rfc8439OfficialVector() {
        val v = vectors.getJSONObject("rfc8439")
        val key = hex(v.getString("key")); val nonce = hex(v.getString("nonce")); val aad = hex(v.getString("aad"))
        val plaintext = hex(v.getString("plaintext")); val expected = hex(v.getString("ciphertextAndTag"))
        assertArrayEquals(nonce + expected, NoteCrypto.seal(key, nonce, plaintext, aad))
        assertArrayEquals(plaintext, NoteCrypto.open(key, nonce + expected, aad))
    }

    @Test fun everyTokenDecryptsToItsPlaintext() {
        val tokens = vectors.getJSONArray("tokens").objects()
        assertTrue(tokens.size >= 20)
        for (v in tokens)
            assertEquals(v.getString("name"), v.getString("plaintext"),
                NoteCrypto.decrypt(hex(v.getString("key")), v.getString("token"), v.getString("aad")))
    }

    // the same nonce gives the same token: what this app writes, the desktop reads and the other way round
    @Test fun encryptingWithTheFixedNonceGivesTheSameToken() {
        for (v in vectors.getJSONArray("tokens").objects())
            assertEquals(v.getString("name"), v.getString("token"),
                NoteCrypto.encrypt(hex(v.getString("key")), v.getString("plaintext"), v.getString("aad"), hex(v.getString("nonce"))))
    }

    @Test fun invalidTokensDoNotDecrypt() {
        val invalid = vectors.getJSONArray("invalid").objects()
        assertTrue(invalid.size >= 15)
        for (v in invalid)
            assertNull(v.getString("name"), NoteCrypto.decrypt(hex(v.getString("key")), v.getString("token"), v.getString("aad")))
    }

    @Test fun keysAreShownAsOnDesktop() {
        for (v in vectors.getJSONArray("keyFormats").objects()) {
            val key = hex(v.getString("key"))
            assertEquals(v.getString("name"), v.getString("formatted"), KeyText.format(key))
            assertEquals(v.getString("name"), v.getString("block"), KeyText.formatBlock(key))
            assertArrayEquals(key, KeyText.parse(v.getString("formatted")))
        }
    }

    @Test fun typedKeysAreAcceptedAndRejectedAsOnDesktop() {
        for (v in vectors.getJSONArray("keyInputs").objects()) {
            val parsed = KeyText.parse(v.getString("input"))
            if (v.isNull("key"))
                assertNull(v.getString("name"), parsed)
            else
                assertEquals(v.getString("name"), v.getString("key"), parsed?.hex())
        }
    }

    @Test fun roundTripWithARandomKeyAndNonce() {
        val key = NoteCrypto.newKey()
        assertEquals(32, key.size)
        assertNotEquals(key.hex(), NoteCrypto.newKey().hex())
        val a = NoteCrypto.encrypt(key, "Přežije to? 🎉", "id-1")
        val b = NoteCrypto.encrypt(key, "Přežije to? 🎉", "id-1")
        assertNotEquals("every encryption has its own nonce", a, b)
        assertEquals("Přežije to? 🎉", NoteCrypto.decrypt(key, a, "id-1"))
        assertEquals("Přežije to? 🎉", NoteCrypto.decrypt(key, b, "id-1"))
        assertNull(NoteCrypto.decrypt(key, a, "id-2"))
        assertNull(NoteCrypto.decrypt(NoteCrypto.newKey(), a, "id-1"))
    }

    @Test fun aNewKeyRoundTripsThroughItsText() {
        repeat(50) {
            val key = NoteCrypto.newKey()
            assertArrayEquals(key, KeyText.parse(KeyText.format(key)))
            assertArrayEquals(key, KeyText.parse(KeyText.formatBlock(key)))
        }
    }
}
