package com.example.jabaviewer.data.repository

import com.example.jabaviewer.data.crypto.CryptoEngine
import com.example.jabaviewer.data.local.dao.CatalogDao
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.CatalogMetadataEntity
import com.example.jabaviewer.data.remote.CatalogRemoteSource
import com.example.jabaviewer.data.remote.model.CatalogPayload
import com.example.jabaviewer.data.security.PassphraseStore
import com.example.jabaviewer.data.settings.SettingsDataStore
import com.example.jabaviewer.core.DocumentFormat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.inject.Inject

class CatalogRepository @Inject constructor(
    private val remoteSource: CatalogRemoteSource,
    private val cryptoEngine: CryptoEngine,
    private val moshi: Moshi,
    private val catalogDao: CatalogDao,
    private val settingsDataStore: SettingsDataStore,
    private val passphraseStore: PassphraseStore,
) {
    suspend fun testConnection(
        baseUrl: String,
        catalogPath: String,
        passphrase: String,
    ): CatalogSyncResult {
        return runCatching {
            val payload = fetchCatalog(baseUrl, catalogPath, passphrase)
            CatalogSyncResult.Success(
                version = payload.version,
                itemCount = payload.items.size,
            )
        }.getOrElse { error ->
            CatalogSyncResult.Error(mapCatalogError(error), error)
        }
    }

    suspend fun syncCatalog(): CatalogSyncResult {
        val settings = settingsDataStore.settingsFlow.first()
        val passphrase = passphraseStore.getPassphrase()
        if (passphrase.isNullOrBlank()) {
            return CatalogSyncResult.Error("Passphrase is missing", null)
        }
        return runCatching {
            val payload = fetchCatalog(settings.baseUrl, settings.catalogPath, passphrase)
            persistCatalog(payload)
            CatalogSyncResult.Success(version = payload.version, itemCount = payload.items.size)
        }.getOrElse { error ->
            CatalogSyncResult.Error(mapCatalogError(error), error)
        }
    }

    private suspend fun fetchCatalog(
        baseUrl: String,
        catalogPath: String,
        passphrase: String,
    ): CatalogPayload = withContext(Dispatchers.IO) {
        val encrypted = remoteSource.fetchEncryptedCatalog(baseUrl, catalogPath)
        val decrypted = cryptoEngine.decryptToBytes(encrypted, passphrase.toCharArray())
        val json = decrypted.toString(StandardCharsets.UTF_8)
        val adapter = moshi.adapter(CatalogPayload::class.java)
        return@withContext checkNotNull(adapter.fromJson(json)) { "Catalog JSON is empty" }
    }

    private suspend fun persistCatalog(payload: CatalogPayload) {
        val now = System.currentTimeMillis()
        val entities = payload.items.map { item ->
            CatalogItemEntity(
                id = item.id,
                title = item.title,
                objectKey = item.objectKey,
                size = item.size,
                tags = item.tags.joinToString("|"),
                format = DocumentFormat.fromRaw(item.format).id,
                updatedAt = now,
            )
        }
        val metadata = CatalogMetadataEntity(
            version = payload.version,
            baseUrl = payload.baseUrl,
            lastSync = now,
            itemCount = payload.items.size,
        )
        catalogDao.replaceCatalog(entities, metadata)
    }

    private fun mapCatalogError(error: Throwable): String {
        return when (error) {
            is AEADBadTagException -> "Wrong passphrase or corrupted catalog"
            is JsonDataException -> "Catalog JSON is invalid"
            is IllegalArgumentException -> error.message ?: "Invalid catalog container"
            else -> error.message ?: "Catalog sync failed"
        }
    }
}

sealed class CatalogSyncResult {
    data class Success(val version: String, val itemCount: Int) : CatalogSyncResult()
    data class Error(val message: String, val cause: Throwable?) : CatalogSyncResult()
}
