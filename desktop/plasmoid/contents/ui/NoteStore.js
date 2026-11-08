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
