package com.chaosnoteviewer.sync

import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.RemoteNote
import org.json.JSONException
import org.json.JSONObject

// The note as it is encrypted into the cloud: the same JSON as desktop (Sync.js _encode / _parse).
// The note id is not inside (it is the document name and the authenticated data of the encryption).
object NoteJson {
    fun encode(n: NoteEntity): String = JSONObject().apply {
        put("title", n.title)
        put("description", n.description)
        put("completed", n.completed)
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
        if (title == null || description == null || completed == null || position == null || !position.isFinite() ||
            createdAt == null || updatedAt == null || !dueOk
        ) null
        else RemoteNote(title, description, completed, position, (dueRaw as? Number)?.toLong(), createdAt, updatedAt)
    } catch (e: JSONException) {
        null
    }
}
