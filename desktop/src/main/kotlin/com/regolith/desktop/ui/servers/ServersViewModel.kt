package com.regolith.desktop.ui.servers

import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.ui.Problem
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.smb.SmbAddressParser
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.fromFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the Servers screen: type an address, connect, move on.
 *
 * A plain class rather than an AndroidX `ViewModel`: the phone's ViewModel
 * exists to survive rotation and process death, which a desktop window does
 * not have. The shape is the same (one [StateFlow] down, functions up), so
 * a screen reads it the way a phone screen does. [scope] belongs to the
 * screen and is cancelled when it leaves.
 *
 * Address parsing is the phone's own [SmbAddressParser]. Connecting means
 * listing the share root once, so a wrong password or a missing share fails
 * here, on the screen that can fix it, not on the next one.
 */
class ServersViewModel(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val onConnected: (Connection) -> Unit,
) {
    private val _state = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = _state.asStateFlow()

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, problem = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, problem = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, problem = null) }

    fun connect() {
        val s = _state.value
        if (s.connecting) return
        val parsed = SmbAddressParser.parse(s.address)
        if (parsed == null) {
            _state.update { it.copy(problem = Problem("Enter the share's address", "For example smb://192.168.1.24/media")) }
            return
        }
        val share = parsed.share
        if (share == null) {
            // The phone can list a server's shares; jcifs cannot on a
            // non-default port, and one share is what an editor opens anyway.
            _state.update { it.copy(problem = Problem("Add the share's name to the address", "For example smb://${parsed.host.host}/media")) }
            return
        }
        val connection = Connection(parsed.host, SmbCredentials.fromFields(s.username, s.password), share)
        _state.update { it.copy(connecting = true, problem = null) }
        scope.launch {
            try {
                graph.gateway.list(connection.host, connection.credentials, connection.share, "")
                _state.update { it.copy(connecting = false) }
                onConnected(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connecting = false, problem = e.toProblem()) }
            }
        }
    }
}
