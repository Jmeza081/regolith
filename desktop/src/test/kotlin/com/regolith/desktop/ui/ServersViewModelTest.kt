package com.regolith.desktop.ui

import com.regolith.desktop.AppGraph
import com.regolith.desktop.InMemoryCredentialStore
import com.regolith.desktop.data.Connection
import com.regolith.desktop.data.SavedServer
import com.regolith.desktop.data.ServerStore
import com.regolith.desktop.ui.servers.ServersViewModel
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServersViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake = FakeSmbGateway().apply { addFile("media", "Films/Heat.1995.mkv", ByteArray(10)) }
    private val passwords = InMemoryCredentialStore()
    private val store by lazy { ServerStore(tmp.root.toPath().resolve("servers.json"), io = Dispatchers.Unconfined) }
    private var connected: Connection? = null

    private fun TestScope.vm() = ServersViewModel(AppGraph(fake, store, passwords), this) { connected = it }

    @Test
    fun `an address with a share connects as guest and is remembered without a password`() = runTest {
        val vm = vm()
        vm.onAddressChange("smb://localhost:1445/media")
        vm.connect()
        advanceUntilIdle()
        assertEquals(Connection(SmbHost("localhost", 1445), SmbCredentials.Guest, "media"), connected)
        assertEquals(listOf(SavedServer(1, "localhost", 1445, "media", "")), store.all())
        assertTrue(passwords.passwords.isEmpty())
        assertEquals(1, vm.state.value.saved.size)
        assertEquals("", vm.state.value.address)
    }

    @Test
    fun `a Windows-style address and a domain username are understood, and the password goes to the Keychain`() = runTest {
        val vm = vm()
        vm.onAddressChange("""\\tower\media""")
        vm.onUsernameChange("""HOME\sam""")
        vm.onPasswordChange("pw")
        vm.connect()
        advanceUntilIdle()
        assertEquals(Connection(SmbHost("tower"), SmbCredentials.Password("sam", "pw", domain = "HOME"), "media"), connected)
        assertEquals(mapOf(1L to "pw"), passwords.passwords)
    }

    @Test
    fun `an address without a share asks for one and does not connect`() = runTest {
        val vm = vm()
        vm.onAddressChange("smb://192.168.4.73")
        vm.connect()
        advanceUntilIdle()
        assertNull(connected)
        assertEquals("Add the share's name to the address", vm.state.value.problem?.message)
        assertTrue(vm.state.value.problem!!.detail!!.contains("smb://192.168.4.73/media"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a refused sign-in stays on the screen with the phone's wording and remembers nothing`() = runTest {
        fake.acceptedCredentials = SmbCredentials.Password("sam", "right")
        val vm = vm()
        vm.onAddressChange("tower/media")
        vm.onUsernameChange("sam")
        vm.onPasswordChange("wrong")
        vm.connect()
        advanceUntilIdle()
        assertNull(connected)
        assertEquals("Sign-in failed", vm.state.value.problem?.message)
        assertFalse(vm.state.value.connecting)
        assertTrue(store.all().isEmpty())
        assertTrue(passwords.passwords.isEmpty())
    }

    @Test
    fun `a saved row connects with its Keychain password`() = runTest {
        fake.acceptedCredentials = SmbCredentials.Password("sam", "right")
        val saved = store.save(SavedServer(host = "tower", share = "media", username = "sam"))
        passwords.put(saved.id, "right")
        val vm = vm()
        advanceUntilIdle()
        vm.connectSaved(vm.state.value.saved.single())
        advanceUntilIdle()
        assertEquals(Connection(SmbHost("tower"), SmbCredentials.Password("sam", "right"), "media"), connected)
        assertNull(vm.state.value.connectingId)
    }

    @Test
    fun `a saved row with no password in the Keychain opens in the form and asks for it`() = runTest {
        store.save(SavedServer(host = "tower", share = "media", username = "sam"))
        val vm = vm()
        advanceUntilIdle()
        vm.connectSaved(vm.state.value.saved.single())
        advanceUntilIdle()
        assertNull(connected)
        assertEquals(1L, vm.state.value.editingId)
        assertEquals("smb://tower/media", vm.state.value.address)
        assertEquals("Enter the password for sam", vm.state.value.problem?.message)
    }

    @Test
    fun `a changed password on the NAS opens the row for editing, and a blank field keeps the stored one`() = runTest {
        fake.acceptedCredentials = SmbCredentials.Password("sam", "new")
        val saved = store.save(SavedServer(host = "tower", share = "media", username = "sam"))
        passwords.put(saved.id, "old")
        val vm = vm()
        advanceUntilIdle()
        vm.connectSaved(vm.state.value.saved.single())
        advanceUntilIdle()
        assertEquals("Sign-in failed", vm.state.value.problem?.message)
        assertEquals(saved.id, vm.state.value.editingId)

        vm.onPasswordChange("new")
        vm.connect()
        advanceUntilIdle()
        assertEquals(SmbCredentials.Password("sam", "new"), connected?.credentials)
        assertEquals("new", passwords.get(saved.id))
        assertEquals("the edit updated the row rather than adding one", 1, store.all().size)

        connected = null
        fake.acceptedCredentials = SmbCredentials.Password("sam", "new")
        vm.edit(vm.state.value.saved.single())
        vm.connect() // password field left blank
        advanceUntilIdle()
        assertEquals(SmbCredentials.Password("sam", "new"), connected?.credentials)
    }

    @Test
    fun `removing a row forgets the share and deletes its password`() = runTest {
        val saved = store.save(SavedServer(host = "tower", share = "media", username = "sam"))
        passwords.put(saved.id, "pw")
        val vm = vm()
        advanceUntilIdle()
        vm.askRemove(vm.state.value.saved.single())
        vm.confirmRemove()
        advanceUntilIdle()
        assertTrue(store.all().isEmpty())
        assertNull(passwords.get(saved.id))
        assertTrue(vm.state.value.saved.isEmpty())
        assertNull(vm.state.value.removing)
    }

    @Test
    fun `editing a field clears the last problem`() = runTest {
        val vm = vm()
        vm.connect()
        assertEquals("Enter the share's address", vm.state.value.problem?.message)
        vm.onAddressChange("t")
        assertNull(vm.state.value.problem)
    }
}
