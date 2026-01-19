package com.example.jabaviewer.data.remote

import com.example.jabaviewer.core.combineUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class CatalogRemoteSource @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetchEncryptedCatalog(baseUrl: String, catalogPath: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = combineUrl(baseUrl, catalogPath)
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Catalog request failed: ${response.code}")
                }
                response.body?.bytes() ?: throw IllegalStateException("Empty catalog response")
            }
        }
}
