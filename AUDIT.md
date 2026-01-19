# JabaViewer Audit Report

## Scope And Baseline
- Modules scanned: root Gradle build, `:app`, all `src/main`, `src/test`, `src/androidTest`, and build tooling files.
- Baseline commands attempted: `./gradlew test lint assembleDebug assembleRelease`
  - Initial run failed because `Theme.Material3.DayNight.NoActionBar` was missing (Material Components dependency).
  - Fixed by adding the Material Components dependency and enabling release minify/shrink rules (see Implemented section).
  - Final run succeeded after fixes.

## Codebase Map
### Modules And Responsibilities
- `:app`
  - `core/`: app constants and URL utilities.
  - `data/crypto/`: LIB1 container parsing and AES-GCM decryption.
  - `data/remote/`: catalog fetch via OkHttp.
  - `data/local/`: Room database entities/DAOs/relations.
  - `data/storage/`: encrypted file storage + decrypted cache.
  - `data/repository/`: orchestrates sync, downloads, and settings.
  - `data/settings/`: DataStore preferences.
  - `data/security/`: passphrase store (EncryptedSharedPreferences).
  - `workers/`: download worker.
  - `ui/`: Compose screens, view models, theme, navigation.
  - `domain/`: UI-ready models.

### Dependency Map (High Level)
`UI (Compose + ViewModels)`
-> `Repositories (Settings/Catalog/Library/Download)`
-> `Data Sources (Room, OkHttp, CryptoEngine, Storage)`
-> `Workers (WorkManager download flow)`

### Key Flows
1. Onboarding → save base URL/catalog path/passphrase → catalog sync.
2. Catalog sync → decrypt catalog → persist metadata/items → library list.
3. Download → WorkManager fetch + resume + atomic write → DB progress.
4. Reader → decrypt on demand → PdfRenderer render → navigation/zoom.

## Findings
### Bugs / Correctness
- `app/src/main/java/com/example/jabaviewer/ui/util/Formatters.kt`: `formatBytes()` crashed for values `< 1024` (negative index into prefix string).
- `app/src/main/java/com/example/jabaviewer/data/crypto/CryptoEngine.kt`: iteration parsing treated unsigned bytes as signed; no ciphertext length guard.
- `app/src/main/java/com/example/jabaviewer/workers/DownloadDocumentWorker.kt`: resume path could accept invalid ranges; non-2xx responses could be misclassified as success.
- `app/src/main/java/com/example/jabaviewer/ui/screens/reader/ReaderViewModel.kt`: heavy I/O (decrypt + PdfRenderer setup) ran on main; frequent DB writes on scroll.
- `app/src/main/java/com/example/jabaviewer/data/storage/DecryptedCacheManager.kt`: pruning could delete the file currently being opened.

### Performance
- Reader zoom triggered re-render storms (every scale change re-rendered full-width bitmaps).
- Thumbnail and page bitmap maps were unbounded, pinning large bitmaps.
- Download progress updates were unthrottled, causing excessive DB writes.
- Settings cache slider updated DataStore on every move.

### Security
- `app/src/main/AndroidManifest.xml`: cleartext HTTP was allowed.
- `app/src/main/java/com/example/jabaviewer/data/crypto/CryptoEngine.kt`: passphrase and key material were not wiped after use.
- Decrypt-to-file used direct writes (risking partial plaintext on failure).

### UX / Accessibility
- Pinch-to-zoom did not visually scale pages (only re-rendered at a larger resolution).
- Page slider triggered immediate scroll for every drag step (janky for long documents).
- Onboarding/settings allowed invalid base URLs (HTTP) without feedback.

### Architecture / Maintainability
- `app/src/main/java/com/example/jabaviewer/data/repository/CatalogRepository.kt`: catalog sync was not transactional (clear + insert could briefly show empty list).
- `app/src/main/java/com/example/jabaviewer/di/DatabaseModule.kt`: destructive migrations were enabled by default.
- `app/src/main/java/com/example/jabaviewer/data/storage/DocumentStorage.kt`: path sanitization allowed ambiguous segments.

## Prioritized Fix Plan
### P0 (Correctness/Security)
- Harden LIB1 header parsing; validate ciphertext length; wipe key material.
- Enforce HTTPS via Network Security Config (disable cleartext).
- Make download writes atomic with resume validation and truncation checks.
- Move heavy reader I/O off the main thread.
- Fix `formatBytes()` crash for small values.

### P1 (Performance/UX)
- Throttle download progress DB writes.
- Debounce reader page persistence.
- Apply zoom scaling visually and quantize render resolution to reduce jank.
- Cap thumbnail cache and prune page bitmap window.
- Apply atomic catalog updates.

### P2 (Follow-up)
- Paging for large catalogs.
- Additional instrumentation tests (reader + WorkManager).
- Room indices for very large libraries (requires migration).

## Implemented Changes (P0/P1)
- Crypto hardening: unsigned iteration parsing, ciphertext length check, bounded PBKDF2 iterations, passphrase/key wiping, temp-file decrypt writes.
- Network security: cleartext HTTP disabled and network security config added.
- Downloads: validated resume range, atomic rename/copy, IO syncing, truncation checks, progress throttling, retry classification.
- Reader: I/O moved to background, zoom scaling applied, render width quantized, cache pruning, debounce persistence, thumbnail LruCache, safe teardown.
- Data: catalog updates made transactional, destructive migrations removed, cache pruning now protects the active document and clears stale DB paths.
- UI: slider updates on release, list/grid items keyed, settings cache slider debounced.
- Tests: expanded LIB1/crypto tests and added formatter regression tests.

## Modified Key Files
- `app/src/main/java/com/example/jabaviewer/data/crypto/CryptoEngine.kt`
- `app/src/main/java/com/example/jabaviewer/workers/DownloadDocumentWorker.kt`
- `app/src/main/java/com/example/jabaviewer/ui/screens/reader/ReaderViewModel.kt`
- `app/src/main/java/com/example/jabaviewer/ui/screens/reader/ReaderScreen.kt`
- `app/src/main/java/com/example/jabaviewer/data/repository/CatalogRepository.kt`
- `app/src/main/java/com/example/jabaviewer/data/local/dao/CatalogDao.kt`
- `app/src/main/java/com/example/jabaviewer/data/storage/DecryptedCacheManager.kt`
- `app/src/main/java/com/example/jabaviewer/di/NetworkModule.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/network_security_config.xml`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `README.md`

## Remaining Risks / Gaps
- HTTP catalogs now require HTTPS; users still using HTTP will need to migrate or add a dev-only override.
- No paging for very large catalogs; memory may still spike for huge libraries.
- Instrumentation tests are still minimal; reader + WorkManager behavior is not covered by device tests yet.
