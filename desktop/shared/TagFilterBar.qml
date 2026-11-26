import QtQuick

// The tag filter: every known tag as a chip, a click chooses or drops it. Several can be chosen. At the right
// end a switch inverts the filter: off, a note needs all the chosen tags, on, notes with any of them are
// hidden. Used by the tray app and the Plasma widget under the header.
Item {
    id: bar

    // every tag the notes carry (ctl.knownTags), the chosen ones (ctl.tagFilter) and the switch (ctl.tagInvert)
    property var known: []
    property var selected: []
    property bool invert: false
    property color accent: "#8aadf4"
    property color textColor: "white"
    property real pointSize: 9

    signal toggled(string tag)
    signal cleared()
    signal invertToggled(bool on)

    implicitHeight: Math.max(flow.implicitHeight, invertSwitch.height)

    function isSelected(t) {
        const low = t.toLowerCase()
        return selected.some(s => s.toLowerCase() === low)
    }

    Flow {
        id: flow
        width: bar.width - invertSwitch.width - 12
        spacing: 6

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

    // the invert switch: the knob is left when off and right when on
    Row {
        id: invertSwitch
        anchors.right: parent.right
        anchors.top: parent.top
        spacing: 6
        height: track.height
        opacity: bar.selected.length > 0 ? 1 : 0.5

        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: qsTr("Invert")
            color: bar.textColor
            font.pointSize: bar.pointSize
        }
        Rectangle {
            id: track
            anchors.verticalCenter: parent.verticalCenter
            width: 34
            height: 18
            radius: height / 2
            color: Qt.alpha(bar.accent, bar.invert ? 0.55 : 0.16)
            border.color: Qt.alpha(bar.accent, bar.invert ? 1.0 : 0.5)
            border.width: 1
            Behavior on color { ColorAnimation { duration: 120 } }

            Rectangle {
                width: 12
                height: 12
                radius: 6
                y: 3
                x: bar.invert ? parent.width - width - 3 : 3
                color: bar.textColor
                Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutQuad } }
            }
        }

        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: bar.invertToggled(!bar.invert) }
        Accessible.role: Accessible.CheckBox
        Accessible.name: qsTr("Invert")
        Accessible.checked: bar.invert
    }
}
