package com.example.jabaviewer.ui

import androidx.lifecycle.ViewModel
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val startDestination: String =
        if (settingsRepository.getPassphrase().isNullOrBlank()) {
            Routes.Onboarding
        } else {
            Routes.Library
        }
}
