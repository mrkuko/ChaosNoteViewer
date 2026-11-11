.pragma library
.import "NoteStore.js" as NoteStore
.import "Crypto.js" as Crypto
.import "FirebaseConfig.js" as Config

// Firestore sync over its REST API: push what changed here, then pull what changed elsewhere.
// Everything is asynchronous: done(error, result). error is null on success, otherwise { code, message }
// where code is "UNAUTHENTICATED" (the id token expired: refresh it and retry), "NETWORK", "BAD_KEY"
// (a document can't be decrypted with this key) or Firestore's status ("PERMISSION_DENIED", ...).
//
// Data model, see core/firebase/firestore.rules:
//   users/{uid}/notes/{noteId}   content: "v1.<encrypted JSON of the note>" (Crypto.js), serverUpdatedAt
//   users/{uid}/meta/keycheck    check: a known text encrypted with the key, proves a typed key is right
//
// Deleting is real: the delete is sent first and the local row is removed only after the server
// confirmed it (deleting something that is already gone counts as confirmed). Notes deleted elsewhere
// stay on the devices that still have them; reconcile() finds those so the UI can mark them.
//
// Cost: a push is one request per 100 changes and nothing when nothing changed. A pull is one query
// returning only documents newer than the cursor, so an idle poll reads no documents.

var PUSH_BATCH = 100
var PULL_PAGE = 200
var LIST_PAGE = 1000
var DELETE_BATCH = 400
var CHECK_TEXT = "chaosnoteviewer key check"

function _db() { return "projects/" + Config.projectId + "/databases/(default)/documents" }
function _root(uid) { return _db() + "/users/" + uid }
function _checkName(uid) { return _root(uid) + "/meta/keycheck" }

function _request(method, url, token, body, done) {
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
    xhr.open(method, url)
    if (body !== null)
        xhr.setRequestHeader("Content-Type", "application/json")
    xhr.setRequestHeader("Authorization", "Bearer " + token)
    xhr.send(body === null ? null : JSON.stringify(body))
}

function _post(url, token, body, done) { _request("POST", url, token, body, done) }

function _commit(token, writes, done) {
    _post(Config.firestoreUrl + "/" + _db() + ":commit", token, { writes: writes }, done)
}

function _ms(ts) {
    // Firestore timestamps have microseconds; keep three digits so every JS engine parses them
    return Date.parse(ts.replace(/(\.\d{3})\d*/, "$1"))
}

// ---------- the note as stored in the cloud ----------

function _encode(key, row) {
    return Crypto.encrypt(key, JSON.stringify({
        title: row.title,
        description: row.description,
        completed: row.completed === 1,
        position: row.position,
        dueDate: row.due_date,
        createdAt: row.created_at,
        updatedAt: row.updated_at
    }), row.id)
}

// null when it is not a note this version understands
function _parse(json) {
    try {
        var n = JSON.parse(json)
        if (typeof n.title !== "string" || typeof n.description !== "string" || typeof n.completed !== "boolean"
                || typeof n.position !== "number" || !isFinite(n.position)
                || typeof n.createdAt !== "number" || typeof n.updatedAt !== "number"
                || !(n.dueDate === null || (typeof n.dueDate === "number" && isFinite(n.dueDate))))
            return null
        return n
    } catch (e) {
        return null
    }
}

// ---------- push ----------

function push(uid, token, key, done) {
    var rows = NoteStore.dirtyRows()
    var sent = 0
    var queue = []
    for (var i = 0; i < rows.length; ++i) {
        var row = rows[i]
        if (row.deleted_at === null)
            queue.push({ row: row, remove: false })
        else if (row.remote_updated_at === null)
            NoteStore.purge(row.id, row.updated_at)             // never uploaded: nothing to delete
        else
            queue.push({ row: row, remove: true })
    }
    function next() {
        if (queue.length === 0) {
            done(null, sent)
            return
        }
        var batch = queue.splice(0, PUSH_BATCH)
        var writes = []
        for (var i = 0; i < batch.length; ++i) {
            var name = _root(uid) + "/notes/" + batch[i].row.id
            if (batch[i].remove)
                writes.push({ "delete": name })
            else
                writes.push({
                    update: { name: name, fields: { content: { stringValue: _encode(key, batch[i].row) } } },
                    updateTransforms: [{ fieldPath: "serverUpdatedAt", setToServerValue: "REQUEST_TIME" }]
                })
        }
        _commit(token, writes, function (err, d) {
            if (err) {
                done(err, sent)
                return
            }
            for (var i = 0; i < batch.length; ++i) {
                if (batch[i].remove)
                    NoteStore.purge(batch[i].row.id, batch[i].row.updated_at)
                else
                    NoteStore.markPushed(batch[i].row.id, batch[i].row.updated_at, _ms(d.writeResults[i].updateTime))
            }
            sent += batch.length
            next()
        })
    }
    next()
}

// ---------- pull ----------

function pull(uid, token, key, done) {
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
        _post(Config.firestoreUrl + "/" + _root(uid) + ":runQuery", token, { structuredQuery: query }, function (err, rows) {
            if (err) {
                done(err, changed)
                return
            }
            var items = []
            for (var i = 0; i < rows.length; ++i) {
                var doc = rows[i].document
                if (!doc || !doc.fields || !doc.fields.serverUpdatedAt)
                    continue
                var id = doc.name.split("/").pop()
                var content = doc.fields.content && doc.fields.content.stringValue
                var tsRaw = doc.fields.serverUpdatedAt.timestampValue
                var note = null
                if (typeof content === "string" && content.indexOf(Crypto.VERSION) === 0) {
                    var json = Crypto.decrypt(key, content, id)
                    if (json === null) {
                        // encrypted for us but not readable: a different key. Stop before changing anything.
                        done({ code: "BAD_KEY", message: "The encryption key does not fit the notes in the cloud." }, changed)
                        return
                    }
                    note = _parse(json)
                }
                // anything else (not encrypted, other format) is skipped, the cursor still moves past it
                items.push({ id: id, tsRaw: tsRaw, tsMs: _ms(tsRaw), note: note })
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
function run(uid, token, key, done) {
    push(uid, token, key, function (err, pushed) {
        if (err) {
            done(err, { pushed: pushed, pulled: 0 })
            return
        }
        pull(uid, token, key, function (err2, pulled) {
            done(err2, { pushed: pushed, pulled: pulled })
        })
    })
}

// ---------- notes that no longer exist on the server ----------

// All note ids on the server (names only, so no content is transferred).
function _remoteIds(uid, token, done) {
    var ids = []
    function page(after) {
        var query = {
            from: [{ collectionId: "notes" }],
            select: { fields: [{ fieldPath: "__name__" }] },
            orderBy: [{ field: { fieldPath: "__name__" }, direction: "ASCENDING" }],
            limit: LIST_PAGE
        }
        if (after !== null)
            query.startAt = { before: false, values: [{ referenceValue: _root(uid) + "/notes/" + after }] }
        _post(Config.firestoreUrl + "/" + _root(uid) + ":runQuery", token, { structuredQuery: query }, function (err, rows) {
            if (err) {
                done(err, null)
                return
            }
            var got = 0
            for (var i = 0; i < rows.length; ++i)
                if (rows[i].document) {
                    ids.push(rows[i].document.name.split("/").pop())
                    ++got
                }
            if (got < LIST_PAGE)
                done(null, ids)
            else
                page(ids[ids.length - 1])
        })
    }
    page(null)
}

// Ids of notes this device has synced before that are not on the server any more. After run(), every
// note on the server is also here, so the counts match unless something vanished (or is unreadable);
// only then are the ids fetched. done(error, [id, ...]).
function reconcile(uid, token, done) {
    var local = NoteStore.syncedIds()
    _post(Config.firestoreUrl + "/" + _root(uid) + ":runAggregationQuery", token, {
        structuredAggregationQuery: {
            structuredQuery: { from: [{ collectionId: "notes" }] },
            aggregations: [{ alias: "n", count: {} }]
        }
    }, function (err, d) {
        if (err) {
            done(err, null)
            return
        }
        var remote = parseInt(d[0].result.aggregateFields.n.integerValue, 10)
        if (remote === local.length) {
            done(null, [])
            return
        }
        _remoteIds(uid, token, function (err2, ids) {
            if (err2) {
                done(err2, null)
                return
            }
            var there = {}
            for (var i = 0; i < ids.length; ++i)
                there[ids[i]] = true
            var missing = []
            for (var j = 0; j < local.length; ++j)
                if (!there[local[j]])
                    missing.push(local[j])
            done(null, missing)
        })
    })
}

// ---------- the key check document ----------

// done(error, text | null): null when there is none yet.
function fetchKeyCheck(uid, token, done) {
    _request("GET", Config.firestoreUrl + "/" + _checkName(uid), token, null, function (err, d) {
        if (err && err.code === "NOT_FOUND")
            done(null, null)
        else if (err)
            done(err, null)
        else
            done(null, d.fields && d.fields.check ? d.fields.check.stringValue : "")
    })
}

function keyMatches(key, checkText) {
    return Crypto.decrypt(key, checkText, "keycheck") === CHECK_TEXT
}

// Creates the check, but only when there is none, so two devices can't each make their own key.
// error.code is "EXISTS" when another device was first.
function createKeyCheck(uid, token, key, done) {
    _commit(token, [{
        update: { name: _checkName(uid), fields: { check: { stringValue: Crypto.encrypt(key, CHECK_TEXT, "keycheck") } } },
        currentDocument: { exists: false }
    }], function (err) {
        if (err && (err.code === "ALREADY_EXISTS" || err.code === "FAILED_PRECONDITION"))
            done({ code: "EXISTS", message: "Another device created the key first." })
        else
            done(err)
    })
}

// Deletes every note and the key check from the cloud (key reset).
function wipeRemote(uid, token, done) {
    _remoteIds(uid, token, function (err, ids) {
        if (err) {
            done(err)
            return
        }
        var names = ids.map(function (id) { return _root(uid) + "/notes/" + id })
        names.push(_checkName(uid))
        function next() {
            if (names.length === 0) {
                done(null)
                return
            }
            var writes = names.splice(0, DELETE_BATCH).map(function (name) { return { "delete": name } })
            _commit(token, writes, function (err2) {
                if (err2)
                    done(err2)
                else
                    next()
            })
        }
        next()
    })
}
