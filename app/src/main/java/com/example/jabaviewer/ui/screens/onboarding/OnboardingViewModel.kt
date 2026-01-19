package com.example.jabaviewer.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.data.repository.CatalogRepository
import com.example.jabaviewer.data.repository.CatalogSyncResult
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val baseUrl: String,
    val catalogPath: String,
    val passphrase: String,
    val isLoading: Boolean = false,
    val testResult: TestResult? = null,
    val errorMessage: String? = null,
)

sealed class TestResult {
    data class Success(val version: String, val itemCount: Int) : TestResult()
    data class Error(val message: String) : TestResult()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            baseUrl = "",
            catalogPath = "",
            passphrase = "",
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                applySettings(settings)
            }
        }
    }

    private fun applySettings(settings: AppSettings) {
        _uiState.update { current ->
            current.copy(
                baseUrl = if (current.baseUrl.isBlank()) settings.baseUrl else current.baseUrl,
                catalogPath = if (current.catalogPath.isBlank()) settings.catalogPath else current.catalogPath,
            )
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value, testResult = null, errorMessage = null) }
    }

    fun updateCatalogPath(value: String) {
        _uiState.update { it.copy(catalogPath = value, testResult = null, errorMessage = null) }
    }

    fun updatePassphrase(value: String) {
        _uiState.update { it.copy(passphrase = value, testResult = null, errorMessage = null) }
    }

    fun testConnection() {
        val state = _uiState.value
        validateInputs(state)?.let { message ->
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, testResult = null) }
            val result = catalogRepository.testConnection(
                baseUrl = state.baseUrl,
                catalogPath = state.catalogPath,
                passphrase = state.passphrase,
            )
            _uiState.update { current ->
                when (result) {
                    is CatalogSyncResult.Success -> current.copy(
                        isLoading = false,
                        testResult = TestResult.Success(result.version, result.itemCount),
                    )
                    is CatalogSyncResult.Error -> current.copy(
                        isLoading = false,
                        testResult = TestResult.Error(result.message),
                    )
                }
            }
        }
    }

    fun saveAndSync(onComplete: () -> Unit) {
        val state = _uiState.value
        validateInputs(state)?.let { message ->
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            settingsRepository.updateBaseUrl(state.baseUrl)
            settingsRepository.updateCatalogPath(state.catalogPath)
            settingsRepository.setPassphrase(state.passphrase)
            val result = catalogRepository.syncCatalog()
            _uiState.update { current ->
                when (result) {
                    is CatalogSyncResult.Success -> current.copy(isLoading = false)
                    is CatalogSyncResult.Error -> current.copy(isLoading = false, errorMessage = result.message)
                }
            }
            if (result is CatalogSyncResult.Success) {
                onComplete()
            }
        }
    }

    private fun validateInputs(state: OnboardingUiState): String? {
        if (state.baseUrl.isBlank()) return "Base URL is required"
        if (!state.baseUrl.trim().startsWith("https://")) return "Base URL must start with https://"
        if (state.catalogPath.isBlank()) return "Catalog path is required"
        if (state.passphrase.isBlank()) return "Passphrase is required"
        return null
    }
}
