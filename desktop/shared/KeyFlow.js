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
// A new key needs real randomness, which QML doesn't have: entropy(32, cb) asks the operating system for
// it, cb(error, bytes).
//
// Phases: "ready"  key known and confirmed, sync may run
//         "save"   a new key was made, the user must see it and confirm (sync waits)
//         "enter"  the cloud has a key this device doesn't know (or the key changed): ask for it
//         ""       could not decide yet (offline), try again later

function currentKey() { return Crypto.parseKey(NoteStore.getState("enc_key")) }   // bytes or null

// the key on one line, to copy
function keyText() {
    var key = currentKey()
    return key === null ? "" : Crypto.formatKey(key)
}

// the key as four lines, to display
function keyBlock() {
    var key = currentKey()
    return key === null ? "" : Crypto.formatKeyBlock(key)
}

function _store(key, confirmed) {
    NoteStore.setState("enc_key", Crypto.formatKey(key))
    NoteStore.setState("key_confirmed", confirmed ? "1" : "0")
}

function confirmSaved() { NoteStore.setState("key_confirmed", "1") }

// A key that came with a setup code: kept as confirmed (the user has it on the other device); begin() checks it
// against the cloud once signed in and asks again if it does not match.
function storeEntered(key) { _store(key, true) }

function _create(call, uid, key, done) {
    call(function (token, cb) { Sync.createKeyCheck(uid, token, key, cb) }, done)
}

// Decides what the user has to do. done(error, { phase, mismatch })
function begin(call, entropy, uid, done, depth) {
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
        function publish(key) {
            _create(call, uid, key, function (err2) {
                if (err2 && err2.code === "EXISTS" && !depth) {
                    begin(call, entropy, uid, done, 1)    // another device was first: look at its key
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
        }
        if (stored !== null) {
            publish(stored)
            return
        }
        entropy(32, function (err3, bytes) {
            if (err3)
                done(err3, { phase: "", mismatch: false })
            else
                publish(bytes)
        })
    })
}

// The user typed a key. done(error, { ok, message })
function enter(call, uid, text, done) {
    var key = Crypto.parseKey(text)
    if (key === null) {
        done(null, { ok: false, message: "That is not a valid key. Check it for typing mistakes: it has 56 letters and digits." })
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
function reset(call, entropy, uid, done) {
    entropy(32, function (errKey, key) {
        if (errKey) {
            done(errKey)
            return
        }
        call(function (token, cb) { Sync.wipeRemote(uid, token, cb) }, function (err) {
            if (err) {
                done(err)
                return
            }
            _store(key, false)
            NoteStore.forgetRemote()
            _create(call, uid, key, done)
        })
    })
}
