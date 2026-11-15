package com.chaosnoteviewer.crypto

import com.chaosnoteviewer.data.StateStore

class MemoryState : StateStore {
    val map = mutableMapOf<String, String>()
    override suspend fun getState(key: String) = map[key] ?: ""
    override suspend fun setState(key: String, value: String) { map[key] = value }
}

// reverses the bytes: enough to see that the stored text is not the key
class FakeWrapper : KeyWrapper {
    var broken = false
    override fun wrap(key: ByteArray) = "fake." + key.reversedArray().joinToString("") { "%02x".format(it) }
    override fun unwrap(blob: String): ByteArray? {
        if (broken || !blob.startsWith("fake.")) return null
        val h = blob.removePrefix("fake.")
        return ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }.reversedArray()
    }
}
