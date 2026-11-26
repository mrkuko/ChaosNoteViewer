import QtQuick
import QtQuick.Layouts
import "Markdown.js" as Markdown

// The description of a note: Markdown (headings, lists, tables, quotes, code, bold/italic), clickable links and
// images (![alt](https://...)) shown as pictures.
// Used by the tray app and the Plasma widget, the text look comes from each one's own SelectableText.
// Two modes: the full text for the note detail, and a preview (preview: true) for the notes list that has no
// pictures, is not selectable (a click goes to the card) and stops after maxLines lines, with a "…" when text is left out.
Item {
    id: body

    // the raw description
    property string source
    // turns plain text into rich text with links (Theme.linkify in the tray app, root.linkify in the widget)
    property var linkify: function (t) { return t }
    // color of the table lines
    property color lineColor: "#888888"

    property bool preview: false
    property int maxLines: 0
    readonly property real maxHeight: maxLines * metrics.lineSpacing
    // look of the preview text (the full text uses SelectableText)
    property color textColor: "black"
    property color linkColor: "blue"
    property real pointSize: 9

    FontMetrics { id: metrics; font.pointSize: body.pointSize }

    readonly property var parts: preview
        ? Markdown.split(source).filter(function (p) { return !p.image })
        : Markdown.split(source)
    readonly property bool empty: parts.length === 0
    readonly property bool truncated: preview && maxHeight > 0 && column.implicitHeight > maxHeight + 1

    implicitHeight: viewport.height + (truncated ? more.implicitHeight : 0)

    Item {
        id: viewport
        width: body.width
        height: body.preview && body.maxHeight > 0 ? Math.min(column.implicitHeight, body.maxHeight) : column.implicitHeight
        clip: body.preview

        ColumnLayout {
            id: column
            width: parent.width
            spacing: body.preview ? 2 : 8

            Repeater {
                model: body.parts

                Loader {
                    id: part
                    required property var modelData
                    readonly property string html: modelData.image ? "" : Markdown.toHtml(modelData.text, body.linkify, body.lineColor)
                    Layout.fillWidth: true
                    sourceComponent: modelData.image ? imageItem : (body.preview ? previewItem : textItem)

                    Component {
                        id: textItem
                        SelectableText {
                            content: part.html
                        }
                    }

                    Component {
                        id: previewItem
                        Text {
                            text: part.html
                            textFormat: Text.RichText
                            wrapMode: Text.Wrap
                            color: body.textColor
                            linkColor: body.linkColor
                            font.pointSize: body.pointSize
                            onLinkActivated: link => Qt.openUrlExternally(link)
                            HoverHandler { cursorShape: parent.hoveredLink !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
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
    }

    // shown under a preview that was cut
    Text {
        id: more
        y: viewport.height
        visible: body.truncated
        text: "…"
        color: body.textColor
        font.pointSize: body.pointSize
    }
}
