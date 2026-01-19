package com.example.jabaviewer.data.repository

import com.example.jabaviewer.data.security.PassphraseStore
import com.example.jabaviewer.data.settings.AppSettings
import com.example.jabaviewer.data.settings.LibraryLayout
import com.example.jabaviewer.data.settings.OrientationLock
import com.example.jabaviewer.data.settings.ReaderMode
import com.example.jabaviewer.data.settings.SettingsDataStore
import com.example.jabaviewer.data.settings.SortOrder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@Suppress("TooManyFunctions")
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val passphraseStore: PassphraseStore,
) {
    val settingsFlow: Flow<AppSettings> = settingsDataStore.settingsFlow

    fun getPassphrase(): String? = passphraseStore.getPassphrase()

    fun setPassphrase(value: String) {
        passphraseStore.setPassphrase(value)
    }

    fun clearPassphrase() {
        passphraseStore.clear()
    }

    suspend fun updateBaseUrl(value: String) = settingsDataStore.updateBaseUrl(value)
    suspend fun updateCatalogPath(value: String) = settingsDataStore.updateCatalogPath(value)
    suspend fun updateLibraryLayout(value: LibraryLayout) = settingsDataStore.updateLibraryLayout(value)
    suspend fun updateSortOrder(value: SortOrder) = settingsDataStore.updateSortOrder(value)
    suspend fun updateReaderMode(value: ReaderMode) = settingsDataStore.updateReaderMode(value)
    suspend fun updateNightMode(value: Boolean) = settingsDataStore.updateNightMode(value)
    suspend fun updateKeepScreenOn(value: Boolean) = settingsDataStore.updateKeepScreenOn(value)
    suspend fun updateOrientationLock(value: OrientationLock) = settingsDataStore.updateOrientationLock(value)
    suspend fun updateDecryptedCacheLimitMb(value: Int) = settingsDataStore.updateDecryptedCacheLimitMb(value)
}
