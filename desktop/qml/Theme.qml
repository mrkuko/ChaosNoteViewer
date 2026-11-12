pragma Singleton
import QtQuick

// Colors, sizes and small helpers of the tray app. Dark slate, like the Plasma widget, because the chalk
// icons (lavender) are drawn for a dark background.
QtObject {
    readonly property color bg: "#24273a"
    readonly property color bgDark: "#1e2030"
    readonly property color text: "#cad3f5"
    readonly property color accent: "#b7bdf8"
    readonly property color highlight: "#8aadf4"
    readonly property color negative: "#ed8796"
    readonly property color positive: "#a6da95"
    readonly property color neutral: "#f5a97f"
    readonly property color link: "#8aadf4"
    readonly property color border: "#363a4f"

    readonly property int radius: 6
    readonly property int small: 4
    readonly property int medium: 8
    readonly property int large: 12
    readonly property int iconSmall: 16
    readonly property int iconMedium: 22
    readonly property int iconNav: 26
    readonly property int shortDuration: 150
    readonly property real smallFont: 9
    readonly property string mono: Qt.platform.os === "windows" ? "Consolas" : "monospace"

    // Note text as StyledText: escaped, with every http(s):// or www. address turned into a clickable link.
    function linkify(text) {
        const esc = String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        return esc.replace(/\b((?:https?:\/\/|www\.)[^\s<>"]*[^\s<>".,;:!?)\]'])/gi, function (url) {
            const href = /^www\./i.test(url) ? "https://" + url : url
            return "<a href=\"" + href + "\">" + url + "</a>"
        })
    }
}
