.pragma library

// Client-side encryption for the cloud copy of the notes: ChaCha20-Poly1305 (RFC 8439) in plain JS,
// QML has no crypto library. It is the standard IETF construction, so Android and everything else can
// read the same data with its built-in implementation.
//
// Stored text:   "v1." + base64url( nonce[12] || ciphertext || tag[16] )
// Key:           32 random bytes, shown to the user as base32 in groups of four (ABCD-EFGH-...)
// Bound to:      the note id (authenticated data), so the server can't move a ciphertext to another note.
// Randomness:    Qt.uuid() (QUuid::createUuid uses the system CSPRNG), never Math.random().

var VERSION = "v1."

// ---------- bytes and text ----------

function utf8Encode(str) {
    var out = []
    for (var i = 0; i < str.length; ++i) {
        var c = str.charCodeAt(i)
        if (c >= 0xd800 && c < 0xdc00 && i + 1 < str.length) {
            var d = str.charCodeAt(i + 1)
            if (d >= 0xdc00 && d < 0xe000) {
                c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00)
                ++i
            }
        }
        if (c < 0x80) {
            out.push(c)
        } else if (c < 0x800) {
            out.push(0xc0 | (c >> 6), 0x80 | (c & 63))
        } else if (c < 0x10000) {
            out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
        } else {
            out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
        }
    }
    return new Uint8Array(out)
}

// null when the bytes are not valid UTF-8
function utf8Decode(bytes) {
    var s = ""
    var i = 0
    while (i < bytes.length) {
        var b = bytes[i++], c, extra
        if (b < 0x80) { c = b; extra = 0 }
        else if (b >= 0xc2 && b < 0xe0) { c = b & 0x1f; extra = 1 }
        else if (b >= 0xe0 && b < 0xf0) { c = b & 0x0f; extra = 2 }
        else if (b >= 0xf0 && b < 0xf5) { c = b & 0x07; extra = 3 }
        else return null
        if (i + extra > bytes.length)
            return null
        for (var k = 0; k < extra; ++k) {
            var n = bytes[i++]
            if ((n & 0xc0) !== 0x80)
                return null
            c = (c << 6) | (n & 63)
        }
        if (c >= 0x10000) {
            c -= 0x10000
            s += String.fromCharCode(0xd800 + (c >> 10), 0xdc00 + (c & 0x3ff))
        } else {
            s += String.fromCharCode(c)
        }
    }
    return s
}

var _B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

function base64urlEncode(bytes) {
    var s = ""
    for (var i = 0; i < bytes.length; i += 3) {
        var n = (bytes[i] << 16) | ((i + 1 < bytes.length ? bytes[i + 1] : 0) << 8) | (i + 2 < bytes.length ? bytes[i + 2] : 0)
        s += _B64.charAt((n >> 18) & 63) + _B64.charAt((n >> 12) & 63)
        if (i + 1 < bytes.length) s += _B64.charAt((n >> 6) & 63)
        if (i + 2 < bytes.length) s += _B64.charAt(n & 63)
    }
    return s
}

// null on any character outside the alphabet or an impossible length
function base64urlDecode(text) {
    if (text.length % 4 === 1)
        return null
    var out = []
    var acc = 0, bits = 0
    for (var i = 0; i < text.length; ++i) {
        var v = _B64.indexOf(text.charAt(i))
        if (v < 0)
            return null
        acc = (acc << 6) | v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.push((acc >> bits) & 255)
            acc &= (1 << bits) - 1
        }
    }
    return new Uint8Array(out)
}

// ---------- random ----------

function randomBytes(n) {
    var out = new Uint8Array(n)
    var have = 0
    while (have < n) {
        // a v4 uuid has 122 random bits; skip the fixed version and variant digits
        var hex = Qt.uuid().replace(/[{}-]/g, "")
        hex = hex.slice(0, 12) + hex.slice(13, 16) + hex.slice(17)
        for (var i = 0; i + 1 < hex.length && have < n; i += 2)
            out[have++] = parseInt(hex.substr(i, 2), 16)
    }
    return out
}

// ---------- ChaCha20 ----------

function _rotl(v, n) { return ((v << n) | (v >>> (32 - n))) >>> 0 }

function _quarter(s, a, b, c, d) {
    s[a] = (s[a] + s[b]) >>> 0; s[d] = _rotl(s[d] ^ s[a], 16)
    s[c] = (s[c] + s[d]) >>> 0; s[b] = _rotl(s[b] ^ s[c], 12)
    s[a] = (s[a] + s[b]) >>> 0; s[d] = _rotl(s[d] ^ s[a], 8)
    s[c] = (s[c] + s[d]) >>> 0; s[b] = _rotl(s[b] ^ s[c], 7)
}

function _le32(b, o) { return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0 }

// One 64-byte keystream block.
function _block(key, counter, nonce, out) {
    var s = new Uint32Array(16), x = new Uint32Array(16), i
    s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
    for (i = 0; i < 8; ++i) s[4 + i] = _le32(key, 4 * i)
    s[12] = counter
    for (i = 0; i < 3; ++i) s[13 + i] = _le32(nonce, 4 * i)
    for (i = 0; i < 16; ++i) x[i] = s[i]
    for (i = 0; i < 10; ++i) {
        _quarter(x, 0, 4, 8, 12); _quarter(x, 1, 5, 9, 13); _quarter(x, 2, 6, 10, 14); _quarter(x, 3, 7, 11, 15)
        _quarter(x, 0, 5, 10, 15); _quarter(x, 1, 6, 11, 12); _quarter(x, 2, 7, 8, 13); _quarter(x, 3, 4, 9, 14)
    }
    for (i = 0; i < 16; ++i) {
        var v = (x[i] + s[i]) >>> 0
        out[4 * i] = v & 255; out[4 * i + 1] = (v >>> 8) & 255; out[4 * i + 2] = (v >>> 16) & 255; out[4 * i + 3] = (v >>> 24) & 255
    }
}

function _stream(key, counter, nonce, data) {
    var out = new Uint8Array(data.length)
    var ks = new Uint8Array(64)
    for (var off = 0; off < data.length; off += 64, ++counter) {
        _block(key, counter, nonce, ks)
        var n = Math.min(64, data.length - off)
        for (var i = 0; i < n; ++i)
            out[off + i] = data[off + i] ^ ks[i]
    }
    return out
}

// ---------- Poly1305 ----------
// The 130-bit numbers are 10 limbs of 13 bits, so every product stays far below 2^53 and plain
// double arithmetic is exact.

function _limbs(b) {                       // b: 17 bytes, little endian, -> 10 limbs
    var l = []
    for (var i = 0; i < 10; ++i) {
        var bit = 13 * i, byte = bit >> 3
        l.push(((b[byte] | (b[byte + 1] << 8) | (b[byte + 2] << 16)) >>> (bit & 7)) & 0x1fff)
    }
    return l
}

function _carry(t) {                        // propagate carries; the part above 2^130 wraps around times 5
    for (var pass = 0; pass < 4; ++pass) {
        var c = 0
        for (var i = 0; i < 10; ++i) {
            t[i] += c
            c = Math.floor(t[i] / 8192)
            t[i] -= c * 8192
        }
        t[0] += c * 5
    }
}

function _poly1305(key, msg) {
    var rb = new Uint8Array(17)
    for (var i = 0; i < 16; ++i) rb[i] = key[i]
    rb[3] &= 15; rb[7] &= 15; rb[11] &= 15; rb[15] &= 15
    rb[4] &= 252; rb[8] &= 252; rb[12] &= 252
    var r = _limbs(rb)
    var h = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

    for (var off = 0; off < msg.length; off += 16) {
        var n = Math.min(16, msg.length - off)
        var mb = new Uint8Array(17)
        for (i = 0; i < n; ++i) mb[i] = msg[off + i]
        mb[n] = 1
        var m = _limbs(mb)
        for (i = 0; i < 10; ++i) h[i] += m[i]

        var t = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        for (i = 0; i < 10; ++i)
            for (var j = 0; j < 10; ++j) {
                var k = i + j
                if (k < 10) t[k] += h[i] * r[j]
                else t[k - 10] += 5 * h[i] * r[j]
            }
        _carry(t)
        h = t
    }

    // reduce fully: if h >= 2^130 - 5, subtract it
    var g = h.slice()
    g[0] += 5
    var c = 0
    for (i = 0; i < 10; ++i) {
        g[i] += c
        c = Math.floor(g[i] / 8192)
        g[i] -= c * 8192
    }
    if (c >= 1)
        h = g

    var tag = new Uint8Array(16)
    for (i = 0; i < 128; ++i)
        tag[i >> 3] |= ((h[Math.floor(i / 13)] >> (i % 13)) & 1) << (i & 7)
    var carry = 0
    for (i = 0; i < 16; ++i) {
        var sum = tag[i] + key[16 + i] + carry
        tag[i] = sum & 255
        carry = sum >> 8
    }
    return tag
}

function _pad16(len) { return (16 - (len % 16)) % 16 }

function _lenBytes(n) {                    // 64-bit little endian
    var b = new Uint8Array(8)
    for (var i = 0; i < 6; ++i) { b[i] = n % 256; n = Math.floor(n / 256) }
    return b
}

function _tag(otk, aad, ct) {
    var mac = new Uint8Array(aad.length + _pad16(aad.length) + ct.length + _pad16(ct.length) + 16)
    mac.set(aad, 0)
    mac.set(ct, aad.length + _pad16(aad.length))
    var p = aad.length + _pad16(aad.length) + ct.length + _pad16(ct.length)
    mac.set(_lenBytes(aad.length), p)
    mac.set(_lenBytes(ct.length), p + 8)
    return _poly1305(otk, mac)
}

function _constantTimeEqual(a, b) {
    var d = 0
    for (var i = 0; i < a.length; ++i) d |= a[i] ^ b[i]
    return d === 0
}

// ---------- AEAD ----------

// Returns nonce || ciphertext || tag.
function seal(key, nonce, plaintext, aad) {
    var otk = new Uint8Array(64)
    _block(key, 0, nonce, otk)
    var ct = _stream(key, 1, nonce, plaintext)
    var tag = _tag(otk, aad, ct)
    var out = new Uint8Array(12 + ct.length + 16)
    out.set(nonce, 0); out.set(ct, 12); out.set(tag, 12 + ct.length)
    return out
}

// null when the data was modified or the key is wrong.
function open(key, sealed, aad) {
    if (sealed.length < 28)
        return null
    var nonce = sealed.subarray(0, 12)
    var ct = sealed.subarray(12, sealed.length - 16)
    var tag = sealed.subarray(sealed.length - 16)
    var otk = new Uint8Array(64)
    _block(key, 0, nonce, otk)
    if (!_constantTimeEqual(_tag(otk, aad, ct), tag))
        return null
    return _stream(key, 1, nonce, ct)
}

// ---------- text API ----------

function encrypt(key, text, aad) {
    return VERSION + base64urlEncode(seal(key, randomBytes(12), utf8Encode(text), utf8Encode(aad)))
}

// null when the text is not ours, was changed, or the key is wrong
function decrypt(key, token, aad) {
    if (typeof token !== "string" || token.indexOf(VERSION) !== 0)
        return null
    var sealed = base64urlDecode(token.slice(VERSION.length))
    if (sealed === null)
        return null
    var plain = open(key, sealed, utf8Encode(aad))
    return plain === null ? null : utf8Decode(plain)
}

// ---------- the key as the user sees it ----------

var _B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

function generateKey() { return randomBytes(32) }

function formatKey(key) {
    var s = "", acc = 0, bits = 0
    for (var i = 0; i < key.length; ++i) {
        acc = (acc << 8) | key[i]
        bits += 8
        while (bits >= 5) {
            bits -= 5
            s += _B32.charAt((acc >> bits) & 31)
        }
        acc &= (1 << bits) - 1
    }
    if (bits > 0)
        s += _B32.charAt((acc << (5 - bits)) & 31)
    return s.match(/.{1,4}/g).join("-")
}

// 32 key bytes from what the user typed (dashes, spaces and case don't matter), or null
function parseKey(text) {
    var s = String(text).replace(/[\s-]/g, "").toUpperCase()
    if (s.length !== 52)
        return null
    var out = [], acc = 0, bits = 0
    for (var i = 0; i < s.length; ++i) {
        var v = _B32.indexOf(s.charAt(i))
        if (v < 0)
            return null
        acc = (acc << 5) | v
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.push((acc >> bits) & 255)
            acc &= (1 << bits) - 1
        }
    }
    return acc === 0 && out.length === 32 ? new Uint8Array(out) : null
}
