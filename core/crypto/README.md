# Encryption format, test vectors and the setup code

Note content is encrypted on the device. Firebase only stores ciphertext. Every client must read what the
others write, so the format is fixed here and checked byte for byte by `vectors.json`.

- **Cipher:** ChaCha20-Poly1305, RFC 8439 (built into Android 9+ and Java 11+, the desktop app has its own JS version).
- **Token:** `v1.` + base64url, no padding, of `nonce[12] ‖ ciphertext ‖ tag[16]`.
- **Authenticated data:** the note id (so the server can't move a ciphertext to another note), and `keycheck` for the
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

## Setup code

What a device that already syncs shows next to the encryption key (as a QR code and as text to copy), so another
device joins with one scan. Every client must make and read exactly this text:

```
chaosnote:setup?v=1&p=<project id>&a=<Web API key>&k=<encryption key>
```

- `v`: the format version, `1`. A reader rejects any other version.
- `p`: the Firebase project id, `a`: the project's Web API key, `k`: the key in its one-line form
  (`ABCDEFG-HIJKLMN-...`, 8 groups of 7). Values are percent-encoded (`encodeURIComponent`), though the ones used today
  need no escaping.
- The prefix is matched without regard to case and surrounding blanks are ignored (scanners and pasting).
- All three fields are required. A code with a missing field or a key that does not parse is rejected as a whole.
- **Never in the code:** the email, the password or any token. The new device signs in itself.
- A field that asks only for the key accepts the setup code as well (its `k` is used), and still accepts a bare key.
- The QR code: error correction level M, byte mode. The longest code (30-character project id) fits version 10.

Example (key bytes `(i * 37 + 11) mod 256`, the value both clients' tests check):

```
chaosnote:setup?v=1&p=my-notes-1a2b3&a=AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q&k=BMYFK6U-7YTUQ4M-2YPWRMP-3ARGZNY-BJOK54K-DSXUDVD-G7EFZ4M-GDAVH2I
```

Implementations: `desktop/shared/SetupCode.js` (desktop branch), `android/.../sync/SetupCode.kt` (android branch).
