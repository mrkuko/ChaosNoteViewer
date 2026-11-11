import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami
import "NoteStore.js" as NoteStore
import "Auth.js" as Auth
import "Sync.js" as Sync
import "KeyFlow.js" as KeyFlow
import "FirebaseConfig.js" as FirebaseConfig

PlasmoidItem {
    id: root

    Plasmoid.icon: "chaosnoteviewer"
    toolTipMainText: i18n("Chaos Note Viewer")

    // 0 = Uncompleted (default), 1 = Completed, 2 = All
    property int filter: 0
    property bool filterOpen: false
    property bool settingsOpen: false
    property bool addOpen: false
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
    property bool syncAgain: false     // a change arrived while a sync was running
    property int syncFailures: 0       // consecutive failed syncs, slows the polling down
    // Encryption key of the cloud copy (KeyFlow.js): "" = not decided yet (offline), "enter" = the cloud
    // has a key this device doesn't know, "save" = a new key must be shown and confirmed, "ready" = sync runs.
    property string keyPhase: ""
    property bool keyMismatch: false   // the key was changed on another device
    property bool keyBusy: false
    property string keyError: ""
    property string keyValue: ""       // the key as shown to the user, "" until needed
    property bool keyShown: false
    property bool resetOpen: false
    // Notes this device had synced that are gone from the server (deleted on another device).
    property var missingIds: ({})
    property double lastReconcile: 0

    // All notes, loaded from the local SQLite database (see NoteStore.js).
    ListModel { id: notes }

    // Filtered copy of `notes`; srcIndex maps a row back to its source note.
    ListModel { id: shown }

    function refresh() {
        shown.clear()
        for (let i = 0; i < notes.count; ++i) {
            const n = notes.get(i)
            if (filter === 2 || (filter === 0) === !n.completed)
                shown.append({ noteId: n.noteId, title: n.title, description: n.description, completed: n.completed, dueDate: n.dueDate, position: n.position,
                              missing: missingIds[n.noteId] === true })
        }
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

    function removeNote(noteId) {
        NoteStore.remove(noteId)
        const i = indexOfNote(noteId)
        if (i >= 0)
            notes.remove(i)
        refresh()
        scheduleSync()
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

    onFilterChanged: refresh()
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
                authError = i18n("The notes on this device belong to %1. Sign in with that account.", NoteStore.getState("email"))
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
            authError = i18n("The notes on this device belong to %1, so a new account can't be created here.", NoteStore.getState("email"))
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
                    authInfo = i18n("%1 is not verified yet. Open the link from the verification email, then press Continue.", account.email)
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
                    authInfo = i18n("We sent a verification link to %1. Open it, then press Continue. Check your spam folder too.", pending.email)
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
                authInfo = i18n("Password reset email sent to %1. Check your spam folder too.", email)
        })
    }

    // The notes stay on the device (and stay bound to the account); only the session ends.
    function signOut() {
        NoteStore.clearState("refresh_token")
        pushTimer.stop()
        keyPhase = ""
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
                authInfo = i18n("%1 is not verified yet. Open the link from the verification email, then press Continue.", email)
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

    function startKeyFlow() {
        if (idToken === "" || keyBusy)
            return
        keyBusy = true
        keyError = ""
        KeyFlow.begin(authed, NoteStore.getState("uid"), function (error, result) {
            keyBusy = false
            keyPhase = result.phase
            keyMismatch = result.mismatch
            keyValue = keyPhase === "save" ? KeyFlow.keyText() : ""
            syncFailures = error ? Math.min(syncFailures + 1, 6) : 0
            if (!error && keyPhase === "ready")
                syncNow()
        })
    }

    function submitKey(text) {
        keyBusy = true
        keyError = ""
        KeyFlow.enter(authed, NoteStore.getState("uid"), text, function (error, result) {
            keyBusy = false
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
        keyValue = keyShown ? KeyFlow.keyText() : ""
    }

    // Lost key: wipes the cloud copy, makes a new key, uploads this device's notes again (after the "save" step).
    function resetKey() {
        keyBusy = true
        keyError = ""
        KeyFlow.reset(authed, NoteStore.getState("uid"), function (error) {
            keyBusy = false
            resetOpen = false
            missingIds = ({})
            if (error) {
                keyError = error.message
                startKeyFlow()                    // find out what state the failed reset left behind
                return
            }
            keyMismatch = false
            keyShown = false
            keyPhase = "save"
            keyValue = KeyFlow.keyText()
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

    function copyText(text) {
        clipboard.text = text
        clipboard.selectAll()
        clipboard.copy()
    }
    TextEdit { id: clipboard; visible: false }

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
        interval: (root.expanded ? 30000 : 300000) * Math.pow(2, root.syncFailures)
        onTriggered: root.keyPhase === "" ? root.startKeyFlow() : root.syncNow()
    }

    onExpandedChanged: {
        if (!expanded)
            return
        lastReconcile = 0                          // look for notes missing online whenever the widget is opened
        if (keyPhase === "")
            startKeyFlow()
        else
            syncNow()
    }

    Component.onCompleted: {
        reload()
        restoreSession()
    }

    compactRepresentation: MouseArea {
        id: compact
        activeFocusOnTab: true
        hoverEnabled: true
        onClicked: root.expanded = !root.expanded

        Kirigami.Icon {
            anchors.fill: parent
            source: "chaosnoteviewer"
            active: compact.containsMouse
        }
    }

    fullRepresentation: Item {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 18
        Layout.preferredWidth: Kirigami.Units.gridUnit * 22
        Layout.minimumHeight: Kirigami.Units.gridUnit * 18
        Layout.preferredHeight: Kirigami.Units.gridUnit * 28

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            // ---- Header ----
            RowLayout {
                Layout.fillWidth: true
                Layout.margins: Kirigami.Units.smallSpacing
                spacing: Kirigami.Units.smallSpacing

                PlasmaComponents3.ToolButton {
                    visible: root.settingsOpen
                    icon.name: "go-previous"
                    onClicked: root.settingsOpen = false
                }
                Kirigami.Heading {
                    Layout.fillWidth: true
                    Layout.leftMargin: Kirigami.Units.smallSpacing
                    level: 3
                    text: root.settingsOpen ? i18n("Settings") : i18n("Notes")
                }
                PlasmaComponents3.ToolButton {
                    visible: !root.settingsOpen
                    icon.name: "list-add"
                    onClicked: root.addOpen = true
                    PlasmaComponents3.ToolTip { text: i18n("New note") }
                }
                PlasmaComponents3.ToolButton {
                    visible: !root.settingsOpen
                    icon.name: "view-filter"
                    checkable: true
                    checked: root.filterOpen
                    onToggled: root.filterOpen = checked
                    PlasmaComponents3.ToolTip { text: i18n("Filter") }
                }
                PlasmaComponents3.ToolButton {
                    visible: !root.settingsOpen
                    icon.name: "configure"
                    onClicked: root.settingsOpen = true
                    PlasmaComponents3.ToolTip { text: i18n("Settings") }
                }
            }

            // ---- Filter bar (slides in under the header) ----
            Item {
                visible: !root.settingsOpen
                Layout.fillWidth: true
                Layout.preferredHeight: root.filterOpen ? filterRow.implicitHeight + Kirigami.Units.smallSpacing * 2 : 0
                clip: true
                Behavior on Layout.preferredHeight { NumberAnimation { duration: Kirigami.Units.shortDuration; easing.type: Easing.OutCubic } }

                RowLayout {
                    id: filterRow
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.margins: Kirigami.Units.smallSpacing
                    spacing: Kirigami.Units.smallSpacing

                    Repeater {
                        model: [i18n("Uncompleted"), i18n("Completed"), i18n("All")]
                        delegate: PlasmaComponents3.Button {
                            required property int index
                            required property string modelData
                            Layout.fillWidth: true
                            text: modelData
                            checkable: true
                            checked: root.filter === index
                            onClicked: root.filter = index
                        }
                    }
                }
            }

            Kirigami.Separator { Layout.fillWidth: true }

            // ---- Body: notes list or settings ----
            StackLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                currentIndex: root.settingsOpen ? 1 : 0

                ListView {
                    id: list
                    clip: true
                    spacing: Kirigami.Units.smallSpacing
                    topMargin: Kirigami.Units.smallSpacing
                    bottomMargin: Kirigami.Units.smallSpacing
                    leftMargin: Kirigami.Units.smallSpacing
                    rightMargin: Kirigami.Units.smallSpacing
                    model: shown


                    // Drag to reorder. While a card is dragged the other cards only slide out of its way
                    // (slot.shift); the models change once, when it is dropped.
                    property int dragFrom: -1       // row being dragged, -1 = none
                    property int dragTo: -1         // row it would land on if dropped now
                    property real dragHeight: 0     // space the dragged card takes, incl. spacing
                    property bool settling: false   // true while a drop is applied: cards jump instead of sliding
                    cacheBuffer: Kirigami.Units.gridUnit * 200   // keep every card alive so the drop row can be measured

                    // Row the dragged card would take, from where its centre is among the other cards.
                    function dropRow(slot, card) {
                        const centre = slot.y + card.y + card.height / 2
                        let row = 0
                        for (let i = 0; i < count; ++i) {
                            if (i === dragFrom)
                                continue
                            const other = itemAtIndex(i)
                            if (!other)
                                row += i < dragFrom ? 1 : 0
                            else if (other.y + other.height / 2 < centre)
                                ++row
                        }
                        return row
                    }

                    PlasmaComponents3.Label {
                        anchors.centerIn: parent
                        visible: list.count === 0
                        horizontalAlignment: Text.AlignHCenter
                        opacity: 0.6
                        text: notes.count === 0 ? i18n("No notes yet.\nPress + to add one.") : i18n("Nothing here")
                    }

                    delegate: Item {
                        id: slot
                        width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
                        height: card.height
                        z: drag.active ? 10 : 1

                        // how far this card slides while another one is dragged past it
                        property real shift: {
                            const from = list.dragFrom, to = list.dragTo
                            if (from < 0 || index === from)
                                return 0
                            if (from < to && index > from && index <= to)
                                return -list.dragHeight
                            if (from > to && index >= to && index < from)
                                return list.dragHeight
                            return 0
                        }
                        Behavior on shift {
                            enabled: !list.settling
                            NumberAnimation { duration: Kirigami.Units.shortDuration }
                        }

                        Rectangle {
                            id: card
                            width: slot.width
                            height: row.implicitHeight + Kirigami.Units.largeSpacing * 2
                            transform: Translate { y: slot.shift }
                            radius: Kirigami.Units.cornerRadius
                            color: hover.hovered
                                ? Qt.alpha(Kirigami.Theme.highlightColor, 0.18)
                                : Qt.alpha(Kirigami.Theme.textColor, 0.06)
                            opacity: model.completed ? 0.55 : 1.0

                            Behavior on color { ColorAnimation { duration: Kirigami.Units.shortDuration } }
                            HoverHandler { id: hover }

                            RowLayout {
                                id: row
                                anchors.fill: parent
                                anchors.margins: Kirigami.Units.largeSpacing
                                spacing: Kirigami.Units.largeSpacing

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2

                                    PlasmaComponents3.Label {
                                        Layout.fillWidth: true
                                        text: model.title
                                        font.bold: true
                                        font.strikeout: model.completed
                                        elide: Text.ElideRight
                                    }
                                    PlasmaComponents3.Label {
                                        Layout.fillWidth: true
                                        visible: text !== ""
                                        text: model.description
                                        maximumLineCount: 1
                                        elide: Text.ElideRight
                                        opacity: 0.7
                                        font.pointSize: Kirigami.Theme.smallFont.pointSize
                                    }
                                    RowLayout {
                                        visible: model.dueDate > 0
                                        spacing: Kirigami.Units.smallSpacing

                                        // overdue = deadline day is before today and the note is still open
                                        readonly property bool overdue: !model.completed && model.dueDate < root.startOfToday()

                                        Kirigami.Icon {
                                            source: "view-calendar"
                                            Layout.preferredWidth: Kirigami.Units.iconSizes.small
                                            Layout.preferredHeight: Kirigami.Units.iconSizes.small
                                            color: parent.overdue ? Kirigami.Theme.negativeTextColor : Kirigami.Theme.textColor
                                        }
                                        PlasmaComponents3.Label {
                                            text: new Date(model.dueDate).toLocaleDateString(Qt.locale(), Locale.ShortFormat)
                                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                                            color: parent.overdue ? Kirigami.Theme.negativeTextColor : Kirigami.Theme.textColor
                                            opacity: parent.overdue ? 1 : 0.7
                                        }
                                    }
                                }

                                // Always occupies its space so the card doesn't jump when it appears.
                                PlasmaComponents3.ToolButton {
                                    Layout.alignment: Qt.AlignVCenter
                                    visible: model.missing
                                    icon.name: "cloud-upload"
                                    opacity: hover.hovered ? 1 : 0
                                    enabled: hover.hovered
                                    onClicked: root.reuploadNote(model.noteId)
                                    PlasmaComponents3.ToolTip { text: i18n("Upload again") }
                                }

                                PlasmaComponents3.ToolButton {
                                    Layout.alignment: Qt.AlignVCenter
                                    icon.name: "edit-delete"
                                    opacity: hover.hovered ? 1 : 0
                                    enabled: hover.hovered
                                    onClicked: root.removeNote(model.noteId)
                                    PlasmaComponents3.ToolTip { text: i18n("Delete") }
                                }

                                PlasmaComponents3.CheckBox {
                                    Layout.alignment: Qt.AlignVCenter
                                    checked: model.completed
                                    onToggled: root.setCompleted(model.noteId, checked)
                                }
                            }

                            // red dot: this note was deleted from the cloud on another device
                            Item {
                                visible: model.missing
                                anchors.top: parent.top
                                anchors.right: parent.right
                                width: Kirigami.Units.iconSizes.small
                                height: width
                                HoverHandler { id: dotHover }
                                Rectangle {
                                    anchors.centerIn: parent
                                    width: Kirigami.Units.smallSpacing * 2
                                    height: width
                                    radius: width / 2
                                    color: Kirigami.Theme.negativeTextColor
                                }
                                PlasmaComponents3.ToolTip {
                                    visible: dotHover.hovered
                                    text: i18n("This note is no longer online (it was deleted on another device). Upload it again or delete it here.")
                                }
                            }

                            onYChanged: {
                                if (drag.active)
                                    list.dragTo = list.dropRow(slot, card)
                            }

                            DragHandler {
                                id: drag
                                target: card
                                xAxis.enabled: false
                                // may overhang the first/last card by half a row, so the ends can be reached
                                yAxis.minimum: -slot.y - card.height / 2
                                yAxis.maximum: list.contentHeight - slot.y - card.height / 2
                                onActiveChanged: {
                                    const view = list
                                    if (active) {
                                        view.dragHeight = card.height + view.spacing
                                        view.dragFrom = index
                                        view.dragTo = index
                                    } else {
                                        const from = view.dragFrom, to = view.dragTo
                                        view.settling = true
                                        view.dragFrom = -1
                                        view.dragTo = -1
                                        root.dropNote(from, to)
                                        card.y = 0
                                        view.settling = false
                                    }
                                }
                            }
                        }
                    }
                }

                // Settings; real options arrive with the features they control.
                ColumnLayout {
                    spacing: Kirigami.Units.largeSpacing

                    Kirigami.Heading {
                        Layout.margins: Kirigami.Units.largeSpacing
                        level: 4
                        text: i18n("Account")
                    }

                    // signed in
                    ColumnLayout {
                        visible: root.userEmail !== ""
                        Layout.fillWidth: true
                        Layout.leftMargin: Kirigami.Units.largeSpacing
                        Layout.rightMargin: Kirigami.Units.largeSpacing
                        spacing: Kirigami.Units.largeSpacing

                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            wrapMode: Text.Wrap
                            text: i18n("Signed in as %1", root.userEmail)
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            wrapMode: Text.Wrap
                            opacity: 0.7
                            text: i18n("Your notes stay on this device when you sign out.")
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authInfo !== ""
                            wrapMode: Text.Wrap
                            color: Kirigami.Theme.positiveTextColor
                            text: root.authInfo
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authError !== ""
                            wrapMode: Text.Wrap
                            color: Kirigami.Theme.negativeTextColor
                            text: root.authError
                        }
                        // ---- encryption key of the cloud copy ----
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: Kirigami.Units.smallSpacing

                            Kirigami.Heading {
                                level: 5
                                text: i18n("Encryption key")
                            }
                            PlasmaComponents3.Label {
                                Layout.fillWidth: true
                                visible: root.keyPhase === "" && !root.keyBusy
                                wrapMode: Text.Wrap
                                opacity: 0.7
                                text: i18n("Waiting for a connection to check the encryption key. Sync starts after that.")
                            }

                            // a new key was made: show it, sync waits for the confirmation
                            ColumnLayout {
                                visible: root.keyPhase === "save"
                                Layout.fillWidth: true
                                spacing: Kirigami.Units.smallSpacing
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    text: i18n("Your notes are encrypted on this device before they are uploaded. This is your key. Without it nobody, you included, can read the notes in the cloud. Store it somewhere safe, you need it to add another device. Sync starts when you confirm.")
                                }
                                TextEdit {
                                    Layout.fillWidth: true
                                    readOnly: true
                                    selectByMouse: true
                                    wrapMode: TextEdit.WrapAnywhere
                                    font.family: "monospace"
                                    font.bold: true
                                    color: Kirigami.Theme.textColor
                                    text: root.keyValue
                                }
                                RowLayout {
                                    PlasmaComponents3.Button {
                                        text: i18n("Copy key")
                                        icon.name: "edit-copy"
                                        onClicked: root.copyText(root.keyValue)
                                    }
                                    PlasmaComponents3.Button {
                                        text: i18n("I saved it")
                                        icon.name: "dialog-ok"
                                        onClicked: root.confirmKey()
                                    }
                                }
                            }

                            // the cloud has a key this device doesn't know
                            ColumnLayout {
                                visible: root.keyPhase === "enter"
                                Layout.fillWidth: true
                                spacing: Kirigami.Units.smallSpacing
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    text: root.keyMismatch
                                        ? i18n("The key for this account was changed on another device. Enter the new key to sync again.")
                                        : i18n("The notes in the cloud are encrypted. Enter your encryption key to sync this device.")
                                }
                                PlasmaComponents3.TextField {
                                    id: keyField
                                    Layout.fillWidth: true
                                    placeholderText: i18n("XXXX-XXXX-XXXX-...")
                                    font.family: "monospace"
                                    enabled: !root.keyBusy
                                    onAccepted: root.submitKey(text)
                                    Connections {
                                        target: root
                                        function onKeyPhaseChanged() { keyField.text = "" }
                                    }
                                }
                                PlasmaComponents3.Button {
                                    text: i18n("Unlock")
                                    icon.name: "unlock"
                                    enabled: !root.keyBusy && keyField.text.trim() !== ""
                                    onClicked: root.submitKey(keyField.text)
                                }
                            }

                            // key known: show or copy it on demand
                            ColumnLayout {
                                visible: root.keyPhase === "ready"
                                Layout.fillWidth: true
                                spacing: Kirigami.Units.smallSpacing
                                RowLayout {
                                    PlasmaComponents3.Button {
                                        text: root.keyShown ? i18n("Hide key") : i18n("Show key")
                                        icon.name: root.keyShown ? "hide_table_row" : "view-visible"
                                        onClicked: root.toggleKeyShown()
                                    }
                                    PlasmaComponents3.Button {
                                        text: i18n("Copy key")
                                        icon.name: "edit-copy"
                                        onClicked: root.copyText(KeyFlow.keyText())
                                    }
                                }
                                TextEdit {
                                    Layout.fillWidth: true
                                    visible: root.keyShown
                                    readOnly: true
                                    selectByMouse: true
                                    wrapMode: TextEdit.WrapAnywhere
                                    font.family: "monospace"
                                    color: Kirigami.Theme.textColor
                                    text: root.keyValue
                                }
                            }

                            PlasmaComponents3.Label {
                                Layout.fillWidth: true
                                visible: root.keyError !== ""
                                wrapMode: Text.Wrap
                                color: Kirigami.Theme.negativeTextColor
                                text: root.keyError
                            }
                            PlasmaComponents3.BusyIndicator {
                                running: root.keyBusy
                                visible: root.keyBusy
                                Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                            }

                            // lost key
                            PlasmaComponents3.Button {
                                visible: (root.keyPhase === "enter" || root.keyPhase === "ready") && !root.resetOpen
                                flat: true
                                text: root.keyPhase === "enter" ? i18n("Lost the key? Reset…") : i18n("Reset key…")
                                enabled: !root.keyBusy
                                onClicked: root.resetOpen = true
                            }
                            ColumnLayout {
                                visible: root.resetOpen
                                Layout.fillWidth: true
                                spacing: Kirigami.Units.smallSpacing
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.negativeTextColor
                                    text: i18n("This deletes ALL notes from the cloud and creates a new key. The notes on this device stay and are uploaded again, encrypted with the new key. Notes that exist only in the cloud or only on other devices are gone from the cloud. Other devices will ask for the new key.")
                                }
                                RowLayout {
                                    PlasmaComponents3.Button {
                                        text: i18n("Delete cloud notes and make a new key")
                                        icon.name: "edit-delete"
                                        enabled: !root.keyBusy
                                        onClicked: root.resetKey()
                                    }
                                    PlasmaComponents3.Button {
                                        flat: true
                                        text: i18n("Cancel")
                                        enabled: !root.keyBusy
                                        onClicked: root.resetOpen = false
                                    }
                                }
                            }
                        }

                        PlasmaComponents3.Button {
                            text: i18n("Sign out")
                            icon.name: "system-log-out"
                            onClicked: root.signOut()
                        }
                    }

                    // signed in, email not verified yet
                    ColumnLayout {
                        visible: root.userEmail === "" && root.pending !== null
                        Layout.fillWidth: true
                        Layout.leftMargin: Kirigami.Units.largeSpacing
                        Layout.rightMargin: Kirigami.Units.largeSpacing
                        spacing: Kirigami.Units.largeSpacing

                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            wrapMode: Text.Wrap
                            text: i18n("Verify your email: %1", root.pending ? root.pending.email : "")
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authInfo !== ""
                            wrapMode: Text.Wrap
                            opacity: 0.8
                            text: root.authInfo
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authError !== ""
                            wrapMode: Text.Wrap
                            color: Kirigami.Theme.negativeTextColor
                            text: root.authError
                        }
                        RowLayout {
                            spacing: Kirigami.Units.smallSpacing

                            PlasmaComponents3.Button {
                                text: i18n("Continue")
                                icon.name: "dialog-ok"
                                enabled: !root.authBusy
                                onClicked: root.checkVerified()
                            }
                            PlasmaComponents3.Button {
                                text: i18n("Resend email")
                                icon.name: "mail-send"
                                enabled: !root.authBusy
                                onClicked: root.resendVerification()
                            }
                            PlasmaComponents3.Button {
                                flat: true
                                text: i18n("Cancel")
                                enabled: !root.authBusy
                                onClicked: root.cancelPending()
                            }
                            PlasmaComponents3.BusyIndicator {
                                running: root.authBusy
                                visible: root.authBusy
                                Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                            }
                        }
                    }

                    // signed out
                    ColumnLayout {
                        visible: root.userEmail === "" && root.pending === null
                        Layout.fillWidth: true
                        Layout.leftMargin: Kirigami.Units.largeSpacing
                        Layout.rightMargin: Kirigami.Units.largeSpacing
                        spacing: Kirigami.Units.largeSpacing

                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            wrapMode: Text.Wrap
                            opacity: 0.7
                            text: FirebaseConfig.configured
                                ? i18n("Notes are stored on this device. Sign in to sync them between devices.")
                                : i18n("Notes are stored on this device. Sign-in is not set up: add your Firebase API key to desktop/firebase.local.json and run install.sh.")
                        }

                        PlasmaComponents3.TextField {
                            id: emailField
                            Layout.fillWidth: true
                            visible: FirebaseConfig.configured
                            enabled: !root.authBusy
                            placeholderText: i18n("Email")
                            inputMethodHints: Qt.ImhEmailCharactersOnly
                            onAccepted: passwordField.forceActiveFocus()
                        }
                        PlasmaComponents3.TextField {
                            id: passwordField
                            Layout.fillWidth: true
                            visible: FirebaseConfig.configured
                            enabled: !root.authBusy
                            placeholderText: i18n("Password")
                            echoMode: TextInput.Password
                            onAccepted: signInButton.clicked()
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authInfo !== ""
                            wrapMode: Text.Wrap
                            color: Kirigami.Theme.positiveTextColor
                            text: root.authInfo
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            visible: root.authError !== ""
                            wrapMode: Text.Wrap
                            color: Kirigami.Theme.negativeTextColor
                            text: root.authError
                        }
                        RowLayout {
                            visible: FirebaseConfig.configured
                            spacing: Kirigami.Units.smallSpacing

                            PlasmaComponents3.Button {
                                id: signInButton
                                text: i18n("Sign in")
                                icon.name: "user-identity"
                                enabled: !root.authBusy && emailField.text.trim() !== "" && passwordField.text !== ""
                                onClicked: {
                                    if (enabled)
                                        root.authenticate(false, emailField.text.trim(), passwordField.text)
                                }
                            }
                            PlasmaComponents3.Button {
                                text: i18n("Create account")
                                enabled: signInButton.enabled
                                onClicked: root.authenticate(true, emailField.text.trim(), passwordField.text)
                            }
                            PlasmaComponents3.Button {
                                flat: true
                                text: i18n("Forgot password?")
                                enabled: !root.authBusy
                                onClicked: root.resetPassword(emailField.text.trim())
                            }
                            PlasmaComponents3.BusyIndicator {
                                running: root.authBusy
                                visible: root.authBusy
                                Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                            }
                        }
                    }
                    Item { Layout.fillHeight: true }

                    // the password never outlives a successful sign-in
                    Connections {
                        target: root
                        function onUserEmailChanged() {
                            if (root.userEmail !== "")
                                passwordField.text = ""
                        }
                        function onPendingChanged() {
                            if (root.pending !== null)
                                passwordField.text = ""
                        }
                    }
                }
            }

            // ---- Footer: account status ----
            Kirigami.Separator { Layout.fillWidth: true }

            RowLayout {
                Layout.fillWidth: true
                Layout.margins: Kirigami.Units.smallSpacing
                Layout.leftMargin: Kirigami.Units.largeSpacing
                spacing: Kirigami.Units.smallSpacing

                Rectangle {
                    width: Kirigami.Units.smallSpacing * 2
                    height: width
                    radius: width / 2
                    color: root.userEmail !== "" ? Kirigami.Theme.positiveTextColor : Kirigami.Theme.disabledTextColor
                }
                PlasmaComponents3.Label {
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                    font.pointSize: Kirigami.Theme.smallFont.pointSize
                    opacity: 0.8
                    text: root.userEmail !== "" ? i18n("Signed in as %1", root.userEmail)
                        : root.pending !== null ? i18n("Waiting for email verification · local only")
                        : i18n("Not signed in · local only")
                }
            }
        }

        // ---- New note: a full-window page on top of everything else in the popup ----
        Rectangle {
            id: addOverlay
            anchors.fill: parent
            visible: root.addOpen
            color: Kirigami.Theme.backgroundColor
            z: 10

            property double due: 0

            function submit() {
                const title = titleField.text.trim()
                if (title === "")
                    return
                root.addNote(title, descField.text.trim(), due)
                root.addOpen = false
            }

            onVisibleChanged: if (visible) {
                titleField.text = ""
                descField.text = ""
                due = 0
                calendar.showMonthOf(0)
                titleField.forceActiveFocus()
            }

            // swallow clicks so nothing underneath reacts
            MouseArea { anchors.fill: parent }

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                RowLayout {
                    Layout.fillWidth: true
                    Layout.margins: Kirigami.Units.smallSpacing
                    spacing: Kirigami.Units.smallSpacing

                    PlasmaComponents3.ToolButton {
                        icon.name: "go-previous"
                        onClicked: root.addOpen = false
                        PlasmaComponents3.ToolTip { text: i18n("Cancel") }
                    }
                    Kirigami.Heading {
                        Layout.fillWidth: true
                        Layout.leftMargin: Kirigami.Units.smallSpacing
                        level: 3
                        text: i18n("New note")
                    }
                    PlasmaComponents3.Button {
                        text: i18n("Add")
                        icon.name: "list-add"
                        enabled: titleField.text.trim() !== ""
                        onClicked: addOverlay.submit()
                    }
                }

                Kirigami.Separator { Layout.fillWidth: true }

                PlasmaComponents3.ScrollView {
                    id: pageScroll
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: availableWidth

                    ColumnLayout {
                        width: pageScroll.availableWidth
                        spacing: Kirigami.Units.largeSpacing

                        PlasmaComponents3.TextField {
                            id: titleField
                            Layout.fillWidth: true
                            Layout.margins: Kirigami.Units.largeSpacing
                            Layout.bottomMargin: 0
                            placeholderText: i18n("Title")
                            onAccepted: descField.forceActiveFocus()
                            Keys.onEscapePressed: root.addOpen = false
                        }
                        PlasmaComponents3.TextArea {
                            id: descField
                            Layout.fillWidth: true
                            Layout.leftMargin: Kirigami.Units.largeSpacing
                            Layout.rightMargin: Kirigami.Units.largeSpacing
                            Layout.preferredHeight: Kirigami.Units.gridUnit * 5
                            placeholderText: i18n("Description (optional)")
                            wrapMode: TextEdit.Wrap
                            Keys.onEscapePressed: root.addOpen = false
                            Keys.onPressed: event => {
                                if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                                        && (event.modifiers & Qt.ControlModifier)) {
                                    addOverlay.submit()
                                    event.accepted = true
                                }
                            }
                        }

                        Kirigami.Separator { Layout.fillWidth: true }

                        RowLayout {
                            Layout.fillWidth: true
                            Layout.leftMargin: Kirigami.Units.largeSpacing
                            Layout.rightMargin: Kirigami.Units.largeSpacing
                            spacing: Kirigami.Units.smallSpacing

                            Kirigami.Icon {
                                source: "view-calendar"
                                Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                                Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                            }
                            PlasmaComponents3.Label {
                                Layout.fillWidth: true
                                text: addOverlay.due > 0
                                    ? i18n("Deadline: %1", new Date(addOverlay.due).toLocaleDateString(Qt.locale(), Locale.LongFormat))
                                    : i18n("No deadline")
                                opacity: addOverlay.due > 0 ? 1 : 0.6
                                elide: Text.ElideRight
                            }
                            PlasmaComponents3.Button {
                                text: i18n("Today")
                                onClicked: {
                                    addOverlay.due = root.startOfToday()
                                    calendar.showMonthOf(addOverlay.due)
                                }
                            }
                            PlasmaComponents3.Button {
                                text: i18n("Clear")
                                enabled: addOverlay.due > 0
                                onClicked: addOverlay.due = 0
                            }
                        }

                        DeadlineCalendar {
                            id: calendar
                            Layout.fillWidth: true
                            Layout.leftMargin: Kirigami.Units.largeSpacing
                            Layout.rightMargin: Kirigami.Units.largeSpacing
                            Layout.bottomMargin: Kirigami.Units.largeSpacing
                            value: addOverlay.due
                            onPicked: ms => addOverlay.due = ms
                        }
                    }
                }
            }
        }
    }
}
