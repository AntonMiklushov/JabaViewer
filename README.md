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
- PdfRenderer-based rendering (no native/PDFium).
- Night mode uses a color-inversion matrix (no true reflow).
- Continuous scroll or single-page mode.
- Pinch-to-zoom scales the view and only re-renders at discrete steps to reduce jank.

## Performance Notes
- Downloads stream to disk with resume and atomic replacement.
- Reader caches are pruned and page writes are throttled to reduce DB churn.
- Decrypted cache pruning skips the currently opened document.
- Release builds use R8 minify/shrink with keep rules for generated code.

## Tests
- Unit tests include LIB1 decryption fixtures.
- Run: `./gradlew test lint assembleDebug assembleRelease`
- Instrumentation (if needed): `./gradlew connectedAndroidTest`

## Security
- Passphrase is stored using AndroidX Security Crypto.
- Decrypted PDFs stay in app-private storage unless explicitly exported.
- Cleartext HTTP is disabled via Network Security Config.

## Changelog
- Hardened crypto parsing and added failure-mode tests.
- Made downloads resumable and atomic with throttled progress updates.
- Reduced reader jank with cache pruning and zoom-aware rendering.
