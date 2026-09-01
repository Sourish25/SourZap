# Project: SourZap Torrent Loading & UI Enhancements

## Architecture
SourZap is an Android BitTorrent client built with Jetpack Compose, Material 3, Kotlin Coroutines/Flow, and libtorrent4j.
The architecture comprises:
- **Torrent Engine Core**: `TorrentEngineManager` (`LibtorrentEngineManager`), `TorrentEngine`, `TorrentStorageHelper`, `TrackerInjector`, `TorrentIntentParser`, and `TorrentFileValidator` / `BencodeValidator`.
- **UI Layer**: `TorrentScreen`, `AddTorrentDialog`, `TorrentHeader`, `TorrentItemCard`, `TorrentSessionStatsBanner`, `DownloadsTorrentScanner`.
- **System Integration**: System File Picker (`ActivityResultContracts.OpenDocument`), MediaStore / Scoped Storage scanner, Intent and Deep Link filters.
- **Testing & Verification**: JUnit 4, Kotlinx Coroutines Test, comprehensive E2E requirement test suites (Tiers 1–5), Gradle build & release APK packaging.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Binary-Safe Bencode Validation | Pre-validate raw `.torrent` byte arrays without string conversion corruption; verify dictionary header/footer, required keys (`info`, `piece length`, `pieces`), and piece hash divisible by 20. | M1 | ORIGINAL_REQUEST R1 |
| F2 | HTML / Web-Blocker / Corrupt Payload Protection | Detect HTML (`<!DOCTYPE`, `<html`), HTTP 302 redirects, JSON errors, empty or truncated buffers, and return actionable typed error results. | M1 | ORIGINAL_REQUEST R1 |
| F3 | Port-443 HTTPS Tracker Injection into TorrentInfo | Automatically inject curated Port-443 HTTPS trackers (`TrackerInjector.HTTPS_PORT_443_TRACKERS`) directly into `TorrentInfo` instances before session download begins. | M1 | ORIGINAL_REQUEST R1 |
| F4 | Actionable Error Messages in UI & Engine | Display specific, user-friendly diagnostic error messages in `TorrentScreen` and `AddTorrentDialog` instead of generic crash or "error loading .torrent". | M1 | ORIGINAL_REQUEST R1 |
| F5 | Fix Intent Fallback Dummy Payload | Replace invalid dummy bencode in `TorrentIntentParser` with a structurally valid fallback or explicit failure handling. | M1 | ORIGINAL_REQUEST R1 |
| F6 | Filtered System File Picker | Upgrade system file picker to filter strictly for BitTorrent MIME types (`application/x-bittorrent`, `application/x-torrent`, `application/octet-stream`) and `.torrent` extensions. | M2 | ORIGINAL_REQUEST R2 |
| F7 | In-Dialog Downloads Torrent Scanner | Implement `DownloadsTorrentScanner` to query `MediaStore.Downloads` (API 29+) and filesystem fallback directories (API 26–35) with deduplication and sorting. | M2 | ORIGINAL_REQUEST R2 |
| F8 | In-Dialog Quick-Picker UI | Embed a quick-selection list in `AddTorrentDialog` showing discovered `.torrent` files with one-tap loading, size, and date info. | M2 | ORIGINAL_REQUEST R2 |
| F9 | Comprehensive Test Fixtures & Unit Tests | Synthetic test fixtures and tests for corrupted, valid single/multi-file, HTML-redirected buffers, and MIME contracts. | M3 | ORIGINAL_REQUEST R3 |
| F10 | Scoped Storage & Integration Tests | Test Scoped Storage streaming, MediaStore fallback, and multi-tier interaction scenarios. | M3 | ORIGINAL_REQUEST R3 |
| F11 | 100% Automated Test Suite Pass Rate | Execute all unit/integration/E2E test suites with 0 failures and 0 errors. | M3 | ORIGINAL_REQUEST R3 / Acceptance |
| F12 | Clean Signed Release APK Assembly | Build release APK cleanly with signing keys (`./gradlew assembleRelease`). | M3 | ORIGINAL_REQUEST R3 / Acceptance |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Robust .torrent File Loading & Exception Protection | F1, F2, F3, F4, F5: `BencodeValidator`, `TorrentEngineManager`, `TorrentIntentParser`, `TorrentScreen` error handling, Port-443 HTTPS tracker injection. | none | DONE |
| M2 | Filtered System File Picker & In-Dialog Downloads Quick-Picker | F6, F7, F8: `DownloadsTorrentScanner`, system file picker MIME filtering, and `AddTorrentDialog` UI integration. | M1 | DONE |
| M3 | Comprehensive Verification, E2E Test Suite & Clean Release APK Build | F9, F10, F11, F12: Test fixtures, unit/integration suites, 100% test pass rate, and `./gradlew assembleRelease`. | M1, M2 | DONE |

## Interface Contracts

### BencodeValidator ↔ TorrentEngineManager & TorrentScreen
- `BencodeValidator.validate(bytes: ByteArray): TorrentValidationResult`
- `sealed class TorrentValidationResult`:
  - `data class Valid(val name: String, val totalSize: Long, val isMultiFile: Boolean, val fileCount: Int, val pieceLength: Long, val pieceCount: Int, val infoHash: String?) : TorrentValidationResult`
  - `data class Invalid(val reason: String, val detailedMessage: String, val isHtmlPayload: Boolean = false) : TorrentValidationResult`

### TrackerInjector ↔ TorrentInfo & TorrentEngineManager
- `TrackerInjector.injectIntoTorrentInfo(torrentInfo: TorrentInfo): Unit`

### DownloadsTorrentScanner ↔ AddTorrentDialog
- `data class DiscoveredTorrentFile(val name: String, val size: Long, val lastModified: Long, val uri: Uri? = null, val file: File? = null)`
- `suspend fun scanDownloads(context: Context): List<DiscoveredTorrentFile>`

## Code Layout
- `app/src/main/java/com/sourzap/app/torrent/core/`
  - `TorrentEngineManager.kt` — Core BitTorrent session management and `.torrent` loading.
  - `TorrentFileValidator.kt` — Binary-safe bencode parser, 64-bit bounds check, validation logic.
  - `TorrentIntentParser.kt` — Deep link, Content URI, and Intent parsing with valid fallback payload.
  - `TrackerInjector.kt` — Port-443 HTTPS tracker injection.
  - `TorrentStorageHelper.kt` — Scoped Storage save directories.
  - `DownloadsTorrentScanner.kt` — Scans MediaStore (API 29+) and filesystem downloads with deduplication.
- `app/src/main/java/com/sourzap/app/ui/torrent/`
  - `TorrentScreen.kt` — Jetpack Compose UI, `AddTorrentDialog`, system file picker (`OpenDocument`), in-dialog scanner.
- `app/src/test/java/com/sourzap/app/`
  - `torrent/` — Unit tests for engine, validator, tracker injector, scanner, intent parser, and stress challenges.
  - `e2e/` — Requirement-driven E2E test suites (Tiers 1–5).
