import QtQuick
import QtQuick.Layouts
import org.kde.plasma.plasmoid
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami

PlasmoidItem {
    id: root

    Plasmoid.icon: "view-pim-notes"
    toolTipMainText: i18n("Chaos Note Viewer")

    compactRepresentation: MouseArea {
        id: compact
        activeFocusOnTab: true
        hoverEnabled: true
        onClicked: root.expanded = !root.expanded

        Kirigami.Icon {
            anchors.fill: parent
            source: "view-pim-notes"
            active: compact.containsMouse
        }
    }

    // Placeholder data, same as the Qt app's NoteModel until the local DB exists.
    ListModel {
        id: notes
        ListElement { title: "Buy milk"; description: "Two liters of oat milk, and check whether the corner shop still has the good bread before it closes at eight."; completed: false }
        ListElement { title: "Reply to landlord"; description: "He asked about the heating invoice and the date for the yearly inspection of the boiler."; completed: false }
        ListElement { title: "Write the crypto spec"; description: "AES-256-GCM for note content, PBKDF2 for the password wrap, recovery code wrap."; completed: false }
        ListElement { title: "Set up Firebase emulators"; description: "Auth and Firestore emulators via firebase init."; completed: true }
    }

    fullRepresentation: ColumnLayout {
        Layout.minimumWidth: Kirigami.Units.gridUnit * 18
        Layout.preferredWidth: Kirigami.Units.gridUnit * 22
        Layout.minimumHeight: Kirigami.Units.gridUnit * 16

        ListView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: Kirigami.Units.smallSpacing
            topMargin: Kirigami.Units.smallSpacing
            bottomMargin: Kirigami.Units.smallSpacing
            leftMargin: Kirigami.Units.smallSpacing
            rightMargin: Kirigami.Units.smallSpacing
            model: notes

            delegate: Rectangle {
                id: card
                width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
                height: row.implicitHeight + Kirigami.Units.largeSpacing * 2
                radius: Kirigami.Units.cornerRadius
                color: hover.hovered
                    ? Qt.alpha(Kirigami.Theme.highlightColor, 0.18)
                    : Qt.alpha(Kirigami.Theme.textColor, 0.06)
                opacity: model.completed ? 0.55 : 1.0

                Behavior on color { ColorAnimation { duration: Kirigami.Units.shortDuration } }
                HoverHandler { id: hover }

                RowLayout {
                    id: row
                    anchors.fill: parent
                    anchors.margins: Kirigami.Units.largeSpacing
                    spacing: Kirigami.Units.largeSpacing

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2

                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            text: model.title
                            font.bold: true
                            font.strikeout: model.completed
                            elide: Text.ElideRight
                        }
                        PlasmaComponents3.Label {
                            Layout.fillWidth: true
                            text: model.description
                            maximumLineCount: 1
                            elide: Text.ElideRight
                            opacity: 0.7
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                        }
                    }

                    PlasmaComponents3.CheckBox {
                        Layout.alignment: Qt.AlignVCenter
                        checked: model.completed
                        onToggled: notes.setProperty(index, "completed", checked)
                    }
                }
            }
        }
    }
}
