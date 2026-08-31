# Project: SourZap BitTorrent Anti-Censorship Downloader

## Architecture
- **Embedded Native Torrent Engine**: Integrates `libtorrent4j:2.1.0-39` with native NDK libraries across 4 Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
- **Strict Anti-Firewall / Anti-DPI Transport**: Enforces pure TCP (disabling uTP) and full-payload RC4 protocol encryption (`pe_forced`, `prefer_rc4 = true`), eliminating plaintext BitTorrent wire signatures.
- **High-Throughput Swarm Saturation**: Configures 500 connections limit, 4000 peer list, 60 connect boost, 1500 queue depth, 64MB disk cache, 1MB TX / 2MB RX socket buffers, and 4 AIO threads.
- **Port-443 HTTPS Tracker Auto-Injection & DoH**: Automatically appends 20+ verified HTTPS trackers on port 443 to all torrents/magnets and pre-resolves tracker domains via SourZap's asynchronous `DohResolver`.
- **Background Foreground Service**: Manages background downloading with Android 14/15 `dataSync` foreground service, persistent speed/progress notification, and interactive actions (Pause All, Resume All, Cancel).
- **Single-Process Coexistence**: Seamlessly coordinates with `SourZapVpnService` (`addDisallowedApplication` bypass) without routing loops or socket leaks.
- **Material 3 Expressive UI**: Introduces a 5th navigation tab ("Torrents") to `FloatingExpressiveDock` and `MainActivity`, with expressive card telemetry, clipboard auto-detection, multi-file priority tree selection, custom SAF storage picker, and action controls.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Build Dependencies & ABIs | `libtorrent4j:2.1.0-39` dependencies, 4 Android ABIs, ProGuard rules, packaging options | M1 | Survey / R1 |
| F2 | Core Engine & Session Settings | `TorrentEngineManager` lifecycle, `SessionManager`, `SessionSettings` initialization | M1 | R1 |
| F3 | Pure TCP Enforcement | Disable incoming/outgoing uTP, enable TCP only to defeat UDP throttling | M1 | R1 |
| F4 | Full RC4 Protocol Encryption | `out_enc_policy = pe_forced`, `in_enc_policy = pe_forced`, `pe_rc4`, `prefer_rc4 = true` | M1 | R1 |
| F5 | Max-Throughput Swarm Tuning | 500 connections, 4000 peer list, 1500 queue depth, 64MB cache, 1MB/2MB socket buffers, 4 aio threads | M1 | R1 |
| F6 | HTTPS Port-443 Tracker Injection | Curated list of 20+ verified HTTPS trackers operating strictly on port 443 auto-injected into magnets and torrents | M2 | R2 |
| F7 | DoH Tracker Pre-Resolution | Asynchronous pre-resolution of tracker hostnames via `DohResolver` to defeat DNS poisoning and cache lookups | M2 | R1, R2 |
| F8 | Magnet Metadata Handling | Rapid `ut_metadata` recovery and parsing with timeout handling in harsh networks | M2 | R2 |
| F9 | Single-Process VPN Coordination | Clean coroutine dispatchers, thread-safe session management, zero socket leaks, VPN exclusion | M2 | R3 |
| F10 | Background Foreground Service | `TorrentDownloadService` with `dataSync` type, persistent notification, speed/progress, action intents (Pause/Resume/Cancel) | M3 | R5 |
| F11 | Torrent Repository & State Flow | `TorrentRepository` exposing reactive `StateFlow` of torrent items, files, and aggregated stats to UI/Service | M3 | R4, R5 |
| F12 | 5th Navigation Dock Tab | Add "Torrents" tab with `Icons.Rounded.CloudDownload` to `FloatingExpressiveDock` and `MainActivity` | M4 | R4 |
| F13 | Active Torrents Dashboard & Cards | Expressive cards with download/upload gauges, progress bars, ETA, seeds/peers, share ratio, filter tabs | M4 | R4 |
| F14 | Add Torrent Workflow | Clipboard magnet auto-detect bottom sheet + SAF file picker for `.torrent` files | M4 | R4 |
| F15 | Multi-File Selection & Priorities | Interactive file tree bottom sheet to selectively set file priorities (Download / Skip) | M4 | R4 |
| F16 | Storage Directory Picker | Android SAF folder picker with fallback to standard `Downloads/SourZap/` | M4 | R4 |
| F17 | Torrent Actions & Controls | Pause, Resume, Delete (with optional file purge), Recheck hash, Copy magnet URI | M4 | R4 |
| F18 | Test Suite & Release Verification | Unit tests covering all subsystems, 100% pass on `./gradlew.bat testDebugUnitTest`, clean `./gradlew.bat assembleRelease` | M5 | Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Engine Core & libtorrent4j Integration | Gradle setup, native ABIs, ProGuard, `TorrentSessionConfig`, `TorrentEngineManager`, pure TCP, RC4 encryption, throughput tuning | none | PLANNED |
| M2 | Anti-Censorship Tracker & Socket Subsystem | HTTPS port-443 tracker auto-injector, DoH pre-resolution, magnet handler, VPN coordination | M1 | PLANNED |
| M3 | Background Service & Torrent Repository | `TorrentDownloadService`, notification controller, `TorrentRepository`, reactive state flows, Android 14/15 dataSync | M1, M2 | PLANNED |
| M4 | Material 3 Expressive UI & 5th Navigation Tab | 5th tab in `FloatingExpressiveDock`, `TorrentsScreen`, `TorrentCard`, `AddTorrentSheet`, `TorrentDetailsSheet`, `TorrentViewModel` | M3 | PLANNED |
| M5 | Test Suite & Release Verification | Unit & integration test suites (Tiers 1-4), `./gradlew.bat testDebugUnitTest` 100% pass, `./gradlew.bat assembleRelease` | M1, M2, M3, M4 | PLANNED |

## Interface Contracts

### `TorrentEngineManager` ↔ `TorrentRepository`
```kotlin
interface TorrentEngineManager {
    fun startSession(context: Context)
    fun stopSession()
    fun addTorrent(torrentSource: TorrentSource, saveDir: File, filePriorities: List<Priority>? = null): String
    fun pauseTorrent(id: String)
    fun resumeTorrent(id: String)
    fun removeTorrent(id: String, deleteFiles: Boolean)
    fun recheckTorrent(id: String)
    fun setFilePriority(id: String, fileIndex: Int, priority: Priority)
    fun getTorrentInfo(id: String): TorrentInfo?
    fun observeTorrents(): StateFlow<List<TorrentItem>>
    fun observeStats(): StateFlow<TorrentSessionStats>
}
```

### `TrackerInjector` ↔ `DohTrackerResolver`
```kotlin
object TrackerInjector {
    val HTTPS_PORT_443_TRACKERS: List<String>
    fun injectTrackers(magnetUri: String): String
    fun getAugmentedTrackers(existingTrackers: List<String>): List<String>
}

class DohTrackerResolver(private val dohResolver: DohResolver) {
    suspend fun preResolveTrackers(trackers: List<String>)
    suspend fun resolveHost(host: String): List<InetAddress>
}
```

### `TorrentRepository` ↔ `TorrentViewModel` / `TorrentDownloadService`
```kotlin
class TorrentRepository(
    private val engineManager: TorrentEngineManager,
    private val trackerInjector: TrackerInjector,
    private val trackerResolver: DohTrackerResolver
) {
    val torrents: StateFlow<List<TorrentItem>>
    val sessionStats: StateFlow<TorrentSessionStats>
    val activeFilter: StateFlow<TorrentFilter>
    
    suspend fun addMagnet(uri: String, destinationDir: File, selectedFiles: List<Int>? = null)
    suspend fun addTorrentFile(fileBytes: ByteArray, destinationDir: File, selectedFiles: List<Int>? = null)
    fun pause(id: String)
    fun resume(id: String)
    fun pauseAll()
    fun resumeAll()
    fun remove(id: String, deleteFiles: Boolean)
    fun recheck(id: String)
}
```

## Code Layout
- `gradle/libs.versions.toml`: `libtorrent4j = "2.1.0-39"`
- `app/build.gradle.kts`: libtorrent4j dependencies & packaging options (`pickFirst("**/libjlibtorrent.so")`)
- `app/proguard-rules.pro`: JNI preservation rules for `org.libtorrent4j.**`
- `app/src/main/AndroidManifest.xml`: `FOREGROUND_SERVICE_DATA_SYNC` permission and `TorrentDownloadService` declaration
- `app/src/main/java/com/sourzap/app/torrent/model/TorrentModels.kt`: Data models (`TorrentItem`, `TorrentState`, `TorrentFileItem`, `TorrentSessionStats`, `TorrentFilter`)
- `app/src/main/java/com/sourzap/app/torrent/core/TorrentSessionConfig.kt`: Anti-censorship and high-speed throughput settings
- `app/src/main/java/com/sourzap/app/torrent/core/TorrentEngineManager.kt`: Native session manager wrapper
- `app/src/main/java/com/sourzap/app/torrent/tracker/TrackerInjector.kt`: Curated HTTPS port-443 tracker auto-injector
- `app/src/main/java/com/sourzap/app/torrent/tracker/DohTrackerResolver.kt`: Asynchronous DoH hostname pre-resolution
- `app/src/main/java/com/sourzap/app/torrent/repository/TorrentRepository.kt`: Central state coordinator
- `app/src/main/java/com/sourzap/app/torrent/service/TorrentDownloadService.kt`: Foreground service
- `app/src/main/java/com/sourzap/app/torrent/service/TorrentNotificationHelper.kt`: Notification channel and persistent notification builder
- `app/src/main/java/com/sourzap/app/ui/screens/TorrentsScreen.kt`: Main torrents tab screen
- `app/src/main/java/com/sourzap/app/ui/screens/torrent/TorrentCard.kt`: Expressive card component
- `app/src/main/java/com/sourzap/app/ui/screens/torrent/AddTorrentSheet.kt`: Add magnet / file bottom sheet
- `app/src/main/java/com/sourzap/app/ui/screens/torrent/TorrentDetailsSheet.kt`: Multi-file tree selection bottom sheet
- `app/src/main/java/com/sourzap/app/ui/screens/torrent/TorrentViewModel.kt`: UI ViewModel
- `app/src/main/java/com/sourzap/app/ui/components/ExpressiveComponents.kt`: `FloatingExpressiveDock` 5th tab
- `app/src/main/java/com/sourzap/app/MainActivity.kt`: Navigation graph route `"torrents"`
- `app/src/main/java/com/sourzap/app/SourZapApp.kt`: Service and repository initialization
- `app/src/test/java/com/sourzap/app/torrent/...`: Comprehensive unit test suites (Tracker, Magnet, Config, Repo, Models)
