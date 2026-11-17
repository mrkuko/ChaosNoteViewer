import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Month calendar for picking a deadline. Stateless about the choice: it shows `value`
// (ms since epoch at local midnight, 0 = none) and reports clicks through `picked`.
ColumnLayout {
    id: cal

    property double value: 0
    property int viewYear: new Date().getFullYear()
    property int viewMonth: new Date().getMonth()

    signal picked(double ms)   // 0 when the selected day is clicked again

    // 6 weeks starting on the locale's first day of the week
    readonly property var days: {
        const first = new Date(viewYear, viewMonth, 1)
        const offset = (first.getDay() - Qt.locale().firstDayOfWeek + 7) % 7
        const out = []
        for (let i = 0; i < 42; ++i)
            out.push(new Date(viewYear, viewMonth, 1 - offset + i))
        return out
    }

    function sameDay(a, b) {
        return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
    }

    function showMonthOf(ms) {
        const d = ms > 0 ? new Date(ms) : new Date()
        viewYear = d.getFullYear()
        viewMonth = d.getMonth()
    }

    function step(delta) {
        const d = new Date(viewYear, viewMonth + delta, 1)
        viewYear = d.getFullYear()
        viewMonth = d.getMonth()
    }

    spacing: Theme.small

    RowLayout {
        Layout.fillWidth: true

        TextButton {
            quiet: true
            leftPadding: Theme.medium
            rightPadding: Theme.medium
            text: "‹"
            onClicked: cal.step(-1)
        }
        Label {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            font.bold: true
            text: Qt.locale().standaloneMonthName(cal.viewMonth, Locale.LongFormat) + " " + cal.viewYear
        }
        TextButton {
            quiet: true
            leftPadding: Theme.medium
            rightPadding: Theme.medium
            text: "›"
            onClicked: cal.step(1)
        }
    }

    GridLayout {
        Layout.fillWidth: true
        columns: 7
        columnSpacing: 2
        rowSpacing: 2

        Repeater {
            model: 7
            delegate: Label {
                required property int index
                Layout.fillWidth: true
                Layout.preferredWidth: 1   // equal columns
                horizontalAlignment: Text.AlignHCenter
                opacity: 0.6
                font.pointSize: Theme.smallFont
                text: Qt.locale().dayName((Qt.locale().firstDayOfWeek + index) % 7, Locale.ShortFormat)
            }
        }

        Repeater {
            model: cal.days
            delegate: Rectangle {
                id: cell
                required property var modelData
                readonly property bool inMonth: modelData.getMonth() === cal.viewMonth
                readonly property bool selected: cal.value > 0 && cal.sameDay(modelData, new Date(cal.value))
                readonly property bool today: cal.sameDay(modelData, new Date())

                Layout.fillWidth: true
                Layout.preferredWidth: 1
                Layout.preferredHeight: 30
                radius: Theme.radius
                color: selected ? Theme.highlight
                     : hover.hovered ? Qt.alpha(Theme.highlight, 0.2)
                     : "transparent"
                border.color: today && !selected ? Theme.highlight : "transparent"

                HoverHandler { id: hover }

                Label {
                    anchors.centerIn: parent
                    text: cell.modelData.getDate()
                    color: cell.selected ? Theme.bgDark : Theme.text
                    opacity: cell.inMonth ? 1 : 0.35
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        const d = cell.modelData
                        cal.picked(cell.selected ? 0 : new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime())
                    }
                }
            }
        }
    }
}
