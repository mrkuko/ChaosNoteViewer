.pragma library

// A small QR code encoder (ISO/IEC 18004, model 2), enough to show the encryption key so a phone can
// scan it. Error correction level M, versions 1 to 10, alphanumeric mode when the text allows it
// (the key does: A-Z, 2-7 and "-"), otherwise UTF-8 bytes. Plain JS, so the tray app and the Plasma
// widget draw it the same way on every OS, with no library to install.
//
// encode(text) -> { size: n, modules: [[bool]] } (modules[y][x], true = dark), or null if too long.

var _ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

// level M, index = version
var _EC_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26]
var _BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5]
var _MAX_VERSION = 10

function encode(text) {
    text = String(text)
    var alnum = true
    for (var i = 0; i < text.length; ++i)
        if (_ALNUM.indexOf(text.charAt(i)) < 0) { alnum = false; break }
    var bytes = alnum ? null : _utf8(text)

    for (var ver = 1; ver <= _MAX_VERSION; ++ver) {
        var capacity = _dataCodewords(ver) * 8
        var bits = []
        if (alnum) {
            _push(bits, 2, 4)
            _push(bits, text.length, ver <= 9 ? 9 : 11)
            for (var j = 0; j + 1 < text.length; j += 2)
                _push(bits, _ALNUM.indexOf(text.charAt(j)) * 45 + _ALNUM.indexOf(text.charAt(j + 1)), 11)
            if (text.length % 2)
                _push(bits, _ALNUM.indexOf(text.charAt(text.length - 1)), 6)
        } else {
            _push(bits, 4, 4)
            _push(bits, bytes.length, ver <= 9 ? 8 : 16)
            for (var k = 0; k < bytes.length; ++k)
                _push(bits, bytes[k], 8)
        }
        if (bits.length > capacity)
            continue

        // terminator, byte boundary, pad bytes
        _push(bits, 0, Math.min(4, capacity - bits.length))
        _push(bits, 0, (8 - bits.length % 8) % 8)
        var data = []
        for (var b = 0; b < bits.length; b += 8) {
            var v = 0
            for (var m = 0; m < 8; ++m)
                v = (v << 1) | bits[b + m]
            data.push(v)
        }
        for (var pad = 0xEC; data.length < capacity / 8; pad ^= 0xEC ^ 0x11)
            data.push(pad)
        return _build(ver, _addEcc(ver, data))
    }
    return null
}

function _utf8(text) {
    var s = unescape(encodeURIComponent(text)), out = []
    for (var i = 0; i < s.length; ++i)
        out.push(s.charCodeAt(i))
    return out
}

function _push(bits, value, count) {
    for (var i = count - 1; i >= 0; --i)
        bits.push((value >>> i) & 1)
}

// all modules that hold data or error correction
function _rawModules(ver) {
    var n = (16 * ver + 128) * ver + 64
    if (ver >= 2) {
        var align = Math.floor(ver / 7) + 2
        n -= (25 * align - 10) * align - 55
        if (ver >= 7)
            n -= 36
    }
    return n
}

function _dataCodewords(ver) {
    return Math.floor(_rawModules(ver) / 8) - _EC_PER_BLOCK[ver] * _BLOCKS[ver]
}

// ---- Reed-Solomon over GF(256), polynomial 0x11D ----

function _mul(x, y) {
    var z = 0
    for (var i = 7; i >= 0; --i) {
        z = (z << 1) ^ ((z >>> 7) * 0x11D)
        z ^= ((y >>> i) & 1) * x
    }
    return z
}

function _divisor(degree) {
    var r = []
    for (var i = 0; i < degree - 1; ++i)
        r.push(0)
    r.push(1)
    var root = 1
    for (var d = 0; d < degree; ++d) {
        for (var j = 0; j < r.length; ++j) {
            r[j] = _mul(r[j], root)
            if (j + 1 < r.length)
                r[j] ^= r[j + 1]
        }
        root = _mul(root, 2)
    }
    return r
}

function _remainder(data, divisor) {
    var r = divisor.map(function () { return 0 })
    for (var i = 0; i < data.length; ++i) {
        var f = data[i] ^ r.shift()
        r.push(0)
        for (var j = 0; j < divisor.length; ++j)
            r[j] ^= _mul(divisor[j], f)
    }
    return r
}

// split into blocks, add error correction to each, interleave
function _addEcc(ver, data) {
    var blocks = _BLOCKS[ver], ecLen = _EC_PER_BLOCK[ver]
    var raw = Math.floor(_rawModules(ver) / 8)
    var shortBlocks = blocks - raw % blocks
    var shortLen = Math.floor(raw / blocks)
    var div = _divisor(ecLen)
    var dataBlocks = [], ecBlocks = []
    for (var i = 0, pos = 0; i < blocks; ++i) {
        var len = shortLen - ecLen + (i < shortBlocks ? 0 : 1)
        var d = data.slice(pos, pos + len)
        pos += len
        dataBlocks.push(d)
        ecBlocks.push(_remainder(d, div))
    }
    var out = []
    for (var c = 0; c <= shortLen - ecLen; ++c)
        for (var b = 0; b < blocks; ++b)
            if (c < dataBlocks[b].length)
                out.push(dataBlocks[b][c])
    for (var e = 0; e < ecLen; ++e)
        for (var b2 = 0; b2 < blocks; ++b2)
            out.push(ecBlocks[b2][e])
    return out
}

// ---- the symbol ----

function _grid(size, value) {
    var g = []
    for (var y = 0; y < size; ++y) {
        var row = []
        for (var x = 0; x < size; ++x)
            row.push(value)
        g.push(row)
    }
    return g
}

function _build(ver, codewords) {
    var size = ver * 4 + 17
    var mods = _grid(size, false), fn = _grid(size, false)
    function set(x, y, dark) { mods[y][x] = dark; fn[y][x] = true }

    // timing patterns
    for (var i = 0; i < size; ++i) {
        set(6, i, i % 2 === 0)
        set(i, 6, i % 2 === 0)
    }
    // finder patterns with their separators
    var corners = [[3, 3], [size - 4, 3], [3, size - 4]]
    corners.forEach(function (c) {
        for (var dy = -4; dy <= 4; ++dy)
            for (var dx = -4; dx <= 4; ++dx) {
                var x = c[0] + dx, y = c[1] + dy
                if (x < 0 || y < 0 || x >= size || y >= size)
                    continue
                var dist = Math.max(Math.abs(dx), Math.abs(dy))
                set(x, y, dist !== 2 && dist !== 4)
            }
    })
    // alignment patterns
    var pos = _alignPositions(ver, size)
    for (var a = 0; a < pos.length; ++a)
        for (var b = 0; b < pos.length; ++b) {
            if ((a === 0 && b === 0) || (a === 0 && b === pos.length - 1) || (a === pos.length - 1 && b === 0))
                continue
            for (var dy2 = -2; dy2 <= 2; ++dy2)
                for (var dx2 = -2; dx2 <= 2; ++dx2)
                    set(pos[a] + dx2, pos[b] + dy2, Math.max(Math.abs(dx2), Math.abs(dy2)) !== 1)
        }
    // reserve the format area (filled per mask below) and add version information
    _format(set, size, 0)
    if (ver >= 7) {
        var rem = ver
        for (var r = 0; r < 12; ++r)
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25)
        var vbits = (ver << 12) | rem
        for (var v = 0; v < 18; ++v) {
            var dark = ((vbits >>> v) & 1) === 1
            set(size - 11 + v % 3, Math.floor(v / 3), dark)
            set(Math.floor(v / 3), size - 11 + v % 3, dark)
        }
    }

    // data, in the zigzag order
    var bit = 0, total = codewords.length * 8
    for (var right = size - 1; right >= 1; right -= 2) {
        if (right === 6)
            right = 5
        for (var vert = 0; vert < size; ++vert)
            for (var j = 0; j < 2; ++j) {
                var x = right - j
                var y = ((right + 1) & 2) === 0 ? size - 1 - vert : vert
                if (!fn[y][x] && bit < total) {
                    mods[y][x] = ((codewords[bit >>> 3] >>> (7 - (bit & 7))) & 1) === 1
                    ++bit
                }
            }
    }

    // the mask with the lowest penalty
    var best = null, bestPenalty = Infinity
    for (var mask = 0; mask < 8; ++mask) {
        var cand = mods.map(function (row) { return row.slice() })
        for (var yy = 0; yy < size; ++yy)
            for (var xx = 0; xx < size; ++xx)
                if (!fn[yy][xx] && _maskBit(mask, xx, yy))
                    cand[yy][xx] = !cand[yy][xx]
        _format(function (fx, fy, d) { cand[fy][fx] = d }, size, mask)
        var p = _penalty(cand, size)
        if (p < bestPenalty) {
            bestPenalty = p
            best = cand
        }
    }
    return { size: size, modules: best }
}

function _alignPositions(ver, size) {
    if (ver === 1)
        return []
    var count = Math.floor(ver / 7) + 2
    var step = Math.floor((ver * 8 + count * 3 + 5) / (count * 4 - 4)) * 2
    var out = [6]
    for (var p = size - 7; out.length < count; p -= step)
        out.splice(1, 0, p)
    return out
}

// format information: level M (bits 00) and the mask, BCH coded, written twice
function _format(set, size, mask) {
    var data = mask, rem = data
    for (var i = 0; i < 10; ++i)
        rem = (rem << 1) ^ ((rem >>> 9) * 0x537)
    var bits = ((data << 10) | rem) ^ 0x5412
    function bit(n) { return ((bits >>> n) & 1) === 1 }
    for (var a = 0; a <= 5; ++a)
        set(8, a, bit(a))
    set(8, 7, bit(6))
    set(8, 8, bit(7))
    set(7, 8, bit(8))
    for (var b = 9; b < 15; ++b)
        set(14 - b, 8, bit(b))
    for (var c = 0; c < 8; ++c)
        set(size - 1 - c, 8, bit(c))
    for (var d = 8; d < 15; ++d)
        set(8, size - 15 + d, bit(d))
    set(8, size - 8, true)
}

function _maskBit(mask, x, y) {
    switch (mask) {
    case 0: return (x + y) % 2 === 0
    case 1: return y % 2 === 0
    case 2: return x % 3 === 0
    case 3: return (x + y) % 3 === 0
    case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0
    case 5: return x * y % 2 + x * y % 3 === 0
    case 6: return (x * y % 2 + x * y % 3) % 2 === 0
    default: return ((x + y) % 2 + x * y % 3) % 2 === 0
    }
}

function _penalty(m, size) {
    var p = 0, dark = 0
    function line(get) {
        // runs of 5+ equal modules, and finder-like 1:1:3:1:1 with 4 light modules on one side
        var run = 0, prev = null, s = "0000"
        for (var i = 0; i < size; ++i) {
            var v = get(i)
            s += v ? "1" : "0"
            if (v === prev) {
                ++run
            } else {
                if (run >= 5)
                    p += run - 2
                run = 1
                prev = v
            }
        }
        if (run >= 5)
            p += run - 2
        s += "0000"
        for (var k = 0; k + 11 <= s.length; ++k) {
            var w = s.substr(k, 11)
            if (w === "10111010000" || w === "00001011101")
                p += 40
        }
    }
    for (var y = 0; y < size; ++y) {
        line(function (x) { return m[y][x] })
        line(function (yy) { return m[yy][y] })
        for (var x = 0; x < size; ++x) {
            if (m[y][x])
                ++dark
            if (x + 1 < size && y + 1 < size && m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1])
                p += 3
        }
    }
    var total = size * size
    p += (Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1) * 10
    return p
}
