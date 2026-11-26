import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// New note / edit note: a full-window page on top of everything else.
Rectangle {
    id: page

    required property var app
    readonly property var ctl: app.ctl
    readonly property bool editing: app.editNote !== null

    visible: app.addOpen || app.editNote !== null
    color: Theme.bg
    property double due: 0
    // the star of the note, saved with Save/Add
    property bool starred: false

    function close() {
        app.addOpen = false
        app.editNote = null
        app.newNoteDue = 0
    }

    function submit() {
        const title = titleField.text.trim()
        if (title === "")
            return
        tagEditor.commitPending()
        if (editing)
            ctl.updateNote(app.editNote.noteId, title, descField.text.trim(), due, starred, tagEditor.tags)
        else
            ctl.addNote(title, descField.text.trim(), due, starred, tagEditor.tags)
        close()
    }

    onVisibleChanged: if (visible) {
        titleField.text = editing ? app.editNote.title : ""
        descField.text = editing ? app.editNote.description : ""
        due = editing ? app.editNote.dueDate : app.newNoteDue
        starred = editing && ctl.isStarred(app.editNote.noteId)
        tagEditor.reset(editing ? ctl.tagsOf(app.editNote.noteId) : [])
        calendar.showMonthOf(due)
        titleField.forceActiveFocus()
    }

    // swallow clicks so nothing underneath reacts
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
                onClicked: page.close()
                ToolTip.text: qsTr("Cancel")
                ToolTip.visible: hovered
                ToolTip.delay: 500
            }
            Label {
                Layout.fillWidth: true
                Layout.leftMargin: Theme.small
                font.pointSize: 13
                font.bold: true
                text: page.editing ? qsTr("Edit note") : qsTr("New note")
            }
            NavButton {
                iconName: page.starred ? "star-filled" : "star"
                iconSize: Theme.iconMedium
                wide: false
                tip: page.starred ? qsTr("Remove the star") : qsTr("Mark as important (star)")
                onClicked: page.starred = !page.starred
            }
            TextButton {
                primary: true
                text: page.editing ? qsTr("Save") : qsTr("Add")
                enabled: titleField.text.trim() !== ""
                onClicked: page.submit()
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

                TextField {
                    id: titleField
                    Layout.fillWidth: true
                    Layout.margins: Theme.large
                    Layout.bottomMargin: 0
                    placeholderText: qsTr("Title")
                    onAccepted: descField.forceActiveFocus()
                    Keys.onEscapePressed: page.close()
                }
                TextArea {
                    id: descField
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    Layout.preferredHeight: 110
                    placeholderText: qsTr("Description (optional)")
                    wrapMode: TextEdit.Wrap
                    background: Rectangle { color: Theme.bgDark; border.color: Theme.border; radius: Theme.small }
                    Keys.onEscapePressed: page.close()
                    Keys.onPressed: event => {
                        if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter)
                                && (event.modifiers & Qt.ControlModifier)) {
                            page.submit()
                            event.accepted = true
                        }
                    }
                }

                TagEditor {
                    id: tagEditor
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    noteId: page.editing ? app.editNote.noteId : ""
                    known: ctl.knownTags
                    usedElsewhere: ctl.tagUsedElsewhere
                    isValid: ctl.isValidTag
                    maxLength: ctl.tagMaxLength
                    accent: Theme.accent
                    textColor: Theme.text
                    pointSize: Theme.smallFont + 1
                }

                Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

                RowLayout {
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    spacing: Theme.small

                    Image {
                        source: Qt.resolvedUrl("../icons/calendar.png")
                        sourceSize: Qt.size(Theme.iconMedium * 2, Theme.iconMedium * 2)
                        smooth: true
                        mipmap: true
                        Layout.preferredWidth: Theme.iconMedium
                        Layout.preferredHeight: Theme.iconMedium
                    }
                    Label {
                        Layout.fillWidth: true
                        text: page.due > 0
                            ? qsTr("Deadline: %1").arg(new Date(page.due).toLocaleDateString(Qt.locale(), Locale.LongFormat))
                            : qsTr("No deadline")
                        opacity: page.due > 0 ? 1 : 0.6
                        elide: Text.ElideRight
                    }
                    TextButton {
                        text: qsTr("Today")
                        onClicked: {
                            page.due = ctl.startOfToday()
                            calendar.showMonthOf(page.due)
                        }
                    }
                    TextButton {
                        text: qsTr("Clear")
                        enabled: page.due > 0
                        onClicked: page.due = 0
                    }
                }

                DeadlineCalendar {
                    id: calendar
                    Layout.fillWidth: true
                    Layout.leftMargin: Theme.large
                    Layout.rightMargin: Theme.large
                    Layout.bottomMargin: Theme.large
                    value: page.due
                    onPicked: ms => page.due = ms
                }
            }
        }
    }
}
