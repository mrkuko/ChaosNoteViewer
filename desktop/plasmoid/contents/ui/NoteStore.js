.pragma library
.import QtQuick.LocalStorage as Sql
.import "Migrations.js" as Migrations

// Local SQLite store for notes (Qt's LocalStorage). The widget is local-first:
// this database is the source of truth, nothing here needs a network or an account.
// The schema is shared with every client: core/storage/migrations/, copied into
// Migrations.js by desktop/install.sh. Rules (ids, soft delete, updated_at, dirty) are described there.

var _db = null

function db() {
    if (_db)
        return _db
    // empty version string = open whatever version exists, the schema version lives in PRAGMA user_version
    _db = Sql.LocalStorage.openDatabaseSync("ChaosNoteViewer", "", "Chaos Note Viewer notes", 1000000)
    _db.transaction(function (tx) {
        var version = tx.executeSql("PRAGMA user_version").rows.item(0).user_version

        if (version === 0 && _hasTable(tx, "notes")) {
            // database from the first plasmoid builds (integer ids)
            if (!_hasColumn(tx, "notes", "due_date"))
                tx.executeSql("ALTER TABLE notes ADD COLUMN due_date INTEGER")
            _run(tx, Migrations.legacyToV1)
            version = 1
        }
        for (var i = 0; i < Migrations.migrations.length; ++i) {
            var m = Migrations.migrations[i]
            if (m.version > version)
                _run(tx, m.statements)
        }
    })
    return _db
}

function _run(tx, statements) {
    for (var i = 0; i < statements.length; ++i)
        tx.executeSql(statements[i])
}

function _hasTable(tx, name) {
    return tx.executeSql("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", [name]).rows.length > 0
}

function _hasColumn(tx, table, column) {
    var cols = tx.executeSql("PRAGMA table_info(" + table + ")")
    for (var i = 0; i < cols.rows.length; ++i)
        if (cols.rows.item(i).name === column)
            return true
    return false
}

function _uuid() {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
        var r = Math.floor(Math.random() * 16)
        return (c === "x" ? r : (r & 3) | 8).toString(16)
    })
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

// All live notes in queue order.
function all() {
    var out = []
    db().readTransaction(function (tx) {
        var rs = tx.executeSql("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY position ASC, id ASC")
        for (var i = 0; i < rs.rows.length; ++i)
            out.push(_row(rs.rows.item(i)))
    })
    return out
}

// Appends a note to the end of the queue and returns it. dueDate: ms since epoch, 0 or omitted = none.
// author_id stays NULL until accounts exist (claimed on first sign-in).
function add(title, description, dueDate) {
    dueDate = dueDate || 0
    var note = null
    var now = Date.now()
    var id = _uuid()
    db().transaction(function (tx) {
        var pos = tx.executeSql("SELECT COALESCE(MAX(position), 0) + 1 AS p FROM notes WHERE deleted_at IS NULL").rows.item(0).p
        tx.executeSql("INSERT INTO notes (id, title, description, completed, position, due_date, created_at, updated_at, dirty) "
            + "VALUES (?, ?, ?, 0, ?, ?, ?, ?, 1)", [id, title, description, pos, dueDate > 0 ? dueDate : null, now, now])
        note = { noteId: id, title: title, description: description, completed: false, position: pos, dueDate: dueDate }
    })
    return note
}

// Soft delete: the tombstone is needed so sync can propagate the deletion.
function remove(noteId) {
    var now = Date.now()
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET deleted_at = ?, updated_at = ?, dirty = 1 WHERE id = ?", [now, now, noteId])
    })
}

function setCompleted(noteId, completed) {
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET completed = ?, updated_at = ?, dirty = 1 WHERE id = ?",
            [completed ? 1 : 0, Date.now(), noteId])
    })
}

// ---- Priority queue ----
// Order is `position` ascending (lower = higher priority). A move only writes the moved note.

var MIN_GAP = 1e-9

// New position for the note that was shown at row `from` and is dropped at row `to` (its row after the
// move) in a list whose notes have the positions `visible`. Returns null when nothing changes, NaN when
// the neighbours are too close together (call renumber() and try again).
function dropPosition(visible, from, to) {
    if (from === to)
        return null
    var others = visible.slice(0, from).concat(visible.slice(from + 1))
    if (to <= 0)
        return others[0] - 1
    if (to >= others.length)
        return others[others.length - 1] + 1
    if (others[to] - others[to - 1] < MIN_GAP)
        return NaN
    return (others[to - 1] + others[to]) / 2
}

function setPosition(noteId, position) {
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET position = ?, updated_at = ?, dirty = 1 WHERE id = ?",
            [position, Date.now(), noteId])
    })
}

// Renumbers live notes 1, 2, 3, ... keeping their order (when neighbours got too close).
function renumber() {
    var now = Date.now()
    db().transaction(function (tx) {
        var rs = tx.executeSql("SELECT id FROM notes WHERE deleted_at IS NULL ORDER BY position ASC, id ASC")
        for (var i = 0; i < rs.rows.length; ++i)
            tx.executeSql("UPDATE notes SET position = ?, updated_at = ?, dirty = 1 WHERE id = ?",
                [i + 1, now, rs.rows.item(i).id])
    })
}

// ---- Account state (table sync_state, key/value) ----
// uid            Firebase uid this database is bound to; notes here belong to that account
// email          shown in the UI
// refresh_token  lets the widget sign in again without a password (stored unencrypted in this database)

function getState(key) {
    var value = ""
    db().readTransaction(function (tx) {
        var rs = tx.executeSql("SELECT value FROM sync_state WHERE key = ?", [key])
        if (rs.rows.length > 0)
            value = rs.rows.item(0).value
    })
    return value
}

function setState(key, value) {
    db().transaction(function (tx) {
        tx.executeSql("INSERT INTO sync_state (key, value) VALUES (?, ?) "
            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", [key, value])
    })
}

function clearState(key) {
    db().transaction(function (tx) {
        tx.executeSql("DELETE FROM sync_state WHERE key = ?", [key])
    })
}

// Notes written before any sign-in have no author; the first account that signs in here owns them.
function claimNotes(uid) {
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET author_id = ?, updated_at = ?, dirty = 1 WHERE author_id IS NULL",
            [uid, Date.now()])
    })
}

// The account these notes belonged to was deleted and the same email registered again: hand the notes over
// and mark them dirty so they sync to the new account.
function rebindNotes(oldUid, newUid) {
    db().transaction(function (tx) {
        tx.executeSql("UPDATE notes SET author_id = ?, updated_at = ?, dirty = 1 WHERE author_id = ?",
            [newUid, Date.now(), oldUid])
    })
}
