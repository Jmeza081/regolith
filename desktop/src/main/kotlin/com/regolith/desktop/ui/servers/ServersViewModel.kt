package com.regolith.desktop.ui.servers

import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.data.SavedServer
import com.regolith.desktop.ui.Problem
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.smb.SmbAddressParser
import com.regolith.domain.smb.SmbFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * State holder for the Servers screen: the remembered shares, and a form to
 * add or edit one.
 *
 * A plain class rather than an AndroidX `ViewModel`: the phone's ViewModel
 * exists to survive rotation and process death, which a desktop window does
 * not have. The shape is the same (one [StateFlow] down, functions up).
 * [scope] belongs to the screen and is cancelled when it leaves.
 *
 * Connecting means listing the share root once, so a wrong password or a
 * missing share fails here, on the screen that can fix it. A share is only
 * remembered after it connects, and its password only then goes to the
 * Keychain. Address parsing is the phone's own [SmbAddressParser].
 */
class ServersViewModel(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val onConnected: (Connection) -> Unit,
) {
    private val _state = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = _state.asStateFlow()

    init {
        scope.launch { reload() }
    }

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, problem = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, problem = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, problem = null) }

    /** Fill the form from a saved row. The password field starts blank: the stored one is never shown. */
    fun edit(server: SavedServer) = _state.update {
        it.copy(editingId = server.id, address = server.address, username = server.username, password = "", problem = null)
    }

    fun cancelEdit() = _state.update { it.copy(editingId = null, address = "", username = "", password = "", problem = null) }

    /** Connect with what the form says; remember the share once it works. */
    fun connect() {
        val s = _state.value
        if (s.busy) return
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
        val server = SavedServer(id = s.editingId ?: 0, host = parsed.host.host, port = parsed.host.port, share = share, username = s.username.trim())
        _state.update { it.copy(connecting = true, problem = null) }
        scope.launch {
            try {
                val password = when {
                    server.isGuest -> ""
                    s.password.isNotEmpty() -> s.password
                    s.editingId != null -> graph.credentials.get(s.editingId).orEmpty()
                    else -> ""
                }
                val connection = server.connection(password)
                graph.gateway.list(connection.host, connection.credentials, connection.share, "")
                val saved = graph.servers.save(server)
                rememberPassword(saved, password)
                reload()
                _state.update { it.copy(connecting = false, editingId = null, address = "", username = "", password = "") }
                onConnected(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(connecting = false, problem = e.toProblem()) }
            }
        }
    }

    /** Connect to a remembered share with its Keychain password. */
    fun connectSaved(server: SavedServer) {
        if (_state.value.busy) return
        _state.update { it.copy(connectingId = server.id, problem = null) }
        scope.launch {
            try {
                val password = if (server.isGuest) "" else graph.credentials.get(server.id)
                if (password == null) {
                    edit(server)
                    _state.update { it.copy(connectingId = null, problem = Problem("Enter the password for ${server.username}", "It is not in the Keychain.")) }
                    return@launch
                }
                val connection = server.connection(password)
                graph.gateway.list(connection.host, connection.credentials, connection.share, "")
                _state.update { it.copy(connectingId = null) }
                onConnected(connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmbFailure.AuthFailed) {
                // Most likely the password changed on the NAS: open the row
                // in the form so the new one can be typed.
                edit(server)
                _state.update { it.copy(connectingId = null, problem = Problem("Sign-in failed", "If the password changed, enter the new one.")) }
            } catch (e: Exception) {
                _state.update { it.copy(connectingId = null, problem = e.toProblem()) }
            }
        }
    }

    fun askRemove(server: SavedServer) = _state.update { it.copy(removing = server) }
    fun cancelRemove() = _state.update { it.copy(removing = null) }

    /** Forget the share and delete its password from the Keychain. */
    fun confirmRemove() {
        val server = _state.value.removing ?: return
        _state.update { it.copy(removing = null) }
        scope.launch {
            try {
                graph.servers.remove(server.id)
                graph.credentials.clear(server.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(problem = e.toProblem()) }
            }
            if (_state.value.editingId == server.id) cancelEdit()
            reload()
        }
    }

    private suspend fun reload() {
        val saved = try {
            graph.servers.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the saved servers", e)
            emptyList()
        }
        _state.update { it.copy(saved = saved.sortedBy { s -> s.label.lowercase() }, loaded = true) }
    }

    /**
     * The share already connected, so a Keychain failure must not stop the
     * user getting to their films; it is logged, and the next connect from
     * the row asks for the password again.
     */
    private suspend fun rememberPassword(server: SavedServer, password: String) {
        try {
            if (server.isGuest) graph.credentials.clear(server.id) else graph.credentials.put(server.id, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not store the password for server {} in the Keychain", server.id, e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger("Regolith/Servers")
    }
}
