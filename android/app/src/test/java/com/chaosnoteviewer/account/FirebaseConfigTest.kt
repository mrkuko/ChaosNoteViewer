package com.chaosnoteviewer.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseConfigTest {
    private val key = "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"   // made up, the shape of a real one

    @Test fun keyShape() {
        assertNull(FirebaseConfig.keyProblem(key))
        assertNull(FirebaseConfig.keyProblem("  $key "))
        assertNotNull(FirebaseConfig.keyProblem(""))
        assertNotNull(FirebaseConfig.keyProblem("AIza-too-short"))
        assertNotNull(FirebaseConfig.keyProblem("x" + key.drop(1)))
    }

    @Test fun projectShape() {
        assertNull(FirebaseConfig.projectProblem("my-notes-1a2b3"))
        assertNull(FirebaseConfig.projectProblem("chaosnoteviewer"))
        assertNotNull(FirebaseConfig.projectProblem(""))
        assertNotNull(FirebaseConfig.projectProblem("My-Notes"))
        assertNotNull(FirebaseConfig.projectProblem("1notes"))
        assertNotNull(FirebaseConfig.projectProblem("notes-"))
        assertNotNull(FirebaseConfig.projectProblem("abc"))
    }

    // the web app snippet the Firebase console shows
    @Test fun extractFromTheWebSnippet() {
        val snippet = """
            const firebaseConfig = {
              apiKey: "$key",
              authDomain: "my-notes-1a2b3.firebaseapp.com",
              projectId: "my-notes-1a2b3",
              storageBucket: "my-notes-1a2b3.firebasestorage.app",
            };
        """.trimIndent()
        assertEquals(key to "my-notes-1a2b3", FirebaseConfig.extract(snippet))
    }

    @Test fun extractFromGoogleServicesJson() {
        val json = """{"project_info": {"project_number": "123", "project_id": "my-notes-1a2b3"},
            "client": [{"api_key": [{"current_key": "$key"}]}]}"""
        assertEquals(key to "my-notes-1a2b3", FirebaseConfig.extract(json))
    }

    @Test fun extractFindsABareKey() =
        assertEquals(key to null, FirebaseConfig.extract("the key is $key, enjoy"))

    @Test fun urls() {
        val real = FirebaseConfig(key, "p")
        assertEquals("https://identitytoolkit.googleapis.com/v1", real.authUrl)
        assertEquals("https://securetoken.googleapis.com/v1", real.tokenUrl)
        assertEquals("https://firestore.googleapis.com/v1", real.firestoreUrl)
        assertEquals("http://localhost:43099/identitytoolkit.googleapis.com/v1", FirebaseConfig.EMULATOR.authUrl)
        assertEquals("http://localhost:43080/v1", FirebaseConfig.EMULATOR.firestoreUrl)
    }
}
