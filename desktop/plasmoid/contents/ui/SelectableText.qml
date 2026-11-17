import QtQuick
import org.kde.kirigami as Kirigami

// Read-only text that can be selected with the mouse and copied (a Label can't). Links (from linkify, as rich
// text) open on click, the pointer turns into a hand over them. Same as the tray app's SelectableText.
TextEdit {
    // the text to show; links in it get the link color (rich text in a TextEdit ignores the palette)
    property string content
    property color linkColor: Kirigami.Theme.linkColor
    text: content.replace(/<a href="([^"]*)">([^<]*)<\/a>/g, '<a href="$1"><font color="' + linkColor + '">$2</font></a>')

    readOnly: true
    selectByMouse: true
    wrapMode: TextEdit.Wrap
    textFormat: TextEdit.RichText
    color: Kirigami.Theme.textColor
    selectionColor: Kirigami.Theme.highlightColor
    selectedTextColor: Kirigami.Theme.highlightedTextColor
    onLinkActivated: link => Qt.openUrlExternally(link)
    HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.IBeamCursor }
}
