import QtQuick

// One tag as a small rounded label. Used by TagFlow (notes list, detail) and TagEditor (new/edit note).
// The look comes from the one using it (accent, textColor, pointSize), so it fits the tray app and the widget.
Rectangle {
    id: chip

    property string text
    property color accent: "#8aadf4"
    property color textColor: accent
    property real pointSize: 9
    // an × at the right end, shown while the pointer is on the chip (removeClicked)
    property bool removable: false
    // the whole chip is a button (clicked)
    property bool clickable: false

    signal clicked()
    signal removeClicked()

    implicitHeight: label.implicitHeight + 4
    implicitWidth: label.implicitWidth + 14 + (removable ? cross.implicitWidth + 4 : 0)
    radius: height / 2
    color: Qt.alpha(accent, clickable && hover.hovered ? 0.32 : 0.16)
    border.color: Qt.alpha(accent, 0.5)
    border.width: 1

    HoverHandler {
        id: hover
        cursorShape: chip.clickable ? Qt.PointingHandCursor : Qt.ArrowCursor
    }
    TapHandler {
        enabled: chip.clickable
        onTapped: chip.clicked()
    }

    Text {
        id: label
        x: 7
        anchors.verticalCenter: parent.verticalCenter
        text: chip.text
        textFormat: Text.PlainText
        color: chip.textColor
        font.pointSize: chip.pointSize
    }
    Text {
        id: cross
        visible: chip.removable
        opacity: hover.hovered ? 1 : 0
        anchors.right: parent.right
        anchors.rightMargin: 6
        anchors.verticalCenter: parent.verticalCenter
        text: "×"
        color: chip.textColor
        font.pointSize: chip.pointSize + 1
        font.bold: true

        HoverHandler { cursorShape: Qt.PointingHandCursor }
        TapHandler {
            enabled: hover.hovered
            onTapped: chip.removeClicked()
        }
    }
}
