package com.chaosnoteviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private fun urls(text: String) = Links.find(text).map { it.url }
    private fun matched(text: String) = Links.find(text).map { text.substring(it.range) }

    @Test fun plainHttps() = assertEquals(listOf("https://example.com/a?b=1"), urls("see https://example.com/a?b=1 now"))

    @Test fun wwwGetsHttps() = assertEquals(listOf("https://www.example.com"), urls("www.example.com"))

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("https://a.com"), matched("go to https://a.com."))
        assertEquals(listOf("https://a.com/x"), matched("(https://a.com/x)"))
        assertEquals(listOf("http://a.com"), matched("http://a.com, http"))
    }

    @Test fun severalLinks() = assertEquals(2, Links.find("http://a.com and www.b.org!").size)

    @Test fun noLinks() = assertEquals(emptyList<String>(), urls("nothing here, just a.com"))

    @Test fun caseInsensitive() = assertEquals(listOf("HTTPS://A.COM"), urls("HTTPS://A.COM"))
}
