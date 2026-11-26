import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The list of notes: cards with hover buttons, drag to reorder (when no forced sort is on).
ListView {
    id: list

    required property var app
    readonly property var ctl: app.ctl

    clip: true
    spacing: Theme.small
    topMargin: Theme.small
    bottomMargin: Theme.small
    leftMargin: Theme.small
    rightMargin: Theme.small
    model: ctl.shown
    ScrollBar.vertical: ScrollBar {}

    // In upcoming-then-overdue order (important view) the overdue notes get a line with "Overdue" above
    // them: below it is what should have been done already. The upcoming notes have no header.
    section.property: "group"
    section.delegate: Item {
        required property string section
        width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
        height: section === "overdue" ? overdueLabel.implicitHeight + Theme.large : 0
        visible: section === "overdue"

        RowLayout {
            anchors.fill: parent
            anchors.topMargin: Theme.small
            spacing: Theme.medium
            Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Qt.alpha(Theme.negative, 0.6) }
            Image {
                source: Qt.resolvedUrl("../icons/calendar-alert.png")
                sourceSize: Qt.size(Theme.iconSmall * 2, Theme.iconSmall * 2)
                smooth: true
                mipmap: true
                Layout.preferredWidth: Theme.iconSmall
                Layout.preferredHeight: Theme.iconSmall
            }
            Label {
                id: overdueLabel
                text: qsTr("Overdue")
                color: Theme.negative
                font.bold: true
                font.pointSize: Theme.smallFont
            }
            Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Qt.alpha(Theme.negative, 0.6) }
        }
    }

    // Drag to reorder. While a card is dragged the other cards only slide out of its way
    // (slot.shift); the models change once, when it is dropped.
    property int dragFrom: -1       // row being dragged, -1 = none
    property int dragTo: -1         // row it would land on if dropped now
    property real dragHeight: 0     // space the dragged card takes, incl. spacing
    property bool settling: false   // true while a drop is applied: cards jump instead of sliding
    cacheBuffer: 100000             // keep every card alive so the drop row can be measured

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

    Label {
        anchors.centerIn: parent
        width: parent.width - Theme.large * 2
        visible: list.count === 0
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        opacity: 0.6
        text: ctl.notes.count === 0 ? qsTr("No notes yet.\nPress + to add one.")
            : ctl.filter === 3 ? qsTr("Nothing urgent.\nOpen notes that are overdue or due in the next %1 days show up here.").arg(ctl.importantDays)
            : qsTr("Nothing here")
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
            NumberAnimation { duration: Theme.shortDuration }
        }

        Rectangle {
            id: card
            width: slot.width
            height: row.implicitHeight + Theme.large * 2
            transform: Translate { y: slot.shift }
            radius: Theme.radius
            color: hover.hovered ? Qt.alpha(Theme.highlight, 0.18) : Qt.alpha(Theme.text, 0.06)
            opacity: model.completed ? 0.55 : 1.0

            Behavior on color { ColorAnimation { duration: Theme.shortDuration } }
            HoverHandler { id: hover }

            // a click on the card (not on a button or link, which sit above it) opens the note
            MouseArea {
                anchors.fill: parent
                onClicked: list.app.detailId = model.noteId
            }

            RowLayout {
                id: row
                anchors.fill: parent
                anchors.margins: Theme.large
                spacing: Theme.large

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.small

                        // a star in front of the title of a starred note
                        Image {
                            visible: model.starred
                            source: Qt.resolvedUrl("../icons/star-filled.png")
                            sourceSize: Qt.size(Theme.iconSmall * 3, Theme.iconSmall * 3)
                            smooth: true
                            mipmap: true
                            Layout.preferredWidth: Theme.iconSmall * 1.1
                            Layout.preferredHeight: Theme.iconSmall * 1.1
                        }
                        Label {
                            Layout.fillWidth: true
                            text: Theme.linkify(model.title)
                            textFormat: Text.StyledText
                            linkColor: Theme.link
                            onLinkActivated: link => Qt.openUrlExternally(link)
                            HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                            font.bold: true
                            font.strikeout: model.completed
                            elide: Text.ElideRight
                        }
                    }
                    // the description as Markdown, cut after a few lines (the whole text is in the detail)
                    NoteBody {
                        Layout.fillWidth: true
                        visible: !empty
                        preview: true
                        maxLines: 4
                        source: model.description
                        linkify: Theme.linkify
                        textColor: Theme.text
                        linkColor: Theme.link
                        lineColor: Qt.alpha(Theme.text, 0.4)
                        pointSize: Theme.smallFont
                        opacity: 0.7
                    }
                    // the first tags that fit on one line
                    TagFlow {
                        Layout.fillWidth: true
                        Layout.topMargin: 2
                        visible: model.tags !== "[]"
                        oneLine: true
                        tags: JSON.parse(model.tags)
                        accent: Theme.accent
                        pointSize: Theme.smallFont - 1
                    }
                    RowLayout {
                        visible: model.dueDate > 0
                        spacing: Theme.small

                        // overdue = deadline day is before today and the note is still open
                        readonly property bool overdue: !model.completed && model.dueDate < ctl.startOfToday()

                        Image {
                            source: Qt.resolvedUrl("../icons/" + (parent.overdue ? "calendar-alert" : "calendar") + ".png")
                            sourceSize: Qt.size(Theme.iconSmall * 3, Theme.iconSmall * 3)
                            smooth: true
                            mipmap: true
                            Layout.preferredWidth: Theme.iconSmall * 1.25
                            Layout.preferredHeight: Theme.iconSmall * 1.25
                        }
                        Label {
                            text: new Date(model.dueDate).toLocaleDateString(Qt.locale(), Locale.ShortFormat)
                            font.pointSize: Theme.smallFont
                            color: parent.overdue ? Theme.negative : Theme.text
                            opacity: parent.overdue ? 1 : 0.7
                        }
                    }
                }

                // Always occupies its space so the card doesn't jump when it appears.
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    visible: model.missing
                    iconName: "upload"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: qsTr("Upload again")
                    opacity: hover.hovered ? 1 : 0
                    enabled: hover.hovered
                    onClicked: ctl.reuploadNote(model.noteId)
                }
                // shown only while the pointer is on the note; a starred note keeps its star in the title
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: model.starred ? "star-filled" : "star"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: model.starred ? qsTr("Remove the star") : qsTr("Mark as important (star)")
                    opacity: hover.hovered ? 1 : 0
                    enabled: hover.hovered
                    onClicked: ctl.setStarred(model.noteId, !model.starred)
                }
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "trash-red"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: qsTr("Delete")
                    opacity: hover.hovered ? 1 : 0
                    enabled: hover.hovered
                    onClicked: ctl.removeNote(model.noteId)
                }
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "pencil"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: qsTr("Edit")
                    opacity: hover.hovered ? 1 : 0
                    enabled: hover.hovered
                    onClicked: list.app.editNote = { noteId: model.noteId, title: model.title,
                                                     description: model.description, dueDate: model.dueDate }
                }
                // shown only while the pointer is on the note; reopens a completed note
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "check-double"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: model.completed ? qsTr("Mark as not completed") : qsTr("Mark as completed")
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
                width: Theme.iconSmall
                height: width
                HoverHandler { id: dotHover }
                Rectangle {
                    anchors.centerIn: parent
                    width: Theme.small * 2
                    height: width
                    radius: width / 2
                    color: Theme.negative
                }
                ToolTip.visible: dotHover.hovered
                ToolTip.text: qsTr("This note is no longer online (it was deleted on another device). Upload it again or delete it here.")
            }

            onYChanged: {
                if (drag.active)
                    list.dragTo = list.dropRow(slot, card)
            }

            DragHandler {
                id: drag
                enabled: !ctl.dragLocked
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
