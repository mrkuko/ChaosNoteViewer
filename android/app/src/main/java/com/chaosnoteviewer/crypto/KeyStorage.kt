package com.chaosnoteviewer.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.chaosnoteviewer.data.StateKeys
import com.chaosnoteviewer.data.StateStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Turns the key bytes into text that is safe to keep in the database, and back.
interface KeyWrapper {
    fun wrap(key: ByteArray): String
    // null when the text is not ours or can't be unwrapped any more (e.g. restored on another phone)
    fun unwrap(blob: String): ByteArray?
}

// The encryption key lives in the app's database (sync_state), wrapped (AES-256-GCM) by a key of the Android
// Keystore that never leaves the secure hardware. A copy of the database, e.g. from a backup, is useless
// without this phone: the user then enters the key again, as on a new device.
class AndroidKeystoreWrapper(private val alias: String = "chaosnoteviewer_key_wrap") : KeyWrapper {
    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "ks1."
        const val IV_SIZE = 12
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun wrap(key: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())          // the Keystore picks a fresh IV
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(key))
    }

    override fun unwrap(blob: String): ByteArray? {
        if (!blob.startsWith(PREFIX))
            return null
        return try {
            val raw = Base64.getDecoder().decode(blob.removePrefix(PREFIX))
            if (raw.size <= IV_SIZE)
                return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, raw, 0, IV_SIZE))
            cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

// The encryption key of the cloud copy as this device knows it, and whether the user confirmed they saved it
// (sync_state enc_key and key_confirmed, as on desktop; the stored text differs).
class KeyStorage(private val state: StateStore, private val wrapper: KeyWrapper) {
    // null = this device has no key (or can't read the stored one)
    suspend fun load(): ByteArray? {
        val blob = state.getState(StateKeys.ENC_KEY)
        return if (blob.isEmpty()) null else wrapper.unwrap(blob)?.takeIf { it.size == NoteCrypto.KEY_SIZE }
    }

    suspend fun save(key: ByteArray, confirmed: Boolean) {
        state.setState(StateKeys.ENC_KEY, wrapper.wrap(key))
        state.setState(StateKeys.KEY_CONFIRMED, if (confirmed) "1" else "0")
    }

    suspend fun isConfirmed(): Boolean = state.getState(StateKeys.KEY_CONFIRMED) == "1"

    suspend fun confirm() = state.setState(StateKeys.KEY_CONFIRMED, "1")

    suspend fun clear() {
        state.setState(StateKeys.ENC_KEY, "")
        state.setState(StateKeys.KEY_CONFIRMED, "0")
    }
}
