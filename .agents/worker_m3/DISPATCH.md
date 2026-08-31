## 2026-08-31T09:59:00Z
You are worker_m3 for SourZap Milestone M3 (UI State Lifecycle & Memory Leak Elimination).
Your working directory is: c:\Users\Sourish\Desktop\SourZap\.agents\worker_m3\
The project root is: c:\Users\Sourish\Desktop\SourZap
Authoritative User Request: c:\Users\Sourish\Desktop\SourZap\.agents\ORIGINAL_REQUEST.md
Project architecture & features: c:\Users\Sourish\Desktop\SourZap\PROJECT.md

Read the detailed exploration reports from the M3 Explorers:
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m3_1\handoff.md (SpeedTestEngine Coroutine Cancellation & OkHttp Call Tracking)
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m3_2\handoff.md (Jetpack Compose Lifecycle, collectAsStateWithLifecycle, Recomposition Optimization)
- c:\Users\Sourish\Desktop\SourZap\.agents\explorer_m3_3\handoff.md (UpdateManager Download Persistence, TrafficMonitor FIFO Bounds & Repositories Thread Safety)

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Your Exclusive Write Ownership:
- `app/src/main/java/com/sourzap/app/speedtest/SpeedTestEngine.kt`, `SpeedTestState.kt`, `SpeedTestResult.kt`
- `app/src/main/java/com/sourzap/app/ui/` (`DashboardScreen.kt`, `TrafficScreen.kt`, `SpeedTestScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt`)
- `app/src/main/java/com/sourzap/app/data/` (`UpdateManager.kt`, `TrafficMonitor.kt`, `SettingsRepository.kt`, `StrategyRepository.kt`)
- `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Test files in `app/src/test/java/com/sourzap/app/`

Requirements to Implement:
1. `SpeedTestEngine.kt`:
   - Assign `currentJob = launch { ... }` in `runSpeedTest()`.
   - Maintain a thread-safe registry of active OkHttp `Call` objects (`activeCalls`) and cancel each with `call.cancel()` in `cancelTest()`.
   - Re-throw `CancellationException` in worker coroutines and `runSpeedTest()` — never swallow cancellation or set `FAILED` state on cancellation.
   - Clean up state and recycle buffers in `finally` and `withContext(NonCancellable)`.
   - Protect `runSpeedTest()` against concurrent execution with `Mutex.withLock`.
   - Synchronize `downloadSpeedSamples` to avoid `ConcurrentModificationException`.
2. Lifecycle & Compose Screens:
   - Ensure `androidx.lifecycle:lifecycle-runtime-compose` is properly included in `libs.versions.toml` / `app/build.gradle.kts` if needed.
   - Integrate `collectAsStateWithLifecycle()` in Compose screens (`DashboardScreen`, `TrafficScreen`, `SpeedTestScreen`, `SettingsScreen`, `MainActivity`) to pause telemetry Flow collections when the app is backgrounded/stopped.
   - Add `DisposableEffect` in `SpeedTestScreen` to cleanly cancel ongoing speed tests when navigating away.
   - Replace `Enum.values()` array allocations with `Enum.entries` in screens.
3. `UpdateManager.kt`:
   - Centralize download state in a persistent singleton `StateFlow<UpdateState>` backed by an application-level CoroutineScope (`SupervisorJob() + Dispatchers.IO`).
   - Preserve download progress across screen navigation and configuration changes without restarting.
   - Atomic `.part` file downloading and renaming to destination APK.
4. `TrafficMonitor.kt`:
   - Enforce strictly bounded FIFO connection log capacity (50 items maximum, dropping oldest) using a synchronized `ArrayDeque<ConnectionLog>`.
   - Clamp atomic counters (`activeConnections.updateAndGet { (it - 1).coerceAtLeast(0) }`) to prevent underflow.
5. `SettingsRepository.kt` & `StrategyRepository.kt`:
   - Enforce thread-safe and persistent preference mutations.
6. Test Suite & Verification:
   - Expand and run test suites: `SpeedTestEngineTest.kt`, `TrafficMonitorTest.kt`, `UpdateManagerTest.kt`, and `M3EmpiricalChallengeTest.kt`.
   - Run `.\gradlew.bat testDebugUnitTest` and ensure 100% of tests pass without any flaky failures.
   - Verify `.\gradlew.bat assembleRelease` finishes with `BUILD SUCCESSFUL`.
