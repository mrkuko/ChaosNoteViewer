package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.KeyText
import com.chaosnoteviewer.ui.QrCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCodeTest {
    private val apiKey = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"
    private val key = ByteArray(32) { ((it * 37 + 11) and 255).toByte() }

    // exactly what desktop/shared/SetupCode.js makes for the same project, API key and key
    private val desktop = "chaosnote:setup?v=1&p=my-notes-1a2b3&a=$apiKey" +
        "&k=BMYFK6U-7YTUQ4M-2YPWRMP-3ARGZNY-BJOK54K-DSXUDVD-G7EFZ4M-GDAVH2I"

    @Test fun sameTextAsTheDesktop() =
        assertEquals(desktop, SetupCode.make("my-notes-1a2b3", apiKey, KeyText.format(key)))

    @Test fun readsTheDesktopsCode() {
        val code = SetupCode.parse(desktop)!!
        assertEquals("my-notes-1a2b3", code.projectId)
        assertEquals(apiKey, code.apiKey)
        assertArrayEquals(key, code.key)
        // case of the prefix and surrounding blanks do not matter (scanners and pasting)
        assertNotNull(SetupCode.parse("  " + desktop.replace("chaosnote:setup?", "ChaosNote:Setup?") + "\n"))
    }

    @Test fun notASetupCode() {
        assertNull(SetupCode.parse("hello"))
        assertNull(SetupCode.parse(KeyText.format(key)))                       // a bare key (the old QR)
        assertNull(SetupCode.parse(desktop.replace("v=1", "v=2")))             // a later format
        assertNull(SetupCode.parse(desktop.replace("&a=$apiKey", "")))          // a part missing
        assertNull(SetupCode.parse(desktop.dropLast(5)))                        // a broken key
        assertNull(SetupCode.parse("chaosnote:setup?v=1&p=%zz&a=x&k=y"))         // broken escaping
        assertEquals("", SetupCode.make("", apiKey, "k"))
    }

    // a key field takes a setup code or the key itself
    @Test fun keyOf() {
        assertEquals(KeyText.format(key), SetupCode.keyOf(desktop))
        assertEquals("ABC", SetupCode.keyOf("ABC"))
    }

    // the longest project id still fits the QR code (version 10 at most)
    @Test fun fitsAQrCode() {
        val longest = SetupCode.make("a".repeat(30), apiKey, KeyText.format(key))
        assertTrue(longest.length < 200)
        assertNotNull(QrCode.encode(longest))
    }
}
