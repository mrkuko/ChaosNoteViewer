import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ItemDelegate {
    width: ListView.view.width

    contentItem: RowLayout {
        spacing: 8

        CheckBox {
            checked: completed
            enabled: false
        }

        Label {
            Layout.fillWidth: true
            text: title
            elide: Text.ElideRight
            font.strikeout: completed
            opacity: completed ? 0.5 : 1.0
        }
    }
}
