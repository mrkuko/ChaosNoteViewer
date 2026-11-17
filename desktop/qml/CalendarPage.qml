import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// Deadlines in a calendar: a month or a week with a mark on every day that has a deadline, and below it
// the notes due on the chosen day. A click on a note opens it, the pencil edits it.
ColumnLayout {
    id: page

    required property var app
    readonly property var ctl: app.ctl

    property bool weekMode: false
    // the chosen day and a day inside the shown month or week, both local midnight in ms
    // set to today in Component.onCompleted: the controller isn't reachable yet while the properties are
    // first evaluated, which left the date at 1 January 1970
    property double selectedDay: dayStart(new Date())
    property double anchorDay: selectedDay

    spacing: 0

    function dayStart(d) {
        return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
    }

    // day (local midnight) -> { open, done } deadline counts over all notes, follows every change
    readonly property var dueByDay: {
        ctl.revision
        const map = {}
        for (let i = 0; i < ctl.notes.count; ++i) {
            const n = ctl.notes.get(i)
            if (n.dueDate <= 0)
                continue
            const key = dayStart(new Date(n.dueDate))
            const entry = map[key] || (map[key] = { open: 0, done: 0 })
            if (n.completed)
                ++entry.done
            else
                ++entry.open
        }
        return map
    }

    // the shown days: one week, or six weeks around the month, starting on the locale's first day of the week
    readonly property var days: {
        const a = new Date(anchorDay)
        const firstDay = Qt.locale().firstDayOfWeek
        const out = []
        if (weekMode) {
            const offset = (a.getDay() - firstDay + 7) % 7
            for (let i = 0; i < 7; ++i)
                out.push(new Date(a.getFullYear(), a.getMonth(), a.getDate() - offset + i))
        } else {
            const first = new Date(a.getFullYear(), a.getMonth(), 1)
            const offset = (first.getDay() - firstDay + 7) % 7
            for (let i = 0; i < 42; ++i)
                out.push(new Date(a.getFullYear(), a.getMonth(), 1 - offset + i))
        }
        return out
    }

    // notes due on the chosen day: open ones first, each group in your own order
    readonly property var dayNotes: {
        ctl.revision
        const out = []
        for (let i = 0; i < ctl.notes.count; ++i) {
            const n = ctl.notes.get(i)
            if (n.dueDate > 0 && dayStart(new Date(n.dueDate)) === selectedDay)
                out.push({ noteId: n.noteId, title: n.title, description: n.description,
                           completed: n.completed, dueDate: n.dueDate, position: n.position })
        }
        out.sort((a, b) => (a.completed === b.completed ? a.position - b.position : a.completed ? 1 : -1))
        return out
    }

    readonly property string periodText: {
        const locale = Qt.locale()
        if (!weekMode) {
            const a = new Date(anchorDay)
            return locale.standaloneMonthName(a.getMonth(), Locale.LongFormat) + " " + a.getFullYear()
        }
        const first = days[0], last = days[6]
        return first.toLocaleDateString(locale, "d MMM") + " – " + last.toLocaleDateString(locale, "d MMM yyyy")
    }

    function step(delta) {
        const a = new Date(anchorDay)
        anchorDay = weekMode ? dayStart(new Date(a.getFullYear(), a.getMonth(), a.getDate() + 7 * delta))
                             : dayStart(new Date(a.getFullYear(), a.getMonth() + delta, 1))
    }

    function select(day) {
        selectedDay = dayStart(day)
        // a day of the next or previous month: show that month
        if (!weekMode && day.getMonth() !== new Date(anchorDay).getMonth())
            anchorDay = selectedDay
    }

    function goToday() {
        selectedDay = dayStart(new Date())
        anchorDay = selectedDay
    }

    Component.onCompleted: goToday()

    onWeekModeChanged: anchorDay = selectedDay

    // ---- Month / week, today, and the period with its arrows ----
    RowLayout {
        Layout.fillWidth: true
        Layout.leftMargin: Theme.small
        Layout.rightMargin: Theme.small
        Layout.topMargin: Theme.small
        spacing: Theme.small

        Repeater {
            model: [{ week: false, text: qsTr("Month") }, { week: true, text: qsTr("Week") }]
            delegate: NavButton {
                required property var modelData
                iconName: modelData.week ? "list" : "grid"
                iconSize: Theme.iconMedium
                label: modelData.text
                tip: modelData.week ? qsTr("Show a week") : qsTr("Show a month")
                checked: page.weekMode === modelData.week
                onClicked: page.weekMode = modelData.week
            }
        }

        Item { Layout.fillWidth: true }

        TextButton {
            text: qsTr("Today")
            onClicked: page.goToday()
        }
    }

    RowLayout {
        Layout.fillWidth: true
        Layout.leftMargin: Theme.small
        Layout.rightMargin: Theme.small

        TextButton {
            quiet: true
            leftPadding: Theme.medium
            rightPadding: Theme.medium
            text: "‹"
            onClicked: page.step(-1)
        }
        Label {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            font.bold: true
            text: page.periodText
        }
        TextButton {
            quiet: true
            leftPadding: Theme.medium
            rightPadding: Theme.medium
            text: "›"
            onClicked: page.step(1)
        }
    }

    // ---- The days ----
    GridLayout {
        Layout.fillWidth: true
        Layout.leftMargin: Theme.large
        Layout.rightMargin: Theme.large
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
            model: page.days
            delegate: Rectangle {
                id: cell
                required property var modelData
                readonly property double day: page.dayStart(modelData)
                readonly property var due: page.dueByDay[day] || { open: 0, done: 0 }
                readonly property bool inMonth: page.weekMode || modelData.getMonth() === new Date(page.anchorDay).getMonth()
                readonly property bool selected: day === page.selectedDay
                readonly property bool today: day === page.dayStart(new Date())
                readonly property bool overdue: due.open > 0 && day < page.dayStart(new Date())

                Layout.fillWidth: true
                Layout.preferredWidth: 1
                Layout.preferredHeight: page.weekMode ? 52 : 30
                radius: Theme.radius
                color: selected ? Qt.alpha(Theme.highlight, 0.35)
                     : cellHover.hovered ? Qt.alpha(Theme.highlight, 0.15)
                     : "transparent"
                border.color: today ? Theme.highlight : "transparent"

                HoverHandler { id: cellHover }

                Column {
                    anchors.centerIn: parent
                    spacing: 2

                    Label {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: cell.modelData.getDate()
                        font.bold: cell.today || cell.due.open > 0
                        opacity: cell.inMonth ? 1 : 0.35
                    }
                    // one dot per deadline (at most three): red when overdue, faint when completed
                    Row {
                        anchors.horizontalCenter: parent.horizontalCenter
                        spacing: 2
                        height: 5
                        opacity: cell.inMonth ? 1 : 0.35
                        Repeater {
                            model: Math.min(3, cell.due.open + cell.due.done)
                            delegate: Rectangle {
                                required property int index
                                width: 5
                                height: 5
                                radius: 2.5
                                color: index >= cell.due.open ? Qt.alpha(Theme.text, 0.35)
                                     : cell.overdue ? Theme.negative : Theme.accent
                            }
                        }
                    }
                    Label {
                        anchors.horizontalCenter: parent.horizontalCenter
                        visible: page.weekMode && cell.due.open + cell.due.done > 0
                        font.pointSize: Theme.smallFont
                        opacity: 0.7
                        text: qsTr("%n due", "", cell.due.open + cell.due.done)
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: page.select(cell.modelData)
                }
            }
        }
    }

    Rectangle {
        Layout.fillWidth: true
        Layout.topMargin: Theme.small
        implicitHeight: 1
        color: Theme.border
    }

    // ---- The notes due on the chosen day ----
    Label {
        Layout.fillWidth: true
        Layout.leftMargin: Theme.large
        Layout.rightMargin: Theme.large
        Layout.topMargin: Theme.small
        elide: Text.ElideRight
        font.bold: true
        text: new Date(page.selectedDay).toLocaleDateString(Qt.locale(), Locale.LongFormat)
    }

    ListView {
        id: dayList
        Layout.fillWidth: true
        Layout.fillHeight: true
        clip: true
        spacing: Theme.small
        topMargin: Theme.small
        bottomMargin: Theme.small
        leftMargin: Theme.small
        rightMargin: Theme.small
        model: page.dayNotes
        ScrollBar.vertical: ScrollBar {}

        Label {
            anchors.centerIn: parent
            width: parent.width - Theme.large * 2
            visible: dayList.count === 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            opacity: 0.6
            text: qsTr("No deadlines on this day.\nPress + to add a note due on it.")
        }

        delegate: Rectangle {
            id: card
            required property var modelData
            width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
            height: row.implicitHeight + Theme.medium * 2
            radius: Theme.radius
            color: cardHover.hovered ? Qt.alpha(Theme.highlight, 0.18) : Qt.alpha(Theme.text, 0.06)
            opacity: modelData.completed ? 0.55 : 1.0
            Behavior on color { ColorAnimation { duration: Theme.shortDuration } }
            HoverHandler { id: cardHover }

            // a click on the card (not on a button or link, which sit above it) opens the note
            MouseArea {
                anchors.fill: parent
                onClicked: page.app.detailId = card.modelData.noteId
            }

            RowLayout {
                id: row
                anchors.fill: parent
                anchors.margins: Theme.medium
                anchors.leftMargin: Theme.large
                spacing: Theme.small

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Label {
                        Layout.fillWidth: true
                        text: Theme.linkify(card.modelData.title)
                        textFormat: Text.StyledText
                        linkColor: Theme.link
                        onLinkActivated: link => Qt.openUrlExternally(link)
                        HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                        font.bold: true
                        font.strikeout: card.modelData.completed
                        elide: Text.ElideRight
                    }
                    Label {
                        Layout.fillWidth: true
                        visible: text !== ""
                        text: Theme.linkify(card.modelData.description)
                        textFormat: Text.StyledText
                        linkColor: Theme.link
                        onLinkActivated: link => Qt.openUrlExternally(link)
                        HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                        maximumLineCount: 1
                        elide: Text.ElideRight
                        opacity: 0.7
                        font.pointSize: Theme.smallFont
                    }
                }

                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "pencil"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: qsTr("Edit")
                    onClicked: page.app.editNote = { noteId: card.modelData.noteId, title: card.modelData.title,
                                                     description: card.modelData.description,
                                                     dueDate: card.modelData.dueDate }
                }
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "check-double"
                    iconSize: Theme.iconMedium
                    wide: false
                    tip: card.modelData.completed ? qsTr("Mark as not completed") : qsTr("Mark as completed")
                    onClicked: ctl.setCompleted(card.modelData.noteId, !card.modelData.completed)
                }
            }
        }
    }
}
