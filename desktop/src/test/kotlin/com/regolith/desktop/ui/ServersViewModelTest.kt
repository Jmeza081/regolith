package com.regolith.desktop.ui

import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.ui.servers.ServersViewModel
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersViewModelTest {
    private val fake = FakeSmbGateway().apply { addFile("media", "Films/Heat.1995.mkv", ByteArray(10)) }
    private var connected: Connection? = null

    private fun TestScope.vm() = ServersViewModel(AppGraph(fake), this) { connected = it }

    @Test
    fun `an address with a share connects as guest when the username is blank`() = runTest {
        val vm = vm()
        vm.onAddressChange("smb://localhost:1445/media")
        vm.connect()
        advanceUntilIdle()
        assertEquals(Connection(SmbHost("localhost", 1445), SmbCredentials.Guest, "media"), connected)
        assertFalse(vm.state.value.connecting)
        assertNull(vm.state.value.problem)
    }

    @Test
    fun `a Windows-style address and a domain username are understood`() = runTest {
        val vm = vm()
        vm.onAddressChange("""\\tower\media""")
        vm.onUsernameChange("""HOME\sam""")
        vm.onPasswordChange("pw")
        vm.connect()
        advanceUntilIdle()
        assertEquals(Connection(SmbHost("tower"), SmbCredentials.Password("sam", "pw", domain = "HOME"), "media"), connected)
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
    }

    @Test
    fun `a refused sign-in stays on the screen with the phone's wording`() = runTest {
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
