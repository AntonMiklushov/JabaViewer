# JabaViewer Agent Guide

This file is a high-level, practical map of the project so an LLM can understand the codebase without opening every file.

## What the app is
- Jaba Viewer is an Android app that mirrors the Wear OS PDF library protocol on phones.
- Users enter a base URL, catalog path, and passphrase to download an encrypted catalog and PDFs.
- PDFs are stored encrypted on disk, decrypted only on demand for viewing or explicit export.

## Tech stack
- Kotlin + Android Jetpack Compose UI
- Hilt for dependency injection
- Room for local persistence
- WorkManager for background downloads (resumable)
- OkHttp for networking
- Moshi for JSON parsing (reflection adapters, no codegen)
- AndroidX Security Crypto for passphrase storage
- PdfRenderer for PDF rendering

## Project layout
- `app/src/main/java/com/example/jabaviewer/`
  - `core/` helpers and constants
  - `data/` local DB, remote sources, repositories, security, settings, storage, crypto
  - `di/` Hilt modules
  - `domain/` app models used by UI
  - `ui/` Compose screens, navigation, view models
  - `workers/` WorkManager downloads
- `app/src/main/res/` resources, themes, icons
- `app/schemas/` Room schema snapshots
- Root: Gradle config, wrapper, README

## Key modules and responsibilities

### Core
- `app/src/main/java/com/example/jabaviewer/core/AppConstants.kt`
  - Defaults like `DEFAULT_BASE_URL`, `DEFAULT_CATALOG_PATH`, `DEFAULT_CACHE_LIMIT_MB`, `REMOTE_ID`.
- `app/src/main/java/com/example/jabaviewer/core/UrlUtils.kt`
  - `combineUrl` for base URL + path assembly.

### Crypto and container format
- `app/src/main/java/com/example/jabaviewer/data/crypto/CryptoEngine.kt`
  - Parses `LIB1` container: magic + iterations + salt + iv + AES-GCM ciphertext.
  - PBKDF2-HMAC-SHA256 derives 256-bit key.
  - Decrypts to bytes or files; writes to temp and renames atomically.
- `README.md` documents the LIB1 container format and catalog JSON requirements.

### Storage
- `app/src/main/java/com/example/jabaviewer/data/storage/DocumentStorage.kt`
  - Encrypted files: `files/remote/default/<objectKey>`
  - Decrypted cache: `no_backup/decrypted_cache/default/<itemId>/document.pdf`
  - Sanitizes keys to prevent traversal.
- `app/src/main/java/com/example/jabaviewer/data/storage/DecryptedCacheManager.kt`
  - Prunes decrypted cache to a size limit, evicting oldest files.
  - Skips protected files (e.g., current reader file).

### Remote data
- `app/src/main/java/com/example/jabaviewer/data/remote/CatalogRemoteSource.kt`
  - Fetches encrypted catalog using OkHttp.

### Repositories
- `app/src/main/java/com/example/jabaviewer/data/repository/CatalogRepository.kt`
  - Decrypts catalog, parses JSON via Moshi.
  - Stores catalog items + metadata into Room.
  - Validates catalog and maps errors to user-friendly messages.
- `app/src/main/java/com/example/jabaviewer/data/repository/LibraryRepository.kt`
  - Aggregates catalog + local document state.
  - Updates download and reading progress in Room.
- `app/src/main/java/com/example/jabaviewer/data/repository/DownloadRepository.kt`
  - Enqueues or cancels WorkManager download jobs.
- `app/src/main/java/com/example/jabaviewer/data/repository/SettingsRepository.kt`
  - Wraps `SettingsDataStore` and passphrase storage.

### Security
- `app/src/main/java/com/example/jabaviewer/data/security/PassphraseStore.kt`
  - Uses EncryptedSharedPreferences and MasterKey to store passphrase.

### Database
- `app/src/main/java/com/example/jabaviewer/data/local/AppDatabase.kt`
  - Room database with catalog and local document tables.
- `app/src/main/java/com/example/jabaviewer/data/local/dao/`
  - Catalog and local document DAOs.
- `app/src/main/java/com/example/jabaviewer/data/local/entities/`
  - Entities for catalog items, metadata, local doc status, bookmarks.

### Background downloads
- `app/src/main/java/com/example/jabaviewer/workers/DownloadDocumentWorker.kt`
  - Resumable download using HTTP Range.
  - Writes to a `.part` file, then atomically replaces the final file.
  - Updates progress in DB with throttling.
  - Retries on server/IO errors.

### Settings
- `app/src/main/java/com/example/jabaviewer/data/settings/SettingsDataStore.kt`
  - DataStore-backed settings for reader mode, night mode, cache limit, etc.

### Dependency injection
- `app/src/main/java/com/example/jabaviewer/di/AppModule.kt`, `DatabaseModule.kt`, `NetworkModule.kt`
  - OkHttp client, Room database, Moshi, CryptoEngine, repositories.
  - Moshi is configured for Kotlin reflection adapters.

### UI and navigation
- Navigation: `app/src/main/java/com/example/jabaviewer/ui/navigation/`
  - `Routes.kt` and `AppNavGraph.kt`.
- Main entry: `app/src/main/java/com/example/jabaviewer/MainActivity.kt`
  - Compose host.
- Start destination logic: `app/src/main/java/com/example/jabaviewer/ui/MainViewModel.kt`
  - If passphrase missing => onboarding; otherwise library.

#### Screens
- Onboarding: `app/src/main/java/com/example/jabaviewer/ui/screens/onboarding/`
  - Enter base URL, catalog path, passphrase.
  - Test connection + save and sync.
- Library: `app/src/main/java/com/example/jabaviewer/ui/screens/library/`
  - List/grid view, search, tags, sort.
  - Download, cancel download, open details.
- Item details: `app/src/main/java/com/example/jabaviewer/ui/screens/details/`
  - Open, remove download, and decrypt-save options.
  - Shows size, tags, last opened, object key.
- Reader: `app/src/main/java/com/example/jabaviewer/ui/screens/reader/`
  - PdfRenderer based, continuous or single-page.
  - Pinch-to-zoom, page caching, thumbnails.
  - Night mode uses color inversion.
- Settings: `app/src/main/java/com/example/jabaviewer/ui/screens/settings/`
  - Reader mode, night mode, keep screen on, orientation lock, cache limit, clear cache.

## Data flow: high level

### Onboarding and catalog sync
1. User inputs base URL, catalog path, passphrase.
2. `CatalogRepository` downloads encrypted catalog via OkHttp.
3. `CryptoEngine` decrypts and `Moshi` parses JSON.
4. Catalog items are stored in Room and shown in Library.

### Downloading a PDF
1. User taps download in Library.
2. `DownloadRepository` enqueues `DownloadDocumentWorker`.
3. Worker downloads encrypted PDF with resume, updates progress in Room.
4. Encrypted file is stored under `files/remote/default/<objectKey>`.

### Reading a PDF
1. Reader uses `DocumentStorage.decryptedFileFor(itemId)` to locate cache.
2. If missing or invalid, `CryptoEngine` decrypts from encrypted file.
3. `PdfDocumentController` renders pages to bitmaps with LRU caches.
4. Reading state is saved (page, last opened) in Room.

### Decrypt save (export)
- Item details screen lets user export a decrypted PDF via system save dialog.
- Decrypts if needed, writes to the chosen Uri, cleans temporary cache if created.

## Rendering and caching
- Pages are rendered at a target width and cached by page index + width.
- Zoom uses discrete render widths to keep text sharp.
- Thumbnails are separately cached and evicted.

## Networking
- OkHttp with timeouts and logging in debug builds.
- HTTPS required (cleartext HTTP blocked by network security config).

## Build and run
- Typical commands:
  - `./gradlew test`
  - `./gradlew lint`
  - `./gradlew assembleDebug`
  - `./gradlew assembleRelease`
- Release build uses R8 shrink/minify.

## Tests
- `app/src/test/java/` includes CryptoEngine tests and fixtures.
- Fixtures in `app/src/test/resources/fixtures/`.

## Resources and icons
- Launcher icons: `app/src/main/res/mipmap-*`.
- Adaptive icons: `app/src/main/res/mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml`.

## Important defaults
- Base URL: `https://example.com`
- Catalog path: `library/catalog.enc`
- Cache limit: `200 MB`

## Common pitfalls
- Passphrase missing => onboarding start.
- Decryption failures often mean wrong passphrase or corrupted container.
- Catalog JSON must match the expected schema (see README).
- Reader uses PdfRenderer; huge PDFs may stress memory if caches are too large.

## Current GitHub repo
- `https://github.com/AntonMiklushov/JabaViewer`
