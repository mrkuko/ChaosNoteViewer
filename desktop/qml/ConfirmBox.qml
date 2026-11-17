import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// "Are you sure?" for deleting one note (from its detail page) or all completed notes.
Rectangle {
    id: page

    required property var app
    readonly property var ctl: app.ctl

    visible: app.confirmDeleteCount > 0 || app.confirmNoteId !== ""
    color: Qt.alpha(Theme.bg, 0.85)

    function cancel() {
        app.confirmDeleteCount = 0
        app.confirmNoteId = ""
    }

    // swallow clicks so nothing underneath reacts; a click outside the box cancels
    MouseArea {
        anchors.fill: parent
        onClicked: page.cancel()
    }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.large * 4, 340)
        height: box.implicitHeight + Theme.large * 2
        radius: Theme.radius
        color: Theme.bg
        border.color: Qt.alpha(Theme.text, 0.25)
        MouseArea { anchors.fill: parent }

        ColumnLayout {
            id: box
            anchors.fill: parent
            anchors.margins: Theme.large
            spacing: Theme.large

            Label {
                Layout.fillWidth: true
                font.pointSize: 12
                font.bold: true
                text: qsTr("Are you sure?")
            }
            Label {
                Layout.fillWidth: true
                wrapMode: Text.WordWrap
                text: app.confirmNoteId !== ""
                    ? qsTr("This note will be deleted. This cannot be undone.")
                    : app.confirmDeleteCount === 1
                        ? qsTr("Total of 1 completed note will be deleted. This cannot be undone.")
                        : qsTr("Total of %1 completed notes will be deleted. This cannot be undone.").arg(app.confirmDeleteCount)
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: Theme.small
                Item { Layout.fillWidth: true }
                TextButton {
                    text: qsTr("Cancel")
                    onClicked: page.cancel()
                }
                TextButton {
                    destructive: true
                    primary: true
                    text: app.confirmNoteId !== "" ? qsTr("Delete") : qsTr("Delete all")
                    onClicked: {
                        if (app.confirmNoteId !== "") {
                            ctl.removeNote(app.confirmNoteId)
                            app.detailId = ""
                        } else {
                            ctl.removeCompleted()
                        }
                        page.cancel()
                    }
                }
            }
        }
    }
}
