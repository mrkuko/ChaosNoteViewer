import QtQuick
import QtQuick.Controls

// Text button in the app's own style. The brush swipe (icons/scratch-N.png, as on NavButton) shows on hover;
// the main action of a form (primary) keeps it all the time. At rest the other buttons get a hand-drawn
// chalk outline instead: two slightly wobbly strokes, a little different on every button, so they read
// as buttons without looking selected. Destructive buttons have red text, quiet buttons (the back arrow)
// no outline.
AbstractButton {
    id: control

    property bool primary: false
    property bool destructive: false
    property bool quiet: false

    readonly property int scratchVariants: 10
    property int scratchVariant: Math.floor(Math.random() * scratchVariants)
    // fixed per button, so the outline doesn't change shape when it is redrawn
    readonly property int chalkSeed: Math.floor(Math.random() * 100000)

    // a new brush each time the pointer arrives, never the same one twice in a row
    onHoveredChanged: {
        if (!hovered)
            return
        let next = Math.floor(Math.random() * (scratchVariants - 1))
        if (next >= scratchVariant)
            ++next
        scratchVariant = next
    }

    hoverEnabled: true
    font.bold: primary
    focusPolicy: Qt.StrongFocus
    topPadding: Theme.small
    bottomPadding: Theme.small
    leftPadding: Theme.large
    rightPadding: Theme.large
    implicitWidth: implicitContentWidth + leftPadding + rightPadding
    implicitHeight: Math.max(Theme.iconMedium + Theme.medium, implicitContentHeight + topPadding + bottomPadding)

    background: Item {
        Image {
            anchors.centerIn: parent
            width: parent.width
            height: parent.height * 1.25
            source: Qt.resolvedUrl("../icons/scratch-" + (control.scratchVariant + 1) + ".png")
            fillMode: Image.Stretch
            smooth: true
            opacity: !control.enabled ? (control.primary ? 0.3 : 0)
                : control.down ? 1.0
                : control.primary ? (control.hovered || control.visualFocus ? 1.0 : 0.75)
                : control.hovered || control.visualFocus ? 0.45 : 0
            Behavior on opacity { NumberAnimation { duration: Theme.shortDuration } }
        }

        Canvas {
            id: chalk
            anchors.fill: parent
            visible: !control.primary && !control.quiet
            opacity: !control.enabled ? 0.35 : control.hovered || control.down ? 0.4 : 1.0
            Behavior on opacity { NumberAnimation { duration: Theme.shortDuration } }
            readonly property color tone: control.destructive ? Theme.negative : Theme.text
            onToneChanged: requestPaint()
            onWidthChanged: requestPaint()
            onHeightChanged: requestPaint()

            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                let seed = control.chalkSeed
                const rnd = function () { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 }
                const w = width, h = height, r = Math.min(6, h / 3)
                // one stroke: a rounded rectangle whose corners and edges wander by about a pixel
                const stroke = function (alpha, lineWidth, spread) {
                    const j = function () { return (rnd() - 0.5) * spread }
                    const l = 1.5 + j(), t = 1.5 + j(), ri = w - 1.5 + j(), b = h - 1.5 + j()
                    ctx.beginPath()
                    ctx.moveTo(l + r + j(), t + j())
                    ctx.quadraticCurveTo(w / 2 + j(), t + j() * 1.5, ri - r + j(), t + j())
                    ctx.quadraticCurveTo(ri + j(), t + j(), ri + j(), t + r + j())
                    ctx.quadraticCurveTo(ri + j() * 1.5, h / 2 + j(), ri + j(), b - r + j())
                    ctx.quadraticCurveTo(ri + j(), b + j(), ri - r + j(), b + j())
                    ctx.quadraticCurveTo(w / 2 + j(), b + j() * 1.5, l + r + j(), b + j())
                    ctx.quadraticCurveTo(l + j(), b + j(), l + j(), b - r + j())
                    ctx.quadraticCurveTo(l + j() * 1.5, h / 2 + j(), l + j(), t + r + j())
                    ctx.quadraticCurveTo(l + j(), t + j(), l + r + 3 + j(), t + j())
                    ctx.lineWidth = lineWidth
                    ctx.lineCap = "round"
                    ctx.strokeStyle = Qt.rgba(tone.r, tone.g, tone.b, alpha)
                    ctx.stroke()
                }
                stroke(0.45, 1.3, 1.6)
                stroke(0.2, 1.0, 2.6)
            }
        }
    }

    contentItem: Label {
        text: control.text
        font: control.font
        color: control.destructive ? Theme.negative : Theme.text
        opacity: control.enabled ? 1.0 : 0.4
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
}
