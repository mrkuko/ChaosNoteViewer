.pragma library
// The little bit of Markdown the notes understand. Images (![alt](https://...)) are cut out by split() and shown as
// pictures by the UI. The rest of the text goes through toHtml(): headings, bullet and numbered lists (nested),
// task lists, tables, quotes, code, rules and **bold**, *italic*, ~~strike~~, `code`. Links are made clickable by the UI.
// Shared by the tray app and the Plasma widget.

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

function _escape(t) {
    return String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
}

// One line of text as rich text. linkify() escapes it and turns addresses into <a> tags, the emphasis marks are
// then applied only outside of links and code spans.
function _inline(t, linkify) {
    var tokens = linkify(t).split(/(<a href="[^"]*">[^<]*<\/a>|`[^`]+`)/)
    for (var i = 0; i < tokens.length; i++) {
        var k = tokens[i]
        if (i % 2 === 1) {
            if (k.charAt(0) === "`") tokens[i] = "<code>" + k.slice(1, -1).replace(/<\/?a[^>]*>/g, "") + "</code>"
        } else {
            tokens[i] = k.replace(/\*\*(?=\S)(.+?\S|\S)\*\*/g, "<b>$1</b>")
                         .replace(/\*(?=\S)([^*]*?\S|\S)\*/g, "<i>$1</i>")
                         .replace(/~~(?=\S)(.+?\S|\S)~~/g, "<s>$1</s>")
        }
    }
    return tokens.join("")
}

function _cells(line) {
    var s = line.trim().replace(/^\|/, "").replace(/\|$/, "")
    return s.split("|").map(function (c) { return c.trim() })
}

var _isRule = /^ {0,3}([-*_])( *\1){2,} *$/
var _isSeparator = /^ *\|? *:?-+:? *(\| *:?-+:? *)*\|? *$/
var _isItem = /^([ \t]*)([-*+]|\d{1,9}[.)])[ \t]+(.*)$/

// A note text as rich text (Qt rich text subset, for a Text or TextEdit with RichText).
//   linkify: function (plainText) -> escaped text with <a> links
//   borderColor: color of the table lines
function toHtml(text, linkify, borderColor) {
    var lines = String(text === undefined || text === null ? "" : text).split(/\r?\n/)
    var out = []
    var para = []
    var lists = []   // open list levels: { indent }

    function flushPara() {
        if (para.length > 0) out.push("<p style=\"margin:0\">" + para.join("<br>") + "</p>")
        para = []
    }
    function closeLists(toIndent) {
        while (lists.length > 0 && (toIndent === undefined || toIndent < lists[lists.length - 1].indent)) {
            lists.pop()
        }
    }
    function indentOf(ws) { return ws.replace(/\t/g, "    ").length }

    for (var i = 0; i < lines.length; i++) {
        var line = lines[i]
        var m

        if (/^ {0,3}(```|~~~)/.test(line)) {
            flushPara(); closeLists()
            var code = []
            for (i++; i < lines.length && !/^ {0,3}(```|~~~)/.test(lines[i]); i++) code.push(_escape(lines[i]))
            out.push("<pre>" + code.join("\n") + "</pre>")
            continue
        }
        if (line.trim() === "") { flushPara(); closeLists(); continue }

        if ((m = /^ {0,3}(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$/.exec(line)) !== null) {
            flushPara(); closeLists()
            var h = Math.min(m[1].length + 1, 4)   // # -> h2, ## -> h3, ### and deeper -> h4
            out.push("<h" + h + " style=\"margin-top:6px;margin-bottom:2px\">" + _inline(m[2], linkify) + "</h" + h + ">")
            continue
        }
        if (_isRule.test(line)) { flushPara(); closeLists(); out.push("<hr>"); continue }

        if (line.indexOf("|") >= 0 && i + 1 < lines.length && lines[i + 1].indexOf("-") >= 0
                && lines[i + 1].indexOf("|") >= 0 && _isSeparator.test(lines[i + 1])) {
            flushPara(); closeLists()
            var head = _cells(line)
            var aligns = _cells(lines[i + 1]).map(function (c) {
                return /^:-*:$/.test(c) ? "center" : /-:$/.test(c) ? "right" : "left"
            })
            var html = "<table border=\"1\" cellspacing=\"0\" cellpadding=\"5\" style=\"border-collapse:collapse;border-color:"
                       + (borderColor || "#888888") + "\"><tr>"
            for (var c = 0; c < head.length; c++) {
                html += "<th align=\"" + (aligns[c] || "left") + "\">" + _inline(head[c], linkify) + "</th>"
            }
            html += "</tr>"
            for (i += 2; i < lines.length && lines[i].trim() !== "" && lines[i].indexOf("|") >= 0; i++) {
                var row = _cells(lines[i])
                html += "<tr>"
                for (var d = 0; d < head.length; d++) {
                    html += "<td align=\"" + (aligns[d] || "left") + "\">" + _inline(row[d] || "", linkify) + "</td>"
                }
                html += "</tr>"
            }
            i--
            out.push(html + "</table>")
            continue
        }

        if ((m = _isItem.exec(line)) !== null) {
            flushPara()
            var indent = indentOf(m[1])
            var ordered = /^\d/.test(m[2])
            closeLists(indent)
            if (lists.length === 0 || indent > lists[lists.length - 1].indent) lists.push({ indent: indent })
            var item = m[3]
            var task = /^\[([ xX])\][ \t]+/.exec(item)
            if (task) item = (task[1] === " " ? "\u2610 " : "\u2611 ") + item.substring(task[0].length)
            // Qt indents its own lists by 40 px, too much for a small card. So an item is a paragraph with the
            // marker in front and a hanging indent, which also keeps wrapped lines under the text.
            var depth = lists.length - 1
            var marker = ordered ? m[2] : (depth === 0 ? "\u2022" : "\u25E6")
            out.push("<p style=\"margin:1px 0 1px " + (14 + depth * 16) + "px;text-indent:-14px\">" + marker + "&nbsp;" + _inline(item, linkify) + "</p>")
            continue
        }

        if (/^ {0,3}>/.test(line)) {
            flushPara(); closeLists()
            var quote = []
            for (; i < lines.length && /^ {0,3}>/.test(lines[i]); i++) quote.push(_inline(lines[i].replace(/^ {0,3}> ?/, ""), linkify))
            i--
            out.push("<blockquote style=\"margin:2px 0 2px 8px\">" + quote.join("<br>") + "</blockquote>")
            continue
        }

        closeLists()
        para.push(_inline(line, linkify))
    }
    flushPara(); closeLists()
    return out.join("")
}
