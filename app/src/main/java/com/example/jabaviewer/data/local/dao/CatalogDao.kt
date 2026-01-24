package com.example.jabaviewer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.CatalogMetadataEntity
import com.example.jabaviewer.data.local.relations.CatalogItemWithLocal
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Transaction
    @Query("SELECT * FROM catalog_items")
    fun observeLibraryItems(): Flow<List<CatalogItemWithLocal>>

    @Query("SELECT * FROM catalog_items WHERE id = :itemId")
    suspend fun getCatalogItem(itemId: String): CatalogItemEntity?

    @Query("SELECT id, format FROM catalog_items")
    suspend fun getItemFormats(): List<CatalogItemFormat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<CatalogItemEntity>)

    @Query("UPDATE catalog_items SET format = :format WHERE id = :itemId")
    suspend fun updateFormat(itemId: String, format: String)

    @Query("DELETE FROM catalog_items")
    suspend fun clearItems()

    @Query("SELECT * FROM catalog_metadata WHERE id = 1")
    fun observeMetadata(): Flow<CatalogMetadataEntity?>

    @Query("SELECT * FROM catalog_metadata WHERE id = 1")
    suspend fun getMetadata(): CatalogMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: CatalogMetadataEntity)

    @Transaction
    suspend fun replaceCatalog(
        items: List<CatalogItemEntity>,
        metadata: CatalogMetadataEntity,
    ) {
        // Keep catalog updates atomic to avoid UI flicker on refresh.
        clearItems()
        upsertItems(items)
        upsertMetadata(metadata)
    }
}

data class CatalogItemFormat(
    val id: String,
    val format: String,
)
