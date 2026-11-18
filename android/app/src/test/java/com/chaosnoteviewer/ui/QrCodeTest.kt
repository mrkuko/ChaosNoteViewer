package com.chaosnoteviewer.ui

import com.chaosnoteviewer.crypto.KeyText
import com.chaosnoteviewer.crypto.readVectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTest {
    // input -> rows, from src/test/resources/qr-golden.txt (made by the desktop encoder)
    private fun golden(): Map<String, List<String>> {
        // the first line is a comment; rows themselves start with "#" or "."
        val lines = javaClass.classLoader!!.getResource("qr-golden.txt")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (l in lines) {
            if (l.startsWith("\"")) {
                current = mutableListOf()
                out[org.json.JSONArray("[$l]").getString(0)] = current
            } else {
                current!!.add(l)
            }
        }
        return out
    }

    private fun rows(q: QrCode) = (0 until q.size).map { y -> (0 until q.size).joinToString("") { x -> if (q.isDark(x, y)) "#" else "." } }

    @Test
    fun sameSymbolAsTheDesktop() {
        val cases = golden()
        assertEquals(2, cases.size)
        for ((text, expected) in cases)
            assertEquals("QR of $text", expected, rows(QrCode.encode(text)!!))
    }

    @Test
    fun theKeyFitsVersion4() {
        val q = QrCode.encode("UGZMHVH-F6YDRQK-J2JNOG2-7UPSAIS-EM2EKVT-HPCEZVK-54ZXPO7-4AAYBDO")!!
        assertEquals(33, q.size)
    }

    @Test
    fun tooLongIsNull() {
        assertNull(QrCode.encode("x".repeat(300)))
        assertNotNull(QrCode.encode("x".repeat(200)))
    }

    // the QR holds exactly the one-line key: what the scanner returns must parse like typed text
    @Test
    fun scannedKeyTextParses() {
        val inputs = readVectors().getJSONArray("keyInputs")
        val first = inputs.getJSONObject(0)
        val key = KeyText.parse(first.getString("input"))!!
        val qrText = KeyText.format(key)
        assertEquals(first.getString("input"), qrText)
        assertArrayEquals(key, KeyText.parse(qrText))
        // anything around the key (a link, a prefix) is not a key
        assertNull(KeyText.parse("chaosnote:$qrText"))
        assertNull(KeyText.parse("https://example.com/?k=$qrText"))
    }
}
