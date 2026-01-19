package com.example.jabaviewer.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jabaviewer.data.repository.LibraryRepository
import com.example.jabaviewer.data.repository.SettingsRepository
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.data.storage.DocumentStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

data class SettingsUiState(
    val baseUrl: String = "",
    val catalogPath: String = "",
    val passphraseInput: String = "",
    val readerMode: ReaderMode = ReaderMode.CONTINUOUS,
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = false,
    val orientationLock: OrientationLock = OrientationLock.SYSTEM,
    val decryptedCacheLimitMb: Int = 200,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val storage: DocumentStorage,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _state.value = _state.value.copy(
                    baseUrl = settings.baseUrl,
                    catalogPath = settings.catalogPath,
                    readerMode = settings.readerMode,
                    nightMode = settings.nightMode,
                    keepScreenOn = settings.keepScreenOn,
                    orientationLock = settings.orientationLock,
                    decryptedCacheLimitMb = settings.decryptedCacheLimitMb,
                )
            }
        }
    }

    fun updateBaseUrl(value: String) {
        _state.value = _state.value.copy(baseUrl = value)
    }

    fun updateCatalogPath(value: String) {
        _state.value = _state.value.copy(catalogPath = value)
    }

    fun updatePassphrase(value: String) {
        _state.value = _state.value.copy(passphraseInput = value)
    }

    fun saveSettings() {
        viewModelScope.launch {
            val validation = validateSourceSettings()
            if (validation != null) {
                _state.value = _state.value.copy(message = validation)
                return@launch
            }
            settingsRepository.updateBaseUrl(_state.value.baseUrl)
            settingsRepository.updateCatalogPath(_state.value.catalogPath)
            if (_state.value.passphraseInput.isNotBlank()) {
                settingsRepository.setPassphrase(_state.value.passphraseInput)
            }
            _state.value = _state.value.copy(
                message = "Settings saved",
                passphraseInput = "",
            )
        }
    }

    fun updateReaderMode(mode: ReaderMode) {
        viewModelScope.launch { settingsRepository.updateReaderMode(mode) }
    }

    fun updateNightMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateNightMode(enabled) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateKeepScreenOn(enabled) }
    }

    fun updateOrientationLock(lock: OrientationLock) {
        viewModelScope.launch { settingsRepository.updateOrientationLock(lock) }
    }

    fun updateCacheLimit(limitMb: Int) {
        viewModelScope.launch { settingsRepository.updateDecryptedCacheLimitMb(limitMb) }
    }

    fun clearDecryptedCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storage.clearDecryptedCache()
            }
            libraryRepository.clearDecryptedPaths()
            _state.value = _state.value.copy(message = "Decrypted cache cleared")
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storage.clearEncryptedFiles()
                storage.clearDecryptedCache()
            }
            libraryRepository.clearAllLocalDocuments()
            _state.value = _state.value.copy(message = "All downloads cleared")
        }
    }

    private fun validateSourceSettings(): String? {
        val baseUrl = _state.value.baseUrl.trim()
        if (baseUrl.isBlank()) return "Base URL is required"
        val parsed = baseUrl.toHttpUrlOrNull() ?: return "Base URL is invalid"
        if (parsed.scheme != "https") return "Base URL must use https"
        if (_state.value.catalogPath.isBlank()) return "Catalog path is required"
        return null
    }
}
