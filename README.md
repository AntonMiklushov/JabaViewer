this is pure vibecode

# Jaba Viewer (Phone)

Jaba Viewer is a phone-focused Android app that mirrors the Wear OS PDF library protocol and adds a richer browsing and reading experience for larger screens.

## Setup
- Open the app and enter:
  - Base URL (where your site is hosted)
  - Catalog path (default: `library/catalog.enc`)
  - Passphrase (used for local decryption only)
- Tap "Test connection" to verify the catalog decrypts.
- Tap "Continue" to sync and load your library.
- HTTPS is required (cleartext HTTP is blocked by default).

## Build and Run
- Unit tests: `./gradlew test`
- Lint: `./gradlew lint`
- Static analysis: `./gradlew detekt ktlintCheck`
- Debug build: `./gradlew assembleDebug`
- Release build: `./gradlew assembleRelease`
- Instrumentation (if needed): `./gradlew connectedAndroidTest`

## Configuration
- Base URL must be a valid `https://` URL.
- Catalog path is relative to the base URL.
- Passphrase is stored with AndroidX Security Crypto and never leaves device storage.

## Protocol Compatibility
The app is compatible with the reference LIB1 container format:
- Magic: `LIB1`
- Iterations: 4-byte big-endian unsigned int
- Salt: 16 bytes
- IV: 12 bytes
- Ciphertext: AES-256-GCM output (includes auth tag)
- Key derivation: PBKDF2(HMAC-SHA256, passphrase, salt, iterations, 256-bit key)

Catalog JSON must include `version`, `baseUrl`, and `items[]` with fields like `id`, `title`, `objectKey`, `size`, and `tags`.

## Storage Model
- Encrypted downloads: `files/remote/default/<objectKey>`
- Decrypted cache (on-demand): `no_backup/decrypted_cache/default/<itemId>/document.pdf`
- Decrypted cache is cleared via Settings and pruned to the configured size limit.

## Reader Notes
- AndroidPdfViewer-based rendering (PDFium-backed).
- Night mode uses a color-inversion matrix (no true reflow).
- Continuous scroll or single-page mode.

## Performance Notes
- Downloads stream to disk with resume and atomic replacement.
- Reader caches are pruned and page writes are throttled to reduce DB churn.
- Decrypted cache pruning skips the currently opened document.
- Release builds use R8 minify/shrink with keep rules for generated code.

## Architecture and Decisions
- Encrypted PDFs are stored in app-private storage; decrypted files are cached in `no_backup`.
- Decryption uses PBKDF2-HMAC-SHA256 and AES-256-GCM, matching the LIB1 format exactly.
- Downloads use WorkManager with resumable HTTP Range requests.
- Passphrase is stored with EncryptedSharedPreferences (AndroidX Security Crypto).
- UI is Jetpack Compose with Navigation-Compose for routing.

## Library Choices
- OkHttp for network calls and MockWebServer for download tests.
- Moshi for JSON parsing (reflection adapters).
- Room for catalog/local state persistence.
- WorkManager for background downloads.
- DataStore for settings persistence.
- Hilt for dependency injection.
- AndroidPdfViewer for PDF rendering and zoom behavior.

## Tests
- Unit tests include LIB1 decryption fixtures.
- Run: `./gradlew test lint detekt ktlintCheck assembleDebug assembleRelease`

## Security
- Passphrase is stored using AndroidX Security Crypto.
- Decrypted PDFs stay in app-private storage unless explicitly exported.
- Cleartext HTTP is disabled via Network Security Config.

## Changelog
- Hardened crypto parsing and added failure-mode tests.
- Made downloads resumable and atomic with throttled progress updates.
- Reduced reader jank with cache pruning and zoom-aware rendering.
