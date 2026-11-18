package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.KeyText
import java.net.URLDecoder
import java.net.URLEncoder

// The setup code: everything another device needs to join, in one text that the QR code carries.
//
//   chaosnote:setup?v=1&p=<project id>&a=<Web API key>&k=<encryption key on one line>
//
// The same format on desktop (desktop/shared/SetupCode.js). The sign-in itself (email, password, refresh token) is
// never part of it: whoever sees the code still has to sign in to the account. A bare key on one line (what the QR
// code held before) is still accepted where a key is asked for.
class SetupCode(val projectId: String, val apiKey: String, val key: ByteArray) {
    val keyText: String get() = KeyText.format(key)

    companion object {
        const val PREFIX = "chaosnote:setup?"

        fun make(projectId: String, apiKey: String, keyLine: String): String {
            if (projectId.isEmpty() || apiKey.isEmpty() || keyLine.isEmpty()) return ""
            return PREFIX + "v=1&p=" + enc(projectId) + "&a=" + enc(apiKey) + "&k=" + enc(keyLine)
        }

        fun looksLikeOne(text: String) = text.trim().startsWith(PREFIX, ignoreCase = true)

        // null when the text is not a setup code (or a part is missing or broken)
        fun parse(text: String): SetupCode? {
            val t = text.trim()
            if (!looksLikeOne(t)) return null
            val fields = try {
                t.substring(PREFIX.length).split("&").mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                }.toMap()
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (fields["v"] != "1") return null
            val project = fields["p"]?.takeIf { it.isNotEmpty() } ?: return null
            val apiKey = fields["a"]?.takeIf { it.isNotEmpty() } ?: return null
            val key = fields["k"]?.let(KeyText::parse) ?: return null
            return SetupCode(project, apiKey, key)
        }

        // what a key field should work with: the key of a setup code, or the text as typed
        fun keyOf(text: String): String = parse(text)?.keyText ?: text

        // URLEncoder writes a space as "+"; the desktop's encodeURIComponent and decoders want "%20" (nothing here has spaces)
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
