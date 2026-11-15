package com.chaosnoteviewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

// Every http(s):// or www. address in a note's text, same pattern as Theme.linkify on desktop.
// A trailing . , ; : ! ? ) ] ' is punctuation of the sentence, not part of the address.
object Links {
    private val pattern = Regex("""\b((?:https?://|www\.)[^\s<>"]*[^\s<>".,;:!?)\]'])""", RegexOption.IGNORE_CASE)

    data class Found(val range: IntRange, val url: String)

    fun find(text: String): List<Found> = pattern.findAll(text).map { m ->
        val raw = m.value
        Found(m.range, if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw)
    }.toList()
}

// The text with its addresses as links; a tap opens them in the browser (Compose's default handler).
fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var at = 0
    for (link in Links.find(text)) {
        append(text, at, link.range.first)
        withLink(LinkAnnotation.Url(link.url, styles)) { append(text.substring(link.range)) }
        at = link.range.last + 1
    }
    append(text, at, text.length)
}
