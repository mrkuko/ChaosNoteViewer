package com.chaosnoteviewer.crypto

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Client-side encryption of the cloud copy of the notes: ChaCha20-Poly1305 (RFC 8439), the platform's own
// implementation (Android 9+). Same format as the desktop app (desktop/shared/Crypto.js), fixed by the shared
// test vectors in core/crypto/vectors.json.
//
// Stored text:  "v1." + base64url(no padding)( nonce[12] || ciphertext || tag[16] )
// Key:          32 random bytes, shown to the user as text by KeyText
// Bound to:     the note id (authenticated data), so the server can't move a ciphertext to another note.
object NoteCrypto {
    const val VERSION = "v1."
    const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val ALGORITHM = "ChaCha20-Poly1305"

    private val random = SecureRandom()
    private val base64Alphabet = Regex("[A-Za-z0-9_-]*")

    fun newKey(): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    // A nonce only has to be unique per key; 96 random bits from the system's secure random.
    fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

    // nonce || ciphertext || tag
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "the key must have 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "the nonce must have 12 bytes" }
        // a new Cipher per call: the JDK refuses to encrypt twice with the same nonce in one Cipher
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    // null when the data was modified or the key (or the authenticated data) is wrong.
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (key.size != KEY_SIZE || sealed.size < NONCE_SIZE + TAG_SIZE)
            return null
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(sealed.copyOfRange(0, NONCE_SIZE)))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    // [nonce] is only passed by the tests, to compare with the fixed vectors
    fun encrypt(key: ByteArray, text: String, aad: String, nonce: ByteArray = newNonce()): String =
        VERSION + Base64.getUrlEncoder().withoutPadding().encodeToString(
            seal(key, nonce, text.toByteArray(Charsets.UTF_8), aad.toByteArray(Charsets.UTF_8)),
        )

    // null when the text is not ours, was changed, or the key is wrong
    fun decrypt(key: ByteArray, token: String, aad: String): String? {
        if (!token.startsWith(VERSION))
            return null
        val body = token.substring(VERSION.length)
        // a stricter reading than Java's decoder: no padding, no standard-alphabet characters, no impossible length
        if (!base64Alphabet.matches(body) || body.length % 4 == 1)
            return null
        val sealed = Base64.getUrlDecoder().decode(body)
        val plain = open(key, sealed, aad.toByteArray(Charsets.UTF_8)) ?: return null
        return utf8OrNull(plain)
    }

    private fun utf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
