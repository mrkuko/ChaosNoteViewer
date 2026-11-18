import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Settings: account (sign in, email verification) and the encryption key of the cloud copy.
ScrollView {
    id: page

    required property var app
    readonly property var ctl: app.ctl

    contentWidth: availableWidth
    clip: true

    // the password never outlives a successful sign-in
    Connections {
        target: ctl
        function onUserEmailChanged() {
            if (ctl.userEmail !== "")
                passwordField.text = ""
        }
        function onPendingChanged() {
            if (ctl.pending !== null)
                passwordField.text = ""
        }
    }

    ColumnLayout {
        width: page.availableWidth
        spacing: Theme.large

        // ---- Firebase project (Web API key, project id): entered here, kept on this device ----
        Label {
            Layout.leftMargin: Theme.large
            Layout.rightMargin: Theme.large
            Layout.topMargin: Theme.large
            font.pointSize: 12
            font.bold: true
            text: qsTr("Firebase project")
        }
        ColumnLayout {
            id: keyBox
            // editing while there is no key yet (first run) or after pressing Edit
            property bool editing: !ctl.configured
        property string problem: ""
            Layout.fillWidth: true
            Layout.leftMargin: Theme.large
            Layout.rightMargin: Theme.large
            spacing: Theme.small

            Connections {
                target: ctl
                function onConfiguredChanged() { keyBox.editing = !ctl.configured }
            }

            Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                opacity: 0.7
                text: keyBox.editing
                    ? qsTr("Needed to sign in and sync. Every user syncs through their own Firebase project: paste its Web API key and Project ID from the Firebase console (Project settings, General). Without them the notes stay on this device only.")
                    : qsTr("Project: %1, key …%2").arg(ctl.projectId).arg(ctl.apiKey.slice(-4))
            }
            TextField {
                id: apiKeyField
                Layout.fillWidth: true
                visible: keyBox.editing
                placeholderText: qsTr("Web API key, or paste a setup code")
                text: ctl.apiKey
                onAccepted: projectField.forceActiveFocus()
                // a setup code from another device (Copy setup code there) fills in the project and the key at once
                onTextChanged: if (text.trim().toLowerCase().indexOf("chaosnote:setup?") === 0) {
                    keyBox.problem = ctl.applySetupCode(text)
                    if (keyBox.problem === "") keyBox.editing = false
                }
            }
            TextField {
                id: projectField
                Layout.fillWidth: true
                visible: keyBox.editing
                placeholderText: qsTr("Project ID")
                text: ctl.projectId
                onAccepted: saveKeyButton.clicked()
            }
            Label {
                Layout.fillWidth: true
                visible: keyBox.editing && keyBox.problem !== ""
                wrapMode: Text.Wrap
                color: "#e06c75"
                text: keyBox.problem
            }
            RowLayout {
                spacing: Theme.small
                TextButton {
                    id: saveKeyButton
                    primary: true
                    visible: keyBox.editing
                    text: qsTr("Save")
                    enabled: apiKeyField.text.trim() !== "" && projectField.text.trim() !== ""
                    onClicked: if (enabled) {
                keyBox.problem = ctl.firebaseProblem(apiKeyField.text, projectField.text)
                if (keyBox.problem === "") { ctl.setFirebase(apiKeyField.text, projectField.text); keyBox.editing = false }
            }
                }
                TextButton {
                    visible: keyBox.editing && ctl.configured
                    text: qsTr("Cancel")
                    onClicked: { apiKeyField.text = ctl.apiKey; projectField.text = ctl.projectId; keyBox.problem = ""; keyBox.editing = false }
                }
                TextButton {
                    visible: keyBox.editing && !ctl.configured && !ctl.setupSeen
                    text: qsTr("Skip for now")
                    onClicked: { ctl.skipSetup(); page.app.settingsOpen = false }
                }
                TextButton {
                    visible: !keyBox.editing
                    text: qsTr("Edit")
                    onClicked: { apiKeyField.text = ctl.apiKey; projectField.text = ctl.projectId; keyBox.editing = true }
                }
            }
        }

        Label {
            Layout.margins: Theme.large
            font.pointSize: 12
            font.bold: true
            text: qsTr("Account")
        }

        // ---- signed in ----
        ColumnLayout {
            visible: ctl.userEmail !== ""
            Layout.fillWidth: true
            Layout.leftMargin: Theme.large
            Layout.rightMargin: Theme.large
            spacing: Theme.large

            Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                text: qsTr("Signed in as %1").arg(ctl.userEmail)
            }
            Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                opacity: 0.7
                text: qsTr("Your notes stay on this device when you sign out.")
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authInfo !== ""
                wrapMode: Text.Wrap
                color: Theme.positive
                text: ctl.authInfo
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authError !== ""
                wrapMode: Text.Wrap
                color: Theme.negative
                text: ctl.authError
            }

            // ---- encryption key of the cloud copy ----
            ColumnLayout {
                Layout.fillWidth: true
                spacing: Theme.small

                Label {
                    font.bold: true
                    text: qsTr("Encryption key")
                }
                RowLayout {
                    visible: ctl.keyBusy
                    Layout.fillWidth: true
                    spacing: Theme.large
                    BusyIndicator {
                        running: ctl.keyBusy
                        Layout.preferredHeight: Theme.iconMedium
                        Layout.preferredWidth: Theme.iconMedium
                    }
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                        text: qsTr("Setting up encryption. Please keep this open, it is not finished yet.")
                    }
                }
                RowLayout {
                    visible: ctl.keyPhase === "" && !ctl.keyBusy
                    Layout.fillWidth: true
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                        opacity: 0.7
                        text: qsTr("The encryption key is not checked yet, so nothing syncs.")
                    }
                    TextButton {
                        text: qsTr("Retry")
                        onClicked: ctl.startKeyFlow()
                    }
                }

                // a new key was made: show it, sync waits for the confirmation
                ColumnLayout {
                    visible: ctl.keyPhase === "save"
                    Layout.fillWidth: true
                    spacing: Theme.small
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                        text: qsTr("Your notes are encrypted on this device before they are uploaded. This is your key. Without it nobody, you included, can read the notes in the cloud. Store it somewhere safe, you need it to add another device. Sync starts when you confirm.")
                    }
                    // four fixed lines, never re-wrapped by the width of the window
                    Rectangle {
                        Layout.alignment: Qt.AlignHCenter
                        implicitWidth: keyBlockSave.implicitWidth + Theme.large * 2
                        implicitHeight: keyBlockSave.implicitHeight + Theme.large * 2
                        radius: Theme.radius
                        color: Qt.alpha(Theme.text, 0.08)
                        TextEdit {
                            id: keyBlockSave
                            anchors.centerIn: parent
                            readOnly: true
                            selectByMouse: true
                            wrapMode: TextEdit.NoWrap
                            horizontalAlignment: TextEdit.AlignHCenter
                            font.family: Theme.mono
                            font.bold: true
                            font.pointSize: 11
                            color: Theme.text
                            text: ctl.keyValue
                        }
                    }
                    // for the phone's camera: the Firebase project and the key at once (SetupCode.js)
                    QrImage {
                        Layout.alignment: Qt.AlignHCenter
                        text: ctl.setupCode !== "" ? ctl.setupCode : ctl.keyValue.replace(/\n/g, "-")
                    }
                    Label {
                        Layout.fillWidth: true
                        visible: ctl.setupCode !== ""
                        wrapMode: Text.Wrap
                        horizontalAlignment: Text.AlignHCenter
                        opacity: 0.7
                        text: qsTr("Scan it with the phone app to set it up at once: the Firebase project and this key. You still sign in there with your password.")
                    }
                    RowLayout {
                        TextButton {
                            text: qsTr("Copy key")
                            onClicked: page.app.copyText(ctl.keyText())
                        }
                        TextButton {
                            visible: ctl.setupCode !== ""
                            text: qsTr("Copy setup code")
                            onClicked: page.app.copyText(ctl.setupCode)
                        }
                        TextButton {
                            primary: true
                            text: qsTr("I saved it")
                            onClicked: ctl.confirmKey()
                        }
                    }
                }

                // the cloud has a key this device doesn't know
                ColumnLayout {
                    visible: ctl.keyPhase === "enter"
                    Layout.fillWidth: true
                    spacing: Theme.small
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                        text: ctl.keyMismatch
                            ? qsTr("The key for this account was changed on another device. Enter the new key to sync again.")
                            : qsTr("The notes in the cloud are encrypted. Enter your encryption key to sync this device.")
                    }
                    TextField {
                        id: keyField
                        Layout.fillWidth: true
                        placeholderText: qsTr("XXXX-XXXX-XXXX-...")
                        font.family: Theme.mono
                        enabled: !ctl.keyBusy
                        onAccepted: ctl.submitKey(text)
                        Connections {
                            target: ctl
                            function onKeyPhaseChanged() { keyField.text = "" }
                        }
                    }
                    TextButton {
                        primary: true
                        text: qsTr("Unlock")
                        enabled: !ctl.keyBusy && keyField.text.trim() !== ""
                        onClicked: ctl.submitKey(keyField.text)
                    }
                }

                // key known: show or copy it on demand
                ColumnLayout {
                    visible: ctl.keyPhase === "ready"
                    Layout.fillWidth: true
                    spacing: Theme.small
                    RowLayout {
                        TextButton {
                            text: ctl.keyShown ? qsTr("Hide key") : qsTr("Show key")
                            onClicked: ctl.toggleKeyShown()
                        }
                        TextButton {
                            text: qsTr("Copy key")
                            onClicked: page.app.copyText(ctl.keyText())
                        }
                        TextButton {
                            visible: ctl.setupCode !== ""
                            text: qsTr("Copy setup code")
                            onClicked: page.app.copyText(ctl.setupCode)
                        }
                    }
                    Rectangle {
                        Layout.alignment: Qt.AlignHCenter
                        visible: ctl.keyShown
                        implicitWidth: keyBlockReady.implicitWidth + Theme.large * 2
                        implicitHeight: keyBlockReady.implicitHeight + Theme.large * 2
                        radius: Theme.radius
                        color: Qt.alpha(Theme.text, 0.08)
                        TextEdit {
                            id: keyBlockReady
                            anchors.centerIn: parent
                            readOnly: true
                            selectByMouse: true
                            wrapMode: TextEdit.NoWrap
                            horizontalAlignment: TextEdit.AlignHCenter
                            font.family: Theme.mono
                            font.bold: true
                            font.pointSize: 11
                            color: Theme.text
                            text: ctl.keyValue
                        }
                    }
                    QrImage {
                        Layout.alignment: Qt.AlignHCenter
                        visible: ctl.keyShown
                        text: ctl.setupCode !== "" ? ctl.setupCode : ctl.keyValue.replace(/\n/g, "-")
                    }
                    Label {
                        Layout.fillWidth: true
                        visible: ctl.keyShown && ctl.setupCode !== ""
                        wrapMode: Text.Wrap
                        horizontalAlignment: Text.AlignHCenter
                        opacity: 0.7
                        text: qsTr("Scan it with the phone app to set it up at once: the Firebase project and this key. You still sign in there with your password.")
                    }
                }

                Label {
                    Layout.fillWidth: true
                    visible: ctl.keyError !== ""
                    wrapMode: Text.Wrap
                    color: Theme.negative
                    text: ctl.keyError
                }

                // lost key
                TextButton {
                    visible: (ctl.keyPhase === "enter" || ctl.keyPhase === "ready") && !ctl.resetOpen
                    text: ctl.keyPhase === "enter" ? qsTr("Lost the key? Reset…") : qsTr("Reset key…")
                    enabled: !ctl.keyBusy
                    onClicked: ctl.resetOpen = true
                }
                ColumnLayout {
                    visible: ctl.resetOpen
                    Layout.fillWidth: true
                    spacing: Theme.small
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.Wrap
                        color: Theme.negative
                        text: qsTr("This deletes ALL notes from the cloud and creates a new key. The notes on this device stay and are uploaded again, encrypted with the new key. Notes that exist only in the cloud or only on other devices are gone from the cloud. Other devices will ask for the new key.")
                    }
                    RowLayout {
                        TextButton {
                            destructive: true
                            text: qsTr("Delete cloud notes and make a new key")
                            enabled: !ctl.keyBusy
                            onClicked: ctl.resetKey()
                        }
                        TextButton {
                            text: qsTr("Cancel")
                            enabled: !ctl.keyBusy
                            onClicked: ctl.resetOpen = false
                        }
                    }
                }
            }

            TextButton {
                text: qsTr("Sign out")
                onClicked: ctl.signOut()
            }
        }

        // ---- signed in, email not verified yet ----
        ColumnLayout {
            visible: ctl.userEmail === "" && ctl.pending !== null
            Layout.fillWidth: true
            Layout.leftMargin: Theme.large
            Layout.rightMargin: Theme.large
            spacing: Theme.large

            Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                text: qsTr("Verify your email: %1").arg(ctl.pending ? ctl.pending.email : "")
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authInfo !== ""
                wrapMode: Text.Wrap
                opacity: 0.8
                text: ctl.authInfo
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authError !== ""
                wrapMode: Text.Wrap
                color: Theme.negative
                text: ctl.authError
            }
            RowLayout {
                spacing: Theme.small
                TextButton {
                    primary: true
                    text: qsTr("Continue")
                    enabled: !ctl.authBusy
                    onClicked: ctl.checkVerified()
                }
                TextButton {
                    text: qsTr("Resend email")
                    enabled: !ctl.authBusy
                    onClicked: ctl.resendVerification()
                }
                TextButton {
                    text: qsTr("Cancel")
                    enabled: !ctl.authBusy
                    onClicked: ctl.cancelPending()
                }
                BusyIndicator {
                    running: ctl.authBusy
                    visible: ctl.authBusy
                    Layout.preferredHeight: Theme.iconMedium
                    Layout.preferredWidth: Theme.iconMedium
                }
            }
        }

        // ---- signed out ----
        ColumnLayout {
            visible: ctl.userEmail === "" && ctl.pending === null
            Layout.fillWidth: true
            Layout.leftMargin: Theme.large
            Layout.rightMargin: Theme.large
            spacing: Theme.large

            Label {
                Layout.fillWidth: true
                wrapMode: Text.Wrap
                opacity: 0.7
                text: ctl.configured
                    ? qsTr("Notes are stored on this device. Sign in to sync them between devices.")
                    : qsTr("Notes are stored on this device. Sign-in is not set up: enter your Firebase project above.")
            }

            TextField {
                id: emailField
                Layout.fillWidth: true
                visible: ctl.configured
                enabled: !ctl.authBusy
                placeholderText: qsTr("Email")
                inputMethodHints: Qt.ImhEmailCharactersOnly
                onAccepted: passwordField.forceActiveFocus()
            }
            TextField {
                id: passwordField
                Layout.fillWidth: true
                visible: ctl.configured
                enabled: !ctl.authBusy
                placeholderText: qsTr("Password")
                echoMode: TextInput.Password
                onAccepted: signInButton.clicked()
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authInfo !== ""
                wrapMode: Text.Wrap
                color: Theme.positive
                text: ctl.authInfo
            }
            Label {
                Layout.fillWidth: true
                visible: ctl.authError !== ""
                wrapMode: Text.Wrap
                color: Theme.negative
                text: ctl.authError
            }
            RowLayout {
                visible: ctl.configured
                spacing: Theme.small

                TextButton {
                    id: signInButton
                    primary: true
                    text: qsTr("Sign in")
                    enabled: !ctl.authBusy && emailField.text.trim() !== "" && passwordField.text !== ""
                    onClicked: {
                        if (enabled)
                            ctl.authenticate(false, emailField.text.trim(), passwordField.text)
                    }
                }
                TextButton {
                    text: qsTr("Create account")
                    enabled: signInButton.enabled
                    onClicked: ctl.authenticate(true, emailField.text.trim(), passwordField.text)
                }
                TextButton {
                    text: qsTr("Forgot password?")
                    enabled: !ctl.authBusy
                    onClicked: ctl.resetPassword(emailField.text.trim())
                }
                BusyIndicator {
                    running: ctl.authBusy
                    visible: ctl.authBusy
                    Layout.preferredHeight: Theme.iconMedium
                    Layout.preferredWidth: Theme.iconMedium
                }
            }
        }
    }
}
