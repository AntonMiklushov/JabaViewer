package com.example.jabaviewer.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DecryptedCacheManager @Inject constructor(
    private val storage: DocumentStorage,
) {
    suspend fun pruneCache(
        limitMb: Int,
        protectedFiles: Set<File> = emptySet(),
    ): List<String> = withContext(Dispatchers.IO) {
        val root = storage.decryptedRootDir()
        if (!root.exists()) return@withContext emptyList()
        val files = root.walkTopDown()
            .filter { it.isFile && !protectedFiles.contains(it) }
            .toList()
        val limitBytes = limitMb * 1024L * 1024L
        var totalBytes = files.sumOf { it.length() }
        if (totalBytes <= limitBytes) return@withContext emptyList()

        // Track evicted item ids so DB can drop stale cache paths.
        val evictedItems = mutableSetOf<String>()
        val sortedByAge = files.sortedBy { it.lastModified() }
        for (file in sortedByAge) {
            if (totalBytes <= limitBytes) break
            val size = file.length()
            if (file.delete()) {
                totalBytes -= size
                val parent = file.parentFile
                if (parent != null) {
                    val relative = parent.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart(File.separatorChar)
                    val itemId = relative.substringBefore(File.separatorChar)
                    if (itemId.isNotBlank()) {
                        evictedItems.add(itemId)
                    }
                    if (parent.listFiles().isNullOrEmpty()) {
                        parent.delete()
                    }
                }
            }
        }
        return@withContext evictedItems.toList()
    }
}
