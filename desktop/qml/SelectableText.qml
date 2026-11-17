import QtQuick

// Read-only text that can be selected with the mouse and copied (a Label can't). Links (from Theme.linkify,
// as rich text) open on click, the pointer turns into a hand over them.
TextEdit {
    // the text to show; links in it get the link color (rich text in a TextEdit ignores the palette)
    property string content
    property color linkColor: Theme.link
    text: content.replace(/<a href="([^"]*)">([^<]*)<\/a>/g, '<a href="$1"><font color="' + linkColor + '">$2</font></a>')

    readOnly: true
    selectByMouse: true
    wrapMode: TextEdit.Wrap
    textFormat: TextEdit.RichText
    color: Theme.text
    selectionColor: Qt.alpha(Theme.highlight, 0.5)
    selectedTextColor: Theme.text
    onLinkActivated: link => Qt.openUrlExternally(link)
    HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.IBeamCursor }
}
