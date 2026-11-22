import QtQuick
import QtQuick.Layouts
import "Markdown.js" as Markdown

// The description of a note: text with clickable links, and Markdown images (![alt](https://...)) shown as pictures.
// Used by the tray app and the Plasma widget; the text look comes from each one's own SelectableText.
ColumnLayout {
    id: body

    // the raw description
    property string source
    // turns plain text into rich text with links (Theme.linkify in the tray app, root.linkify in the widget)
    property var linkify: function (t) { return t }

    readonly property var parts: Markdown.split(source)
    readonly property bool empty: parts.length === 0

    spacing: 8

    Repeater {
        model: body.parts

        Loader {
            id: part
            required property var modelData
            Layout.fillWidth: true
            sourceComponent: modelData.image ? imageItem : textItem

            Component {
                id: textItem
                SelectableText {
                    content: body.linkify(part.modelData.text).replace(/\n/g, "<br>")
                }
            }

            Component {
                id: imageItem
                Item {
                    id: holder
                    // the picture is never wider than the page and never scaled up
                    implicitHeight: pic.status === Image.Ready ? pic.paintedHeight : note.implicitHeight
                    implicitWidth: parent ? parent.width : 0

                    Image {
                        id: pic
                        source: part.modelData.url
                        asynchronous: true
                        fillMode: Image.PreserveAspectFit
                        smooth: true
                        mipmap: !shrunk
                        // A photo much wider than the page (e.g. 4000 px, about 48 MB decoded) is loaded again,
                        // shrunk to the page width, so it does not keep that memory while the note is open.
                        // Qt scales images UP to sourceSize too, so it is set only once the real width is known.
                        // The page width goes in steps of 256 px, so resizing does not reload on every pixel.
                        property int naturalWidth: 0
                        readonly property int pageWidth: Math.max(256, Math.ceil(holder.width * Screen.devicePixelRatio / 256) * 256)
                        readonly property bool shrunk: naturalWidth > 2 * pageWidth
                        sourceSize: shrunk ? Qt.size(pageWidth, 0) : undefined
                        onStatusChanged: if (status === Image.Ready && !shrunk) naturalWidth = implicitWidth
                        // implicitWidth/Height = the decoded size
                        width: Math.min(implicitWidth, holder.width)
                        height: implicitWidth > 0 ? width * implicitHeight / implicitWidth : 0
                        visible: status === Image.Ready
                    }
                    // shown while loading and when the picture can not be loaded (offline, wrong address)
                    SelectableText {
                        id: note
                        width: holder.width
                        visible: pic.status !== Image.Ready
                        font.italic: true
                        opacity: 0.7
                        content: pic.status === Image.Error
                            ? qsTr("Image could not be loaded") + (part.modelData.alt !== "" ? ": " + part.modelData.alt : "")
                            : qsTr("Loading image…")
                    }
                }
            }
        }
    }
}
