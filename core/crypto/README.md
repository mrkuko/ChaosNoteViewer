# Encryption test vectors

Note content is encrypted on the device; Firebase only stores ciphertext. Every client must read what the
others write, so the format is fixed here and checked byte for byte by `vectors.json`.

- **Cipher:** ChaCha20-Poly1305, RFC 8439 (built into Android 9+, Java 11+; the desktop app has its own JS version).
- **Token:** `v1.` + base64url, no padding, of `nonce[12] ‖ ciphertext ‖ tag[16]`.
- **Authenticated data:** the note id (so the server can't move a ciphertext to another note); `keycheck` for the
  key check document.
- **Key:** 32 random bytes, shown to the user as base32 + 4 characters of CRC-32, groups of 7 (`ABCDEFG-HIJKLMN-...`).

`vectors.json` is generated from the desktop implementation (`desktop/shared/Crypto.js`):

```bash
node vectors.mjs           # regenerate (only on the desktop branch, or set CRYPTO_JS=<path to Crypto.js>)
node vectors.mjs --check   # verify the committed file
```

Each encryption vector has a fixed nonce, so a client tests both directions: `decrypt(token) == plaintext` and
`encrypt(plaintext, nonce) == token`. `invalid` lists tokens that must not decrypt, `keyInputs` what the key entry
field accepts and rejects. `rfc8439` is the official test vector (section 2.8.2) that anchors everything.
