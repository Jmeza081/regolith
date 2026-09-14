package com.regolith.desktop.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Against the real login Keychain, under its own service name so it cannot
 * touch the app's entries. It writes one item and always deletes it.
 * Skipped anywhere `/usr/bin/security` does not exist.
 */
class KeychainCredentialStoreTest {
    @Test
    fun `passwords with any characters round-trip, update in place, and clear`() = runTest {
        assumeTrue("macOS only", System.getProperty("os.name").startsWith("Mac") && File("/usr/bin/security").canExecute())
        val store = KeychainCredentialStore(service = "Regolith Chapters (test)")
        val id = Random.nextLong(1_000_000, Long.MAX_VALUE)
        try {
            // Accents, an emoji, quotes, a backslash, a leading dash and a
            // run of hex digits: the cases `security` quoting and its hex
            // output would otherwise get wrong.
            val tricky = "-w pässwörd \"quoted\" \\back ☕ deadbeef"
            store.put(id, tricky)
            assertEquals(tricky, store.get(id))

            store.put(id, "second")
            assertEquals("second", store.get(id))

            store.clear(id)
            assertNull(store.get(id))
            store.clear(id) // clearing what is not there is fine
        } finally {
            store.clear(id)
        }
    }
}
