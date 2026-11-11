.pragma library
.import "NoteStore.js" as NoteStore
.import "Sync.js" as Sync
.import "Crypto.js" as Crypto

// The encryption key of the cloud copy: created on this device, shown to the user, typed in on other
// devices. It lives in the local database (sync_state: enc_key, key_confirmed), next to the notes it
// protects the cloud copy of, so storing it there exposes nothing that isn't already there.
//
// Every function that talks to the server takes call(fn, cb): it runs fn(idToken, cb) and takes care of
// an expired token, so this file doesn't need to know about sessions. done(error, result).
//
// Phases: "ready"  key known and confirmed, sync may run
//         "save"   a new key was made, the user must see it and confirm (sync waits)
//         "enter"  the cloud has a key this device doesn't know (or the key changed): ask for it
//         ""       could not decide yet (offline), try again later

function currentKey() { return Crypto.parseKey(NoteStore.getState("enc_key")) }   // bytes or null

function keyText() {
    var key = currentKey()
    return key === null ? "" : Crypto.formatKey(key)
}

function _store(key, confirmed) {
    NoteStore.setState("enc_key", Crypto.formatKey(key))
    NoteStore.setState("key_confirmed", confirmed ? "1" : "0")
}

function confirmSaved() { NoteStore.setState("key_confirmed", "1") }

function _create(call, uid, key, done) {
    call(function (token, cb) { Sync.createKeyCheck(uid, token, key, cb) }, done)
}

// Decides what the user has to do. done(error, { phase, mismatch })
function begin(call, uid, done, depth) {
    var stored = currentKey()
    var confirmed = NoteStore.getState("key_confirmed") === "1"
    call(function (token, cb) { Sync.fetchKeyCheck(uid, token, cb) }, function (err, check) {
        if (err) {
            // offline or similar: a confirmed key keeps working, the sync will report the real problem
            done(err, { phase: stored !== null && confirmed ? "ready" : "", mismatch: false })
            return
        }
        if (check !== null) {
            if (stored !== null && Sync.keyMatches(stored, check))
                done(null, { phase: confirmed ? "ready" : "save", mismatch: false })
            else
                done(null, { phase: "enter", mismatch: stored !== null })
            return
        }
        // no key in the cloud: publish ours, or make one when this is the first device
        var key = stored !== null ? stored : Crypto.generateKey()
        _create(call, uid, key, function (err2) {
            if (err2 && err2.code === "EXISTS" && !depth) {
                begin(call, uid, done, 1)             // another device was first: look at its key
                return
            }
            if (err2) {
                done(err2, { phase: "", mismatch: false })
                return
            }
            if (stored === null) {
                _store(key, false)
                NoteStore.markAllDirty()               // anything uploaded before without a key goes up encrypted
            }
            done(null, { phase: stored !== null && confirmed ? "ready" : "save", mismatch: false })
        })
    })
}

// The user typed a key. done(error, { ok, message })
function enter(call, uid, text, done) {
    var key = Crypto.parseKey(text)
    if (key === null) {
        done(null, { ok: false, message: "That is not a valid key. It has 52 letters and digits." })
        return
    }
    call(function (token, cb) { Sync.fetchKeyCheck(uid, token, cb) }, function (err, check) {
        if (err) {
            done(err, null)
        } else if (check === null) {
            done(null, { ok: false, message: "There is no key in the cloud any more. Close this and try again." })
        } else if (!Sync.keyMatches(key, check)) {
            done(null, { ok: false, message: "That key does not match the notes in the cloud." })
        } else {
            _store(key, true)
            done(null, { ok: true, message: "" })
        }
    })
}

// Key reset for a lost key: delete everything in the cloud, make a new key, upload what is on this
// device again. The old key is replaced only after the cloud was wiped, and the new check is created
// last: if that fails, begin() recreates it from the stored key next time. done(error)
function reset(call, uid, done) {
    var key = Crypto.generateKey()
    call(function (token, cb) { Sync.wipeRemote(uid, token, cb) }, function (err) {
        if (err) {
            done(err)
            return
        }
        _store(key, false)
        NoteStore.forgetRemote()
        _create(call, uid, key, done)
    })
}
