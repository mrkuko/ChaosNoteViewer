import QtQuick
import "QrCode.js" as QrCode

// Draws `text` as a QR code: black on white with the 4-module quiet zone, whatever the theme, so phone
// cameras read it. Shared by the tray app and the Plasma widget.
Canvas {
    id: qr

    property string text: ""
    readonly property var code: text === "" ? null : QrCode.encode(text)
    readonly property int modules: code ? code.size + 8 : 0

    implicitWidth: 200
    implicitHeight: implicitWidth

    onCodeChanged: requestPaint()
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()

    onPaint: {
        var ctx = getContext("2d")
        ctx.reset()
        if (!code)
            return
        // whole pixels per module, so the edges stay sharp
        var side = Math.min(width, height)
        var scale = Math.max(1, Math.floor(side / modules))
        var origin = Math.floor((side - scale * modules) / 2)
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(origin, origin, scale * modules, scale * modules)
        ctx.fillStyle = "#000000"
        for (var y = 0; y < code.size; ++y)
            for (var x = 0; x < code.size; ++x)
                if (code.modules[y][x])
                    ctx.fillRect(origin + (x + 4) * scale, origin + (y + 4) * scale, scale, scale)
    }
}
