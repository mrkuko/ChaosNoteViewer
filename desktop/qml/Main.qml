import QtQuick
import QtQuick.Controls
import ChaosNotes

ApplicationWindow {
    id: root
    width: 360
    height: 520
    visible: false
    title: "Chaos Note Viewer"
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint

    // Popup closes as soon as it loses focus; main.cpp reads the hide time
    // to tell "tray click that dismissed it" from "tray click to open it".
    onActiveChanged: if (!active) hide()

    NoteModel {
        id: notes
    }

    background: Rectangle {
        color: palette.window
        border.color: palette.mid
    }

    ListView {
        anchors.fill: parent
        anchors.margins: 1
        model: notes
        clip: true
        spacing: 1
        delegate: NoteDelegate {}

        ScrollBar.vertical: ScrollBar {}
    }
}
