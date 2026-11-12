import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The tray app's popup. Same screens as the Plasma widget (plasmoid/contents/ui/main.qml): side navigation,
// notes list with sort, new/edit/detail pages, settings, sync state in the footer. All app logic is in the
// shared AppController, reached through TrayController.
ApplicationWindow {
    id: root
    width: 560
    height: 560
    visible: false
    title: "Chaos Note Viewer"
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint

    // Popup closes as soon as it loses focus; main.cpp reads the hide time
    // to tell "tray click that dismissed it" from "tray click to open it".
    onActiveChanged: if (!active) hide()

    // UI state of this launcher
    property bool settingsOpen: false
    property bool addOpen: false
    // the note being edited in the same page as "New note"; null = not editing
    property var editNote: null
    // number of notes the "delete all completed" confirmation is asking about; 0 = not asking
    property int confirmDeleteCount: 0
    // the note shown in the detail page ("" = none) and the note its delete confirmation asks about
    property string detailId: ""
    property string confirmNoteId: ""
    property alias ctl: tray

    TrayController {
        id: tray
        active: root.visible
    }

    function copyText(text) {
        clipboard.text = text
        clipboard.selectAll()
        clipboard.copy()
    }
    TextEdit { id: clipboard; visible: false }

    // the Basic style takes all its colors from the palette
    palette {
        window: Theme.bg
        windowText: Theme.text
        base: Theme.bgDark
        text: Theme.text
        button: Theme.border
        buttonText: Theme.text
        highlight: Theme.highlight
        highlightedText: Theme.bgDark
        mid: Theme.border
        dark: Theme.border
        light: Theme.text
        placeholderText: Qt.alpha(Theme.text, 0.5)
        toolTipBase: Theme.bgDark
        toolTipText: Theme.text
        link: Theme.link
    }

    background: Rectangle {
        color: Theme.bg
        border.color: Theme.border
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 1
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // ---- Side navigation: which notes the list shows, settings at the bottom ----
            ColumnLayout {
                Layout.fillHeight: true
                Layout.topMargin: Theme.large        // the scratch is taller than its button
                Layout.bottomMargin: Theme.small
                spacing: Theme.large

                Repeater {
                    model: [
                        { icon: "compass", text: qsTr("Open notes"), filter: 0 },
                        { icon: "exclamation", text: qsTr("Important: overdue or due in the next %1 days").arg(ctl.importantDays), filter: 3 },
                        { icon: "check", text: qsTr("Completed"), filter: 1 }
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
                    tip: qsTr("Settings")
                    checked: root.settingsOpen
                    onClicked: root.settingsOpen = true
                }
            }

            Rectangle { Layout.fillHeight: true; implicitWidth: 1; color: Theme.border }

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: 0

                // ---- Header ----
                RowLayout {
                    Layout.fillWidth: true
                    Layout.margins: Theme.small
                    spacing: Theme.small

                    Label {
                        Layout.fillWidth: true
                        Layout.leftMargin: Theme.small
                        font.pointSize: 15
                        font.weight: Font.Black
                        elide: Text.ElideRight
                        text: root.settingsOpen ? qsTr("Settings")
                            : [qsTr("Open notes"), qsTr("Completed"), qsTr("All notes"), qsTr("Important")][ctl.filter]
                    }
                    NavButton {
                        id: sortButton
                        visible: !root.settingsOpen
                        iconName: "filter"
                        tip: ctl.sorted ? qsTr("Sorted by %1").arg(ctl.sortText) : qsTr("Sort by")
                        checked: ctl.sorted
                        tipEnabled: !sortMenu.visible
                        onClicked: sortMenu.popup(sortButton, 0, sortButton.height)
                    }
                    NavButton {
                        visible: !root.settingsOpen && ctl.filter === 1 && ctl.shown.count > 0
                        iconName: "trash-red"
                        tip: qsTr("Delete all completed notes")
                        onClicked: root.confirmDeleteCount = ctl.shown.count
                    }
                    NavButton {
                        visible: !root.settingsOpen
                        iconName: "plus"
                        tip: qsTr("New note")
                        onClicked: root.addOpen = true
                    }

                    // step 1: what to sort by. The direction is step 2, the switch under the title.
                    Menu {
                        id: sortMenu
                        width: 260
                        Repeater {
                            model: [
                                { field: 0, text: qsTr("Your order (drag to reorder)"), icon: "list" },
                                { field: 1, text: qsTr("Name"), icon: "sort-az" },
                                { field: 2, text: qsTr("Deadline"), icon: "sort-deadline" },
                                { field: 3, text: qsTr("Date created"), icon: "sort-date" }
                            ]
                            delegate: MenuItem {
                                required property var modelData
                                text: modelData.text
                                icon.source: Qt.resolvedUrl("../icons/" + modelData.icon + ".png")
                                icon.color: "transparent"      // keep the chalk colors
                                checkable: true
                                checked: ctl.sortField === modelData.field
                                // the click toggles `checked` itself, put the binding back. Choosing the mode
                                // that is already on keeps its direction; a new one starts with its natural one.
                                onTriggered: {
                                    if (ctl.sortField !== modelData.field)
                                        ctl.setSortField(modelData.field)
                                    checked = Qt.binding(() => ctl.sortField === modelData.field)
                                }
                            }
                        }
                    }
                }

                // a forced order is on: say so, and offer the way back to your own order
                RowLayout {
                    visible: !root.settingsOpen && ctl.sorted
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.small
                    spacing: Theme.small
                    Label {
                        font.pointSize: Theme.smallFont
                        opacity: 0.75
                        text: qsTr("Sorted by %1:").arg(ctl.sortFieldNames[ctl.sortField])
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
                            label: ctl.sortField === 1 ? "" : qsTr(ctl.sortDirectionNames[ctl.sortField][desc ? 1 : 0])
                            iconSize: ctl.sortField === 1 ? Theme.iconNav : Theme.iconMedium
                            wide: true
                            tip: qsTr(ctl.sortDirectionNames[ctl.sortField][desc ? 1 : 0])
                            checked: ctl.sortDesc === desc
                            onClicked: ctl.sortDesc = desc
                        }
                    }
                    Label {
                        Layout.fillWidth: true
                        elide: Text.ElideRight
                        font.pointSize: Theme.smallFont
                        opacity: 0.6
                        text: qsTr("dragging is off")
                    }
                    ToolButton {
                        text: qsTr("Your order")
                        font.pointSize: Theme.smallFont
                        onClicked: ctl.setSortField(0)
                    }
                }

                Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

                StackLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    currentIndex: root.settingsOpen ? 1 : 0

                    NotesList { app: root }
                    SettingsPage { app: root }
                }
            }
        }

        // ---- Footer: account status and sync state ----
        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.small
            Layout.leftMargin: Theme.large
            spacing: Theme.small

            Rectangle {
                width: Theme.small * 2
                height: width
                radius: width / 2
                color: ctl.userEmail === "" ? Qt.alpha(Theme.text, 0.4)
                    : ctl.syncError !== "" ? Theme.negative
                    : ctl.keyPhase === "save" || ctl.keyPhase === "enter" ? Theme.neutral
                    : Theme.positive
            }
            Label {
                Layout.fillWidth: true
                elide: Text.ElideRight
                font.pointSize: Theme.smallFont
                opacity: 0.8
                text: ctl.userEmail !== "" ? (
                        ctl.syncError !== "" ? qsTr("Sync failed: %1").arg(ctl.syncError)
                        : ctl.keyPhase === "save" ? qsTr("Confirm your encryption key in Settings to start syncing")
                        : ctl.keyPhase === "enter" ? qsTr("Enter your encryption key in Settings to sync")
                        : ctl.syncing ? qsTr("Syncing…")
                        : qsTr("Signed in as %1").arg(ctl.userEmail))
                    : ctl.pending !== null ? qsTr("Waiting for email verification · local only")
                    : qsTr("Not signed in · local only")
                ToolTip.visible: footerHover.hovered && ctl.syncError !== ""
                ToolTip.text: ctl.syncError
                HoverHandler { id: footerHover }
            }

            // Sync state, also for the automatic syncs. A click syncs now and the button shows the result.
            ToolButton {
                id: syncButton
                visible: ctl.userEmail !== ""
                // "syncing" as shown: stays on for at least a second so a quick sync is still visible
                property bool busy: ctl.syncing
                Connections {
                    target: ctl
                    function onSyncingChanged() {
                        if (ctl.syncing) {
                            syncButton.busy = true
                            minSpin.restart()
                        } else if (!minSpin.running) {
                            syncButton.busy = false
                        }
                    }
                }
                Timer {
                    id: minSpin
                    interval: 1000
                    onTriggered: syncButton.busy = ctl.syncing
                }
                enabled: !busy
                // Synced blue, Syncing orange, Failed red
                readonly property color stateColor: busy ? Theme.neutral : ctl.syncError !== "" ? Theme.negative : Theme.highlight
                readonly property string stateText: busy ? qsTr("Syncing") : ctl.syncError !== "" ? qsTr("Failed") : qsTr("Synced")
                onClicked: ctl.keyPhase === "" ? ctl.startKeyFlow() : ctl.syncNow()
                Accessible.name: stateText
                background: Item {}

                contentItem: Row {
                    spacing: Theme.small
                    Image {
                        id: syncIcon
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.iconMedium
                        height: width
                        source: Qt.resolvedUrl("../icons/sync-arrows.png")
                        sourceSize: Qt.size(width * 2, width * 2)
                        smooth: true
                        mipmap: true
                        RotationAnimator on rotation {
                            from: 0
                            to: 360
                            duration: 1000
                            loops: Animation.Infinite
                            running: syncButton.busy
                        }
                        // back upright when it stops
                        Connections {
                            target: syncButton
                            function onBusyChanged() { if (!syncButton.busy) syncIcon.rotation = 0 }
                        }
                    }
                    Label {
                        anchors.verticalCenter: parent.verticalCenter
                        text: syncButton.stateText
                        color: syncButton.stateColor
                        font.pointSize: Theme.smallFont
                    }
                }
                ToolTip.visible: hovered
                ToolTip.delay: 500
                ToolTip.text: ctl.syncError !== "" ? ctl.syncError : qsTr("Sync now")
            }
        }
    }

    // full-window pages on top of everything else, the last one is the topmost
    DetailPage { anchors.fill: parent; app: root; z: 8 }
    EditPage { anchors.fill: parent; app: root; z: 10 }
    ConfirmBox { anchors.fill: parent; app: root; z: 20 }
}
