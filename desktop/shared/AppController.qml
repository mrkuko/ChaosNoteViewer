import QtQuick
import "NoteStore.js" as NoteStore
import "Auth.js" as Auth
import "Sync.js" as Sync
import "KeyFlow.js" as KeyFlow
import "FirebaseConfig.js" as FirebaseConfig

// The whole non-visual part of the app, shared by every launcher (Plasma widget, Windows/Linux tray app):
// notes, account (Firebase Auth), encryption key, sync. A launcher only provides
//   active    true while the user is looking at the app (it polls faster then)
//   entropy   function (n, callback) giving n random bytes from the operating system, callback(error, bytes).
//             QML has no secure random numbers; each platform supplies its own, see the launchers.
// and binds its UI to the properties and functions below.
Item {
    id: root
    visible: false

    property bool active: false
    property var entropy: null

    // texts with %1, %2: the launchers' i18n() is not available here
    function fmt(text) {
        let s = text
        for (let i = 1; i < arguments.length; ++i)
            s = s.replace("%" + i, arguments[i])
        return s
    }

    function randomBytes(n, callback) {
        if (entropy)
            entropy(n, callback)
        else
            callback({ code: "RANDOM", message: "This app has no source of secure random numbers." }, null)
    }

    function keyText() { return KeyFlow.keyText() }

    // false while there is no Firebase API key, then there is nothing to sign in to. The key is entered
    // in the app (Settings, or the first-run setup) and kept in this device's database; a key built in
    // from desktop/firebase.local.json is the default.
    property bool configured: FirebaseConfig.configured
    property string apiKey: FirebaseConfig.apiKey
    // the first-run setup was shown (and saved or skipped), so the app opens on the notes from now on
    property bool setupSeen: true

    function setApiKey(key) {
        key = key.trim()
        NoteStore.setState("api_key", key)
        FirebaseConfig.setApiKey(key)
        apiKey = key
        configured = key !== ""
        authError = ""
        setupSeen = true
        NoteStore.setState("setup_seen", "1")
        if (configured && userEmail !== "" && idToken === "")
            restoreSession()
    }

    function skipSetup() {
        setupSeen = true
        NoteStore.setState("setup_seen", "1")
    }

    // Which notes the list shows: 0 = open (default), 1 = completed, 2 = all,
    // 3 = important (open and overdue or due within IMPORTANT_DAYS days, today included).
    property int filter: 0
    readonly property int importantDays: 3
    // Order of the list. sortField: 0 = your own order (drag to reorder), 1 = name, 2 = deadline,
    // 3 = date created, 4 = upcoming then overdue (only in the important view, and its default there).
    // sortDesc flips the direction (name A-Z / Z-A, deadline closest / furthest first, created oldest /
    // newest first; 4 has none). Any field but 0 is a forced order: dragging is off while it is on.
    // The important view keeps its own order, the other views share one. Both are kept on this device
    // (sync_state sort_field, sort_desc and important_sort_field, important_sort_desc), not synced.
    property int sortField: 0
    property bool sortDesc: false
    readonly property bool sorted: sortField !== 0
    readonly property int upcomingSort: 4
    readonly property var sortFieldNames: ["Your order", "Name", "Deadline", "Date created", "Upcoming, then overdue"]
    // the two directions of each field, [ascending, descending]; 0 and 4 have none
    readonly property var sortDirectionNames: [["", ""], ["A–Z", "Z–A"], ["Closest first", "Furthest first"], ["Oldest first", "Newest first"], ["", ""]]
    // icons of the two directions, same order as sortDirectionNames (icons/<name>.png)
    readonly property var sortDirectionIcons: [["", ""], ["sort-az", "sort-za"], ["sort-up", "sort-down"], ["sort-up", "sort-down"], ["", ""]]
    readonly property bool sortHasDirection: sortField !== 0 && sortField !== upcomingSort
    readonly property string sortText: sortHasDirection ? sortFieldNames[sortField] + ": " + sortDirectionNames[sortField][sortDesc ? 1 : 0]
                                                        : sortFieldNames[sortField]
    // true while the order of another view is being loaded, so loading it doesn't save it again
    property bool loadingSort: false

    // where the order of the current view is kept
    function sortKey(name) {
        return filter === 3 ? "important_" + name : name
    }

    // the saved order of the current view; the important view starts with upcoming-then-overdue
    function loadSort() {
        loadingSort = true
        const saved = parseInt(NoteStore.getState(sortKey("sort_field")), 10)
        const valid = saved >= 0 && saved < sortFieldNames.length && (saved !== upcomingSort || filter === 3)
        sortDesc = NoteStore.getState(sortKey("sort_desc")) === "1"
        sortField = valid ? saved : filter === 3 ? upcomingSort : 0
        loadingSort = false
    }

    // choosing a field starts with its natural direction: A-Z, closest first, newest first
    function setSortField(field) {
        setSort(field, field === 3)
    }

    function setSort(field, desc) {
        sortDesc = desc
        sortField = field
    }
    // The app is local-first and works without an account; signing in is only needed to sync.
    property string userEmail: ""      // "" = not signed in
    property string idToken: ""        // Firebase id token (valid for an hour), kept in memory only
    property bool authBusy: false
    property string authError: ""
    property string authInfo: ""       // confirmation shown under the account form, e.g. "email sent"
    // Account that signed in but has not confirmed its email yet ({ uid, email, idToken, refreshToken }),
    // null otherwise. It is not a session: nothing is bound or stored until the email is verified.
    property var pending: null
    property bool syncing: false
    property string syncError: ""     // why the last sync failed, "" when it worked
    property bool syncAgain: false     // a change arrived while a sync was running
    property int syncFailures: 0       // consecutive failed syncs, slows the polling down
    // Encryption key of the cloud copy (KeyFlow.js): "" = not decided yet (offline), "enter" = the cloud
    // has a key this device doesn't know, "save" = a new key must be shown and confirmed, "ready" = sync runs.
    property string keyPhase: ""
    property bool keyMismatch: false   // the key was changed on another device
    property bool keyBusy: false
    property int keyRun: 0             // identifies the running key task, a stale one is ignored
    property string keyError: ""
    property string keyValue: ""       // the key as shown to the user, "" until needed
    property bool keyShown: false
    property bool resetOpen: false
    // Notes this device had synced that are gone from the server (deleted on another device).
    property var missingIds: ({})
    property double lastReconcile: 0

    // All notes, loaded from the local SQLite database (see NoteStore.js).
    ListModel { id: notesModel }
    property alias notes: notesModel

    // Filtered copy of `notes`; srcIndex maps a row back to its source note.
    ListModel { id: shownModel }
    property alias shown: shownModel

    function matches(n) {
        if (filter === 1)
            return n.completed
        if (filter === 2)
            return true
        if (filter === 3)
            return !n.completed && n.dueDate > 0 && n.dueDate < startOfToday() + importantDays * 86400000
        return !n.completed
    }

    // notes without a deadline go last in both deadline directions; ties keep your own order
    function compare(a, b) {
        const dir = sortDesc ? -1 : 1
        let d = 0
        if (sortField === upcomingSort) {
            // upcoming first, closest deadline first; then the overdue ones, the most recently missed first
            const today = startOfToday()
            const aLate = a.dueDate < today, bLate = b.dueDate < today
            if (aLate !== bLate)
                d = aLate ? 1 : -1
            else
                d = aLate ? b.dueDate - a.dueDate : a.dueDate - b.dueDate
        } else if (sortField === 1) {
            d = a.title.localeCompare(b.title) * dir
        } else if (sortField === 2) {
            if (a.dueDate > 0 && b.dueDate > 0)
                d = (a.dueDate - b.dueDate) * dir
            else if (a.dueDate > 0)
                d = -1
            else if (b.dueDate > 0)
                d = 1
        } else if (sortField === 3) {
            d = (a.createdAt - b.createdAt) * dir
        }
        return d !== 0 ? d : a.position - b.position
    }

    // bumped on every refresh(), so views of one note (the detail page) can follow its changes
    property int revision: 0

    function refresh() {
        ++revision
        const rows = []
        // in upcoming-then-overdue order the overdue notes are their own section (ListView.section)
        const split = sortField === upcomingSort && filter === 3
        const today = startOfToday()
        for (let i = 0; i < notes.count; ++i) {
            const n = notes.get(i)
            if (matches(n))
                rows.push({ noteId: n.noteId, title: n.title, description: n.description, completed: n.completed,
                            dueDate: n.dueDate, position: n.position, createdAt: n.createdAt,
                            missing: missingIds[n.noteId] === true,
                            group: split && n.dueDate > 0 && n.dueDate < today ? "overdue" : "" })
        }
        // not `sorted`: this also runs from the change handler of sortField, before that flag has updated
        if (sortField !== 0)
            rows.sort(compare)
        shown.clear()
        for (const r of rows)
            shown.append(r)
    }

    function startOfToday() {
        const d = new Date()
        return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
    }

    function indexOfNote(noteId) {
        for (let i = 0; i < notes.count; ++i)
            if (notes.get(i).noteId === noteId)
                return i
        return -1
    }

    function addNote(title, description, dueDate) {
        notes.append(NoteStore.add(title, description, dueDate))
        refresh()
        scheduleSync()
    }

    function updateNote(noteId, title, description, dueDate) {
        NoteStore.update(noteId, title, description, dueDate)
        const i = indexOfNote(noteId)
        if (i >= 0) {
            notes.setProperty(i, "title", title)
            notes.setProperty(i, "description", description)
            notes.setProperty(i, "dueDate", dueDate)
        }
        refresh()
        scheduleSync()
    }

    function removeNote(noteId) {
        NoteStore.remove(noteId)
        const i = indexOfNote(noteId)
        if (i >= 0)
            notes.remove(i)
        refresh()
        scheduleSync()
    }

    // Deletes every completed note; returns how many there were.
    function removeCompleted() {
        let n = 0
        for (let i = notes.count - 1; i >= 0; --i) {
            if (!notes.get(i).completed)
                continue
            NoteStore.remove(notes.get(i).noteId)
            notes.remove(i)
            ++n
        }
        refresh()
        scheduleSync()
        return n
    }

    function setCompleted(noteId, completed) {
        NoteStore.setCompleted(noteId, completed)
        const i = indexOfNote(noteId)
        if (i >= 0)
            notes.setProperty(i, "completed", completed)
        refresh()
        scheduleSync()
    }

    function reload() {
        notes.clear()
        for (const n of NoteStore.all())
            notes.append(n)
        refresh()
    }

    // Applies a drag: the note shown at row `from` is dropped at row `to` of the shown list.
    function dropNote(from, to) {
        const visible = []
        for (let i = 0; i < shown.count; ++i)
            visible.push(shown.get(i).position)

        let pos = NoteStore.dropPosition(visible, from, to)
        if (pos !== null && isNaN(pos)) {
            NoteStore.renumber()
            reload()
            for (let i = 0; i < shown.count; ++i)
                visible[i] = shown.get(i).position
            pos = NoteStore.dropPosition(visible, from, to)
        }
        if (pos === null || isNaN(pos))
            return

        const noteId = shown.get(from).noteId
        NoteStore.setPosition(noteId, pos)

        // keep both models in order without rebuilding them
        const notesFrom = indexOfNote(noteId)
        notes.setProperty(notesFrom, "position", pos)
        let notesTo = 0
        for (let i = 0; i < notes.count; ++i)
            if (i !== notesFrom && notes.get(i).position < pos)
                ++notesTo
        notes.move(notesFrom, notesTo, 1)

        shown.setProperty(from, "position", pos)
        shown.move(from, to, 1)
        scheduleSync()
    }

    onFilterChanged: {
        loadSort()
        refresh()
    }
    onSortFieldChanged: {
        if (loadingSort)
            return
        NoteStore.setState(sortKey("sort_field"), String(sortField))
        refresh()
    }
    onSortDescChanged: {
        if (loadingSort)
            return
        NoteStore.setState(sortKey("sort_desc"), sortDesc ? "1" : "0")
        refresh()
    }
    // ---- Account (Firebase Authentication, see Auth.js) ----
    // An account counts only once its email is verified. The real check is on the server: the Firestore
    // rules require email_verified in the signed token, so an unverified account can't reach any data
    // whatever this widget does. The checks here only keep the UI honest about it.

    // Binds this device's notes to a verified account. A database belongs to one account for good,
    // so signing in as somebody else is refused; the first account to sign in claims the existing notes.
    function sameEmail(a, b) {
        return a.trim().toLowerCase() === b.trim().toLowerCase()
    }

    function startSession(account) {
        const bound = NoteStore.getState("uid")
        if (bound !== "" && bound !== account.uid) {
            // Same email, different uid: emails are unique in Firebase, so the account these notes
            // belonged to was deleted and this verified one replaces it. Anyone else is refused.
            if (!sameEmail(account.email, NoteStore.getState("email"))) {
                authError = fmt("The notes on this device belong to %1. Sign in with that account.", NoteStore.getState("email"))
                return false
            }
            NoteStore.rebindNotes(bound, account.uid)
        }
        NoteStore.setState("uid", account.uid)
        NoteStore.setState("email", account.email)
        NoteStore.setState("refresh_token", account.refreshToken)
        NoteStore.claimNotes(account.uid)
        pending = null
        idToken = account.idToken
        userEmail = account.email
        startKeyFlow()
        return true
    }

    function authenticate(create, email, password) {
        authError = ""
        authInfo = ""
        if (create && NoteStore.getState("uid") !== "" && !sameEmail(email, NoteStore.getState("email"))) {
            authError = fmt("The notes on this device belong to %1, so a new account can't be created here.", NoteStore.getState("email"))
            return
        }
        authBusy = true
        const done = function (error, account) {
            authBusy = false
            if (error) {
                authError = error.message
            } else if (Auth.isVerified(account.idToken)) {
                startSession(account)
            } else {
                pending = account
                if (create)
                    resendVerification()
                else
                    authInfo = fmt("%1 is not verified yet. Open the link from the verification email, then press Continue.", account.email)
            }
        }
        if (create)
            Auth.signUp(email, password, done)
        else
            Auth.signIn(email, password, done)
    }

    // Gets a fresh token from Firebase; once the link in the email was opened it says email_verified.
    function checkVerified() {
        if (!pending)
            return
        authError = ""
        authInfo = ""
        authBusy = true
        Auth.refresh(pending.refreshToken, function (error, result) {
            authBusy = false
            if (error) {
                authError = error.message
                if (Auth.isSessionLost(error))
                    pending = null
                return
            }
            const account = { uid: result.uid, email: pending.email, idToken: result.idToken, refreshToken: result.refreshToken }
            if (Auth.isVerified(account.idToken)) {
                startSession(account)
            } else {
                pending = account
                authError = Auth.errorText("NOT_VERIFIED")
            }
        })
    }

    // Sends the verification email again (with a fresh token, the old one may have expired).
    function resendVerification() {
        if (!pending)
            return
        authError = ""
        authBusy = true
        Auth.refresh(pending.refreshToken, function (error, result) {
            if (error) {
                authBusy = false
                authError = error.message
                return
            }
            pending = { uid: result.uid, email: pending.email, idToken: result.idToken, refreshToken: result.refreshToken }
            Auth.sendVerification(result.idToken, function (error) {
                authBusy = false
                if (error)
                    authError = error.message
                else
                    authInfo = fmt("We sent a verification link to %1. Open it, then press Continue. Check your spam folder too.", pending.email)
            })
        })
    }

    function cancelPending() {
        pending = null
        authError = ""
        authInfo = ""
    }

    function resetPassword(email) {
        authError = ""
        authInfo = ""
        if (email === "") {
            authError = Auth.errorText("MISSING_EMAIL")
            return
        }
        authBusy = true
        Auth.sendPasswordReset(email, function (error) {
            authBusy = false
            if (error)
                authError = error.code === "EMAIL_NOT_FOUND" ? Auth.errorText("RESET_EMAIL_NOT_FOUND") : error.message
            else
                authInfo = fmt("Password reset email sent to %1. Check your spam folder too.", email)
        })
    }

    // The notes stay on the device (and stay bound to the account); only the session ends.
    function signOut() {
        NoteStore.clearState("refresh_token")
        pushTimer.stop()
        keyPhase = ""
        syncError = ""
        keyMismatch = false
        keyError = ""
        keyValue = ""
        keyShown = false
        resetOpen = false
        missingIds = ({})
        lastReconcile = 0
        pending = null
        idToken = ""
        userEmail = ""
        authError = ""
        authInfo = ""
    }

    // Signs in again from the stored refresh token. Offline keeps the session, only a revoked one ends it.
    // A session from before verification was required, still unverified, goes back to waiting for it.
    function restoreSession() {
        const token = NoteStore.getState("refresh_token")
        if (token === "")
            return
        userEmail = NoteStore.getState("email")
        if (!FirebaseConfig.configured)
            return
        Auth.refresh(token, function (error, result) {
            if (error) {
                if (Auth.isSessionLost(error))
                    signOut()
                return
            }
            if (!Auth.isVerified(result.idToken)) {
                const email = userEmail
                signOut()
                pending = { uid: result.uid, email: email, idToken: result.idToken, refreshToken: result.refreshToken }
                authInfo = fmt("%1 is not verified yet. Open the link from the verification email, then press Continue.", email)
                return
            }
            NoteStore.setState("refresh_token", result.refreshToken)
            idToken = result.idToken
            startKeyFlow()
        })
    }

    // ---- Sync (see Sync.js) and the encryption key (see KeyFlow.js) ----
    // Pushes local changes a moment after they happen and pulls remote ones. It polls rarely (often while
    // the widget is open, seldom while it is only an icon) and slows down while offline or failing, so
    // an idle widget costs one small request every few minutes. Nothing syncs until the key is ready.

    // Runs a server call with the current id token; an expired one is refreshed once and the call repeated.
    function authed(call, done, retried) {
        call(idToken, function (error, result) {
            if (error && error.code === "UNAUTHENTICATED" && !retried) {
                Auth.refresh(NoteStore.getState("refresh_token"), function (refreshError, fresh) {
                    if (refreshError) {
                        if (Auth.isSessionLost(refreshError))
                            signOut()
                        done(refreshError, null)
                        return
                    }
                    NoteStore.setState("refresh_token", fresh.refreshToken)
                    idToken = fresh.idToken
                    authed(call, done, true)
                })
                return
            }
            done(error, result)
        })
    }

    function scheduleSync() {
        if (idToken !== "")
            pushTimer.restart()
    }

    // ---- key tasks: one at a time, never allowed to spin forever ----
    function keyStart() {
        keyBusy = true
        keyError = ""
        keyWatchdog.restart()
        return ++keyRun
    }

    // false when the task was already given up on (watchdog), its late result must be ignored
    function keyFinish(run) {
        if (run !== keyRun)
            return false
        keyBusy = false
        keyWatchdog.stop()
        return true
    }

    Timer {
        id: keyWatchdog
        interval: 60000
        onTriggered: {
            root.keyRun++
            root.keyBusy = false
            root.keyError = fmt("This took too long. Check your connection and press Retry.")
        }
    }


    function startKeyFlow() {
        if (idToken === "" || keyBusy)
            return
        const run = keyStart()
        KeyFlow.begin(authed, randomBytes, NoteStore.getState("uid"), function (error, result) {
            if (!keyFinish(run))
                return
            keyPhase = result.phase
            keyMismatch = result.mismatch
            keyValue = keyPhase === "save" ? KeyFlow.keyBlock() : ""
            if (error)
                keyError = error.message
            syncFailures = error ? Math.min(syncFailures + 1, 6) : 0
            if (!error && keyPhase === "ready")
                syncNow()
        })
    }

    function submitKey(text) {
        const run = keyStart()
        KeyFlow.enter(authed, NoteStore.getState("uid"), text, function (error, result) {
            if (!keyFinish(run))
                return
            if (error || !result.ok) {
                keyError = error ? error.message : result.message
                return
            }
            keyMismatch = false
            keyPhase = "ready"
            syncNow()
        })
    }

    function confirmKey() {
        KeyFlow.confirmSaved()
        keyPhase = "ready"
        keyValue = ""
        keyShown = false
        syncNow()
    }

    function toggleKeyShown() {
        keyShown = !keyShown
        keyValue = keyShown ? KeyFlow.keyBlock() : ""
    }

    // Lost key: wipes the cloud copy, makes a new key, uploads this device's notes again (after the "save" step).
    function resetKey() {
        const run = keyStart()
        KeyFlow.reset(authed, randomBytes, NoteStore.getState("uid"), function (error) {
            if (!keyFinish(run))
                return
            resetOpen = false
            missingIds = ({})
            if (error) {
                startKeyFlow()                    // find out what state the failed reset left behind
                keyError = error.message
                return
            }
            keyMismatch = false
            keyShown = false
            keyPhase = "save"
            keyValue = KeyFlow.keyBlock()
            refresh()
        })
    }

    function syncNow() {
        if (idToken === "" || keyPhase !== "ready")
            return
        if (syncing) {
            syncAgain = true
            return
        }
        syncing = true
        const uid = NoteStore.getState("uid")
        authed(function (token, done) { Sync.run(uid, token, KeyFlow.currentKey(), done) }, syncFinished)
    }

    function syncFinished(error, result) {
        syncing = false
        if (error && error.code === "BAD_KEY") {
            // the cloud copy was re-encrypted with another key (reset on another device)
            keyMismatch = true
            keyPhase = "enter"
            return
        }
        syncFailures = error ? Math.min(syncFailures + 1, 6) : 0
        syncError = error ? error.message + (error.code ? " (" + error.code + ")" : "") : ""
        if (result && result.pulled > 0)
            reload()
        if (!error && Date.now() - lastReconcile > 600000)
            checkMissing()
        if (syncAgain && !error) {
            syncAgain = false
            syncNow()
        } else {
            syncAgain = false
        }
    }

    // Marks notes that this device synced before but the server no longer has (deleted on another device).
    // Costs one count query; the ids are only fetched when the numbers differ.
    function checkMissing() {
        lastReconcile = Date.now()
        const uid = NoteStore.getState("uid")
        authed(function (token, done) { Sync.reconcile(uid, token, done) }, function (error, ids) {
            if (error) {
                lastReconcile = 0                 // try again at the next sync
                return
            }
            const map = {}
            for (const id of ids)
                map[id] = true
            missingIds = map
            refresh()
        })
    }

    // Puts a note back on the server that was deleted there.
    function reuploadNote(noteId) {
        NoteStore.reupload(noteId)
        const map = Object.assign({}, missingIds)
        delete map[noteId]
        missingIds = map
        refresh()
        scheduleSync()
    }


    // push soon after a change, merged into one sync when several changes come quickly
    Timer {
        id: pushTimer
        interval: 2000
        onTriggered: root.syncNow()
    }

    // 30 s while the widget is open, 5 min otherwise, doubled after each consecutive failure (max 64x)
    Timer {
        id: pollTimer
        running: root.idToken !== ""
        repeat: true
        interval: (root.active ? 30000 : 300000) * Math.pow(2, root.syncFailures)
        onTriggered: root.keyPhase === "" ? root.startKeyFlow() : root.syncNow()
    }

    onActiveChanged: {
        if (!active)
            return
        lastReconcile = 0                          // look for notes missing online whenever the widget is opened
        if (keyPhase === "")
            startKeyFlow()
        else
            syncNow()
    }

    Component.onCompleted: {
        const savedKey = NoteStore.getState("api_key")
        if (savedKey !== "") {
            FirebaseConfig.setApiKey(savedKey)
            apiKey = savedKey
            configured = true
        }
        setupSeen = configured || NoteStore.getState("setup_seen") === "1"
        loadSort()
        reload()
        restoreSession()
    }
}
