import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami

PlasmoidItem {
    id: root

    Plasmoid.icon: "view-pim-notes"
    toolTipMainText: i18n("Chaos Note Viewer")

    // 0 = Uncompleted (default), 1 = Completed, 2 = All
    property int filter: 0
    property bool filterOpen: false
    property bool settingsOpen: false
    // Placeholder until sign-in exists; the app is local-first and works without an account.
    property string userEmail: ""

    // Placeholder data, same as the Qt app's NoteModel until the local DB exists.
    ListModel {
        id: notes
        ListElement { title: "Buy milk"; description: "Two liters of oat milk, and check whether the corner shop still has the good bread before it closes at eight."; completed: false }
        ListElement { title: "Reply to landlord"; description: "He asked about the heating invoice and the date for the yearly inspection of the boiler."; completed: false }
        ListElement { title: "Write the crypto spec"; description: "AES-256-GCM for note content, PBKDF2 for the password wrap, recovery code wrap."; completed: false }
        ListElement { title: "Set up Firebase emulators"; description: "Auth and Firestore emulators via firebase init."; completed: true }
    }

    // Filtered copy of `notes`; srcIndex maps a row back to its source note.
    ListModel { id: shown }

    function refresh() {
        shown.clear()
        for (let i = 0; i < notes.count; ++i) {
            const n = notes.get(i)
            if (filter === 2 || (filter === 0) === !n.completed)
                shown.append({ title: n.title, description: n.description, completed: n.completed, srcIndex: i })
        }
    }
    onFilterChanged: refresh()
    Component.onCompleted: refresh()

    compactRepresentation: MouseArea {
        id: compact
        activeFocusOnTab: true
        hoverEnabled: true
        onClicked: root.expanded = !root.expanded

        Kirigami.Icon {
            anchors.fill: parent
            source: "view-pim-notes"
            active: compact.containsMouse
        }
    }

    fullRepresentation: ColumnLayout {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 18
        Layout.preferredWidth: Kirigami.Units.gridUnit * 22
        Layout.minimumHeight: Kirigami.Units.gridUnit * 18
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

                PlasmaComponents3.Label {
                    anchors.centerIn: parent
                    visible: list.count === 0
                    opacity: 0.6
                    text: i18n("Nothing here")
                }

                delegate: Rectangle {
                    id: card
                    width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
                    height: row.implicitHeight + Kirigami.Units.largeSpacing * 2
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
                                text: model.description
                                maximumLineCount: 1
                                elide: Text.ElideRight
                                opacity: 0.7
                                font.pointSize: Kirigami.Theme.smallFont.pointSize
                            }
                        }

                        PlasmaComponents3.CheckBox {
                            Layout.alignment: Qt.AlignVCenter
                            checked: model.completed
                            onToggled: {
                                notes.setProperty(model.srcIndex, "completed", checked)
                                root.refresh()
                            }
                        }
                    }
                }
            }

            // Settings placeholder; real options arrive with the features they control.
            ColumnLayout {
                spacing: Kirigami.Units.largeSpacing

                Kirigami.Heading {
                    Layout.margins: Kirigami.Units.largeSpacing
                    level: 4
                    text: i18n("Account")
                }
                PlasmaComponents3.Label {
                    Layout.fillWidth: true
                    Layout.leftMargin: Kirigami.Units.largeSpacing
                    Layout.rightMargin: Kirigami.Units.largeSpacing
                    wrapMode: Text.Wrap
                    opacity: 0.7
                    text: i18n("Notes are stored on this device. Sign in to sync them between devices.")
                }
                PlasmaComponents3.Button {
                    Layout.leftMargin: Kirigami.Units.largeSpacing
                    text: i18n("Sign in to sync")
                    icon.name: "user-identity"
                    enabled: false // sign-in is not implemented yet
                }
                Item { Layout.fillHeight: true }
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
                text: root.userEmail !== ""
                    ? i18n("Signed in as %1", root.userEmail)
                    : i18n("Not signed in · local only")
            }
        }
    }
}
