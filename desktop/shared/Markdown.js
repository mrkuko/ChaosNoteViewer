.pragma library
// The little bit of Markdown the notes understand: images, written ![alt](https://...). Everything else stays plain text
// (links are made clickable by the UI). Shared by the tray app and the Plasma widget.

var _image = /!\[([^\]\n]*)\]\(\s*((?:https?:\/\/|data:image\/)[^\s)]+)\s*\)/gi

// Splits a note text into pieces in the order they appear:
//   { image: false, text: "..." }  and  { image: true, alt: "...", url: "..." }
// A single line break next to an image is dropped, and text that is only white space is skipped.
function split(text) {
    var parts = []
    var s = String(text === undefined || text === null ? "" : text)
    var last = 0
    var m
    _image.lastIndex = 0
    while ((m = _image.exec(s)) !== null) {
        _pushText(parts, s.substring(last, m.index), false, true)
        parts.push({ image: true, alt: m[1], url: m[2] })
        last = m.index + m[0].length
    }
    _pushText(parts, s.substring(last), parts.length > 0, false)
    return parts
}

function _pushText(parts, t, afterImage, beforeImage) {
    if (afterImage) t = t.replace(/^\r?\n/, "")
    if (beforeImage) t = t.replace(/\r?\n$/, "")
    if (t.trim() !== "") parts.push({ image: false, text: t })
}
