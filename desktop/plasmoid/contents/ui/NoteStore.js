.pragma library
.import QtQuick.LocalStorage as Sql

// Local SQLite store for notes (Qt's LocalStorage). The widget is local-first:
// this database is the source of truth, nothing here needs a network or an account.

var _db = null

function db() {
    if (_db)
        return _db
    // empty version string = open whatever version exists, schema changes are handled below
    _db = Sql.LocalStorage.openDatabaseSync("ChaosNoteViewer", "", "Chaos Note Viewer notes", 1000000)
    _db.transaction(function (tx) {
        tx.executeSql("CREATE TABLE IF NOT EXISTS notes ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "title TEXT NOT NULL, "
            + "description TEXT NOT NULL DEFAULT '', "
            + "completed INTEGER NOT NULL DEFAULT 0, "
            + "position REAL NOT NULL, "          // queue order, lower = higher priority
            + "due_date INTEGER, "                // ms since epoch (local midnight of the day), NULL = no deadline
            + "created_at INTEGER NOT NULL, "
            + "updated_at INTEGER NOT NULL)")

        // databases created before deadlines existed
        var cols = tx.executeSql("PRAGMA table_info(notes)")
        var hasDue = false
        for (var i = 0; i < cols.rows.length; ++i)
            if (cols.rows.item(i).name === "due_date")
                hasDue = true
        if (!hasDue)
            tx.executeSql("ALTER TABLE notes ADD COLUMN due_date INTEGER")
    })
    return _db
}

function _row(r) {
    return {
        noteId: r.id,
        title: r.title,
        description: r.description,
        completed: r.completed === 1,
        position: r.position,
        dueDate: r.due_date === null ? 0 : r.due_date   // 0 = no deadline
    }
}

// All notes in queue order.
function all() {
    var out = []
    db().readTransaction(function (tx) {
        var rs = tx.executeSql("SELECT * FROM notes ORDER BY position ASC, id ASC")
        for (var i = 0; i < rs.rows.length; ++i)
            out.push(_row(rs.rows.item(i)))
    })
    return out
}

// Appends a note to the end of the queue and returns it. dueDate: ms since epoch, 0 or omitted = none.
function add(title, description, dueDate) {
    dueDate = dueDate || 0
    var note = null
    var now = Date.now()
    db().transaction(function (tx) {
        var pos = tx.executeSql("SELECT COALESCE(MAX(position), 0) + 1 AS p FROM notes").rows.item(0).p
        var rs = tx.executeSql("INSERT INTO notes (title, description, completed, position, due_date, created_at, updated_at) "
            + "VALUES (?, ?, 0, ?, ?, ?, ?)", [title, description, pos, dueDate > 0 ? dueDate : null, now, now])
        note = { noteId: Number(rs.insertId), title: title, description: description, completed: false, position: pos,
                 dueDate: dueDate }
    })
    return note
}

function remove(noteId) {
    db().transaction(function (tx) {
        tx.executeSql("DELETE FROM notes WHERE id = ?", [noteId])
    })
}

function setCompleted(noteId, completed) {
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET completed = ?, updated_at = ? WHERE id = ?",
            [completed ? 1 : 0, Date.now(), noteId])
    })
}
