import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Note details: a full-window page, opened by clicking a note.
Rectangle {
    id: page

    required property var app
    readonly property var ctl: app.ctl

    visible: app.detailId !== ""
    color: Theme.bg

    // the note, read again whenever the list changes (ctl.revision); null once it is gone
    readonly property var note: {
        ctl.revision
        const i = app.detailId === "" ? -1 : ctl.indexOfNote(app.detailId)
        return i < 0 ? null : ctl.notes.get(i)
    }
    onNoteChanged: if (visible && note === null) app.detailId = ""

    MouseArea { anchors.fill: parent }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.small
            spacing: Theme.small

            TextButton {
                quiet: true
                leftPadding: Theme.medium
                rightPadding: Theme.medium
                text: "←"
                onClicked: app.detailId = ""
                ToolTip.text: qsTr("Back")
                ToolTip.visible: hovered
                ToolTip.delay: 500
            }
            Label {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.small
                font.pointSize: 14
                font.weight: Font.Black
                text: qsTr("Note")
            }
            NavButton {
                iconName: page.note && page.note.starred ? "star-filled" : "star"
                iconSize: Theme.iconMedium
                wide: false
                tip: page.note && page.note.starred ? qsTr("Remove the star") : qsTr("Mark as important (star)")
                onClicked: ctl.setStarred(app.detailId, !page.note.starred)
            }
            NavButton {
                iconName: "check-double"
                iconSize: Theme.iconMedium
                wide: false
                tip: page.note && page.note.completed ? qsTr("Mark as not completed") : qsTr("Mark as completed")
                onClicked: ctl.setCompleted(app.detailId, !page.note.completed)
            }
            NavButton {
                iconName: "pencil"
                iconSize: Theme.iconMedium
                wide: false
                tip: qsTr("Edit")
                onClicked: app.editNote = { noteId: app.detailId, title: page.note.title,
                                            description: page.note.description, dueDate: page.note.dueDate }
            }
            NavButton {
                iconName: "trash-red"
                iconSize: Theme.iconMedium
                wide: false
                tip: qsTr("Delete")
                onClicked: app.confirmNoteId = app.detailId
            }
        }

        Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

        ScrollView {
            id: scroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: availableWidth

            ColumnLayout {
                width: scroll.availableWidth
                spacing: Theme.large

                SelectableText {
                    Layout.fillWidth: true
                    Layout.margins: Theme.large
                    Layout.bottomMargin: 0
                    font.pointSize: 13
                    font.bold: true
                    font.strikeout: page.note ? page.note.completed : false
                    content: page.note ? Theme.linkify(page.note.title) : ""
                }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    spacing: Theme.small
                    visible: page.note !== null && page.note.dueDate > 0
                    readonly property bool overdue: page.note !== null && !page.note.completed
                                                    && page.note.dueDate < ctl.startOfToday()

                    Image {
                        source: Qt.resolvedUrl("../icons/" + (parent.overdue ? "calendar-alert" : "calendar") + ".png")
                        sourceSize: Qt.size(Theme.iconMedium * 2, Theme.iconMedium * 2)
                        smooth: true
                        mipmap: true
                        Layout.preferredWidth: Theme.iconMedium
                        Layout.preferredHeight: Theme.iconMedium
                    }
                    SelectableText {
                        Layout.fillWidth: true
                        color: parent.overdue ? Theme.negative : Theme.text
                        content: page.note && page.note.dueDate > 0
                            ? qsTr("Deadline: %1").arg(new Date(page.note.dueDate).toLocaleDateString(Qt.locale(), Locale.LongFormat))
                            : ""
                    }
                }
                SelectableText {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    font.pointSize: Theme.smallFont
                    opacity: 0.7
                    content: page.note
                        ? (page.note.completed ? qsTr("Completed") + " · " : "")
                          + qsTr("Created %1").arg(new Date(page.note.createdAt).toLocaleDateString(Qt.locale(), Locale.LongFormat))
                        : ""
                }
                // all the tags of the note
                TagFlow {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    visible: page.note !== null && page.note.tags !== "[]"
                    tags: page.note ? JSON.parse(page.note.tags) : []
                    accent: Theme.accent
                    pointSize: Theme.smallFont
                }

                Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

                // the whole description: text with links and Markdown images, line breaks kept
                NoteBody {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    Layout.bottomMargin: Theme.large
                    visible: !empty
                    source: page.note ? page.note.description : ""
                    linkify: Theme.linkify
                    lineColor: Qt.alpha(Theme.text, 0.4)
                }
                // room for more content in the future
            }
        }
    }
}
