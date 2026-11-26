import QtQuick
import QtQuick.Layouts

// The tag filter: every known tag as a chip, a click chooses or drops it. Several can be chosen, a note
// then has to carry all of them. Used by the tray app and the Plasma widget under the header.
Flow {
    id: bar

    // every tag the notes carry (ctl.knownTags) and the chosen ones (ctl.tagFilter)
    property var known: []
    property var selected: []
    property color accent: "#8aadf4"
    property real pointSize: 9

    signal toggled(string tag)
    signal cleared()

    spacing: 6

    function isSelected(t) {
        const low = t.toLowerCase()
        return selected.some(s => s.toLowerCase() === low)
    }

    Repeater {
        model: bar.known
        TagChip {
            required property string modelData
            text: modelData
            accent: bar.accent
            pointSize: bar.pointSize
            clickable: true
            selected: bar.isSelected(modelData)
            onClicked: bar.toggled(modelData)
        }
    }
    // a chip like the others, so it sits on the same line and height (a button has its own padding)
    TagChip {
        visible: bar.selected.length > 0
        text: qsTr("Clear")
        accent: bar.accent
        pointSize: bar.pointSize
        clickable: true
        opacity: 0.8
        onClicked: bar.cleared()
    }
}
