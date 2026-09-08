package com.regolith

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.prefs.AppPreferences
import com.regolith.ui.navigation.RegolithKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state that outlives any single screen: which key the back stack
 * starts on. `null` means "still reading preferences" and keeps the splash up.
 *
 * A ViewModel is a store that survives rotation. This one is scoped to the
 * Activity, so it is created once per app session.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val startDestination: StateFlow<RegolithKey?> = prefs.onboardingDone
        .map<Boolean, RegolithKey?> { done -> if (done) RegolithKey.Home else RegolithKey.Onboarding }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }
}
