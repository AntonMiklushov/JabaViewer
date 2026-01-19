package com.example.jabaviewer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalDocumentDao {
    @Query("SELECT * FROM local_documents WHERE catalogItemId = :itemId")
    fun observeLocalDocument(itemId: String): Flow<LocalDocumentEntity?>

    @Query("SELECT * FROM local_documents WHERE catalogItemId = :itemId")
    suspend fun getLocalDocument(itemId: String): LocalDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(localDocument: LocalDocumentEntity)

    @Query("DELETE FROM local_documents WHERE catalogItemId = :itemId")
    suspend fun delete(itemId: String)

    @Query("DELETE FROM local_documents")
    suspend fun clearAll()

    @Query("UPDATE local_documents SET decryptedCachePath = NULL")
    suspend fun clearDecryptedPaths()

    @Query("UPDATE local_documents SET decryptedCachePath = NULL WHERE catalogItemId IN (:itemIds)")
    suspend fun clearDecryptedPaths(itemIds: List<String>)
}
