import QtQuick

// The tags of a note as chips. oneLine: only the chips that fit whole on one line (the notes list),
// otherwise all of them, wrapped (the note detail).
Item {
    id: flowBox

    property var tags: []
    property bool oneLine: false
    property color accent: "#8aadf4"
    property real pointSize: 9

    readonly property real chipHeight: rep.count > 0 && rep.itemAt(0) ? rep.itemAt(0).implicitHeight : 0
    implicitHeight: oneLine ? chipHeight : flow.implicitHeight
    clip: oneLine

    // a chip that does not fit goes to the next line, which the clip hides
    Flow {
        id: flow
        width: flowBox.width
        spacing: 4

        Repeater {
            id: rep
            model: flowBox.tags
            TagChip {
                required property string modelData
                text: modelData
                accent: flowBox.accent
                pointSize: flowBox.pointSize
            }
        }
    }
}
