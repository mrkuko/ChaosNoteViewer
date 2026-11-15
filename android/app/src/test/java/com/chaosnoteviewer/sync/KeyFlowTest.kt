package com.chaosnoteviewer.sync

import com.chaosnoteviewer.crypto.FakeWrapper
import com.chaosnoteviewer.crypto.KeyStorage
import com.chaosnoteviewer.crypto.KeyText
import com.chaosnoteviewer.crypto.MemoryState
import com.chaosnoteviewer.crypto.NoteCrypto
import com.chaosnoteviewer.data.KeyFlowStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFlowTest {
    private class Notes : KeyFlowStore {
        var markedDirty = 0
        var forgotten = 0
        override suspend fun markAllDirty() { markedDirty++ }
        override suspend fun forgetRemote() { forgotten++ }
    }

    private class Device(cloud: RemoteStore) {
        val notes = Notes()
        val storage = KeyStorage(MemoryState(), FakeWrapper())
        val flow = KeyFlow(storage, cloud, notes)
    }

    private val uid = "u1"
    private val cloud = FakeRemoteStore()

    @Test fun theFirstDeviceMakesTheKeyAndMustConfirmIt() = runTest {
        val a = Device(cloud)
        val r = a.flow.begin(uid)
        assertEquals(KeyPhase.Save, r.phase)
        assertNull(r.error)
        assertNotNull(a.storage.load())
        assertFalse(a.storage.isConfirmed())
        assertEquals(1, a.notes.markedDirty)             // notes uploaded before without a key go up encrypted
        assertTrue(a.flow.keyBlock().lines().size == 4)
        // the cloud has only the check, never the key
        val check = cloud.checks[uid]!!
        assertTrue(KeyFlow.keyMatches(a.storage.load()!!, check))
        assertFalse(check.contains(KeyText.format(a.storage.load()!!)))
        // not confirmed yet: still Save after a restart
        assertEquals(KeyPhase.Save, a.flow.begin(uid).phase)
        a.flow.confirmSaved()
        assertEquals(KeyPhase.Ready, a.flow.begin(uid).phase)
    }

    @Test fun theSecondDeviceMustTypeTheKey() = runTest {
        val a = Device(cloud); a.flow.begin(uid); a.flow.confirmSaved()
        val b = Device(cloud)
        val r = b.flow.begin(uid)
        assertEquals(KeyPhase.Enter, r.phase)
        assertFalse("no key here yet, so nothing changed", r.mismatch)
        assertEquals(0, b.notes.markedDirty)

        val wrong = b.flow.enter(uid, KeyText.format(NoteCrypto.newKey()))
        assertFalse(wrong.ok)
        assertEquals("That key does not match the notes in the cloud.", wrong.message)
        assertNull(b.storage.load())

        val typo = b.flow.enter(uid, "ABCD-EFGH")
        assertEquals("That is not a valid key. Check it for typing mistakes: it has 56 letters and digits.", typo.message)

        // typed from the display of the first device, in the four-line form, any case
        val right = b.flow.enter(uid, a.flow.keyBlock().lowercase())
        assertTrue(right.ok)
        assertArrayEquals(a.storage.load(), b.storage.load())
        assertTrue(b.storage.isConfirmed())
        assertEquals(KeyPhase.Ready, b.flow.begin(uid).phase)
    }

    @Test fun aKeyResetOnAnotherDeviceAsksForTheNewKey() = runTest {
        val a = Device(cloud); a.flow.begin(uid); a.flow.confirmSaved()
        val b = Device(cloud); b.flow.begin(uid); b.flow.enter(uid, a.flow.keyText())
        assertEquals(KeyPhase.Ready, b.flow.begin(uid).phase)
        val oldKey = a.storage.load()!!

        a.flow.reset(uid)                                 // A lost its key and made a new one
        assertNotEquals(KeyText.format(oldKey), a.flow.keyText())
        assertEquals(1, a.notes.forgotten)
        assertFalse(a.storage.isConfirmed())              // A must save the new key first

        val r = b.flow.begin(uid)
        assertEquals(KeyPhase.Enter, r.phase)
        assertTrue("B has a key, but it is the old one", r.mismatch)
        assertTrue(b.flow.enter(uid, a.flow.keyText()).ok)
        assertEquals(KeyPhase.Ready, b.flow.begin(uid).phase)
    }

    @Test fun resetWipesTheCloudAndPublishesTheNewCheck() = runTest {
        val a = Device(cloud); a.flow.begin(uid); a.flow.confirmSaved()
        cloud.put(uid, "n1", "v1.whatever"); cloud.put(uid, "n2", "v1.whatever")
        a.flow.reset(uid)
        assertEquals(emptySet<String>(), cloud.docIds(uid))
        assertTrue(KeyFlow.keyMatches(a.storage.load()!!, cloud.checks[uid]!!))
        assertEquals(KeyPhase.Save, a.flow.begin(uid).phase)
    }

    @Test fun anInterruptedResetIsRepairedFromTheStoredKey() = runTest {
        val a = Device(cloud); a.flow.begin(uid); a.flow.confirmSaved()
        // the reset got as far as wiping the cloud and saving the new key, but not as far as the check
        cloud.wipe(uid)
        a.storage.save(NoteCrypto.newKey(), confirmed = false)
        val r = a.flow.begin(uid)
        assertEquals(KeyPhase.Save, r.phase)
        assertTrue(KeyFlow.keyMatches(a.storage.load()!!, cloud.checks[uid]!!))
    }

    @Test fun offlineWithAConfirmedKeyKeepsWorking() = runTest {
        val a = Device(cloud); a.flow.begin(uid); a.flow.confirmSaved()
        cloud.offline = true
        val r = a.flow.begin(uid)
        assertEquals(KeyPhase.Ready, r.phase)
        assertEquals("NETWORK", r.error?.code)
    }

    @Test fun offlineWithoutAKeyCantDecideYet() = runTest {
        cloud.offline = true
        val r = Device(cloud).flow.begin(uid)
        assertEquals(KeyPhase.Unknown, r.phase)
        assertEquals("NETWORK", r.error?.code)
    }

    @Test fun offlineWithAnUnconfirmedKeyCantDecideEither() = runTest {
        val a = Device(cloud); a.flow.begin(uid)
        cloud.offline = true
        assertEquals(KeyPhase.Unknown, a.flow.begin(uid).phase)
    }

    // two new devices start at the same moment: only one check can be created, the loser looks at the winner's key
    @Test fun theLoserOfARaceAsksForTheWinnersKey() = runTest {
        val racing = object : RemoteStore by cloud {
            var first = true
            override suspend fun fetchKeyCheck(uid: String): String? = if (first) null else cloud.fetchKeyCheck(uid)
            override suspend fun createKeyCheck(uid: String, text: String): Boolean {
                first = false
                cloud.createKeyCheck(uid, KeyFlow.makeCheck(NoteCrypto.newKey()))     // the other device was first
                return cloud.createKeyCheck(uid, text)
            }
        }
        val b = Device(racing)
        val r = b.flow.begin(uid)
        assertEquals(KeyPhase.Enter, r.phase)
        assertNull("the loser keeps no key of its own", b.storage.load())
    }

    @Test fun noKeyInTheCloudAnymoreWhileTyping() = runTest {
        val b = Device(cloud)
        assertEquals("There is no key in the cloud any more. Close this and try again.",
            b.flow.enter(uid, KeyText.format(NoteCrypto.newKey())).message)
    }

    @Test fun anExistingKeyIsPublishedWhenTheCloudHasNone() = runTest {
        // this device has a confirmed key (e.g. from before the cloud was emptied): it publishes it, stays Ready
        val a = Device(cloud)
        a.storage.save(NoteCrypto.newKey(), confirmed = true)
        val r = a.flow.begin(uid)
        assertEquals(KeyPhase.Ready, r.phase)
        assertTrue(KeyFlow.keyMatches(a.storage.load()!!, cloud.checks[uid]!!))
        assertEquals(0, a.notes.markedDirty)
    }
}
