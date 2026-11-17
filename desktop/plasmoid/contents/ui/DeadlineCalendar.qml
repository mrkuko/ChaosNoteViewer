import QtQuick
import QtQuick.Layouts
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami

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

    spacing: Kirigami.Units.smallSpacing

    RowLayout {
        Layout.fillWidth: true

        TextButton {
            quiet: true
            leftPadding: Kirigami.Units.largeSpacing
            rightPadding: Kirigami.Units.largeSpacing
            text: "‹"
            onClicked: cal.step(-1)
        }
        Kirigami.Heading {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            level: 5
            text: Qt.locale().standaloneMonthName(cal.viewMonth, Locale.LongFormat) + " " + cal.viewYear
        }
        TextButton {
            quiet: true
            leftPadding: Kirigami.Units.largeSpacing
            rightPadding: Kirigami.Units.largeSpacing
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
            delegate: PlasmaComponents3.Label {
                required property int index
                Layout.fillWidth: true
                Layout.preferredWidth: 1   // equal columns
                horizontalAlignment: Text.AlignHCenter
                opacity: 0.6
                font.pointSize: Kirigami.Theme.smallFont.pointSize
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
                Layout.preferredHeight: Kirigami.Units.gridUnit * 1.9
                radius: Kirigami.Units.cornerRadius
                color: selected ? Kirigami.Theme.highlightColor
                     : hover.hovered ? Qt.alpha(Kirigami.Theme.highlightColor, 0.2)
                     : "transparent"
                border.color: today && !selected ? Kirigami.Theme.highlightColor : "transparent"

                HoverHandler { id: hover }

                PlasmaComponents3.Label {
                    anchors.centerIn: parent
                    text: cell.modelData.getDate()
                    color: cell.selected ? Kirigami.Theme.highlightedTextColor : Kirigami.Theme.textColor
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
