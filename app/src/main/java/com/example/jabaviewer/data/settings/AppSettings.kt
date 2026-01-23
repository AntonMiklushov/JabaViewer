package com.example.jabaviewer.data.settings

data class AppSettings(
    val baseUrl: String,
    val catalogPath: String,
    val libraryLayout: LibraryLayout,
    val sortOrder: SortOrder,
    val readerMode: ReaderMode,
    val nightMode: Boolean,
    val keepScreenOn: Boolean,
    val orientationLock: OrientationLock,
    val decryptedCacheLimitMb: Int,
    val djvuConversionDpi: Int,
)

enum class LibraryLayout { LIST, GRID }

enum class SortOrder { RECENT, TITLE, SIZE, DOWNLOADED }

enum class ReaderMode { CONTINUOUS, SINGLE }

enum class OrientationLock { SYSTEM, PORTRAIT, LANDSCAPE }
