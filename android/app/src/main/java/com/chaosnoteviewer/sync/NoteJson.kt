package com.chaosnoteviewer.sync

import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.RemoteNote
import org.json.JSONException
import org.json.JSONObject

// The note as it is encrypted into the cloud: the same JSON as desktop (Sync.js _encode / _parse).
// The note id is not inside (it is the document name and the authenticated data of the encryption).
// "starred" is optional when reading: a note written before stars existed (or by an older client) counts as not starred.
object NoteJson {
    fun encode(n: NoteEntity): String = JSONObject().apply {
        put("title", n.title)
        put("description", n.description)
        put("completed", n.completed)
        put("starred", n.starred)
        put("position", n.position)
        put("dueDate", n.dueDate ?: JSONObject.NULL)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
    }.toString()

    // null when it is not a note this version understands (strict like desktop, nothing is guessed)
    fun parse(json: String): RemoteNote? = try {
        val o = JSONObject(json)
        val title = o.opt("title") as? String
        val description = o.opt("description") as? String
        val completed = o.opt("completed") as? Boolean
        val position = (o.opt("position") as? Number)?.toDouble()
        val createdAt = (o.opt("createdAt") as? Number)?.toLong()
        val updatedAt = (o.opt("updatedAt") as? Number)?.toLong()
        val dueRaw = o.opt("dueDate")
        val dueOk = dueRaw == null || dueRaw == JSONObject.NULL || dueRaw is Number
        val starredRaw = o.opt("starred")
        val starredOk = starredRaw == null || starredRaw is Boolean      // a value of another type: not a note we understand
        if (title == null || description == null || completed == null || position == null || !position.isFinite() ||
            createdAt == null || updatedAt == null || !dueOk || !starredOk
        ) null
        else RemoteNote(
            title, description, completed, starredRaw == true, position, (dueRaw as? Number)?.toLong(), createdAt, updatedAt,
        )
    } catch (e: JSONException) {
        null
    }
}
