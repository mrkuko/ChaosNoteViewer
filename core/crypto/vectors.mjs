// Test vectors for the encryption of the cloud copy, shared by every client (desktop JS, Android Kotlin, ...).
//
//   node vectors.mjs           regenerate vectors.json from the desktop implementation
//   node vectors.mjs --check   verify vectors.json with the desktop implementation
//
// The reference is desktop/shared/Crypto.js (only present on the desktop branch; CRYPTO_JS=<path> overrides).
// Every encryption vector has a fixed nonce, so the token is the same on every platform and a client can check
// both directions byte for byte: decrypt(token) == plaintext and encrypt(plaintext, nonce) == token.
// The vectors are anchored to the official RFC 8439 section 2.8.2 test vector, see `rfc8439`.
import { readFileSync, writeFileSync, existsSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import assert from "node:assert/strict"

const here = dirname(fileURLToPath(import.meta.url))
const cryptoPath = process.env.CRYPTO_JS || resolve(here, "../../desktop/shared/Crypto.js")
const vectorsPath = resolve(here, "vectors.json")

if (!existsSync(cryptoPath)) {
    console.log("desktop/shared/Crypto.js not found (not the desktop branch): nothing to do. Set CRYPTO_JS to use another copy.")
    process.exit(0)
}

// Crypto.js is a QML library: drop the ".pragma"/".import" lines, the rest is plain JavaScript.
const src = readFileSync(cryptoPath, "utf8").split("\n").filter(l => !/^\.(pragma|import)\b/.test(l)).join("\n")
const C = new Function(src + "\nreturn { VERSION, seal, open, encrypt, decrypt, utf8Encode, base64urlEncode, base64urlDecode, formatKey, formatKeyBlock, parseKey }")()

const hex = b => Array.from(b, x => x.toString(16).padStart(2, "0")).join("")
const bytes = h => Uint8Array.from(h.match(/../g) || [], x => parseInt(x, 16))
const unhex = bytes
const seq = (n, start = 0) => Uint8Array.from({ length: n }, (_, i) => (start + i) & 255)

const keys = {
    sequential: seq(32),
    mixed: bytes("a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00"),
    zeros: new Uint8Array(32),
    ones: new Uint8Array(32).fill(255),
}
const nonce = n => seq(12, n * 16 + 1)

// ---- RFC 8439, section 2.8.2 (the independent anchor) ----
const rfc = {
    key: seq(32, 0x80),
    nonce: bytes("070000004041424344454647"),
    aad: bytes("50515253c0c1c2c3c4c5c6c7"),
    plaintext: new TextEncoder().encode("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."),
}
const rfcSealed = C.seal(rfc.key, rfc.nonce, rfc.plaintext, rfc.aad)       // nonce || ciphertext || tag
const rfcBody = rfcSealed.subarray(12)
assert.equal(hex(rfcBody.subarray(rfcBody.length - 16)), "1ae10b594f09e26a7e902ecbd0600691",
    "the desktop implementation does not produce the RFC 8439 tag")
assert.equal(hex(rfcBody.subarray(0, 16)), "d31a8d34648e60db7b86afbc53ef7ec2")

function token(name, keyName, n, aad, plaintext) {
    const key = keys[keyName]
    const t = C.VERSION + C.base64urlEncode(C.seal(key, nonce(n), C.utf8Encode(plaintext), C.utf8Encode(aad)))
    assert.equal(C.decrypt(key, t, aad), plaintext)
    return { name, key: hex(key), nonce: hex(nonce(n)), aad, plaintext, token: t }
}

const pattern = n => "0123456789abcdef".repeat(20).slice(0, n)
const note = JSON.stringify({ title: "Zaplatiť faktúry", description: "https://example.com/pay?id=7\nDo piatku.", completed: false,
    position: 2.5, dueDate: 1781049600000, createdAt: 1778000000000, updatedAt: 1778000123456 })

const tokens = [
    token("empty text, empty aad", "sequential", 1, "", ""),
    token("ascii", "sequential", 2, "note-id-1", "Buy milk"),
    token("aad is part of the data: other aad, same text", "sequential", 3, "note-id-2", "Buy milk"),
    token("slovak letters", "mixed", 4, "3f2b8c1e-6a4d-4e0b-9c55-0d1e2f3a4b5c", "Zaplatiť faktúry — čšžýáíé ľščťžýáíé ĽŠČŤŽÝ ôäň"),
    token("emoji (4 byte UTF-8, surrogate pairs)", "mixed", 5, "emoji", "🎉 party 👨‍👩‍👧‍👦 ✅"),
    token("line breaks and tabs", "sequential", 6, "multiline", "line1\nline2\r\n\ttabbed\n"),
    ...[1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1000].map((n, i) => token(`${n} bytes (block boundaries)`, "mixed", 7 + i, "len", pattern(n))),
    token("all-zero key", "zeros", 20, "zeros", "key made of zeros"),
    token("all-ff key", "ones", 21, "ones", "key made of 0xff"),
    token("key check document", "sequential", 22, "keycheck", "chaosnoteviewer key check"),
    token("a note as stored in the cloud (JSON)", "mixed", 23, "3f2b8c1e-6a4d-4e0b-9c55-0d1e2f3a4b5c", note),
]

// ---- tokens that must NOT decrypt (decrypt returns null) ----
const base = tokens[1]
const body = base.token.slice(C.VERSION.length)
const flip = (s, i) => s.slice(0, i) + (s[i] === "A" ? "B" : "A") + s.slice(i + 1)
const invalid = [
    { name: "wrong key", key: hex(keys.mixed), aad: base.aad, token: base.token },
    { name: "wrong aad", key: base.key, aad: "note-id-OTHER", token: base.token },
    { name: "empty aad instead of the real one", key: base.key, aad: "", token: base.token },
    { name: "changed ciphertext", key: base.key, aad: base.aad, token: C.VERSION + flip(body, 14) },
    { name: "changed tag", key: base.key, aad: base.aad, token: C.VERSION + flip(body, body.length - 5) },
    { name: "changed nonce", key: base.key, aad: base.aad, token: C.VERSION + flip(body, 2) },
    { name: "truncated", key: base.key, aad: base.aad, token: base.token.slice(0, base.token.length - 4) },
    { name: "other version prefix", key: base.key, aad: base.aad, token: "v2." + body },
    { name: "no prefix", key: base.key, aad: base.aad, token: body },
    { name: "plain text, not a token", key: base.key, aad: base.aad, token: "Buy milk" },
    { name: "padding character", key: base.key, aad: base.aad, token: C.VERSION + body + "=" },
    { name: "standard base64 character", key: base.key, aad: base.aad, token: C.VERSION + body.slice(0, 10) + "+" + body.slice(11) },
    { name: "impossible base64 length", key: base.key, aad: base.aad, token: C.VERSION + body + "A".repeat((4 - body.length % 4) % 4 + 1) },
    { name: "27 bytes, one short of nonce + tag", key: base.key, aad: base.aad, token: C.VERSION + C.base64urlEncode(seq(27)) },
    { name: "only the prefix", key: base.key, aad: base.aad, token: C.VERSION },
    { name: "empty string", key: base.key, aad: base.aad, token: "" },
]
for (const v of invalid)
    assert.equal(C.decrypt(unhex(v.key), v.token, v.aad), null, v.name)

// ---- the key as the user sees it ----
const keyFormats = Object.entries(keys).map(([name, k]) => ({ name, key: hex(k), formatted: C.formatKey(k), block: C.formatKeyBlock(k) }))
const kf = keyFormats[1]
const f = kf.formatted
const lastOf = (s, from, to) => s.slice(0, s.length - 1) + (s.endsWith(from) ? to : from)
const plain52 = f.replace(/-/g, "").slice(0, 52)
// accepted: dashes, spaces, line breaks and case don't matter; the checksum is optional
const accepted = [
    ["formatted, as stored and copied", f], ["lower case", f.toLowerCase()], ["no dashes", f.replace(/-/g, "")],
    ["as displayed, four lines", kf.block], ["spaces and a line break around it", "  " + f.replace(/-/g, " ") + " \n"],
    ["52 characters, no checksum (earlier versions)", plain52], ["52 characters with dashes", plain52.match(/.{1,7}/g).join("-")],
]
// rejected: parseKey gives null
const rejected = [
    ["wrong checksum", lastOf(f, "A", "B")], ["checksum of another key", plain52 + keyFormats[0].formatted.replace(/-/g, "").slice(52)],
    ["55 characters", f.replace(/-/g, "").slice(0, 55)], ["57 characters", f.replace(/-/g, "") + "A"],
    ["51 characters", plain52.slice(0, 51)], ["character outside base32 (1)", f.replace(/-/g, "").replace(/^./, "1")],
    ["character outside base32 (8)", f.replace(/-/g, "").replace(/^./, "8")], ["empty", ""], ["only dashes", "----"],
    ["non-zero padding bits in the last character", plain52.slice(0, 51) + "B"],
]
const keyInputs = [
    ...accepted.map(([name, input]) => ({ name, input, key: hex(C.parseKey(input) || []) })),
    ...rejected.map(([name, input]) => ({ name, input, key: null })),
]
for (const v of keyInputs)
    assert.equal(C.parseKey(v.input) === null, v.key === null, v.name)
for (const v of accepted.map((a, i) => keyInputs[i]))
    assert.equal(v.key, kf.key, v.name)
for (const k of keyFormats)
    assert.equal(hex(C.parseKey(k.formatted)), k.key)

const vectors = {
    description: "Shared test vectors for the encrypted cloud copy. Generated by vectors.mjs from the desktop Crypto.js. See README.md.",
    format: {
        token: "v1. + base64url(no padding)( nonce[12] || ciphertext || tag[16] ), ChaCha20-Poly1305 (RFC 8439)",
        aad: "the note id (UTF-8); \"keycheck\" for the key check document",
        key: "32 bytes; shown as base32 (A-Z, 2-7) + 4 character CRC-32 checksum, groups of 7 separated by dashes",
    },
    rfc8439: {
        key: hex(rfc.key), nonce: hex(rfc.nonce), aad: hex(rfc.aad), plaintext: hex(rfc.plaintext),
        ciphertextAndTag: hex(rfcBody),
    },
    tokens, invalid, keyFormats, keyInputs,
}
const json = JSON.stringify(vectors, null, 2) + "\n"

if (process.argv.includes("--check")) {
    assert.equal(readFileSync(vectorsPath, "utf8"), json, "vectors.json is out of date, run: node vectors.mjs")
    console.log("vectors.json matches the desktop implementation:", tokens.length, "tokens,", invalid.length, "invalid,", keyInputs.length, "key inputs")
} else {
    writeFileSync(vectorsPath, json)
    console.log("wrote", vectorsPath, "-", tokens.length, "tokens,", invalid.length, "invalid,", keyInputs.length, "key inputs")
}
