package com.chaosnoteviewer.sync

import com.chaosnoteviewer.data.NoteEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoteJsonTest {
    private val note = NoteEntity(
        id = "3f2b8c1e", title = "Zaplatiť faktúry", description = "https://example.com\nDo piatku.", completed = true,
        position = 2.5, dueDate = 1781049600000, createdAt = 1778000000000, updatedAt = 1778000123456,
    )

    @Test fun roundTrip() {
        val parsed = NoteJson.parse(NoteJson.encode(note))!!
        assertEquals(note.title, parsed.title)
        assertEquals(note.description, parsed.description)
        assertEquals(true, parsed.completed)
        assertEquals(2.5, parsed.position, 0.0)
        assertEquals(1781049600000, parsed.dueDate)
        assertEquals(1778000000000, parsed.createdAt)
        assertEquals(1778000123456, parsed.updatedAt)
    }

    @Test fun theStarTravels() {
        assertEquals(true, NoteJson.parse(NoteJson.encode(note.copy(starred = true)))!!.starred)
        assertEquals(false, NoteJson.parse(NoteJson.encode(note.copy(starred = false)))!!.starred)
    }

    // an older client (or the desktop before stars) writes no "starred": the note is readable and not starred
    @Test fun aMissingStarMeansNotStarred() {
        val old = """{"title":"a","description":"","completed":false,"position":1,"dueDate":null,"createdAt":1,"updatedAt":2}"""
        assertEquals(false, NoteJson.parse(old)!!.starred)
    }

    // what the desktop writes now (Sync.js _encode with a starred note)
    @Test fun readsTheDesktopFormWithAStar() {
        val n = NoteJson.parse("""{"title":"a","description":"","completed":false,"starred":true,"position":3,"dueDate":null,"createdAt":1,"updatedAt":2}""")!!
        assertEquals(true, n.starred)
    }

    @Test fun aStarOfAnotherTypeIsNotANote() {
        val ok = """{"title":"a","description":"","completed":false,"position":1,"dueDate":null,"createdAt":1,"updatedAt":2"""
        assertNull(NoteJson.parse("$ok,\"starred\":\"yes\"}"))
        assertNull(NoteJson.parse("$ok,\"starred\":null}"))         // like the desktop: null is not a boolean
        assertNull(NoteJson.parse("$ok,\"starred\":1}"))
        assertNotNull(NoteJson.parse("$ok,\"starred\":false}"))
    }

    @Test fun noDeadlineIsNull() = assertNull(NoteJson.parse(NoteJson.encode(note.copy(dueDate = null)))!!.dueDate)

    @Test fun theIdIsNotInsideTheJson() = assertEquals(
        setOf("title", "description", "completed", "starred", "position", "dueDate", "createdAt", "updatedAt"),
        JSONObject(NoteJson.encode(note)).keys().asSequence().toSet(),
    )

    // what the desktop app writes (JSON.stringify of the same fields, null deadline, integer position)
    @Test fun readsTheDesktopForm() {
        val n = NoteJson.parse("""{"title":"a","description":"","completed":false,"position":3,"dueDate":null,"createdAt":1,"updatedAt":2}""")!!
        assertEquals(3.0, n.position, 0.0)
        assertNull(n.dueDate)
    }

    @Test fun refusesWhatIsNotANote() {
        val ok = """{"title":"a","description":"","completed":false,"position":1,"dueDate":null,"createdAt":1,"updatedAt":2}"""
        assertNotNull(NoteJson.parse(ok))
        for (bad in listOf(
            "not json", "[]", "{}", "null",
            ok.replace("\"title\":\"a\"", "\"title\":5"),
            ok.replace("\"completed\":false", "\"completed\":\"no\""),
            ok.replace("\"position\":1", "\"position\":\"1\""),
            ok.replace("\"dueDate\":null", "\"dueDate\":\"soon\""),
            ok.replace("\"updatedAt\":2", "\"updatedAt\":null"),
            ok.replace(",\"createdAt\":1", ""),
        ))
            assertNull(bad, NoteJson.parse(bad))
    }
}
