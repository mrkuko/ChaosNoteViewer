import QtQuick
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami

// Icon-only tool button with one of our own hand-drawn icons (icons/<name>.png, copied from shared/icons/ by
// install.sh), drawn exactly as they are in the file (lavender chalk), whatever the system icon theme.
//
// The selected button gets a hand-drawn marker swipe behind its icon instead of the standard highlighted
// background: one of ten hand-painted brush variants (icons/scratch-1.png ... scratch-10.png, alpha masks
// cut from the reference sheet, slanted from the top left to the bottom right), recolored slate blue-gray
// and picked at random each time the pointer arrives and kept when the button is clicked. Every variant is drawn with a margin inside its square, so it is never cut off, and the
// square fits inside the button, so the layout and the icon position are the same selected or not.
PlasmaComponents3.ToolButton {
    id: control

    property string iconName
    property string tip
    // the icon itself; the navbar uses the default, small buttons (e.g. on a note card) pass a smaller one
    property int iconSize: Kirigami.Units.iconSizes.medium
    // wide buttons give the scratch room to spread sideways; small buttons are square
    property bool wide: true
    // optional text right of the icon; the button then grows to fit it
    property string label
    // off while something this button opened (a menu) is showing, so the tooltip doesn't cover it
    property bool tipEnabled: true

    // how many icons/scratch-N.png there are
    readonly property int scratchVariants: 10
    property int scratchVariant: Math.floor(Math.random() * scratchVariants)

    // The brush is chosen when the pointer arrives, so the one shown on hover is the one that stays when
    // the button is clicked. It never changes while the button is selected or on the way out, and it is
    // never the same one twice in a row.
    onHoveredChanged: {
        if (!hovered || checked)
            return
        let next = Math.floor(Math.random() * (scratchVariants - 1))
        if (next >= scratchVariant)
            ++next
        scratchVariant = next
    }

    text: tip
    display: PlasmaComponents3.AbstractButton.IconOnly
    Accessible.name: tip
    implicitHeight: iconSize + Kirigami.Units.largeSpacing
    implicitWidth: label !== "" ? contentRow.implicitWidth + Kirigami.Units.largeSpacing * 2
                                 : wide ? implicitHeight * 1.5 : implicitHeight

    // no standard background at all: its highlight color is what made the selected icon disappear
    background: Item {
        // a plain Image, not Kirigami.Icon: the icon keeps its square shape, this one spreads over the
        // whole (wider) button. The PNGs are already slate blue-gray (#484e64, not the theme accent) with the
        // brush as their alpha channel, so no color effect is needed. On the bar's background (#24273a) a single
        // pass shows as about #41465c, the overlaps a little lighter.
        Image {
            id: scratch
            anchors.centerIn: parent
            width: parent.width
            height: parent.height * 1.25
            source: Qt.resolvedUrl("icons/scratch-" + (control.scratchVariant + 1) + ".png")
            fillMode: Image.Stretch
            smooth: true
            opacity: control.checked ? 1.0 : control.hovered ? 0.45 : 0
            Behavior on opacity { NumberAnimation { duration: Kirigami.Units.shortDuration } }
        }
    }

    contentItem: Item {
        Row {
            id: contentRow
            anchors.centerIn: parent
            spacing: Kirigami.Units.smallSpacing
            // a plain Image, not Kirigami.Icon: inside Plasma, Kirigami.Icon shows the system theme's icon of the
            // same name ("compass", "check", "gear"...) instead of our file
            Image {
                anchors.verticalCenter: parent.verticalCenter
                width: control.iconSize
                height: width
                source: Qt.resolvedUrl("icons/" + control.iconName + ".png")
                sourceSize: Qt.size(control.iconSize * 2, control.iconSize * 2)
                smooth: true
                mipmap: true
            }
            PlasmaComponents3.Label {
                anchors.verticalCenter: parent.verticalCenter
                visible: control.label !== ""
                text: control.label
                font.bold: control.checked
            }
        }
    }

    PlasmaComponents3.ToolTip {
        text: control.tip
        visible: control.hovered && control.tipEnabled
    }
}
