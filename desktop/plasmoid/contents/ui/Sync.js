.pragma library
.import "NoteStore.js" as NoteStore
.import "FirebaseConfig.js" as Config

// Firestore sync over its REST API: push what changed here, then pull what changed elsewhere.
// Everything is asynchronous: done(error, count). error is null on success, otherwise { code, message }
// where code is "UNAUTHENTICATED" (the id token expired: refresh it and retry), "NETWORK" or Firestore's
// status ("PERMISSION_DENIED", ...).
//
// Data model, see core/firebase/firestore.rules: users/{uid}/notes/{noteId} with
//   content          string, the whole note as JSON (this is where encryption will plug in)
//   serverUpdatedAt  set by the server on every write; the pull cursor orders by it
//
// Cost: a push is one request per 100 changed notes and nothing when nothing changed. A pull is one
// query that returns only documents newer than the cursor, so an idle poll reads no documents.

var PUSH_BATCH = 100
var PULL_PAGE = 200

function _root(uid) {
    return "projects/" + Config.projectId + "/databases/(default)/documents/users/" + uid
}

function _request(url, token, body, done) {
    var xhr = new XMLHttpRequest()
    xhr.timeout = 20000
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== XMLHttpRequest.DONE)
            return
        var data = null
        try { data = JSON.parse(xhr.responseText) } catch (e) { }
        if (xhr.status >= 200 && xhr.status < 300 && data !== null) {
            done(null, data)
        } else if (xhr.status === 0 || data === null) {
            done({ code: "NETWORK", message: "Could not reach Firestore." }, null)
        } else {
            var e = (Array.isArray(data) ? data[0] : data).error || {}
            done({ code: e.status || ("HTTP_" + xhr.status), message: e.message || "Sync failed." }, null)
        }
    }
    xhr.open("POST", url)
    xhr.setRequestHeader("Content-Type", "application/json")
    xhr.setRequestHeader("Authorization", "Bearer " + token)
    xhr.send(JSON.stringify(body))
}

function _encode(row) {
    return JSON.stringify({
        title: row.title,
        description: row.description,
        completed: row.completed === 1,
        position: row.position,
        dueDate: row.due_date,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        deletedAt: row.deleted_at
    })
}

// null when the document is not a note this version understands.
function _decode(content) {
    try {
        var n = JSON.parse(content)
        var nullableNumber = function (v) { return v === null || (typeof v === "number" && isFinite(v)) }
        if (typeof n.title !== "string" || typeof n.description !== "string" || typeof n.completed !== "boolean"
                || typeof n.position !== "number" || !isFinite(n.position)
                || typeof n.createdAt !== "number" || typeof n.updatedAt !== "number"
                || !nullableNumber(n.dueDate) || !nullableNumber(n.deletedAt))
            return null
        return n
    } catch (e) {
        return null
    }
}

// Firestore timestamps have microseconds; keep three digits so every JS engine parses them.
function _ms(ts) {
    return Date.parse(ts.replace(/(\.\d{3})\d*/, "$1"))
}

function push(uid, token, done) {
    var rows = NoteStore.dirtyRows()
    var sent = 0
    function next() {
        if (rows.length === 0) {
            done(null, sent)
            return
        }
        var batch = rows.splice(0, PUSH_BATCH)
        var writes = []
        for (var i = 0; i < batch.length; ++i)
            writes.push({
                update: { name: _root(uid) + "/notes/" + batch[i].id, fields: { content: { stringValue: _encode(batch[i]) } } },
                updateTransforms: [{ fieldPath: "serverUpdatedAt", setToServerValue: "REQUEST_TIME" }]
            })
        _request(Config.firestoreUrl + "/projects/" + Config.projectId + "/databases/(default)/documents:commit",
            token, { writes: writes }, function (err, d) {
                if (err) {
                    done(err, sent)
                    return
                }
                for (var i = 0; i < batch.length; ++i)
                    NoteStore.markPushed(batch[i].id, batch[i].updated_at, _ms(d.writeResults[i].updateTime))
                sent += batch.length
                next()
            })
    }
    next()
}

function pull(uid, token, done) {
    var changed = 0
    function page() {
        var query = {
            from: [{ collectionId: "notes" }],
            orderBy: [{ field: { fieldPath: "serverUpdatedAt" }, direction: "ASCENDING" },
                      { field: { fieldPath: "__name__" }, direction: "ASCENDING" }],
            limit: PULL_PAGE
        }
        var ts = NoteStore.getState("cursor_ts")
        if (ts !== "")
            query.startAt = { before: false, values: [{ timestampValue: ts },
                { referenceValue: _root(uid) + "/notes/" + NoteStore.getState("cursor_id") }] }
        _request(Config.firestoreUrl + "/" + _root(uid) + ":runQuery", token, { structuredQuery: query }, function (err, rows) {
            if (err) {
                done(err, changed)
                return
            }
            var items = []
            for (var i = 0; i < rows.length; ++i) {
                var doc = rows[i].document
                if (!doc || !doc.fields || !doc.fields.serverUpdatedAt)
                    continue
                var content = doc.fields.content
                var tsRaw = doc.fields.serverUpdatedAt.timestampValue
                items.push({ id: doc.name.split("/").pop(), tsRaw: tsRaw, tsMs: _ms(tsRaw),
                             note: content && typeof content.stringValue === "string" ? _decode(content.stringValue) : null })
            }
            changed += NoteStore.applyRemote(items, uid)
            if (items.length < PULL_PAGE)
                done(null, changed)
            else
                page()
        })
    }
    page()
}

// Push first so our newest edits are on the server, then pull. done(error, { pushed, pulled }).
function run(uid, token, done) {
    push(uid, token, function (err, pushed) {
        if (err) {
            done(err, { pushed: pushed, pulled: 0 })
            return
        }
        pull(uid, token, function (err2, pulled) {
            done(err2, { pushed: pushed, pulled: pulled })
        })
    })
}
