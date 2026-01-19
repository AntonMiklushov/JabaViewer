package com.example.jabaviewer.data.local.relations

import androidx.room.Embedded
import androidx.room.Relation
import com.example.jabaviewer.data.local.entities.CatalogItemEntity
import com.example.jabaviewer.data.local.entities.LocalDocumentEntity

data class CatalogItemWithLocal(
    @Embedded val item: CatalogItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "catalogItemId",
    )
    val local: LocalDocumentEntity?,
)
