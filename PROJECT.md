# Project: SourZap

## Architecture
SourZap is an Android application providing high-performance DPI bypass / VPN and anti-censorship BitTorrent downloading with libtorrent4j.
Key architectural layers:
- **UI Layer (Jetpack Compose & Navigation)**: `MainActivity`, `TorrentScreen`, `AddTorrentDialog`, `TorrentCard`, `SpeedBadge`, `FilterTabs`.
- **Torrent Engine Core**: `TorrentEngineManager` (`LibtorrentEngineManager`), `TorrentSessionConfig`, `TrackerInjector`, `DohTrackerResolver`, `MagnetHandler`, `TorrentModels`.
- **Services & Notifications**: `TorrentDownloadService` (Foreground Service with `dataSync`), `UpdateManager` (in-app APK update downloads), `SourZapApp` (Application lifecycle and notification channels).
- **Storage & System Integration**: Scoped storage app-specific directory fallback, Android Intent Filters for `magnet:` URIs and `.torrent` files, Android 13+ `POST_NOTIFICATIONS` runtime permission.

## Feature Inventory
| # | Feature | Description | Milestone | Source | Status |
|---|---------|-------------|-----------|--------|--------|
| F1 | Magnet Parsing & Normalization | Robust parsing and normalization of 40-char Hex and 32-char Base32 info-hashes to prevent metadata desynchronization | M1 | ORIGINAL_REQUEST §R1 | DONE |
| F2 | Scoped Storage Safe Directory | App-compatible writable directory resolution for Android 10+ (API 29-35) with fallback to eliminate EACCES native write failures | M1 | ORIGINAL_REQUEST §R1 | DONE |
| F3 | Torrent Session Auto-Start | Engine automatically starts session when `addTorrent` is invoked if session is not running | M1 | ORIGINAL_REQUEST §R1, §R3 | DONE |
| F4 | Engine State & Sequential Fixes | Fix `mapTorrentState` paused detection (`status.isPaused`) and `setSequentialDownload` native handle invocation | M1 | ORIGINAL_REQUEST §R3 | DONE |
| F5 | System Intent Filters Registration | Register `IntentFilter`s in `AndroidManifest.xml` for `scheme="magnet"`, MIME types, and `.torrent` extensions with `singleTask` launchMode | M2 | ORIGINAL_REQUEST §R2 | DONE |
| F6 | External Intent Handling & Deep Linking | Handle external intents in `MainActivity.onCreate` and `onNewIntent`, parse payload, and auto-navigate to `"torrents"` tab | M2 | ORIGINAL_REQUEST §R2 | DONE |
| F7 | Auto-Open Confirmation Dialog | `TorrentScreen` detects incoming intent payload and auto-opens `AddTorrentDialog` pre-filled with magnet URI or torrent file | M2 | ORIGINAL_REQUEST §R2 | DONE |
| F8 | SAF File Name Resolution | Query `OpenableColumns.DISPLAY_NAME` in .torrent file picker for accurate original file names | M2 | ORIGINAL_REQUEST §R1 | DONE |
| F9 | App Update Progress Notification | Live progress bar, downloaded / total MBs, Cancel action PendingIntent, completion install intent, and update notification channel | M3 | ORIGINAL_REQUEST §R4 | DONE |
| F10 | Active Torrent Progress & Dismiss Notification | Foreground service notification with live speeds, aggregate progress bar, Pause/Resume/Dismiss actions, and throttled updates | M3 | ORIGINAL_REQUEST §R4 | DONE |
| F11 | Android 13+ POST_NOTIFICATIONS Permission | Request runtime notification permission in `MainActivity` for Android 13+ (API 33+) | M3 | ORIGINAL_REQUEST §R4 | DONE |
| F12 | Full Test & Release Build Verification | 100% pass rate on full unit test suite (706/706 tests pass) and clean `assembleRelease` APK compilation | Final | ORIGINAL_REQUEST Acceptance Criteria | DONE |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| E2E | E2E Testing Track | Test infra, Tier 1-4 test suite derived from requirements, publish TEST_READY.md | none | DONE |
| M1 | Engine Core & Storage Fixes | F1, F2, F3, F4, DpiEngineTest lint fix | none | DONE |
| M2 | Intent Filters, Deep Linking & UI | F5, F6, F7, F8 | M1 | DONE |
| M3 | Rich Interactive Notifications & Permissions | F9, F10, F11 | M1 | DONE |
| Final | E2E Test Pass & Adversarial Hardening | F12 (Pass 100% E2E tests, Tier 5 Adversarial Coverage, assembleRelease) | E2E, M1, M2, M3 | DONE |

## Interface Contracts
### MainActivity ↔ TorrentScreen (Pending Torrent Intent)
- `MainActivity` exposes `pendingTorrentIntent: StateFlow<PendingTorrentIntent?>` or passes through `SourZapApp` / navigation arguments.
- `sealed class PendingTorrentIntent { data class Magnet(val uri: String, val name: String? = null) : PendingTorrentIntent(); data class TorrentFile(val bytes: ByteArray, val fileName: String) : PendingTorrentIntent() }`
- When `pendingTorrentIntent != null`, `MainActivity` navigates to `"torrents"` and `TorrentScreen` consumes the intent to display pre-filled `AddTorrentDialog`.

### TorrentEngineManager ↔ TorrentDownloadService
- `TorrentSessionStats` provides `totalDownloadSpeed`, `totalUploadSpeed`, `totalDownloadedBytes`, `totalUploadedBytes`, `activeTorrents`, `pausedTorrents`, `seedingTorrents`, `dhtNodes`, `totalBytes`, `aggregateProgress`.
- Actions supported by `TorrentDownloadService`: `ACTION_PAUSE_ALL`, `ACTION_RESUME_ALL`, `ACTION_STOP_SERVICE`.

### UpdateManager ↔ Notification Subsystem
- `UpdateState.Downloading(progress: Float, downloadedBytes: Long, totalBytes: Long)`
- `UpdateState.ReadyToInstall(apkFile: File)`
- `UpdateState.Cancelled` / `UpdateState.Error(message: String)`
- Action: `com.sourzap.app.ACTION_CANCEL_UPDATE` cancels ongoing download job.

## Code Layout
- `app/src/main/AndroidManifest.xml`: System Intent Filters, permissions (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`), singleTask launchMode.
- `app/src/main/res/values/strings.xml`: Notification channel strings.
- `app/src/main/java/com/sourzap/app/SourZapApp.kt`: Application lifecycle, notification channel setup, shared pending intent state.
- `app/src/main/java/com/sourzap/app/MainActivity.kt`: Intent handling (`onNewIntent`), deep link routing, runtime permissions (`POST_NOTIFICATIONS`).
- `app/src/main/java/com/sourzap/app/torrent/core/TorrentEngineManager.kt`: Engine lifecycle, session auto-start, state mapping, info-hash normalization.
- `app/src/main/java/com/sourzap/app/torrent/core/TorrentStorageHelper.kt`: Safe scoped storage directory resolution with 3-tier fallback.
- `app/src/main/java/com/sourzap/app/torrent/core/TorrentIntentParser.kt`: Unified external intent parser and SAF display name resolver.
- `app/src/main/java/com/sourzap/app/torrent/service/TorrentDownloadService.kt`: Active torrent foreground notifications, progress bar, dismiss action, update throttling.
- `app/src/main/java/com/sourzap/app/update/UpdateManager.kt`: App update download progress notifications, cancel intent, completion intent.
- `app/src/main/java/com/sourzap/app/update/UpdateCancelReceiver.kt`: BroadcastReceiver handling cancel button from notification shade.
- `app/src/main/java/com/sourzap/app/ui/torrent/TorrentScreen.kt`: UI dialog auto-open prefilling, file picker SAF resolution, save directory.
- `app/src/test/java/com/sourzap/app/...`: Comprehensive unit, E2E, and adversarial test suites (706 tests).
