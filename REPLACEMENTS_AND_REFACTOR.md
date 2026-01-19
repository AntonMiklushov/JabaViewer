# Replacements and Refactor Plan

## Stage 1: Homemade-Code Candidates

| Candidate | Path(s) | What it does | Recommended replacement | Pros | Cons | Risk | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PDF rendering via third-party view | `app/src/main/java/com/example/jabaviewer/ui/screens/reader/ReaderScreen.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts` | Renders PDFs using `AndroidPdfViewer` (PDFium-based) inside Compose | Use platform `android.graphics.pdf.PdfRenderer` with a Compose-driven renderer | Official API, no native/PDFium dependency, aligns with security/compat requirements | Requires custom rendering/caching logic and more UI work | P1 | **Kept (reverted)**. User requested AndroidPdfViewer zoom behavior; PdfRenderer approach removed. |
| Manual URL concatenation | `app/src/main/java/com/example/jabaviewer/core/UrlUtils.kt`, usages in `CatalogRemoteSource.kt`, `DownloadDocumentWorker.kt` | Joins base URL and path via string trimming | Use OkHttp `HttpUrl` building (`toHttpUrl()` + `newBuilder().addPathSegments(...)`) | Correct encoding/normalization, avoids edge cases | Slightly more verbose; need to handle invalid base URLs | P2 | **Replaced (implemented)**. Now uses `HttpUrl` builder. |
| Manual byte-size formatting | `app/src/main/java/com/example/jabaviewer/ui/util/Formatters.kt` | Formats bytes into human-readable strings | `android.text.format.Formatter.formatShortFileSize` | Locale-aware, standardized output | Requires `Context`, complicates unit tests without Robolectric | P2 | **Keep** for now. Custom formatter is small, tested, and avoids adding test infra; revisit if UI formatting needs localization. |
| In-memory filtering/sorting of large lists | `app/src/main/java/com/example/jabaviewer/ui/screens/library/LibraryViewModel.kt` | Applies search/tag filtering and sorting in memory | Paging 3 + Room `PagingSource` | Scales to large catalogs, less memory | Significant UI and DAO changes | P2 | **Keep** in Stage 1. No evidence of large catalogs; may revisit in Stage 2 perf pass. |
| Custom decrypted-cache eviction | `app/src/main/java/com/example/jabaviewer/data/storage/DecryptedCacheManager.kt` | Evicts old decrypted files by size and mtime | DiskLruCache-style management | Automated eviction | DiskLruCache not a perfect fit for per-item dirs and “protected file” requirement | P2 | **Keep**. Domain-specific behavior (protected file) outweighs generic cache wins. |
| Manual OkHttp request wrapper | `app/src/main/java/com/example/jabaviewer/data/remote/CatalogRemoteSource.kt` | Fetches catalog with raw OkHttp call | Retrofit service interface | Declarative API, easier testing | Adds Retrofit dependency; single endpoint only | P2 | **Keep**. OkHttp is sufficient and already used. |

## New Dependencies (planned/added) and Versioning Strategy

- **Added (Stage 2):** `detekt`, `ktlint` (quality gates).
- **Added (Stage 2, test-only):** `mockwebserver`, `mockk`, `robolectric`, `androidx.test:core`, `room-testing`.
- **Kept:** `com.github.mhiew:AndroidPdfViewer` to retain zoom behavior.
- **No new runtime dependencies** added beyond existing OkHttp + Android framework APIs.

Versioning strategy:
- Keep all versions centralized in `gradle/libs.versions.toml`.
- Prefer stable releases; only update after local `test/lint/assembleRelease` pass.
- Compose stays on BOM to avoid mismatched artifacts.

## Refactor Plan and Status

Stage 1 (homemade-code replacements):
- Keep AndroidPdfViewer after reverting PdfRenderer reader due to zoom regression.
- Replace manual URL concatenation with OkHttp `HttpUrl` builder (done).
- Keep small formatter utility and in-memory filtering for now (documented rationale).

Stage 2 (production hardening) updates:
- Extracted resumable download logic into `ResumableDownloader` for testability.
- Added exponential backoff to downloads via WorkManager.
- Centralized PDF header validation in `core/isPdfValid`.
- Hardened base URL validation using OkHttp `HttpUrl`.
- Added detekt/ktlint configs, CI workflow, and new tests (crypto malformed inputs, downloader behavior, Room DAOs, ViewModel).
