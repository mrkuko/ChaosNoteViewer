import QtQuick
import QtQuick.Layouts
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami

// Menu entry with one of our own hand-drawn icons (icons/<name>.png), drawn as they are in the file.
PlasmaComponents3.MenuItem {
    id: item

    property string iconName

    // the active entry is marked with our own check mark on the right instead of the standard checkbox
    indicator: Item {}

    // Plasma's Menu sizes itself once, from its entries' sizes before our content is laid out, which cut the
    // labels off and squeezed the rows. A fixed row height (room for the icon) avoids that; the menu's
    // width is set where the menu is made.
    implicitHeight: Kirigami.Units.iconSizes.medium + Kirigami.Units.smallSpacing * 2 + topPadding + bottomPadding

    contentItem: RowLayout {
        spacing: Kirigami.Units.largeSpacing
        Image {
            // not Kirigami.Icon: inside Plasma it shows the theme's icon of the same name instead of our file
            // medium: several of these icons are wide (A-Z, deadline + lines) and get small in a small square
            Layout.preferredWidth: Kirigami.Units.iconSizes.medium
            Layout.preferredHeight: Kirigami.Units.iconSizes.medium
            source: Qt.resolvedUrl("icons/" + item.iconName + ".png")
            sourceSize: Qt.size(Kirigami.Units.iconSizes.medium * 2, Kirigami.Units.iconSizes.medium * 2)
            smooth: true
            mipmap: true
        }
        PlasmaComponents3.Label {
            Layout.fillWidth: true
            text: item.text
            font.bold: item.checked
            elide: Text.ElideRight
        }
        Image {
            Layout.preferredWidth: Kirigami.Units.iconSizes.smallMedium
            Layout.preferredHeight: Kirigami.Units.iconSizes.smallMedium
            Layout.leftMargin: Kirigami.Units.largeSpacing
            source: Qt.resolvedUrl("icons/check.png")
            sourceSize: Qt.size(Kirigami.Units.iconSizes.smallMedium * 2, Kirigami.Units.iconSizes.smallMedium * 2)
            smooth: true
            mipmap: true
            opacity: item.checked ? 1 : 0          // keeps its space, so the entries don't change width
        }
    }
}
