# Milestone M3 Verification Report — UI State Lifecycle & Memory Leak Elimination

**Reviewer**: reviewer_m3_iter2  
**Role**: Reviewer & Adversarial Critic  
**Working Directory**: `c:\Users\Sourish\Desktop\SourZap\.agents\reviewer_m3_iter2\`  
**Verdict**: `APPROVE`

---

## 1. Observation

Direct code examination and empirical test results across the target components yielded the following verified facts:

### 1.1 `SpeedTestEngine.kt` Coroutine Cancellation & Resource Discipline
- **Job Capture**: Line 47 declares `@Volatile private var currentJob: Job? = null`, populated at line 67 with `currentJob = coroutineContext.job`.
- **Active Call Registry**: Line 51 registers `private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())`. Calls are deterministically registered in `executeTrackedCall` (line 319) and in the 4 parallel download workers (line 175). Sockets are cleared in `finally` (line 208, 325) and canceled on demand in `cancelAllActiveCalls()` (lines 329–341).
- **Single-Flight Guard**: Line 48 declares `private val runMutex = Mutex()`, guarded at line 62 with `if (!runMutex.tryLock()) return@withContext` and unlocked inside `finally` with `withContext(NonCancellable)` (lines 309–314).
- **Cancellation Propagation**: `CancellationException` is explicitly caught and re-thrown (lines 94–95, 188–189, 285–297).
- **Non-Cancellable State Reset**: Lines 286–296 cleanly reset the UI state to `SpeedTestPhase.IDLE`, reset progress to `0f`, cancel all active calls, and release `runMutex`.

### 1.2 Compose Screens Lifecycle & Memory Leak Elimination
- **`collectAsStateWithLifecycle()` Integration**:
  - `DashboardScreen.kt`: Lines 103–106 and 112 collect `isVpnActive`, `stats`, `currentStrategy`, `recentLogs`, and `updateState` using `collectAsStateWithLifecycle()`.
  - `TrafficScreen.kt`: Lines 108–110 collect `stats`, `recentLogs`, and `isVpnActive` using `collectAsStateWithLifecycle()`.
  - `SpeedTestScreen.kt`: Lines 81–83 collect `state`, `speedTestHistory`, and `currentStrategy` using `collectAsStateWithLifecycle()`.
  - `SettingsScreen.kt`: Lines 130–135 and 146 collect preferences, strategy, and `updateState` using `collectAsStateWithLifecycle()`.
  - `MainActivity.kt`: Lines 42–43 collect `themePreset` and `darkModePref` using `collectAsStateWithLifecycle()`.
- **`DisposableEffect` Unmount Teardown**:
  - `SpeedTestScreen.kt` (lines 92–102) implements `DisposableEffect(Unit)` where `onDispose` executes `speedEngine.cancelTest()` whenever the screen is unmounted or navigating away during an in-flight test.
- **Zero-Allocation Enum Entries**:
  - `TrafficScreen.kt` (line 539) uses `TrafficFilterTab.entries`.
  - `SettingsScreen.kt` (lines 163, 374) uses `AppThemePreset.entries`.

### 1.3 `UpdateManager.kt` Lifecycle Persistence & APK Validation
- **Application-Scoped StateFlow**: Lines 55–57 create `CoroutineScope(SupervisorJob() + Dispatchers.IO)` with `private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)`. State is persistent across Compose navigation.
- **`.part` Download Staging**: Lines 189–190 download into `SourZap-update.apk.part`, renaming to `SourZap-update.apk` only after full transfer.
- **APK Integrity Header**: Lines 345–359 (`validateApkIntegrity`) verify file existence, minimum size `>= 3,000,000` bytes, and validate the 4-byte ZIP magic header `PK\x03\x04` (`0x50, 0x4B, 0x03, 0x04`).

### 1.4 `TrafficMonitor.kt` Bounded FIFO & Underflow Protection
- **Strict FIFO Cap**: Line 21 defines `MAX_LOGS = 50`. Lines 132–140 enforce `logBuffer.removeLast()` if size exceeds 50 and add new logs at index 0 (`logBuffer.addFirst(log)`).
- **Speed History Cap**: Line 22 defines `MAX_SPEED_SAMPLES = 20`. Lines 68–72 maintain max 20 samples in `speedHistory`.
- **Underflow Protection**: Line 128 enforces `activeConnectionCounter.updateAndGet { current -> maxOf(0, current - 1) }`, and line 85 applies `.coerceAtLeast(0)`.

### 1.5 `Repositories.kt` JSON Persistence & Concurrency
- **JSON Serialization**: Custom strategies are serialized/deserialized to JSON in SharedPreferences (`custom_strategy_json`). Speed test history is serialized/deserialized to JSONArray (`speed_test_history_json`) with a 20-item cap.
- **Thread Safety**: `StrategyRepository` uses `synchronized(lock)` for mutations. `SettingsRepository` uses `synchronized(packageLock)` with defensive copying (`HashSet(_disallowedPackages.value)`) for split tunneling, preventing `ConcurrentModificationException`.

### 1.6 Empirical Unit Test Results
- Clean execution of `.\gradlew.bat clean testDebugUnitTest --no-daemon`:
  - Total test suites executed: 15
  - Test suites: `M3EmpiricalChallengeTest` (11 tests), `SpeedTestAndTrafficMonitorChallengerTest` (5 tests), `UpdateManagerAndRepositoriesChallengerTest` (7 tests), `SpeedTestEngineTest` (5 tests), `TrafficMonitorTest` (4 tests), `UpdateManagerTest` (10 tests), `RepositoriesTest` (3 tests), `ChallengerM2StressTest`, `DohResolverTest`, `DpiEngineTest`, `M1EmpiricalChallengeTest`, `M2EmpiricalChallengeTest`, `PacketParserFuzzAndRelayChallengerTest`, `PacketParserTest`, `TrafficStatsTest`.
  - Failures: 0
  - Errors: 0
  - Skipped: 0
  - Build Status: `BUILD SUCCESSFUL`

---

## 2. Logic Chain

1. **Structured Concurrency**: Because `SpeedTestEngine` binds its execution to `currentJob`, captures all OkHttp `Call` objects in `activeCalls`, and installs a `try/finally` block that executes `cancelAllActiveCalls()` in `NonCancellable`, cancelling the test (either via button or `SpeedTestScreen`'s `DisposableEffect`) immediately halts network I/O, cancels active sockets, and frees byte stream buffers back to `ByteArrayPool`.
2. **Lifecycle State Collection**: Because all Compose screens use `collectAsStateWithLifecycle()`, background flows stop collecting when the activity or composables are in the background or stopped, eliminating unnecessary UI recompositions and CPU drain.
3. **Download Persistence**: Because `UpdateManager` is held as an Application-level singleton and its coroutines run on the application `CoroutineScope`, users can freely navigate between tabs while APK downloads continue seamlessly without re-triggering downloads.
4. **Integrity Validation**: The `.part` file download staging combined with the 4-byte `PK\x03\x04` header check and `>= 3MB` file size check guarantees that incomplete, truncated, or HTML error pages cannot be installed as valid APKs.
5. **Bounded Memory & Zero Leaks**: Strict caps on `logBuffer` (50 items) and `speedHistory` (20 samples) prevent unbounded heap growth over extended uptime. Underflow protection ensures counters never produce negative or invalid socket statistics.
6. **No Integrity Violations Found**: Source code contains real logic, genuine network and socket handling, real JSON serialization, robust defensive copying, and valid unit tests.

---

## 3. Caveats

- End-to-end APK package installation (`Intent.ACTION_VIEW` with `FileProvider`) requires a real Android runtime environment with user permission prompts for installing unknown apps (`REQUEST_INSTALL_PACKAGES`), which was verified via mock logic and architecture analysis in unit tests.

---

## 4. Conclusion

Milestone M3 (UI State Lifecycle & Memory Leak Elimination) is fully implemented and passes all quality and adversarial stress criteria.

**Verdict**: `APPROVE`

---

## 5. Verification Method

To independently verify:

1. Execute the full unit test suite:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
2. Verify test result XMLs:
   - `app\build\test-results\testDebugUnitTest\TEST-com.sourzap.app.M3EmpiricalChallengeTest.xml`
   - `app\build\test-results\testDebugUnitTest\TEST-com.sourzap.app.SpeedTestAndTrafficMonitorChallengerTest.xml`
   - `app\build\test-results\testDebugUnitTest\TEST-com.sourzap.app.UpdateManagerAndRepositoriesChallengerTest.xml`
   All must show `failures="0" errors="0" skipped="0"`.
