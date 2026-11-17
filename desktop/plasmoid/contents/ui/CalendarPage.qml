import QtQuick
import org.kde.plasma.components as PlasmaComponents3
import org.kde.kirigami as Kirigami
import QtQuick.Layouts

// Deadlines in a calendar: a month or a week with a mark on every day that has a deadline, and below it
// the notes due on the chosen day. A click on a note opens it, the pencil edits it. Same as the tray
// app's CalendarPage.
ColumnLayout {
    id: page

    required property var app
    // the AppController (in the widget an id of main.qml, not a property of `app`). Not named `ctl`: inside
    // `CalendarPage { controller: ctl }` that name would then mean this property, not main.qml's id.
    required property var controller

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
        controller.revision
        const map = {}
        for (let i = 0; i < controller.notes.count; ++i) {
            const n = controller.notes.get(i)
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
        controller.revision
        const out = []
        for (let i = 0; i < controller.notes.count; ++i) {
            const n = controller.notes.get(i)
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
        Layout.leftMargin: Kirigami.Units.smallSpacing
        Layout.rightMargin: Kirigami.Units.smallSpacing
        Layout.topMargin: Kirigami.Units.smallSpacing
        spacing: Kirigami.Units.smallSpacing

        Repeater {
            model: [{ week: false, text: i18n("Month") }, { week: true, text: i18n("Week") }]
            delegate: NavButton {
                required property var modelData
                iconName: modelData.week ? "list" : "grid"
                iconSize: Kirigami.Units.iconSizes.smallMedium
                label: modelData.text
                tip: modelData.week ? i18n("Show a week") : i18n("Show a month")
                checked: page.weekMode === modelData.week
                onClicked: page.weekMode = modelData.week
            }
        }

        Item { Layout.fillWidth: true }

        TextButton {
            text: i18n("Today")
            onClicked: page.goToday()
        }
    }

    RowLayout {
        Layout.fillWidth: true
        Layout.leftMargin: Kirigami.Units.smallSpacing
        Layout.rightMargin: Kirigami.Units.smallSpacing

        TextButton {
            quiet: true
            leftPadding: Kirigami.Units.largeSpacing
            rightPadding: Kirigami.Units.largeSpacing
            text: "‹"
            onClicked: page.step(-1)
        }
        PlasmaComponents3.Label {
            Layout.fillWidth: true
            horizontalAlignment: Text.AlignHCenter
            font.bold: true
            text: page.periodText
        }
        TextButton {
            quiet: true
            leftPadding: Kirigami.Units.largeSpacing
            rightPadding: Kirigami.Units.largeSpacing
            text: "›"
            onClicked: page.step(1)
        }
    }

    // ---- The days ----
    GridLayout {
        Layout.fillWidth: true
        Layout.leftMargin: Kirigami.Units.largeSpacing
        Layout.rightMargin: Kirigami.Units.largeSpacing
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
                radius: Kirigami.Units.cornerRadius
                color: selected ? Qt.alpha(Kirigami.Theme.highlightColor, 0.35)
                     : cellHover.hovered ? Qt.alpha(Kirigami.Theme.highlightColor, 0.15)
                     : "transparent"
                border.color: today ? Kirigami.Theme.highlightColor : "transparent"

                HoverHandler { id: cellHover }

                Column {
                    anchors.centerIn: parent
                    spacing: 2

                    PlasmaComponents3.Label {
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
                                color: index >= cell.due.open ? Qt.alpha(Kirigami.Theme.textColor, 0.35)
                                     : cell.overdue ? Kirigami.Theme.negativeTextColor : Kirigami.Theme.highlightColor
                            }
                        }
                    }
                    PlasmaComponents3.Label {
                        anchors.horizontalCenter: parent.horizontalCenter
                        visible: page.weekMode && cell.due.open + cell.due.done > 0
                        font.pointSize: Kirigami.Theme.smallFont.pointSize
                        opacity: 0.7
                        text: i18np("%1 due", "%1 due", cell.due.open + cell.due.done)
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: page.select(cell.modelData)
                }
            }
        }
    }

    Kirigami.Separator {
        Layout.fillWidth: true
        Layout.topMargin: Kirigami.Units.smallSpacing
    }

    // ---- The notes due on the chosen day ----
    PlasmaComponents3.Label {
        Layout.fillWidth: true
        Layout.leftMargin: Kirigami.Units.largeSpacing
        Layout.rightMargin: Kirigami.Units.largeSpacing
        Layout.topMargin: Kirigami.Units.smallSpacing
        elide: Text.ElideRight
        font.bold: true
        text: new Date(page.selectedDay).toLocaleDateString(Qt.locale(), Locale.LongFormat)
    }

    ListView {
        id: dayList
        Layout.fillWidth: true
        Layout.fillHeight: true
        clip: true
        spacing: Kirigami.Units.smallSpacing
        topMargin: Kirigami.Units.smallSpacing
        bottomMargin: Kirigami.Units.smallSpacing
        leftMargin: Kirigami.Units.smallSpacing
        rightMargin: Kirigami.Units.smallSpacing
        model: page.dayNotes
        PlasmaComponents3.ScrollBar.vertical: PlasmaComponents3.ScrollBar {}

        PlasmaComponents3.Label {
            anchors.centerIn: parent
            width: parent.width - Kirigami.Units.largeSpacing * 2
            visible: dayList.count === 0
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            opacity: 0.6
            text: i18n("No deadlines on this day.\nPress + to add a note due on it.")
        }

        delegate: Rectangle {
            id: card
            required property var modelData
            width: ListView.view.width - ListView.view.leftMargin - ListView.view.rightMargin
            height: row.implicitHeight + Kirigami.Units.largeSpacing * 2
            radius: Kirigami.Units.cornerRadius
            color: cardHover.hovered ? Qt.alpha(Kirigami.Theme.highlightColor, 0.18) : Qt.alpha(Kirigami.Theme.textColor, 0.06)
            opacity: modelData.completed ? 0.55 : 1.0
            Behavior on color { ColorAnimation { duration: Kirigami.Units.shortDuration } }
            HoverHandler { id: cardHover }

            // a click on the card (not on a button or link, which sit above it) opens the note
            MouseArea {
                anchors.fill: parent
                onClicked: page.app.detailId = card.modelData.noteId
            }

            RowLayout {
                id: row
                anchors.fill: parent
                anchors.margins: Kirigami.Units.largeSpacing
                anchors.leftMargin: Kirigami.Units.largeSpacing
                spacing: Kirigami.Units.smallSpacing

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    PlasmaComponents3.Label {
                        Layout.fillWidth: true
                        text: page.app.linkify(card.modelData.title)
                        textFormat: Text.StyledText
                        linkColor: Kirigami.Theme.linkColor
                        onLinkActivated: link => Qt.openUrlExternally(link)
                        HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                        font.bold: true
                        font.strikeout: card.modelData.completed
                        elide: Text.ElideRight
                    }
                    PlasmaComponents3.Label {
                        Layout.fillWidth: true
                        visible: text !== ""
                        text: page.app.linkify(card.modelData.description)
                        textFormat: Text.StyledText
                        linkColor: Kirigami.Theme.linkColor
                        onLinkActivated: link => Qt.openUrlExternally(link)
                        HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                        maximumLineCount: 1
                        elide: Text.ElideRight
                        opacity: 0.7
                        font.pointSize: Kirigami.Theme.smallFont.pointSize
                    }
                }

                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "pencil"
                    iconSize: Kirigami.Units.iconSizes.smallMedium
                    wide: false
                    tip: i18n("Edit")
                    onClicked: page.app.editNote = { noteId: card.modelData.noteId, title: card.modelData.title,
                                                     description: card.modelData.description,
                                                     dueDate: card.modelData.dueDate }
                }
                NavButton {
                    Layout.alignment: Qt.AlignVCenter
                    iconName: "check-double"
                    iconSize: Kirigami.Units.iconSizes.smallMedium
                    wide: false
                    tip: card.modelData.completed ? i18n("Mark as not completed") : i18n("Mark as completed")
                    onClicked: controller.setCompleted(card.modelData.noteId, !card.modelData.completed)
                }
            }
        }
    }
}
