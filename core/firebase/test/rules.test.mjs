// Firestore security rules tests. Run with: npm test (starts the emulator itself).
import { test, before, after, beforeEach } from "node:test"
import { readFileSync } from "node:fs"
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing"
import { doc, setDoc, getDoc, updateDoc, deleteDoc, serverTimestamp, Timestamp } from "firebase/firestore"

let env
const note = (over = {}) => ({ content: "ciphertext", completed: false, order: 1, serverUpdatedAt: serverTimestamp(), ...over })
const verified = uid => env.authenticatedContext(uid, { email: uid + "@example.com", email_verified: true }).firestore()
const alice = () => verified("alice")
const bob = () => verified("bob")
const path = (uid, id = "n1") => `users/${uid}/notes/${id}`

before(async () => {
    env = await initializeTestEnvironment({
        projectId: "demo-chaosnoteviewer",
        firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
    })
})
after(() => env.cleanup())
beforeEach(() => env.clearFirestore())

test("owner can create and update own note", async () => {
    await assertSucceeds(setDoc(doc(alice(), path("alice")), note()))
    await assertSucceeds(updateDoc(doc(alice(), path("alice")), { content: "changed", serverUpdatedAt: serverTimestamp() }))
})

test("owner can read own note", async () => {
    await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path("alice")), note({ serverUpdatedAt: Timestamp.now() })))
    await assertSucceeds(getDoc(doc(alice(), path("alice"))))
})

test("other user cannot read or write someone else's note", async () => {
    await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path("alice")), note({ serverUpdatedAt: Timestamp.now() })))
    await assertFails(getDoc(doc(bob(), path("alice"))))
    await assertFails(setDoc(doc(bob(), path("alice", "n2")), note()))
    await assertFails(updateDoc(doc(bob(), path("alice")), { content: "x", serverUpdatedAt: serverTimestamp() }))
})

test("signed-out user has no access", async () => {
    const anon = env.unauthenticatedContext().firestore()
    await assertFails(getDoc(doc(anon, path("alice"))))
    await assertFails(setDoc(doc(anon, path("alice")), note()))
})

test("notes are never physically deleted", async () => {
    await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path("alice")), note({ serverUpdatedAt: Timestamp.now() })))
    await assertFails(deleteDoc(doc(alice(), path("alice"))))
})

test("serverUpdatedAt must be the server time", async () => {
    await assertFails(setDoc(doc(alice(), path("alice")), note({ serverUpdatedAt: Timestamp.fromMillis(1000) })))
    const { serverUpdatedAt, ...without } = note()
    await assertFails(setDoc(doc(alice(), path("alice")), without))
})

test("content must be a string under 200000 chars", async () => {
    await assertFails(setDoc(doc(alice(), path("alice")), note({ content: 42 })))
    await assertFails(setDoc(doc(alice(), path("alice")), note({ content: "x".repeat(200000) })))
    await assertSucceeds(setDoc(doc(alice(), path("alice")), note({ content: "x".repeat(199999) })))
})

test("meta (wrapped key) is readable and writable only by its owner", async () => {
    await assertSucceeds(setDoc(doc(alice(), "users/alice/meta/keys"), { wrapped: "abc" }))
    await assertSucceeds(getDoc(doc(alice(), "users/alice/meta/keys")))
    await assertFails(getDoc(doc(bob(), "users/alice/meta/keys")))
    await assertFails(setDoc(doc(bob(), "users/alice/meta/keys"), { wrapped: "evil" }))
})

test("nothing outside users/{uid} is accessible", async () => {
    await assertFails(setDoc(doc(alice(), "other/doc"), { a: 1 }))
    await assertFails(getDoc(doc(alice(), "other/doc")))
})

test("a user whose email is not verified has no access to their own data", async () => {
    await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path("alice")), note({ serverUpdatedAt: Timestamp.now() })))
    const unverified = env.authenticatedContext("alice", { email: "alice@example.com", email_verified: false }).firestore()
    await assertFails(getDoc(doc(unverified, path("alice"))))
    await assertFails(setDoc(doc(unverified, path("alice", "n2")), note()))
    await assertFails(updateDoc(doc(unverified, path("alice")), { content: "x", serverUpdatedAt: serverTimestamp() }))
    await assertFails(setDoc(doc(unverified, "users/alice/meta/keys"), { wrapped: "abc" }))
    await assertFails(getDoc(doc(unverified, "users/alice/meta/keys")))
})

test("a token without the email_verified claim is treated as unverified", async () => {
    const noClaim = env.authenticatedContext("alice").firestore()
    await assertFails(setDoc(doc(noClaim, path("alice")), note()))
})
