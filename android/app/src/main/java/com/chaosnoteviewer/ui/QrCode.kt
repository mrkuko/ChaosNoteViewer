package com.chaosnoteviewer.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// A small QR code encoder (ISO/IEC 18004, model 2), enough to show the encryption key so another phone can scan
// it. The same algorithm as the desktop's desktop/shared/QrCode.js, so both draw the identical symbol: error
// correction level M, versions 1 to 10, alphanumeric mode when the text allows it (the key does: A-Z, 2-7 and
// "-"), otherwise UTF-8 bytes. No library needed.
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    // true = dark module; x = column, y = row
    fun isDark(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        private const val ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        private val EC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val MAX_VERSION = 10

        // the symbol for [text], or null when it does not fit in version 10
        fun encode(text: String): QrCode? {
            val alnum = text.all { ALNUM.indexOf(it) >= 0 }
            val bytes = if (alnum) null else text.toByteArray(Charsets.UTF_8)

            for (ver in 1..MAX_VERSION) {
                val capacity = dataCodewords(ver) * 8
                val bits = ArrayList<Int>()
                if (alnum) {
                    push(bits, 2, 4)
                    push(bits, text.length, if (ver <= 9) 9 else 11)
                    var j = 0
                    while (j + 1 < text.length) {
                        push(bits, ALNUM.indexOf(text[j]) * 45 + ALNUM.indexOf(text[j + 1]), 11)
                        j += 2
                    }
                    if (text.length % 2 == 1)
                        push(bits, ALNUM.indexOf(text.last()), 6)
                } else {
                    push(bits, 4, 4)
                    push(bits, bytes!!.size, if (ver <= 9) 8 else 16)
                    for (b in bytes) push(bits, b.toInt() and 0xff, 8)
                }
                if (bits.size > capacity)
                    continue

                // terminator, byte boundary, pad bytes
                push(bits, 0, min(4, capacity - bits.size))
                push(bits, 0, (8 - bits.size % 8) % 8)
                val data = ArrayList<Int>()
                for (b in bits.indices step 8) {
                    var v = 0
                    for (m in 0 until 8) v = (v shl 1) or bits[b + m]
                    data.add(v)
                }
                var pad = 0xEC
                while (data.size < capacity / 8) {
                    data.add(pad)
                    pad = pad xor 0xEC xor 0x11
                }
                return build(ver, addEcc(ver, data))
            }
            return null
        }

        private fun push(bits: MutableList<Int>, value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1)
        }

        // all modules that hold data or error correction
        private fun rawModules(ver: Int): Int {
            var n = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val align = ver / 7 + 2
                n -= (25 * align - 10) * align - 55
                if (ver >= 7) n -= 36
            }
            return n
        }

        private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - EC_PER_BLOCK[ver] * BLOCKS[ver]

        // ---- Reed-Solomon over GF(256), polynomial 0x11D ----

        private fun mul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun divisor(degree: Int): IntArray {
            val r = IntArray(degree)
            r[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in r.indices) {
                    r[j] = mul(r[j], root)
                    if (j + 1 < r.size) r[j] = r[j] xor r[j + 1]
                }
                root = mul(root, 2)
            }
            return r
        }

        private fun remainder(data: List<Int>, div: IntArray): IntArray {
            val r = IntArray(div.size)
            for (d in data) {
                val f = d xor r[0]
                System.arraycopy(r, 1, r, 0, r.size - 1)
                r[r.size - 1] = 0
                for (j in div.indices) r[j] = r[j] xor mul(div[j], f)
            }
            return r
        }

        // split into blocks, add error correction to each, interleave
        private fun addEcc(ver: Int, data: List<Int>): IntArray {
            val blocks = BLOCKS[ver]
            val ecLen = EC_PER_BLOCK[ver]
            val raw = rawModules(ver) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val div = divisor(ecLen)
            val dataBlocks = ArrayList<List<Int>>()
            val ecBlocks = ArrayList<IntArray>()
            var pos = 0
            for (i in 0 until blocks) {
                val len = shortLen - ecLen + if (i < shortBlocks) 0 else 1
                val d = data.subList(pos, pos + len)
                pos += len
                dataBlocks.add(d)
                ecBlocks.add(remainder(d, div))
            }
            val out = ArrayList<Int>()
            for (c in 0..shortLen - ecLen)
                for (b in 0 until blocks)
                    if (c < dataBlocks[b].size) out.add(dataBlocks[b][c])
            for (e in 0 until ecLen)
                for (b in 0 until blocks) out.add(ecBlocks[b][e])
            return out.toIntArray()
        }

        // ---- the symbol ----

        private fun build(ver: Int, codewords: IntArray): QrCode {
            val size = ver * 4 + 17
            val mods = Array(size) { BooleanArray(size) }
            val fn = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, dark: Boolean) {
                mods[y][x] = dark
                fn[y][x] = true
            }

            // timing patterns
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            // finder patterns with their separators
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4))
                for (dy in -4..4)
                    for (dx in -4..4) {
                        val x = cx + dx
                        val y = cy + dy
                        if (x < 0 || y < 0 || x >= size || y >= size) continue
                        val dist = max(abs(dx), abs(dy))
                        set(x, y, dist != 2 && dist != 4)
                    }
            // alignment patterns
            val pos = alignPositions(ver, size)
            for (a in pos.indices)
                for (b in pos.indices) {
                    if ((a == 0 && b == 0) || (a == 0 && b == pos.size - 1) || (a == pos.size - 1 && b == 0)) continue
                    for (dy in -2..2)
                        for (dx in -2..2) set(pos[a] + dx, pos[b] + dy, max(abs(dx), abs(dy)) != 1)
                }
            // reserve the format area (filled per mask below) and add version information
            format(::set, size, 0)
            if (ver >= 7) {
                var rem = ver
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val vbits = (ver shl 12) or rem
                for (v in 0 until 18) {
                    val dark = ((vbits ushr v) and 1) == 1
                    set(size - 11 + v % 3, v / 3, dark)
                    set(v / 3, size - 11 + v % 3, dark)
                }
            }

            // data, in the zigzag order
            var bit = 0
            val total = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size)
                    for (j in 0 until 2) {
                        val x = right - j
                        val y = if (((right + 1) and 2) == 0) size - 1 - vert else vert
                        if (!fn[y][x] && bit < total) {
                            mods[y][x] = ((codewords[bit ushr 3] ushr (7 - (bit and 7))) and 1) == 1
                            bit++
                        }
                    }
                right -= 2
            }

            // the mask with the lowest penalty
            var best = mods
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                val cand = Array(size) { mods[it].copyOf() }
                for (y in 0 until size)
                    for (x in 0 until size)
                        if (!fn[y][x] && maskBit(mask, x, y)) cand[y][x] = !cand[y][x]
                format({ x, y, d -> cand[y][x] = d }, size, mask)
                val p = penalty(cand, size)
                if (p < bestPenalty) {
                    bestPenalty = p
                    best = cand
                }
            }
            return QrCode(size, best)
        }

        private fun alignPositions(ver: Int, size: Int): List<Int> {
            if (ver == 1) return emptyList()
            val count = ver / 7 + 2
            val step = (ver * 8 + count * 3 + 5) / (count * 4 - 4) * 2
            val out = mutableListOf(6)
            var p = size - 7
            while (out.size < count) {
                out.add(1, p)
                p -= step
            }
            return out
        }

        // format information: level M (bits 00) and the mask, BCH coded, written twice
        private fun format(set: (Int, Int, Boolean) -> Unit, size: Int, mask: Int) {
            var rem = mask
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((mask shl 10) or rem) xor 0x5412
            fun bit(n: Int) = ((bits ushr n) and 1) == 1
            for (a in 0..5) set(8, a, bit(a))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (b in 9 until 15) set(14 - b, 8, bit(b))
            for (c in 0 until 8) set(size - 1 - c, 8, bit(c))
            for (d in 8 until 15) set(8, size - 15 + d, bit(d))
            set(8, size - 8, true)
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }

        private fun penalty(m: Array<BooleanArray>, size: Int): Int {
            var p = 0
            // runs of 5+ equal modules, and finder-like 1:1:3:1:1 with 4 light modules on one side
            fun line(get: (Int) -> Boolean) {
                var run = 0
                var prev: Boolean? = null
                val s = StringBuilder("0000")
                for (i in 0 until size) {
                    val v = get(i)
                    s.append(if (v) '1' else '0')
                    if (v == prev) {
                        run++
                    } else {
                        if (run >= 5) p += run - 2
                        run = 1
                        prev = v
                    }
                }
                if (run >= 5) p += run - 2
                s.append("0000")
                for (k in 0..s.length - 11) {
                    val w = s.substring(k, k + 11)
                    if (w == "10111010000" || w == "00001011101") p += 40
                }
            }
            var dark = 0
            for (y in 0 until size) {
                line { x -> m[y][x] }
                line { yy -> m[yy][y] }
                for (x in 0 until size) {
                    if (m[y][x]) dark++
                    if (x + 1 < size && y + 1 < size && m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1])
                        p += 3
                }
            }
            val total = size * size
            p += (ceil(abs(dark * 20 - total * 10).toDouble() / total).toInt() - 1) * 10
            return p
        }
    }
}
