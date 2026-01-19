package com.example.jabaviewer.data.local

import androidx.room.TypeConverter
import com.example.jabaviewer.data.local.entities.DownloadState

class Converters {
    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.NOT_DOWNLOADED)
}
