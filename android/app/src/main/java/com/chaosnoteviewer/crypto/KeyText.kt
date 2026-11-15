package com.chaosnoteviewer.crypto

import java.util.Locale
import java.util.zip.CRC32

// The encryption key as the user sees it (same as Crypto.js on desktop): 32 bytes as base32 (52 letters and
// digits) plus a 4 character checksum, so a typo is caught when the key is typed in. On one line (to copy)
// as 8 groups of 7 separated by dashes; displayed as 4 lines of "XXXXXXX-XXXXXXX". A key without the checksum
// (52 characters, as earlier versions showed it) is still accepted.
object KeyText {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val KEY_CHARS = 52
    private val ignored = Regex("[\\p{javaWhitespace}\\p{Z}-]")

    private fun base32(bytes: ByteArray): String {
        val s = StringBuilder()
        var acc = 0
        var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                s.append(ALPHABET[(acc shr bits) and 31])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0)
            s.append(ALPHABET[(acc shl (5 - bits)) and 31])
        return s.toString()
    }

    // 4 characters from the top 20 bits of the CRC-32 of the key
    private fun checksum(key: ByteArray): String {
        val crc = CRC32().also { it.update(key) }.value          // unsigned 32 bit
        return listOf(27, 22, 17, 12).map { ALPHABET[((crc shr it) and 31).toInt()] }.joinToString("")
    }

    private fun chars(key: ByteArray) = base32(key) + checksum(key)       // 56 characters

    // one line, 8 groups of 7: the form that is copied
    fun format(key: ByteArray): String = chars(key).chunked(7).joinToString("-")

    // four lines of 14 characters: the form that is displayed
    fun formatBlock(key: ByteArray): String =
        chars(key).chunked(7).chunked(2).joinToString("\n") { it.joinToString("-") }

    // The 32 key bytes from what the user typed (dashes, spaces, line breaks and case don't matter), or null.
    fun parse(text: String): ByteArray? {
        val s = text.replace(ignored, "").uppercase(Locale.ROOT)
        if (s.length != KEY_CHARS && s.length != KEY_CHARS + 4)
            return null
        val out = ArrayList<Byte>()
        var acc = 0
        var bits = 0
        for (i in 0 until KEY_CHARS) {
            val v = ALPHABET.indexOf(s[i])
            if (v < 0)
                return null
            acc = (acc shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 255).toByte())
                acc = acc and ((1 shl bits) - 1)
            }
        }
        // the 4 bits after the last byte must be zero
        if (acc != 0 || out.size != NoteCrypto.KEY_SIZE)
            return null
        val key = out.toByteArray()
        if (s.length == KEY_CHARS + 4 && s.substring(KEY_CHARS) != checksum(key))
            return null
        return key
    }
}
