import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami
import "NoteStore.js" as NoteStore

PlasmoidItem {
    id: root

    Plasmoid.icon: "chaosnoteviewer"
    toolTipMainText: i18n("Chaos Note Viewer")

    // 0 = Uncompleted (default), 1 = Completed, 2 = All
    property int filter: 0
    property bool filterOpen: false
    property bool settingsOpen: false
    property bool addOpen: false
    // Placeholder until sign-in exists; the app is local-first and works without an account.
    property string userEmail: ""

    // All notes, loaded from the local SQLite database (see NoteStore.js).
    ListModel { id: notes }

    // Filtered copy of `notes`; srcIndex maps a row back to its source note.
    ListModel { id: shown }

    function refresh() {
        shown.clear()
        for (let i = 0; i < notes.count; ++i) {
            const n = notes.get(i)
            if (filter === 2 || (filter === 0) === !n.completed)
                shown.append({ noteId: n.noteId, title: n.title, description: n.description, completed: n.completed, dueDate: n.dueDate })
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
    }

    function removeNote(noteId) {
        NoteStore.remove(noteId)
        const i = indexOfNote(noteId)
        if (i >= 0)
            notes.remove(i)
        refresh()
    }

    function setCompleted(noteId, completed) {
        NoteStore.setCompleted(noteId, completed)
        const i = indexOfNote(noteId)
        if (i >= 0)
            notes.setProperty(i, "completed", completed)
        refresh()
    }

    onFilterChanged: refresh()
    Component.onCompleted: {
        for (const n of NoteStore.all())
            notes.append(n)
        refresh()
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

                    PlasmaComponents3.Label {
                        anchors.centerIn: parent
                        visible: list.count === 0
                        horizontalAlignment: Text.AlignHCenter
                        opacity: 0.6
                        text: notes.count === 0 ? i18n("No notes yet.\nPress + to add one.") : i18n("Nothing here")
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
