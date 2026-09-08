package com.regolith.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.smb.SmbAddressParser
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manual entry -> connecting -> share picker (design section 03).
 * Nothing is written until the server answers; a failed attempt leaves no
 * half-added server behind.
 */
@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val sources: SourceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddServerUiState())
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    private var connectJob: Job? = null

    fun onAddressChange(value: String) = _uiState.update { it.copy(address = value, addressError = null) }
    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onSaveCredentialsChange(value: Boolean) = _uiState.update { it.copy(saveCredentials = value) }

    /** Connect with whatever is in the fields; empty username means guest. */
    fun connect() {
        val s = _uiState.value
        val credentials = if (s.isGuest) SmbCredentials.Guest else SmbCredentials.Password(s.username.trim(), s.password)
        connect(credentials)
    }

    /** "Connect as guest" on the sign-in-failed screen: drop the typed user. */
    fun connectAsGuest() {
        _uiState.update { it.copy(username = "", password = "") }
        connect(SmbCredentials.Guest)
    }

    fun cancelConnect() {
        connectJob?.cancel()
        _uiState.update { it.copy(phase = AddServerUiState.Phase.Editing) }
    }

    /** The screen calls this after navigating on, so a back press does not re-navigate. */
    fun consumeNavigation() = _uiState.update { it.copy(connectedServerId = null, phase = AddServerUiState.Phase.Editing) }

    private fun connect(credentials: SmbCredentials) {
        val s = _uiState.value
        val parsed = SmbAddressParser.parse(s.address)
        if (parsed == null) {
            _uiState.update { it.copy(addressError = "Enter a server name or address, like smb://192.168.1.24") }
            return
        }
        connectJob?.cancel()
        _uiState.update { it.copy(phase = AddServerUiState.Phase.Connecting, error = null) }
        connectJob = viewModelScope.launch {
            try {
                val serverId = sources.connect(parsed, credentials, saveCredentials = s.saveCredentials)
                _uiState.update { it.copy(phase = AddServerUiState.Phase.Editing, connectedServerId = serverId) }
            } catch (e: SmbFailure) {
                _uiState.update { it.copy(phase = AddServerUiState.Phase.Failed, error = messageFor(e, credentials)) }
            }
        }
    }

    private fun messageFor(e: SmbFailure, credentials: SmbCredentials): String = when (e) {
        is SmbFailure.AuthFailed ->
            if (credentials is SmbCredentials.Guest) "This server does not allow guests. Sign in with a username and password."
            else "That username and password didn't work. Check them and try again."
        is SmbFailure.Unreachable -> "${e.message}. Check the server is awake and on the same Wi-Fi as this phone."
        is SmbFailure.NotFound -> "Nothing answered at that address. Check it and try again."
        is SmbFailure.Other -> e.message ?: "Couldn't connect."
    }
}
