.pragma library
.import "Crypto.js" as Crypto

// The setup code: everything another device needs to join, in one text that the QR code carries.
//
//   chaosnote:setup?v=1&p=<project id>&a=<Web API key>&k=<encryption key on one line>
//
// The same format on Android (sync/SetupCode.kt). The sign-in itself (email, password, refresh token) is never part
// of it: whoever sees the code still has to sign in to the account. A bare key on one line (what the QR code held
// before) is still accepted where a key is asked for.

var PREFIX = "chaosnote:setup?"

function make(projectId, apiKey, keyLine) {
    if (projectId === "" || apiKey === "" || keyLine === "")
        return ""
    return PREFIX + "v=1&p=" + encodeURIComponent(projectId) + "&a=" + encodeURIComponent(apiKey)
        + "&k=" + encodeURIComponent(keyLine)
}

// { projectId, apiKey, key (32 bytes), keyText (the key on one line) } or null when the text is not a setup code
// (or a part is missing or broken).
function parse(text) {
    text = String(text).trim()
    if (text.toLowerCase().indexOf(PREFIX) !== 0)
        return null
    var fields = {}
    var parts = text.substring(PREFIX.length).split("&")
    for (var i = 0; i < parts.length; ++i) {
        var eq = parts[i].indexOf("=")
        if (eq <= 0)
            continue
        try {
            fields[parts[i].substring(0, eq)] = decodeURIComponent(parts[i].substring(eq + 1))
        } catch (e) {
            return null
        }
    }
    if (fields.v !== "1" || !fields.p || !fields.a || !fields.k)
        return null
    var key = Crypto.parseKey(fields.k)
    if (key === null)
        return null
    return { projectId: fields.p, apiKey: fields.a, key: key, keyText: Crypto.formatKey(key) }
}
