package com.example.jabaviewer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.jabaviewer.core.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.settingsDataStore

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val catalogPath = stringPreferencesKey("catalog_path")
        val libraryLayout = stringPreferencesKey("library_layout")
        val sortOrder = stringPreferencesKey("sort_order")
        val readerMode = stringPreferencesKey("reader_mode")
        val nightMode = booleanPreferencesKey("night_mode")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val orientationLock = stringPreferencesKey("orientation_lock")
        val decryptedCacheLimitMb = intPreferencesKey("decrypted_cache_limit_mb")
    }

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[Keys.baseUrl] ?: AppConstants.DEFAULT_BASE_URL,
            catalogPath = prefs[Keys.catalogPath] ?: AppConstants.DEFAULT_CATALOG_PATH,
            libraryLayout = prefs[Keys.libraryLayout]?.let { runCatching { LibraryLayout.valueOf(it) }.getOrNull() }
                ?: LibraryLayout.LIST,
            sortOrder = prefs[Keys.sortOrder]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.RECENT,
            readerMode = prefs[Keys.readerMode]?.let { runCatching { ReaderMode.valueOf(it) }.getOrNull() }
                ?: ReaderMode.CONTINUOUS,
            nightMode = prefs[Keys.nightMode] ?: false,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: false,
            orientationLock = prefs[Keys.orientationLock]?.let { runCatching { OrientationLock.valueOf(it) }.getOrNull() }
                ?: OrientationLock.SYSTEM,
            decryptedCacheLimitMb = prefs[Keys.decryptedCacheLimitMb] ?: AppConstants.DEFAULT_CACHE_LIMIT_MB,
        )
    }

    suspend fun updateBaseUrl(value: String) {
        dataStore.edit { it[Keys.baseUrl] = value.trim() }
    }

    suspend fun updateCatalogPath(value: String) {
        dataStore.edit { it[Keys.catalogPath] = value.trim().trimStart('/') }
    }

    suspend fun updateLibraryLayout(value: LibraryLayout) {
        dataStore.edit { it[Keys.libraryLayout] = value.name }
    }

    suspend fun updateSortOrder(value: SortOrder) {
        dataStore.edit { it[Keys.sortOrder] = value.name }
    }

    suspend fun updateReaderMode(value: ReaderMode) {
        dataStore.edit { it[Keys.readerMode] = value.name }
    }

    suspend fun updateNightMode(value: Boolean) {
        dataStore.edit { it[Keys.nightMode] = value }
    }

    suspend fun updateKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.keepScreenOn] = value }
    }

    suspend fun updateOrientationLock(value: OrientationLock) {
        dataStore.edit { it[Keys.orientationLock] = value.name }
    }

    suspend fun updateDecryptedCacheLimitMb(value: Int) {
        dataStore.edit { it[Keys.decryptedCacheLimitMb] = value.coerceIn(50, 1000) }
    }
}
