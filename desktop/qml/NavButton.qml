import QtQuick
import QtQuick.Controls

// Icon-only tool button with one of our own hand-drawn icons (../icons/<name>.png), drawn exactly as in
// the file. The selected button gets a hand-painted brush swipe behind its icon (icons/scratch-N.png, one
// of ten, picked at random when the pointer arrives and kept when the button is clicked). Same as the
// Plasma widget's NavButton.
ToolButton {
    id: control

    property string iconName
    property string tip
    property int iconSize: Theme.iconNav
    // wide buttons give the scratch room to spread sideways; small buttons are square
    property bool wide: true
    // optional text right of the icon; the button then grows to fit it
    property string label
    // off while something this button opened (a menu) is showing, so the tooltip doesn't cover it
    property bool tipEnabled: true

    readonly property int scratchVariants: 10
    property int scratchVariant: Math.floor(Math.random() * scratchVariants)

    // never the same brush twice in a row, never changes while the button is selected
    onHoveredChanged: {
        if (!hovered || checked)
            return
        let next = Math.floor(Math.random() * (scratchVariants - 1))
        if (next >= scratchVariant)
            ++next
        scratchVariant = next
    }

    text: tip
    display: AbstractButton.IconOnly
    Accessible.name: tip
    padding: 0
    implicitHeight: iconSize + Theme.medium
    implicitWidth: label !== "" ? contentRow.implicitWidth + Theme.medium * 2
                                : wide ? implicitHeight * 1.3 : implicitHeight

    background: Item {
        Image {
            anchors.centerIn: parent
            width: parent.width
            height: parent.height * 1.25
            source: Qt.resolvedUrl("../icons/scratch-" + (control.scratchVariant + 1) + ".png")
            fillMode: Image.Stretch
            smooth: true
            opacity: control.checked ? 1.0 : control.hovered ? 0.45 : 0
            Behavior on opacity { NumberAnimation { duration: Theme.shortDuration } }
        }
    }

    contentItem: Item {
        Row {
            id: contentRow
            anchors.centerIn: parent
            spacing: Theme.small
            Image {
                anchors.verticalCenter: parent.verticalCenter
                width: control.iconSize
                height: width
                source: Qt.resolvedUrl("../icons/" + control.iconName + ".png")
                sourceSize: Qt.size(control.iconSize * 2, control.iconSize * 2)
                smooth: true
                mipmap: true
            }
            Label {
                anchors.verticalCenter: parent.verticalCenter
                visible: control.label !== ""
                text: control.label
                font.bold: control.checked
            }
        }
    }

    ToolTip.text: tip
    ToolTip.delay: 500
    ToolTip.visible: hovered && tipEnabled && tip !== ""
}
