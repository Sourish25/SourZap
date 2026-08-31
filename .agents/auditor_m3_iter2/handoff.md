# Milestone M3 Forensic Audit Report: UI State Lifecycle & Memory Leak Elimination

**Auditor**: `auditor_m3_iter2`  
**Milestone**: M3 (UI State Lifecycle & Memory Leak Elimination)  
**Project**: SourZap (Android Rootless DPI Bypass & Traffic Routing Utility)  
**Verdict**: **`CLEAN`**  
**Date**: 2026-08-31  

---

## 1. Observation

A forensic audit of Milestone M3 deliverables was executed across static code analysis, facade detection, hardcoded response scans, coroutine cancellation semantics, and build/test execution.

### 1.1 Source Code & Integrity Inspection

1. **`SpeedTestEngine.kt` (`com.sourzap.app.speedtest.SpeedTestEngine`)**:
   - **Job Tracking**: Captures coroutine execution job via `currentJob = coroutineContext.job` inside `runSpeedTest()`.
   - **OkHttp Call Registry**: Manages in-flight HTTP calls using `private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())`. All requests register before execution and deregister in `finally`.
   - **Socket Cancellation**: Implements `cancelAllActiveCalls()` which invokes `call.cancel()` across all registered calls and purges dispatcher queue (`httpClient.dispatcher.cancelAll()`).
   - **Structured Cancellation Propagation**: Re-throws `CancellationException` in all worker coroutines and outer test blocks (`catch (e: CancellationException) { throw e }`), ensuring cancellation signals are not converted to `SpeedTestPhase.FAILED`.
   - **State Reset Under `NonCancellable`**: Resets `SpeedTestState` to `IDLE` (`progress = 0f`, `activeGaugeSpeedMbps = 0f`) in `withContext(NonCancellable)`.
   - **Reentrancy Protection**: Guards `runSpeedTest()` with `runMutex.tryLock()` to prevent duplicate concurrent runs.
   - **Buffer Pool Recycling**: Reclaims `ByteArrayPool.obtainStreamBuffer()` buffers in worker `finally` blocks.

2. **Jetpack Compose UI Screens**:
   - **`MainActivity.kt`**: `themePreset` and `darkModePref` StateFlows collected using `collectAsStateWithLifecycle()`.
   - **`DashboardScreen.kt`**: `isVpnActive`, `stats`, `currentStrategy`, `recentLogs`, and `updateState` collected via `collectAsStateWithLifecycle()`. Pauses Flow collection when the app is backgrounded.
   - **`TrafficScreen.kt`**: `stats`, `recentLogs`, and `isVpnActive` collected via `collectAsStateWithLifecycle()`. Uses `TrafficFilterTab.entries` instead of `.values()`.
   - **`SpeedTestScreen.kt`**: `state`, `history`, and `currentStrategy` collected via `collectAsStateWithLifecycle()`. Contains `DisposableEffect(Unit)` with `onDispose { if (isRunning) speedEngine.cancelTest() }` to immediately terminate active speed tests upon screen navigation.
   - **`SettingsScreen.kt`**: All preferences and `updateState` collected via `collectAsStateWithLifecycle()`. Uses `AppThemePreset.entries`.

3. **`UpdateManager.kt` (`com.sourzap.app.update.UpdateManager`)**:
   - **Application-Scoped Lifecycle**: Backed by `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, persisting download progress across screen transitions.
   - **Centralized StateFlow**: `_updateState` exposed as `StateFlow<UpdateState>`.
   - **Atomic File Staging & Integrity**: Streams downloads to `SourZap-update.apk.part`, verifies ZIP magic header (`PK\x03\x04` = `0x50, 0x4B, 0x03, 0x04`) and file size (`>= 3,000,000L`), then atomically renames to `SourZap-update.apk`.
   - **Version Comparison**: Robust semantic version parser and comparison matrix (`isVersionNewer`, `extractCleanVersion`).

4. **`TrafficMonitor.kt` (`com.sourzap.app.service.TrafficMonitor`)**:
   - **Bounded 50-Item FIFO**: Maintains `logBuffer = ArrayDeque<ConnectionLog>(MAX_LOGS)` guarded by `synchronized(logLock)`, evicting oldest item when capacity is reached and inserting newest at index 0.
   - **Underflow Protection**: `activeConnectionCounter.updateAndGet { current -> maxOf(0, current - 1) }`.
   - **Session Reset**: `resetSession()` wipes `sessionRxBytes`, `sessionTxBytes`, `lastSecPackets`, and `speedHistory`.

5. **`Repositories.kt` (`StrategyRepository` & `SettingsRepository`)**:
   - Persists custom strategies and speed test history (capped at 20) in JSON format.
   - Uses defensive `HashSet` copying in `toggleAppBypass` to prevent `SharedPreferences` cache corruption.

---

## 2. Logic Chain

```
[Auditor Verification Checklist]
        │
        ├── 1. Hardcoded Output Check:
        │      └── Verified genuine calculations and network I/O in SpeedTestEngine, UpdateManager, TrafficMonitor, and Repositories. No mocks, dummy constants, or test bypasses. -> PASS
        │
        ├── 2. Facade Implementation Check:
        │      └── Verified actual OkHttp call registration/cancellation, coroutine job tracking, NonCancellable state resets, bounded FIFO log queues, and atomic APK validation. -> PASS
        │
        ├── 3. Pre-Populated Artifact Check:
        │      └── Workspace clean of pre-populated results. -> PASS
        │
        ├── 4. Automated Unit Test Execution:
        │      └── Executed: `.\gradlew.bat testDebugUnitTest`
        │      └── Result: 173 tests executed across 15 test suites, 0 failures, 0 ignored, 100% success rate. -> PASS
        │
        └── 5. Release Assembly:
               └── Executed: `.\gradlew.bat assembleRelease`
               └── Result: BUILD SUCCESSFUL, generated release APK (11,657,383 bytes). -> PASS
```

---

## 3. Caveats

- `collectAsStateWithLifecycle` requires the Compose runtime to be hosted within an `androidx.lifecycle.LifecycleOwner` (such as `ComponentActivity`), which is standard in Android Jetpack.
- No integrity violations or unresolved defects exist.

---

## 4. Conclusion

**Final Verdict**: **`CLEAN`**

The Milestone M3 implementation satisfies all requirements for UI state lifecycle management, memory leak elimination, bounded queues, OkHttp socket cancellation, and cross-navigation update persistence.

---

## 5. Verification Method

### 5.1 Unit Test Suite
Command:
```powershell
.\gradlew.bat testDebugUnitTest
```
Output:
```
BUILD SUCCESSFUL in 1m 22s
24 actionable tasks: 24 executed
```
Test results summary (`app/build/reports/tests/testDebugUnitTest/index.html`):
- Total tests: 173
- Failures: 0
- Ignored: 0
- Success rate: 100%

Key test suites verified:
- `M3EmpiricalChallengeTest` (11 tests passed)
- `SpeedTestAndTrafficMonitorChallengerTest` (5 tests passed)
- `SpeedTestEngineTest` (5 tests passed)
- `TrafficMonitorTest` (4 tests passed)
- `UpdateManagerAndRepositoriesChallengerTest` (7 tests passed)
- `UpdateManagerTest` (14 tests passed)
- `RepositoriesTest` (3 tests passed)

### 5.2 Release Compilation
Command:
```powershell
.\gradlew.bat assembleRelease
```
Output:
```
BUILD SUCCESSFUL in 22s
35 actionable tasks: 35 executed
```
Release artifact generated:
- `app/build/outputs/apk/release/app-release.apk` (11,657,383 bytes)
