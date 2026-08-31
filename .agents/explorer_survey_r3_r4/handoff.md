# Handoff Report: R3 (UI State Lifecycle & Memory Leak Elimination) & R4 (Automated Test Suite Expansion & QA)

**Subagent**: Explorer Survey R3 & R4  
**Working Directory**: `c:\Users\Sourish\Desktop\SourZap\.agents\explorer_survey_r3_r4`  
**Timestamp**: 2026-08-31T07:45:00Z  
**Target Milestone**: Exploration & Mapping of Requirements R3 and R4  

---

## 1. Observation

### 1.1 Codebase & UI Architectural Overview

The SourZap user interface is built exclusively using modern **Jetpack Compose (BOM 2024.08.00)** with **Material 3 (1.3.0)** and custom Material You Expressive design components. The UI architecture does not utilize traditional `androidx.lifecycle.ViewModel` classes; instead, it binds directly to application singletons (`SourZapApp.instance`, `TrafficMonitor`, `DpiProbeEngine`) and reactive repositories (`SettingsRepository`, `StrategyRepository`) which expose `StateFlow` primitives.

#### Core Screen Map:
1. **`MainActivity.kt`** (`app/src/main/java/com/sourzap/app/MainActivity.kt`):
   - Configures edge-to-edge layout, dynamic theme switching via `SourZapTheme`, and bottom dock navigation via `NavHost` (`dashboard`, `speedtest`, `traffic`, `settings`).
   - Uses `rememberNavController()` and `FloatingExpressiveDock` with `saveState = true`, `restoreState = true`, and `launchSingleTop = true` (lines 108–115).
   - Collects theme preferences using `collectAsState()` (lines 42–43).

2. **`DashboardScreen.kt`** (`app/src/main/java/com/sourzap/app/ui/dashboard/DashboardScreen.kt`):
   - Collects 4 live StateFlows: `TrafficMonitor.isVpnActive`, `TrafficMonitor.stats`, `strategyRepo.currentStrategy`, `TrafficMonitor.recentLogs` via `collectAsState()` (lines 103–106).
   - Launches GitHub release check via `LaunchedEffect(Unit)` collecting `updateManager.checkForUpdates(currentVer)` (lines 117–126).
   - Handles APK downloads with `rememberCoroutineScope()` and local mutable states `isDownloadingUpdate` and `downloadProgress` (lines 114–115, 236–249).
   - Displays `HeroConnectButton`, `LiveThroughputCard`, `ExpressiveTrafficWave` telemetry canvas, and recent 4 intercepted flow chips.

3. **`TrafficScreen.kt`** (`app/src/main/java/com/sourzap/app/ui/traffic/TrafficScreen.kt`):
   - Collects `TrafficMonitor.stats`, `TrafficMonitor.recentLogs`, `TrafficMonitor.isVpnActive` via `collectAsState()` (lines 108–110).
   - Performs client-side filtering over up to 50 logs with `remember(logs, searchQuery, selectedTab)` across search query and 5 filter tabs (`ALL`, `TLS`, `DNS`, `P2P`, `UDP`) (lines 122–150).
   - Uses `LazyColumn` with stable item keys `key = { it.id }` (line 617).
   - Provides session counter reset (`TrafficMonitor.resetSession()`) and log clearing (`TrafficMonitor.clearLogs()`) with confirmation dialog (lines 152–167).

4. **`SpeedTestScreen.kt`** (`app/src/main/java/com/sourzap/app/ui/speedtest/SpeedTestScreen.kt`):
   - Collects `speedEngine.state`, `settingsRepo.speedTestHistory`, `strategyRepo.currentStrategy` via `collectAsState()` (lines 80–82).
   - Drives 4 spring-physics animated diagnostic values: `animatedPing`, `animatedJitter`, `animatedDownload`, `animatedUpload` using `animateFloatAsState` (lines 92–112).
   - Renders `ExpressiveSpeedGauge` canvas (lines 298–301) and `ExpressiveWavyProgressIndicator` (lines 305–309).
   - Triggers speed test via `rememberCoroutineScope().launch { speedEngine.runSpeedTest() }` and cancellation via `speedEngine.cancelTest()` (lines 188–195).

5. **`SettingsScreen.kt`** (`app/src/main/java/com/sourzap/app/ui/settings/SettingsScreen.kt`):
   - Sub-page navigation hierarchy: `MAIN`, `APPEARANCE`, `NETWORK`, `DNS`, `UPDATES`, `ABOUT` managed through `AnimatedContent` with horizontal slide transitions (lines 166–180).
   - Integrates `BackHandler(enabled = currentPage != SettingsPage.MAIN)` to trap Android system back gestures (lines 150–152).
   - Implements Split-Tunneling App Selection modal bottom sheet (`ModalBottomSheet`, lines 1183–1354) asynchronously loading launchable apps via `LaunchedEffect(showAppSheet)` and `AppListHelper.getInstalledLaunchableApps` (lines 154–158).
   - Collects settings preferences: `bypassLan`, `autoConnectOnBoot`, `themePreset`, `darkModePref`, `disallowedPackages`, `currentStrategy` via `collectAsState()` (lines 130–136).

---

### 1.2 State Lifecycle & Coroutine Bugs Discovered

#### Bug 1: `SpeedTestEngine.cancelTest()` Fails to Cancel Background Network Coroutine
- **Location**: `app/src/main/java/com/sourzap/app/speedtest/SpeedTestEngine.kt:37, 46, 247–257`
- **Observation**:
  ```kotlin
  // Line 37
  private var currentJob: Job? = null

  // Line 46
  suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
      // CRITICAL: currentJob is NEVER assigned here!
      try {
          _state.value = SpeedTestState(
              phase = SpeedTestPhase.PING,
              statusMessage = "Measuring Ping & Jitter..."
          )
          ...
  ```
  And `cancelTest()`:
  ```kotlin
  // Line 247
  fun cancelTest() {
      currentJob?.cancel() // NO-OP! currentJob is always null!
      _state.update {
          it.copy(
              phase = SpeedTestPhase.IDLE,
              progress = 0f,
              activeGaugeSpeedMbps = 0f,
              statusMessage = "Ready"
          )
      }
  }
  ```
- **Impact**: When the user taps "CANCEL TEST", `cancelTest()` updates `_state` to `IDLE`, but the background parallel download streams (4 workers downloading 70MB of data via `OkHttpClient`) and upload loops continue executing in `Dispatchers.IO`. Once they complete, they overwrite `_state` with `COMPLETED` or `FAILED`, resulting in state corruption, unexpected UI jumps, and cellular data waste.

#### Bug 2: Lack of Lifecycle-Aware Flow Collection (`collectAsState` vs `collectAsStateWithLifecycle`)
- **Location**: `MainActivity.kt`, `DashboardScreen.kt`, `TrafficScreen.kt`, `SpeedTestScreen.kt`, `SettingsScreen.kt`
- **Observation**: StateFlows are currently collected using `collectAsState()` from `androidx.compose.runtime:runtime`.
- **Impact**: `collectAsState()` keeps the flow collection active even when the Android Activity is in the `STOPPED` state (e.g. user presses Home button, switches apps, or locks the screen). Because `TrafficMonitor.startMonitoring()` emits `TrafficStats` updates every 1,000ms (1 second), the background coroutines continue processing emissions unnecessarily, causing battery drain.
- **Remedy**: Integrate `androidx.lifecycle:lifecycle-runtime-compose` and replace `collectAsState()` with `collectAsStateWithLifecycle()` on all screen composables.

#### Bug 3: Ephemeral Update Download State Lost on Screen Navigation
- **Location**: `DashboardScreen.kt:114–115, 236–249` and `SettingsScreen.kt:795–863`
- **Observation**: `isDownloadingUpdate` and `downloadProgress` are stored in composable `remember { mutableStateOf(...) }` inside `DashboardScreen`. If the user initiates an update download and navigates to the Settings or Traffic screen, the `DashboardScreen` leaves composition, canceling the `rememberCoroutineScope()` and terminating the APK download stream mid-transfer.
- **Impact**: The user cannot monitor APK download progress across navigation, and navigating back restarts the download.

---

### 1.3 Testing Infrastructure & Build Status

#### Build & Test Execution Verification:
1. **Unit Test Suite Execution**:
   - Command: `.\gradlew.bat testDebugUnitTest`
   - Result: `BUILD SUCCESSFUL in 50s`, 24 actionable tasks, 100% passing tests (0 failures, 0 flaky tests).
2. **Release APK Assemble Execution**:
   - Command: `.\gradlew.bat assembleRelease`
   - Result: `BUILD SUCCESSFUL in 33s`, 45 actionable tasks, release APK generated and signed with `sourzap_signing.jks`.

#### Current Test Coverage Inventory (`app/src/test/java/com/sourzap/app/`):
- Total existing test files: **5**
- Total test lines of code: **~2,526 lines**
- Suite breakdown:
  1. `DohResolverTest.kt` (577 lines): Wire DNS packet building, DNS response wire parsing, `DnsLruCache` LRU eviction/expiration, `WireQuestionKey` hashing, 50-coroutine stress test.
  2. `DpiEngineTest.kt` (525 lines): `TlsParser` ClientHello parsing/fuzzing, `HttpParser` methods/headers/case modification, BitTorrent/SSH handshake detection, critical domain passthrough list, `ByteArrayPool` concurrency stress.
  3. `PacketParserTest.kt` (854 lines): IPv4/IPv6 header parsing, Happy Eyeballs ICMPv6 synthesis, TCP SYN-ACK MSS options, TCP sequence wrap-around, UDP RFC 768 checksums, UDP NAT table 5,000-packet burst, TCP 1,400 MTU segment splitting, ICMP port unreachable synthesis.
  4. `TrafficStatsTest.kt` (307 lines): Speed/byte formatters (Gbps, Mbps, Kbps, B), model calculations, ping/jitter math, `ByteArrayPool` size buckets, speed history circular buffer.
  5. `UpdateManagerTest.kt` (263 lines): Version clean extraction, SemVer comparison matrix, APK magic header (`0x50 0x4B 0x03 0x04`) validation, update state transitions.

---

### 1.4 Comprehensive Testing Gaps Enumeration

Despite robust parsing and checksum unit tests, significant components and edge cases lack automated test coverage:

| Category | Component / Module | Identified Testing Gap | Impact / Severity |
| :--- | :--- | :--- | :--- |
| **R1 Relay** | `TunTcpRelay` | TCP 5-state machine transitions (`SYN_RECEIVED`, `ESTABLISHED`, `SERVER_FIN_SENT`, `CLIENT_FIN_RECEIVED`, `CLOSED`) | **HIGH**: Potential socket leaks or half-open states on abrupt network loss |
| **R1 Relay** | `TunTcpRelay` | Connection limits (`MAX_CONCURRENT_CONNECTING = 64`, `MAX_SESSIONS = 4096`) returning `RST\|ACK` | **MEDIUM**: Swarm socket flood protection verification |
| **R1 Relay** | `TunTcpRelay` | Upstream connection failure synthesis of `RST\|ACK` to TUN interface | **HIGH**: Prevents client apps from hanging in `CLOSE_WAIT` |
| **R1 Relay** | `TunUdpRelay` | Multi-socket pool round-robin selection and NAT eviction at `MAX_NAT_ENTRIES = 4096` | **MEDIUM**: Memory bounds under high-speed UDP swarms |
| **R1 Proxy** | `LocalDpiProxyServer` | HTTP `CONNECT` tunneling header parsing with bracketed IPv6 literals `[2001:db8::1]:443` | **MEDIUM**: Proxy compatibility on IPv6 cellular connections |
| **R1 Proxy** | `LocalDpiProxyServer` | Absolute proxy URI normalization (`GET http://tracker.com/ann -> GET /ann`) | **HIGH**: P2P HTTP tracker compatibility |
| **R2 DPI** | `DpiEngine` | Strategy execution matrix (`AUTO_PILOT`, `STREAMING_TURBO`, `GAMING_VOICE`, `STRICT_FIREWALL`) | **HIGH**: Ensures correct splitting and evasion techniques are dispatched |
| **R2 DPI** | `DpiEngine` | Split2 edge cases: packets smaller than 2 bytes, empty payloads, multi-split index calculations | **HIGH**: Prevents `IndexOutOfBoundsException` on fragmented packets |
| **R2 P2P** | `DpiEngine` | BitTorrent uTP protocol header detection (types ST_DATA, ST_FIN, ST_STATE, ST_RESET, ST_SYN) | **HIGH**: P2P UDP acceleration and DPI evasion |
| **R2 P2P** | `DpiEngine` | BitTorrent DHT bencoded query detection (`d1:ad2:id20:...`) | **MEDIUM**: DHT burst detection and non-blocking forwarding |
| **R3 State** | `TrafficMonitor` | Thread-safe byte counters, connection counters (never negative), max 50 log FIFO trimming | **HIGH**: UI telemetry stability and memory leak prevention |
| **R3 State** | `SpeedTestEngine` | Coroutine cancellation (`cancelTest()`), multi-stream progress reporting, jitter calculations | **HIGH**: Fixes active cancellation bug and verifies test engine |
| **R3 Repo** | `SettingsRepository` | Disallowed packages set toggle, persistence, speed test history max 20 cap | **MEDIUM**: State holder correctness |
| **R3 Repo** | `StrategyRepository` | DoH provider switching, strategy selection persistence | **LOW**: Repository verification |

---

## 2. Logic Chain

### 2.1 UI State Lifecycle & Memory Analysis

```
[Observation 1]: SpeedTestEngine.kt has `private var currentJob: Job? = null` which is never set in `runSpeedTest()`.
       ↓
[Inference 1.1]: Calling `cancelTest()` executes `currentJob?.cancel()`, which evaluates to null and performs no action.
       ↓
[Inference 1.2]: Background OkHttpClient streams continue downloading up to 70MB on Dispatchers.IO and mutate `_state` post-cancellation.
       ↓
[Solution 1]: Capture coroutine job in `runSpeedTest()` via `currentJob = coroutineContext[Job]` and ensure `cancelTest()` calls `currentJob?.cancelChildren()` and `currentJob?.cancel()`.
```

```
[Observation 2]: All Composable screens use `collectAsState()` directly from Compose runtime on singleton StateFlows.
       ↓
[Inference 2.1]: When Activity enters `onStop()` (backgrounded), `collectAsState()` coroutines remain active in composition.
       ↓
[Inference 2.2]: `TrafficMonitor` emits `TrafficStats` every 1,000ms, waking up inactive Composable collector scopes and consuming battery.
       ↓
[Solution 2]: Add `androidx.lifecycle:lifecycle-runtime-compose` dependency and migrate to `collectAsStateWithLifecycle()`.
```

```
[Observation 3]: `DashboardScreen` and `SettingsScreen` store download state in local `mutableStateOf` within Composable scope.
       ↓
[Inference 3.1]: Navigation between tabs cancels the `rememberCoroutineScope()` and destroys local download state.
       ↓
[Solution 3]: Centralize update state observation inside `UpdateManager` (exposing `StateFlow<UpdateState>`), allowing downloads to proceed seamlessly across screen transitions.
```

---

### 2.2 Test Expansion Strategy & Quality Assurance

```
[Observation 4]: Existing test suite has 5 comprehensive test files (2,526 lines) covering raw packet headers, checksums, and parser edge cases.
       ↓
[Inference 4.1]: Core networking and relay engines (`TunTcpRelay`, `TunUdpRelay`, `LocalDpiProxyServer`, `SpeedTestEngine`, `TrafficMonitor`) lack dedicated unit test fixtures.
       ↓
[Inference 4.2]: Expanding unit tests to cover state machines, NAT bounds, P2P uTP detection, and repository state holders guarantees 0 regressions.
       ↓
[Solution 4]: Create new test suites:
   1. `TunTcpRelayTest.kt`: TCP state transitions, connection bounds (64 connecting / 4096 max), RST synthesis on failure, MTU segment splitting.
   2. `TunUdpRelayTest.kt`: Socket pool distribution, NAT key resolution, scavenger eviction at 4096 entries, uTP header detection.
   3. `LocalDpiProxyServerTest.kt`: CONNECT parsing, IPv6 literal hosts, absolute URI rewriting, WhatsApp Noise protocol passthrough.
   4. `SpeedTestEngineTest.kt`: Speed test state machine, ping/jitter math, cancellation mechanics, and result formatting.
   5. `TrafficMonitorTest.kt`: Byte counters, connection count bounds, log cap at 50 entries, session reset, speed wave history.
   6. `RepositoriesTest.kt`: SettingsRepository and StrategyRepository persistence, split tunneling toggle, speed history capping.
```

---

## 3. Caveats

1. **Android Framework Dependencies in Unit Tests**:
   - `app/build.gradle.kts` sets `testOptions.unitTests.isReturnDefaultValues = true`. Unit tests run purely in JVM without an Android emulator or Robolectric.
   - Tests requiring Android classes (`InetAddress`, `DatagramPacket`, `SharedPreferences`) use JVM equivalents or mock wrappers.
2. **Virtual Coroutine Time**:
   - Currently, tests use `kotlinx.coroutines.runBlocking` and `Dispatchers.IO`. Adding `kotlinx-coroutines-test` enables deterministic virtual-time testing without `delay()` / `Thread.sleep()`.
3. **No Unresolved Warnings in Current Build**:
   - Both `./gradlew.bat testDebugUnitTest` and `./gradlew.bat assembleRelease` compile cleanly and finish with `BUILD SUCCESSFUL`.

---

## 4. Conclusion & Actionable Recommendations

### Summary of Findings:
1. **UI State & Lifecycle (R3)**:
   - Overall Compose UI is well-structured and responsive with adaptive phone/tablet layouts.
   - 1 critical cancellation bug in `SpeedTestEngine.kt` (`currentJob` not captured, leaving background streams running on cancel).
   - Recommended migration to `collectAsStateWithLifecycle()` to pause Flow collection during app backgrounding.
   - Centralization of `UpdateManager` download state into a singleton `StateFlow` to prevent aborted downloads on tab navigation.
2. **Automated Test Suite (R4)**:
   - Existing 5 test files are completely green (100% passing).
   - 6 major test suites should be added to bridge all testing gaps across TCP relay state machines, UDP NAT scaling, P2P/uTP protocol evasion, proxy URI normalization, speed test engine, and UI repositories.

### Concrete Implementation Tasks for Implementation Agent:
1. **Task R3.1**: Fix coroutine cancellation in `SpeedTestEngine.kt` by assigning `currentJob = coroutineContext[Job]` and enforcing cooperative cancellation.
2. **Task R3.2**: Add `androidx-lifecycle-runtime-compose` to `gradle/libs.versions.toml` & `app/build.gradle.kts`, and migrate `collectAsState()` calls in Compose screens to `collectAsStateWithLifecycle()`.
3. **Task R3.3**: Ensure `UpdateManager` maintains active download state in a persistent `StateFlow` so downloads survive navigation changes.
4. **Task R4.1**: Create `TunTcpRelayTest.kt` covering RFC 793 state machine, connection bounds, RST packet synthesis, and segment splitting.
5. **Task R4.2**: Create `TunUdpRelayTest.kt` covering multi-socket pool distribution, NAT key resolution, and 4096-entry eviction.
6. **Task R4.3**: Create `LocalDpiProxyServerTest.kt` covering HTTP CONNECT tunnel parsing, IPv6 bracketed hosts, and absolute URI normalization.
7. **Task R4.4**: Create `TrafficMonitorTest.kt` & `RepositoriesTest.kt` covering telemetry counters, 50-item log trimming, and split-tunneling toggle logic.
8. **Task R4.5**: Create `SpeedTestEngineTest.kt` testing cancellation, ping/jitter math, and phase transitions.

---

## 5. Verification Method

To independently verify all findings and validate subsequent implementations:

```powershell
# 1. Run full unit test suite (must execute 100% passing without failure)
.\gradlew.bat testDebugUnitTest

# 2. Build signed release APK (must complete with BUILD SUCCESSFUL)
.\gradlew.bat assembleRelease

# 3. Check for zero lint errors and clean compilation
.\gradlew.bat lintDebug
```

**Invalidation Conditions**:
- Any unit test failure in `app/src/test/...`.
- Unresolved coroutine execution or data downloading after calling `SpeedTestEngine.cancelTest()`.
- Recomposition loops or state collection continuing while Activity lifecycle is `STOPPED`.
