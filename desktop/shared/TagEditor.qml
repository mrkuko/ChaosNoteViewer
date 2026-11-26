import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The tags of a note being created or edited. A tag loses its place with the × that shows on hover. "+ New tag"
// opens a field: type a name and press Enter, or pick one of the known tags shown under it. Removing the last
// use of a tag (no other note has it) asks first, because the tag then disappears from the known tags.
// Nothing is saved here: the page reads `tags` when the note is saved (call commitPending() first).
ColumnLayout {
    id: editor

    // the note's tags, replaced by a new array on every change
    property var tags: []
    // every tag the notes carry (ctl.knownTags)
    property var known: []
    // the note being edited, "" for a new one
    property string noteId: ""
    // ctl.tagUsedElsewhere and ctl.isValidTag
    property var usedElsewhere: function (tag, noteId) { return true }
    property var isValid: function (tag) { return tag !== "" }
    property int maxLength: 15

    property color accent: "#8aadf4"
    property color textColor: "white"
    property real pointSize: 9

    property bool adding: false
    // the tag whose removal waits for "Remove" or "Keep"
    property string pendingRemove: ""

    readonly property var suggestions: {
        const typed = input.text.trim().toLowerCase()
        return known.filter(k => !has(k) && (typed === "" || k.toLowerCase().indexOf(typed) >= 0))
    }

    spacing: 6

    function reset(list) {
        tags = list
        adding = false
        pendingRemove = ""
        input.text = ""
    }

    function has(t) {
        const low = t.toLowerCase()
        return tags.some(x => x.toLowerCase() === low)
    }

    // adds a tag (a known one keeps its spelling); false when the name is not a valid tag
    function add(t) {
        t = t.trim()
        if (!isValid(t))
            return false
        if (!has(t)) {
            const k = known.find(x => x.toLowerCase() === t.toLowerCase())
            tags = tags.concat([k !== undefined ? k : t])
        }
        input.text = ""
        return true
    }

    function drop(t) {
        tags = tags.filter(x => x !== t)
    }

    function remove(t) {
        const isKnown = known.some(k => k.toLowerCase() === t.toLowerCase())
        if (isKnown && !usedElsewhere(t, noteId))
            pendingRemove = t
        else
            drop(t)
    }

    // a name still in the field when the note is saved is added too
    function commitPending() {
        if (input.text.trim() !== "")
            add(input.text)
    }

    Flow {
        Layout.fillWidth: true
        spacing: 6

        Repeater {
            model: editor.tags
            TagChip {
                required property string modelData
                text: modelData
                accent: editor.accent
                pointSize: editor.pointSize
                removable: true
                onRemoveClicked: editor.remove(modelData)
            }
        }
        TagChip {
            visible: !editor.adding
            text: qsTr("+ New tag")
            accent: editor.accent
            pointSize: editor.pointSize
            clickable: true
            opacity: 0.8
            onClicked: {
                editor.adding = true
                input.forceActiveFocus()
            }
        }
        TextField {
            id: input
            visible: editor.adding
            width: Math.max(140, implicitWidth)
            placeholderText: qsTr("Tag name, Enter adds")
            maximumLength: editor.maxLength
            // printable ASCII only
            validator: RegularExpressionValidator { regularExpression: /[ -~]*/ }
            font.pointSize: editor.pointSize
            onAccepted: editor.add(text)
            onActiveFocusChanged: if (!activeFocus && text.trim() === "") editor.adding = false
            Keys.onEscapePressed: event => {
                text = ""
                editor.adding = false
                event.accepted = true
            }
        }
    }

    // the known tags this note doesn't have, narrowed by what is typed
    Flow {
        Layout.fillWidth: true
        visible: editor.adding && editor.suggestions.length > 0
        spacing: 6

        Repeater {
            model: editor.adding ? editor.suggestions : []
            TagChip {
                required property string modelData
                text: modelData
                accent: editor.accent
                pointSize: editor.pointSize
                clickable: true
                opacity: 0.7
                onClicked: editor.add(modelData)
            }
        }
    }
    Text {
        Layout.fillWidth: true
        visible: editor.adding && input.text.trim() !== "" && !editor.isValid(input.text.trim())
        wrapMode: Text.Wrap
        color: editor.textColor
        opacity: 0.7
        font.pointSize: editor.pointSize
        text: qsTr("A tag has 1 to %1 characters: letters, digits, spaces and signs (ASCII).").arg(editor.maxLength)
    }

    RowLayout {
        Layout.fillWidth: true
        visible: editor.pendingRemove !== ""
        spacing: 6

        Text {
            Layout.fillWidth: true
            wrapMode: Text.Wrap
            color: editor.textColor
            font.pointSize: editor.pointSize
            text: qsTr("No other note has the tag “%1”. Remove it and the tag is gone.").arg(editor.pendingRemove)
        }
        TextButton {
            text: qsTr("Remove")
            destructive: true
            onClicked: {
                editor.drop(editor.pendingRemove)
                editor.pendingRemove = ""
            }
        }
        TextButton {
            text: qsTr("Keep")
            onClicked: editor.pendingRemove = ""
        }
    }
}
