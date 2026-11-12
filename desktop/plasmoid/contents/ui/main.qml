import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami
import org.kde.plasma.plasma5support as P5Support
import "Crypto.js" as Crypto

PlasmoidItem {
    id: root

    Plasmoid.icon: "chaosnoteviewer"
    toolTipMainText: i18n("Chaos Note Viewer")

    // UI state of this launcher
    property bool settingsOpen: false
    property bool addOpen: false

    // Everything that is not Plasma-specific lives in the shared AppController.qml (notes, account,
    // encryption key, sync). This widget supplies the open/closed state and the source of random numbers.
    AppController {
        id: ctl
        active: root.expanded
        entropy: root.osRandom
    }

    // QML has no secure random numbers: ask the operating system for n random bytes.
    property var randomWaiting: ({})
    property int randomCounter: 0
    P5Support.DataSource {
        id: randomSource
        engine: "executable"
        connectedSources: []
        onNewData: function (source, data) {
            disconnectSource(source)
            const done = root.randomWaiting[source]
            if (!done)
                return
            delete root.randomWaiting[source]
            const bytes = Crypto.fromHex(String(data.stdout || ""), done.length)
            if (bytes === null)
                done.callback({ code: "RANDOM", message: i18n("Could not get random numbers from the system (%1).", String(data.stderr || "").trim()) }, null)
            else
                done.callback(null, bytes)
        }
    }

    function osRandom(n, callback) {
        // the trailing comment only makes every command text unique, the engine runs a text once at a time
        const command = "head -c " + n + " /dev/urandom | od -An -tx1 | tr -d ' \\n' # " + (++randomCounter)
        randomWaiting[command] = { length: n, callback: callback }
        randomSource.connectSource(command)
    }

    function copyText(text) {
        clipboard.text = text
        clipboard.selectAll()
        clipboard.copy()
    }
    TextEdit { id: clipboard; visible: false }

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
        Layout.minimumWidth: Kirigami.Units.gridUnit * 34
        Layout.preferredWidth: Kirigami.Units.gridUnit * 48
        Layout.minimumHeight: Kirigami.Units.gridUnit * 12
        Layout.preferredHeight: Kirigami.Units.gridUnit * 24

        ColumnLayout {
            anchors.fill: parent
            spacing: 0

            RowLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                // ---- Side navigation: which notes the list shows, settings at the bottom ----
                ColumnLayout {
                    Layout.fillHeight: true
                    Layout.topMargin: Kirigami.Units.largeSpacing        // the scratch is taller than its button
                    Layout.bottomMargin: Kirigami.Units.smallSpacing
                    // no side margins: the buttons are as wide as the bar, nothing empty beside them
                    spacing: Kirigami.Units.largeSpacing

                    Repeater {
                        model: [
                            { icon: "compass", text: i18n("Open notes"), filter: 0 },
                            { icon: "exclamation", text: i18n("Important: overdue or due in the next %1 days", ctl.importantDays), filter: 3 },
                            { icon: "check", text: i18n("Completed"), filter: 1 }
                        ]
                        delegate: NavButton {
                            required property var modelData
                            iconName: modelData.icon
                            tip: modelData.text
                            // not checkable: a click would break the binding, the binding decides
                            checked: !root.settingsOpen && ctl.filter === modelData.filter
                            onClicked: {
                                root.settingsOpen = false
                                ctl.filter = modelData.filter
                            }
                        }
                    }

                    Item { Layout.fillHeight: true }

                    NavButton {
                        iconName: "gear"
                        tip: i18n("Settings")
                        checked: root.settingsOpen
                        onClicked: root.settingsOpen = true
                    }
                }

                Kirigami.Separator { Layout.fillHeight: true }

                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    spacing: 0

                    // ---- Header ----
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.margins: Kirigami.Units.smallSpacing
                        spacing: Kirigami.Units.smallSpacing

                        Kirigami.Heading {
                            Layout.fillWidth: true
                            Layout.leftMargin: Kirigami.Units.smallSpacing
                            level: 1
                            font.weight: 900
                            elide: Text.ElideRight
                            text: root.settingsOpen ? i18n("Settings")
                                : [i18n("Open notes"), i18n("Completed"), i18n("All notes"), i18n("Important")][ctl.filter]
                        }
                        NavButton {
                            id: sortButton
                            visible: !root.settingsOpen
                            iconName: "filter"
                            tip: ctl.sorted ? i18n("Sorted by %1", ctl.sortText) : i18n("Sort by")
                            checked: ctl.sorted
                            tipEnabled: !sortMenu.visible
                            onClicked: sortMenu.popup(sortButton, 0, sortButton.height)
                        }
                        NavButton {
                            visible: !root.settingsOpen
                            iconName: "plus"
                            tip: i18n("New note")
                            onClicked: root.addOpen = true
                        }

                        PlasmaComponents3.Menu {
                            id: sortMenu
                            // step 1: what to sort by. The direction is step 2, the switch under the title.
                            width: Kirigami.Units.gridUnit * 18
                            IconMenuItem {
                                iconName: "list"
                                text: i18n("Your order (drag to reorder)")
                                checkable: true
                                checked: ctl.sortField === 0
                                // the click toggles `checked` itself, put the binding back. Choosing the mode
                                // that is already on keeps its direction; a new one starts with its natural one.
                                onTriggered: {
                                    if (ctl.sortField !== 0)
                                        ctl.setSortField(0)
                                    checked = Qt.binding(() => ctl.sortField === 0)
                                }
                            }
                            IconMenuItem {
                                iconName: "sort-az"
                                text: i18n("Name")
                                checkable: true
                                checked: ctl.sortField === 1
                                // the click toggles `checked` itself, put the binding back. Choosing the mode
                                // that is already on keeps its direction; a new one starts with its natural one.
                                onTriggered: {
                                    if (ctl.sortField !== 1)
                                        ctl.setSortField(1)
                                    checked = Qt.binding(() => ctl.sortField === 1)
                                }
                            }
                            IconMenuItem {
                                iconName: "sort-deadline"
                                text: i18n("Deadline")
                                checkable: true
                                checked: ctl.sortField === 2
                                // the click toggles `checked` itself, put the binding back. Choosing the mode
                                // that is already on keeps its direction; a new one starts with its natural one.
                                onTriggered: {
                                    if (ctl.sortField !== 2)
                                        ctl.setSortField(2)
                                    checked = Qt.binding(() => ctl.sortField === 2)
                                }
                            }
                            IconMenuItem {
                                iconName: "sort-date"
                                text: i18n("Date created")
                                checkable: true
                                checked: ctl.sortField === 3
                                // the click toggles `checked` itself, put the binding back. Choosing the mode
                                // that is already on keeps its direction; a new one starts with its natural one.
                                onTriggered: {
                                    if (ctl.sortField !== 3)
                                        ctl.setSortField(3)
                                    checked = Qt.binding(() => ctl.sortField === 3)
                                }
                            }
                        }
                    }

                    // a forced order is on: say so, and offer the way back to your own order
                    RowLayout {
                        visible: !root.settingsOpen && ctl.sorted
                        Layout.fillWidth: true
                        Layout.leftMargin: Kirigami.Units.largeSpacing
                        Layout.rightMargin: Kirigami.Units.smallSpacing
                        spacing: Kirigami.Units.smallSpacing
                        PlasmaComponents3.Label {
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                            opacity: 0.75
                            text: i18n("Sorted by %1:", ctl.sortFieldNames[ctl.sortField])
                        }
                        // step 2: the direction of the chosen mode (A-Z | Z-A, closest | furthest, newest | oldest)
                        Repeater {
                            model: ctl.sorted ? [false, true] : []
                            delegate: NavButton {
                                required property bool modelData
                                // Date created lists "newest first" first: that is its natural direction
                                readonly property bool desc: ctl.sortField === 3 ? !modelData : modelData
                                iconName: ctl.sortDirectionIcons[ctl.sortField][desc ? 1 : 0]
                                // the Name icons already read "A-Z" / "Z-A": no text next to them
                                label: ctl.sortField === 1 ? "" : i18n(ctl.sortDirectionNames[ctl.sortField][desc ? 1 : 0])
                                iconSize: ctl.sortField === 1 ? Kirigami.Units.iconSizes.medium : Kirigami.Units.iconSizes.smallMedium
                                wide: true
                                tip: i18n(ctl.sortDirectionNames[ctl.sortField][desc ? 1 : 0])
                                checked: ctl.sortDesc === desc
                                onClicked: ctl.sortDesc = desc
                            }
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            elide: Text.ElideRight
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                            opacity: 0.6
                            text: i18n("dragging is off")
                        }
                        PlasmaComponents3.ToolButton {
                            text: i18n("Your order")
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                            flat: true
                            onClicked: ctl.setSortField(0)
                        }
                    }

                    Kirigami.Separator { Layout.fillWidth: true }

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
                            model: ctl.shown


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
                                text: ctl.notes.count === 0 ? i18n("No notes yet.\nPress + to add one.")
                                    : ctl.filter === 3 ? i18n("Nothing urgent.\nOpen notes that are overdue or due in the next %1 days show up here.", ctl.importantDays)
                                    : i18n("Nothing here")
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
                                                readonly property bool overdue: !model.completed && model.dueDate < ctl.startOfToday()

                                                Image {
                                                    source: Qt.resolvedUrl("icons/" + (parent.overdue ? "calendar-alert" : "calendar") + ".png")
                                                    sourceSize: Qt.size(Kirigami.Units.iconSizes.small * 3, Kirigami.Units.iconSizes.small * 3)
                                                    smooth: true
                                                    mipmap: true
                                                    Layout.preferredWidth: Kirigami.Units.iconSizes.small * 1.25
                                                    Layout.preferredHeight: Kirigami.Units.iconSizes.small * 1.25
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
                                        NavButton {
                                            Layout.alignment: Qt.AlignVCenter
                                            visible: model.missing
                                            iconName: "upload"
                                            iconSize: Kirigami.Units.iconSizes.smallMedium
                                            wide: false
                                            tip: i18n("Upload again")
                                            opacity: hover.hovered ? 1 : 0
                                            enabled: hover.hovered
                                            onClicked: ctl.reuploadNote(model.noteId)
                                        }

                                        NavButton {
                                            Layout.alignment: Qt.AlignVCenter
                                            iconName: "trash-red"
                                            iconSize: Kirigami.Units.iconSizes.smallMedium
                                            wide: false
                                            tip: i18n("Delete")
                                            opacity: hover.hovered ? 1 : 0
                                            enabled: hover.hovered
                                            onClicked: ctl.removeNote(model.noteId)
                                        }

                                        // shown only while the pointer is on the note; completed notes are already
                                        // struck through and dimmed, so the button then reopens the note
                                        NavButton {
                                            Layout.alignment: Qt.AlignVCenter
                                            iconName: "check-double"
                                            iconSize: Kirigami.Units.iconSizes.smallMedium
                                            wide: false
                                            tip: model.completed ? i18n("Mark as not completed") : i18n("Mark as completed")
                                            opacity: hover.hovered ? 1 : 0
                                            enabled: hover.hovered
                                            onClicked: ctl.setCompleted(model.noteId, !model.completed)
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
                                        enabled: !ctl.sorted
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
                                                ctl.dropNote(from, to)
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
                                visible: ctl.userEmail !== ""
                                Layout.fillWidth: true
                                Layout.leftMargin: Kirigami.Units.largeSpacing
                                Layout.rightMargin: Kirigami.Units.largeSpacing
                                spacing: Kirigami.Units.largeSpacing

                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    text: i18n("Signed in as %1", ctl.userEmail)
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    opacity: 0.7
                                    text: i18n("Your notes stay on this device when you sign out.")
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authInfo !== ""
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.positiveTextColor
                                    text: ctl.authInfo
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authError !== ""
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.negativeTextColor
                                    text: ctl.authError
                                }
                                // ---- encryption key of the cloud copy ----
                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: Kirigami.Units.smallSpacing

                                    Kirigami.Heading {
                                        level: 5
                                        text: i18n("Encryption key")
                                    }
                                    RowLayout {
                                        visible: ctl.keyBusy
                                        Layout.fillWidth: true
                                        spacing: Kirigami.Units.largeSpacing
                                        PlasmaComponents3.BusyIndicator {
                                            running: ctl.keyBusy
                                            Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                            Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                                        }
                                        PlasmaComponents3.Label {
                                            Layout.fillWidth: true
                                            wrapMode: Text.Wrap
                                            text: i18n("Setting up encryption. Please keep this open, it is not finished yet.")
                                        }
                                    }
                                    RowLayout {
                                        visible: ctl.keyPhase === "" && !ctl.keyBusy
                                        Layout.fillWidth: true
                                        PlasmaComponents3.Label {
                                            Layout.fillWidth: true
                                            wrapMode: Text.Wrap
                                            opacity: 0.7
                                            text: i18n("The encryption key is not checked yet, so nothing syncs.")
                                        }
                                        PlasmaComponents3.Button {
                                            text: i18n("Retry")
                                            icon.name: "view-refresh"
                                            onClicked: ctl.startKeyFlow()
                                        }
                                    }

                                    // a new key was made: show it, sync waits for the confirmation
                                    ColumnLayout {
                                        visible: ctl.keyPhase === "save"
                                        Layout.fillWidth: true
                                        spacing: Kirigami.Units.smallSpacing
                                        PlasmaComponents3.Label {
                                            Layout.fillWidth: true
                                            wrapMode: Text.Wrap
                                            text: i18n("Your notes are encrypted on this device before they are uploaded. This is your key. Without it nobody, you included, can read the notes in the cloud. Store it somewhere safe, you need it to add another device. Sync starts when you confirm.")
                                        }
                                        // four fixed lines, never re-wrapped by the width of the window
                                        Rectangle {
                                            Layout.alignment: Qt.AlignHCenter
                                            implicitWidth: keyBlockSave.implicitWidth + Kirigami.Units.largeSpacing * 2
                                            implicitHeight: keyBlockSave.implicitHeight + Kirigami.Units.largeSpacing * 2
                                            radius: Kirigami.Units.cornerRadius
                                            color: Qt.alpha(Kirigami.Theme.textColor, 0.08)
                                            TextEdit {
                                                id: keyBlockSave
                                                anchors.centerIn: parent
                                                readOnly: true
                                                selectByMouse: true
                                                wrapMode: TextEdit.NoWrap
                                                horizontalAlignment: TextEdit.AlignHCenter
                                                font.family: "monospace"
                                                font.bold: true
                                                font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.2
                                                color: Kirigami.Theme.textColor
                                                text: ctl.keyValue
                                            }
                                        }
                                        RowLayout {
                                            PlasmaComponents3.Button {
                                                text: i18n("Copy key")
                                                icon.name: "edit-copy"
                                                onClicked: root.copyText(ctl.keyText())
                                            }
                                            PlasmaComponents3.Button {
                                                text: i18n("I saved it")
                                                icon.name: "dialog-ok"
                                                onClicked: ctl.confirmKey()
                                            }
                                        }
                                    }

                                    // the cloud has a key this device doesn't know
                                    ColumnLayout {
                                        visible: ctl.keyPhase === "enter"
                                        Layout.fillWidth: true
                                        spacing: Kirigami.Units.smallSpacing
                                        PlasmaComponents3.Label {
                                            Layout.fillWidth: true
                                            wrapMode: Text.Wrap
                                            text: ctl.keyMismatch
                                                ? i18n("The key for this account was changed on another device. Enter the new key to sync again.")
                                                : i18n("The notes in the cloud are encrypted. Enter your encryption key to sync this device.")
                                        }
                                        PlasmaComponents3.TextField {
                                            id: keyField
                                            Layout.fillWidth: true
                                            placeholderText: i18n("XXXX-XXXX-XXXX-...")
                                            font.family: "monospace"
                                            enabled: !ctl.keyBusy
                                            onAccepted: ctl.submitKey(text)
                                            Connections {
                                                target: ctl
                                                function onKeyPhaseChanged() { keyField.text = "" }
                                            }
                                        }
                                        PlasmaComponents3.Button {
                                            text: i18n("Unlock")
                                            icon.name: "unlock"
                                            enabled: !ctl.keyBusy && keyField.text.trim() !== ""
                                            onClicked: ctl.submitKey(keyField.text)
                                        }
                                    }

                                    // key known: show or copy it on demand
                                    ColumnLayout {
                                        visible: ctl.keyPhase === "ready"
                                        Layout.fillWidth: true
                                        spacing: Kirigami.Units.smallSpacing
                                        RowLayout {
                                            PlasmaComponents3.Button {
                                                text: ctl.keyShown ? i18n("Hide key") : i18n("Show key")
                                                icon.name: ctl.keyShown ? "hide_table_row" : "view-visible"
                                                onClicked: ctl.toggleKeyShown()
                                            }
                                            PlasmaComponents3.Button {
                                                text: i18n("Copy key")
                                                icon.name: "edit-copy"
                                                onClicked: root.copyText(ctl.keyText())
                                            }
                                        }
                                        Rectangle {
                                            Layout.alignment: Qt.AlignHCenter
                                            visible: ctl.keyShown
                                            implicitWidth: keyBlockReady.implicitWidth + Kirigami.Units.largeSpacing * 2
                                            implicitHeight: keyBlockReady.implicitHeight + Kirigami.Units.largeSpacing * 2
                                            radius: Kirigami.Units.cornerRadius
                                            color: Qt.alpha(Kirigami.Theme.textColor, 0.08)
                                            TextEdit {
                                                id: keyBlockReady
                                                anchors.centerIn: parent
                                                readOnly: true
                                                selectByMouse: true
                                                wrapMode: TextEdit.NoWrap
                                                horizontalAlignment: TextEdit.AlignHCenter
                                                font.family: "monospace"
                                                font.bold: true
                                                font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.2
                                                color: Kirigami.Theme.textColor
                                                text: ctl.keyValue
                                            }
                                        }
                                    }

                                    PlasmaComponents3.Label {
                                        Layout.fillWidth: true
                                        visible: ctl.keyError !== ""
                                        wrapMode: Text.Wrap
                                        color: Kirigami.Theme.negativeTextColor
                                        text: ctl.keyError
                                    }

                                    // lost key
                                    PlasmaComponents3.Button {
                                        visible: (ctl.keyPhase === "enter" || ctl.keyPhase === "ready") && !ctl.resetOpen
                                        flat: true
                                        text: ctl.keyPhase === "enter" ? i18n("Lost the key? Reset…") : i18n("Reset key…")
                                        enabled: !ctl.keyBusy
                                        onClicked: ctl.resetOpen = true
                                    }
                                    ColumnLayout {
                                        visible: ctl.resetOpen
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
                                                enabled: !ctl.keyBusy
                                                onClicked: ctl.resetKey()
                                            }
                                            PlasmaComponents3.Button {
                                                flat: true
                                                text: i18n("Cancel")
                                                enabled: !ctl.keyBusy
                                                onClicked: ctl.resetOpen = false
                                            }
                                        }
                                    }
                                }

                                PlasmaComponents3.Button {
                                    text: i18n("Sign out")
                                    icon.name: "system-log-out"
                                    onClicked: ctl.signOut()
                                }
                            }

                            // signed in, email not verified yet
                            ColumnLayout {
                                visible: ctl.userEmail === "" && ctl.pending !== null
                                Layout.fillWidth: true
                                Layout.leftMargin: Kirigami.Units.largeSpacing
                                Layout.rightMargin: Kirigami.Units.largeSpacing
                                spacing: Kirigami.Units.largeSpacing

                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    text: i18n("Verify your email: %1", ctl.pending ? ctl.pending.email : "")
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authInfo !== ""
                                    wrapMode: Text.Wrap
                                    opacity: 0.8
                                    text: ctl.authInfo
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authError !== ""
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.negativeTextColor
                                    text: ctl.authError
                                }
                                RowLayout {
                                    spacing: Kirigami.Units.smallSpacing

                                    PlasmaComponents3.Button {
                                        text: i18n("Continue")
                                        icon.name: "dialog-ok"
                                        enabled: !ctl.authBusy
                                        onClicked: ctl.checkVerified()
                                    }
                                    PlasmaComponents3.Button {
                                        text: i18n("Resend email")
                                        icon.name: "mail-send"
                                        enabled: !ctl.authBusy
                                        onClicked: ctl.resendVerification()
                                    }
                                    PlasmaComponents3.Button {
                                        flat: true
                                        text: i18n("Cancel")
                                        enabled: !ctl.authBusy
                                        onClicked: ctl.cancelPending()
                                    }
                                    PlasmaComponents3.BusyIndicator {
                                        running: ctl.authBusy
                                        visible: ctl.authBusy
                                        Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                        Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                                    }
                                }
                            }

                            // signed out
                            ColumnLayout {
                                visible: ctl.userEmail === "" && ctl.pending === null
                                Layout.fillWidth: true
                                Layout.leftMargin: Kirigami.Units.largeSpacing
                                Layout.rightMargin: Kirigami.Units.largeSpacing
                                spacing: Kirigami.Units.largeSpacing

                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    wrapMode: Text.Wrap
                                    opacity: 0.7
                                    text: ctl.configured
                                        ? i18n("Notes are stored on this device. Sign in to sync them between devices.")
                                        : i18n("Notes are stored on this device. Sign-in is not set up: add your Firebase API key to desktop/firebase.local.json and run install.sh.")
                                }

                                PlasmaComponents3.TextField {
                                    id: emailField
                                    Layout.fillWidth: true
                                    visible: ctl.configured
                                    enabled: !ctl.authBusy
                                    placeholderText: i18n("Email")
                                    inputMethodHints: Qt.ImhEmailCharactersOnly
                                    onAccepted: passwordField.forceActiveFocus()
                                }
                                PlasmaComponents3.TextField {
                                    id: passwordField
                                    Layout.fillWidth: true
                                    visible: ctl.configured
                                    enabled: !ctl.authBusy
                                    placeholderText: i18n("Password")
                                    echoMode: TextInput.Password
                                    onAccepted: signInButton.clicked()
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authInfo !== ""
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.positiveTextColor
                                    text: ctl.authInfo
                                }
                                PlasmaComponents3.Label {
                                    Layout.fillWidth: true
                                    visible: ctl.authError !== ""
                                    wrapMode: Text.Wrap
                                    color: Kirigami.Theme.negativeTextColor
                                    text: ctl.authError
                                }
                                RowLayout {
                                    visible: ctl.configured
                                    spacing: Kirigami.Units.smallSpacing

                                    PlasmaComponents3.Button {
                                        id: signInButton
                                        text: i18n("Sign in")
                                        icon.name: "user-identity"
                                        enabled: !ctl.authBusy && emailField.text.trim() !== "" && passwordField.text !== ""
                                        onClicked: {
                                            if (enabled)
                                                ctl.authenticate(false, emailField.text.trim(), passwordField.text)
                                        }
                                    }
                                    PlasmaComponents3.Button {
                                        text: i18n("Create account")
                                        enabled: signInButton.enabled
                                        onClicked: ctl.authenticate(true, emailField.text.trim(), passwordField.text)
                                    }
                                    PlasmaComponents3.Button {
                                        flat: true
                                        text: i18n("Forgot password?")
                                        enabled: !ctl.authBusy
                                        onClicked: ctl.resetPassword(emailField.text.trim())
                                    }
                                    PlasmaComponents3.BusyIndicator {
                                        running: ctl.authBusy
                                        visible: ctl.authBusy
                                        Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
                                        Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
                                    }
                                }
                            }
                            Item { Layout.fillHeight: true }

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
                    color: ctl.userEmail === "" ? Kirigami.Theme.disabledTextColor
                        : ctl.syncError !== "" ? Kirigami.Theme.negativeTextColor
                        : ctl.keyPhase === "save" || ctl.keyPhase === "enter" ? Kirigami.Theme.neutralTextColor
                        : Kirigami.Theme.positiveTextColor
                }
                PlasmaComponents3.Label {
                    Layout.fillWidth: true
                    elide: Text.ElideRight
                    font.pointSize: Kirigami.Theme.smallFont.pointSize
                    opacity: 0.8
                    text: ctl.userEmail !== "" ? (
                            ctl.syncError !== "" ? i18n("Sync failed: %1", ctl.syncError)
                            : ctl.keyPhase === "save" ? i18n("Confirm your encryption key in Settings to start syncing")
                            : ctl.keyPhase === "enter" ? i18n("Enter your encryption key in Settings to sync")
                            : ctl.syncing ? i18n("Syncing…")
                            : i18n("Signed in as %1", ctl.userEmail))
                        : ctl.pending !== null ? i18n("Waiting for email verification · local only")
                        : i18n("Not signed in · local only")
                    PlasmaComponents3.ToolTip {
                        visible: footerHover.hovered && ctl.syncError !== ""
                        text: ctl.syncError
                    }
                    HoverHandler { id: footerHover }
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
                ctl.addNote(title, descField.text.trim(), due)
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

                            Image {
                                source: Qt.resolvedUrl("icons/calendar.png")
                                sourceSize: Qt.size(Kirigami.Units.iconSizes.smallMedium * 2, Kirigami.Units.iconSizes.smallMedium * 2)
                                smooth: true
                                mipmap: true
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
                                    addOverlay.due = ctl.startOfToday()
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
