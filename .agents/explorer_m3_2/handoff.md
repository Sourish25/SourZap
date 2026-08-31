# Milestone M3 Exploration Report: UI State Lifecycle & Memory Leak Elimination

**Date**: 2026-08-31  
**Author**: `explorer_m3_2`  
**Milestone**: M3 (UI State Lifecycle & Memory Leak Elimination)  
**Target Project**: SourZap (Android Rootless DPI Bypass & Traffic Routing Utility)  

---

## 1. Observation

A comprehensive codebase audit was conducted across Gradle build configurations, Jetpack Compose screens, telemetry engines, and repositories. The following specific observations and defects were directly identified:

### 1.1 Missing Lifecycle Compose Dependency
- **File**: `gradle/libs.versions.toml` (lines 1-39) and `app/build.gradle.kts` (lines 78-101).
- **Observation**:
  - `libs.versions.toml` declares `lifecycleRuntimeKtx = "2.8.4"`, with libraries `androidx-lifecycle-runtime-ktx` and `androidx-lifecycle-viewmodel-compose`.
  - `androidx-lifecycle-runtime-compose` (`androidx.lifecycle:lifecycle-runtime-compose`) is **completely missing**.
  - As a consequence, `collectAsStateWithLifecycle()` (from package `androidx.lifecycle.compose`) is not available in the project classpath, and any screen attempting to use it fails to compile without adding this dependency.

### 1.2 Unpaused Flow Telemetry & Background Battery Drain via `collectAsState()`
- **Files & Line Numbers**:
  - `app/src/main/java/com/sourzap/app/MainActivity.kt`:
    - Line 42: `val themePreset by settingsRepo.themePreset.collectAsState()`
    - Line 43: `val darkModePref by settingsRepo.darkModePref.collectAsState()`
  - `app/src/main/java/com/sourzap/app/ui/dashboard/DashboardScreen.kt`:
    - Line 103: `val isConnected by TrafficMonitor.isVpnActive.collectAsState()`
    - Line 104: `val stats by TrafficMonitor.stats.collectAsState()`
    - Line 105: `val currentStrategy by strategyRepo.currentStrategy.collectAsState()`
    - Line 106: `val recentLogs by TrafficMonitor.recentLogs.collectAsState()`
  - `app/src/main/java/com/sourzap/app/ui/traffic/TrafficScreen.kt`:
    - Line 108: `val stats by TrafficMonitor.stats.collectAsState()`
    - Line 109: `val logs by TrafficMonitor.recentLogs.collectAsState()`
    - Line 110: `val isVpnActive by TrafficMonitor.isVpnActive.collectAsState()`
  - `app/src/main/java/com/sourzap/app/ui/speedtest/SpeedTestScreen.kt`:
    - Line 80: `val state by speedEngine.state.collectAsState()`
    - Line 81: `val history by settingsRepo.speedTestHistory.collectAsState()`
    - Line 82: `val currentStrategy by strategyRepo.currentStrategy.collectAsState()`
  - `app/src/main/java/com/sourzap/app/ui/settings/SettingsScreen.kt`:
    - Lines 130-135: `bypassLan`, `autoConnect`, `themePreset`, `darkModePref`, `disallowedPackages`, and `currentStrategy` collected via `collectAsState()`.
- **Observation**:
  `collectAsState()` collects continuously in the composition's coroutine context. When the user backgrounds the app (e.g. Home button, screen lock, app switch), `TrafficMonitor.stats` (1 Hz tick) and `TrafficMonitor.recentLogs` (high-frequency packet interception stream) continue emitting. The background composables continue collecting, allocating Snapshot objects, and causing unnecessary CPU wakeups while the UI is not visible on screen.

### 1.3 `SpeedTestEngine` Broken Coroutine Cancellation & Resource Leak
- **File**: `app/src/main/java/com/sourzap/app/speedtest/SpeedTestEngine.kt` (lines 34-258)
- **Observation**:
  1. **Unassigned Job**: Line 37 declares `private var currentJob: Job? = null`. Line 46 declares `suspend fun runSpeedTest() = withContext(Dispatchers.IO) { ... }`. `currentJob` is **never assigned** in `runSpeedTest()`.
  2. **No-Op Cancellation**: Line 248 in `cancelTest()` calls `currentJob?.cancel()`, which is a no-op because `currentJob` remains `null`. The coroutine launched by `SpeedTestScreen` (`scope.launch { speedEngine.runSpeedTest() }`) continues running in the background.
  3. **State Corruption on Cancel**: When `cancelTest()` is called, it resets `_state` to `SpeedTestPhase.IDLE`. However, since `runSpeedTest()` is still running, its next step immediately overwrites `_state` with `DOWNLOAD`, `UPLOAD`, or `COMPLETED`.
  4. **Swallowing `CancellationException`**: Line 237 catches generic `Exception` (`catch (e: Exception)`). In Kotlin Coroutines, cancelling a coroutine throws `CancellationException` (which extends `IllegalStateException`). Catching generic `Exception` catches `CancellationException` and transitions `_state` to `SpeedTestPhase.FAILED` with `"Test completed with fallback data"`, instead of cleanly stopping.
  5. **Uncancelled OkHttpClient Sockets**: Blocking OkHttp calls (`httpClient.newCall(req).execute()`) in Phase 1 and Phase 2 are not cancelled when coroutines are cancelled, leaving network streams and worker threads blocked until timeout.
  6. **Missing Screen Teardown**: `SpeedTestScreen.kt` has no `DisposableEffect` to cancel an active test when leaving the screen.

### 1.4 `UpdateManager` State Loss Across Navigation
- **Files**:
  - `app/src/main/java/com/sourzap/app/update/UpdateManager.kt` (lines 90-254)
  - `app/src/main/java/com/sourzap/app/ui/dashboard/DashboardScreen.kt` (lines 111-126, 235-249)
  - `app/src/main/java/com/sourzap/app/ui/settings/SettingsScreen.kt` (lines 793-879)
- **Observation**:
  1. `UpdateManager` returns cold `Flow<UpdateState>` and does not retain the current `UpdateState` or hold an active download job in an application scope.
  2. Both `DashboardScreen` and `SettingsScreen` manage their own local `remember { mutableStateOf<UpdateState>(...) }` and `rememberCoroutineScope()`.
  3. When a user clicks "Update" in `DashboardScreen`, navigating to "Traffic" or "SpeedTest" destroys the `rememberCoroutineScope()`, immediately cancelling the APK download. When returning to Dashboard, the download progress is lost.

### 1.5 Recomposition Allocation Churn in Lists & Menus
- **Files**:
  - `app/src/main/java/com/sourzap/app/ui/traffic/TrafficScreen.kt` (line 539: `TrafficFilterTab.values()`)
  - `app/src/main/java/com/sourzap/app/ui/settings/SettingsScreen.kt` (line 163 and line 374: `AppThemePreset.values()`)
- **Observation**:
  Using `Enum.values()` creates a newly allocated array on every invocation/recomposition frame. Under Kotlin 2.0.0, the idiomatic `Enum.entries` property should be used to avoid garbage creation during frequent recompositions.

---

## 2. Logic Chain

1. **Premise 1 (Lifecycle Awareness)**: Telemetry streams (`stats`, `recentLogs`, `speedTestState`) generate continuous updates. In Android Jetpack Compose, `collectAsStateWithLifecycle()` pauses Flow collection when the hosting Lifecycle drops below `Lifecycle.State.STARTED`, halting background recomposition churn and saving battery and CPU.
2. **Premise 2 (Dependency Resolution)**: `collectAsStateWithLifecycle()` requires `androidx.lifecycle:lifecycle-runtime-compose`. Adding `androidx-lifecycle-runtime-compose` to `libs.versions.toml` and `app/build.gradle.kts` allows compilation across all screens.
3. **Premise 3 (Structured Cancellation)**: For background engines like `SpeedTestEngine`, cancellation must:
   - Hold an active `Job` or allow parent coroutine cancellation.
   - Rethrow `CancellationException` so coroutine cancellation behaves correctly according to Kotlin coroutines structured concurrency rules.
   - Actively cancel in-flight `OkHttpClient` calls via `Call.cancel()` or `httpClient.dispatcher.cancelAll()`.
   - Use `DisposableEffect` on `SpeedTestScreen` exit to cancel background benchmarks when leaving composition.
4. **Premise 4 (Cross-Navigation State Persistence)**: The update download process is an application-level operation. By lifting `UpdateState` to a `StateFlow<UpdateState>` inside `UpdateManager` and executing downloads within an app/manager `CoroutineScope`, the download continues unaffected when switching Compose navigation routes, and all UI screens stay synchronized.
5. **Premise 5 (Recomposition Efficiency)**: Replacing `Enum.values()` with `Enum.entries` eliminates continuous array heap allocations across `TrafficScreen` and `SettingsScreen`.

---

## 3. Caveats

1. **Unit Test Environment**: `collectAsStateWithLifecycle()` relies on `LocalLifecycleOwner`. In purely headless Compose unit tests (without a `LifecycleOwner`), standard Robolectric or `ComposeContentTestRule` must provide a `TestLifecycleOwner` if lifecycle state transitions are asserted.
2. **VPN Service Independence**: `TrafficMonitor` continues monitoring packets in the background service when the VPN is active (which is desired for accurate session counters). Pausing Compose `collectAsStateWithLifecycle()` in the UI does NOT stop `TrafficMonitor` from logging; it only stops the Compose UI from collecting when the app is backgrounded.
3. **Alternative Architecture Considered**: Moving all state entirely into AndroidX `ViewModel` classes. However, `SourZapApp` already maintains singletons (`SettingsRepository`, `StrategyRepository`, `SpeedTestEngine`, `UpdateManager`, `TrafficMonitor`) accessible across the single-activity architecture. Exposing `StateFlow` from these domain engines and collecting with `collectAsStateWithLifecycle()` provides leak-free, lifecycle-safe state collection without unnecessary boilerplate indirection.

---

## 4. Conclusion & Proposed Implementation Plan

### 4.1 Detailed Action Items

#### Step 1: Add `lifecycle-runtime-compose` Dependency
- In `gradle/libs.versions.toml`:
  ```toml
  [libraries]
  androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
  ```
- In `app/build.gradle.kts`:
  ```kotlin
  dependencies {
      implementation(libs.androidx.lifecycle.runtime.compose)
      ...
  }
  ```

#### Step 2: Update `SpeedTestEngine.kt` for Bulletproof Cancellation
- Implement active `Job` assignment and `OkHttpClient` call tracking.
- Rethrow `CancellationException`.
- Complete proposed code:
  ```kotlin
  package com.sourzap.app.speedtest

  import com.sourzap.app.data.model.SpeedTestPhase
  import com.sourzap.app.data.model.SpeedTestResult
  import com.sourzap.app.data.model.SpeedTestState
  import com.sourzap.app.data.repository.SettingsRepository
  import com.sourzap.app.data.repository.StrategyRepository
  import com.sourzap.app.service.core.ByteArrayPool
  import kotlinx.coroutines.CancellationException
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.async
  import kotlinx.coroutines.awaitAll
  import kotlinx.coroutines.coroutineScope
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.flow.update
  import kotlinx.coroutines.isActive
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext
  import okhttp3.Call
  import okhttp3.ConnectionPool
  import okhttp3.OkHttpClient
  import okhttp3.Request
  import java.io.InputStream
  import java.util.Collections
  import java.util.concurrent.ConcurrentHashMap
  import java.util.concurrent.TimeUnit
  import java.util.concurrent.atomic.AtomicLong
  import kotlin.coroutines.coroutineContext

  class SpeedTestEngine(
      private val settingsRepository: SettingsRepository,
      private val strategyRepository: StrategyRepository
  ) {
      private val _state = MutableStateFlow(SpeedTestState())
      val state: StateFlow<SpeedTestState> = _state.asStateFlow()

      private var currentJob: Job? = null
      private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

      private val httpClient = OkHttpClient.Builder()
          .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
          .connectTimeout(4, TimeUnit.SECONDS)
          .readTimeout(8, TimeUnit.SECONDS)
          .build()

      suspend fun runSpeedTest() {
          withContext(Dispatchers.IO) {
              currentJob = coroutineContext[Job]
              try {
                  _state.value = SpeedTestState(
                      phase = SpeedTestPhase.PING,
                      statusMessage = "Measuring Ping & Jitter..."
                  )

                  // Phase 1: Ping & Jitter
                  val pingResults = mutableListOf<Float>()
                  val pingUrls = listOf(
                      "https://1.1.1.1/cdn-cgi/trace",
                      "https://dns.google/resolve?name=google.com",
                      "https://speed.cloudflare.com/__down?bytes=0"
                  )

                  for (url in pingUrls) {
                      if (!coroutineContext.isActive) return@withContext
                      val start = System.nanoTime()
                      try {
                          val req = Request.Builder().url(url).build()
                          val call = httpClient.newCall(req)
                          activeCalls.add(call)
                          try {
                              call.execute().use { res ->
                                  val durationMs = (System.nanoTime() - start) / 1_000_000f
                                  if (res.isSuccessful) {
                                      pingResults.add(durationMs)
                                  }
                              }
                          } finally {
                              activeCalls.remove(call)
                          }
                      } catch (e: Exception) {
                          if (e is CancellationException) throw e
                          pingResults.add((14..32).random().toFloat())
                      }
                      delay(80)
                  }

                  val avgPing = if (pingResults.isNotEmpty()) pingResults.average().toFloat() else 18.5f
                  val jitter = if (pingResults.size > 1) {
                      var diffSum = 0f
                      for (i in 0 until pingResults.size - 1) {
                          diffSum += Math.abs(pingResults[i] - pingResults[i + 1])
                      }
                      diffSum / (pingResults.size - 1)
                  } else 1.6f

                  _state.update {
                      it.copy(
                          currentPingMs = avgPing,
                          currentJitterMs = jitter,
                          progress = 0.20f,
                          phase = SpeedTestPhase.DOWNLOAD,
                          statusMessage = "Testing Multi-Stream Download Speed..."
                      )
                  }

                  // Phase 2: Download Stream Test
                  val totalBytesReceived = AtomicLong(0L)
                  val downloadStartTime = System.currentTimeMillis()
                  val downloadDurationTargetMs = 4500L
                  val downloadSpeedSamples = mutableListOf<Float>()

                  val downloadUrls = listOf(
                      "https://speed.cloudflare.com/__down?bytes=25000000",
                      "https://speed.cloudflare.com/__down?bytes=25000000",
                      "https://speed.cloudflare.com/__down?bytes=10000000",
                      "https://speed.cloudflare.com/__down?bytes=10000000"
                  )

                  coroutineScope {
                      val monitorJob = launch {
                          var lastSampleTime = System.currentTimeMillis()
                          var lastSampleBytes = 0L

                          while (isActive && System.currentTimeMillis() - downloadStartTime < downloadDurationTargetMs) {
                              delay(150)
                              val now = System.currentTimeMillis()
                              val currentBytes = totalBytesReceived.get()
                              val elapsed = (now - lastSampleTime).coerceAtLeast(1)
                              val deltaBytes = currentBytes - lastSampleBytes

                              val currentSpeedMbps = ((deltaBytes * 8f) / (elapsed / 1000f)) / 1_000_000f
                              if (currentSpeedMbps > 0) {
                                  downloadSpeedSamples.add(currentSpeedMbps)
                                  val overallProgress = 0.20f + (0.55f * ((now - downloadStartTime).toFloat() / downloadDurationTargetMs).coerceIn(0f, 1f))

                                  _state.update {
                                      it.copy(
                                          currentDownloadMbps = currentSpeedMbps,
                                          activeGaugeSpeedMbps = currentSpeedMbps,
                                          progress = overallProgress,
                                          statusMessage = String.format("Turbo Download: %.1f Mbps", currentSpeedMbps)
                                      )
                                  }
                              }

                              lastSampleBytes = currentBytes
                              lastSampleTime = now
                          }
                      }

                      val downloadWorkers = downloadUrls.map { url ->
                          async(Dispatchers.IO) {
                              val buffer = ByteArrayPool.obtainStreamBuffer()
                              try {
                                  val req = Request.Builder().url(url).build()
                                  val call = httpClient.newCall(req)
                                  activeCalls.add(call)
                                  try {
                                      call.execute().use { response ->
                                          val input: InputStream? = response.body?.byteStream()
                                          if (input != null) {
                                              var read = input.read(buffer)
                                              while (read != -1 && isActive && System.currentTimeMillis() - downloadStartTime < downloadDurationTargetMs) {
                                                  totalBytesReceived.addAndGet(read.toLong())
                                                  read = input.read(buffer)
                                              }
                                          }
                                      }
                                  } finally {
                                      activeCalls.remove(call)
                                  }
                              } catch (e: Exception) {
                                  if (e is CancellationException) throw e
                                  for (step in 1..6) {
                                      if (!isActive) break
                                      totalBytesReceived.addAndGet(3_000_000L)
                                      delay(150)
                                  }
                              } finally {
                                  ByteArrayPool.recycleStreamBuffer(buffer)
                              }
                          }
                      }

                      downloadWorkers.awaitAll()
                      monitorJob.cancel()
                  }

                  val finalDownloadMbps = if (downloadSpeedSamples.isNotEmpty()) {
                      downloadSpeedSamples.takeLast(12).average().toFloat()
                  } else 112.4f

                  _state.update {
                      it.copy(
                          currentDownloadMbps = finalDownloadMbps,
                          progress = 0.75f,
                          phase = SpeedTestPhase.UPLOAD,
                          statusMessage = "Testing Upload Speed..."
                      )
                  }

                  // Phase 3: Upload Stream Test
                  val uploadSpeedSamples = mutableListOf<Float>()
                  for (step in 1..8) {
                      if (!coroutineContext.isActive) return@withContext
                      val baseUpload = (finalDownloadMbps * 0.48f).coerceAtLeast(20f)
                      val currentUpload = (baseUpload + ((-3..6).random().toFloat())).coerceAtLeast(10f)
                      uploadSpeedSamples.add(currentUpload)

                      val overallProgress = 0.75f + (0.25f * (step / 8f))
                      _state.update {
                          it.copy(
                              currentUploadMbps = currentUpload,
                              activeGaugeSpeedMbps = currentUpload,
                              progress = overallProgress,
                              statusMessage = String.format("Upload: %.1f Mbps", currentUpload)
                          )
                      }
                      delay(200)
                  }

                  val finalUploadMbps = if (uploadSpeedSamples.isNotEmpty()) {
                      uploadSpeedSamples.average().toFloat()
                  } else 54.2f

                  // Phase 4: Save Result
                  val currentStrategy = strategyRepository.currentStrategy.value
                  val result = SpeedTestResult(
                      pingMs = avgPing,
                      jitterMs = jitter,
                      downloadMbps = finalDownloadMbps,
                      uploadMbps = finalUploadMbps,
                      serverName = "Cloudflare Global Edge",
                      serverLocation = "Anycast Turbo CDN",
                      strategyName = currentStrategy.name
                  )

                  settingsRepository.saveSpeedTestResult(result)

                  _state.update {
                      it.copy(
                          phase = SpeedTestPhase.COMPLETED,
                          progress = 1.0f,
                          currentDownloadMbps = finalDownloadMbps,
                          currentUploadMbps = finalUploadMbps,
                          activeGaugeSpeedMbps = finalDownloadMbps,
                          statusMessage = "Speed Test Completed",
                          recentResult = result
                      )
                  }
              } catch (e: CancellationException) {
                  // Cancelled cleanly: reset state to IDLE
                  _state.update {
                      it.copy(
                          phase = SpeedTestPhase.IDLE,
                          progress = 0f,
                          activeGaugeSpeedMbps = 0f,
                          statusMessage = "Ready"
                      )
                  }
                  throw e
              } catch (e: Exception) {
                  _state.update {
                      it.copy(
                          phase = SpeedTestPhase.FAILED,
                          statusMessage = "Test completed with fallback data"
                      )
                  }
              } finally {
                  currentJob = null
                  cancelActiveNetworkCalls()
              }
          }
      }

      fun cancelTest() {
          currentJob?.cancel()
          currentJob = null
          cancelActiveNetworkCalls()
          _state.update {
              it.copy(
                  phase = SpeedTestPhase.IDLE,
                  progress = 0f,
                  activeGaugeSpeedMbps = 0f,
                  statusMessage = "Ready"
              )
          }
      }

      private fun cancelActiveNetworkCalls() {
          for (call in activeCalls) {
              try { call.cancel() } catch (_: Exception) {}
          }
          activeCalls.clear()
          try {
              httpClient.dispatcher.cancelAll()
          } catch (_: Exception) {}
      }
  }
  ```

#### Step 3: Update `SpeedTestScreen.kt` for `collectAsStateWithLifecycle` and `DisposableEffect`
- Add imports:
  ```kotlin
  import androidx.compose.runtime.DisposableEffect
  import androidx.lifecycle.compose.collectAsStateWithLifecycle
  ```
- Replace state collection:
  ```kotlin
  val state by speedEngine.state.collectAsStateWithLifecycle()
  val history by settingsRepo.speedTestHistory.collectAsStateWithLifecycle()
  val currentStrategy by strategyRepo.currentStrategy.collectAsStateWithLifecycle()
  ```
- Add `DisposableEffect` on screen exit:
  ```kotlin
  DisposableEffect(Unit) {
      onDispose {
          if (isRunning) {
              speedEngine.cancelTest()
          }
      }
  }
  ```

#### Step 4: Update `UpdateManager.kt` to Persist State Across Navigation
- Add internal `StateFlow<UpdateState>` and a supervised coroutine scope:
  ```kotlin
  private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
  val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

  private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var activeJob: Job? = null

  fun checkForUpdates(currentVersion: String) {
      activeJob?.cancel()
      activeJob = managerScope.launch {
          checkForUpdatesFlow(currentVersion).collect { state ->
              _updateState.value = state
          }
      }
  }

  fun downloadAndInstallUpdate(downloadUrl: String) {
      activeJob?.cancel()
      activeJob = managerScope.launch {
          downloadAndPrepareApk(downloadUrl).collect { state ->
              _updateState.value = state
              if (state is UpdateState.ReadyToInstall) {
                  installApk(state.apkFile)
              }
          }
      }
  }

  fun cancelUpdate() {
      activeJob?.cancel()
      activeJob = null
      _updateState.value = UpdateState.Idle
  }
  ```

#### Step 5: Update `DashboardScreen.kt`, `TrafficScreen.kt`, `SettingsScreen.kt`, and `MainActivity.kt`
- In `MainActivity.kt`:
  - `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
  - `val themePreset by settingsRepo.themePreset.collectAsStateWithLifecycle()`
  - `val darkModePref by settingsRepo.darkModePref.collectAsStateWithLifecycle()`
- In `DashboardScreen.kt`:
  - `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
  - Collect `TrafficMonitor.isVpnActive`, `TrafficMonitor.stats`, `strategyRepo.currentStrategy`, and `TrafficMonitor.recentLogs` with `.collectAsStateWithLifecycle()`.
  - Collect `updateState by updateManager.updateState.collectAsStateWithLifecycle()`.
  - Render update card and progress according to `updateState`.
- In `TrafficScreen.kt`:
  - `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
  - Collect `TrafficMonitor.stats`, `TrafficMonitor.recentLogs`, and `TrafficMonitor.isVpnActive` with `.collectAsStateWithLifecycle()`.
  - Replace `TrafficFilterTab.values()` with `TrafficFilterTab.entries`.
- In `SettingsScreen.kt`:
  - `import androidx.lifecycle.compose.collectAsStateWithLifecycle`
  - Collect all repository flows with `.collectAsStateWithLifecycle()`.
  - Replace `AppThemePreset.values()` with `AppThemePreset.entries`.
  - Collect `updateState by updateManager.updateState.collectAsStateWithLifecycle()`.

---

## 5. Verification Method

To independently verify these improvements:

1. **Dependency Verification**:
   - Verify `androidx.lifecycle:lifecycle-runtime-compose` compiles cleanly:
   - Run: `./gradlew.bat compileDebugKotlin`
2. **Automated Unit Tests**:
   - Run: `./gradlew.bat testDebugUnitTest`
   - Verify test suite passes with 100% success.
3. **Speed Test Cancellation & Structured Concurrency Verification**:
   - Launch speed test in coroutine and cancel immediately; verify `state.value.phase == SpeedTestPhase.IDLE` without throwing unhandled exceptions or setting `SpeedTestPhase.FAILED`.
   - Verify all active OkHttp network calls are cancelled immediately on disposal.
4. **Update Download Cross-Screen Navigation**:
   - Initiate download via `updateManager.downloadAndInstallUpdate(url)`.
   - Navigate from Dashboard to Settings; verify `updateManager.updateState` maintains the same download progress percentage across screens.
5. **Release Build Verification**:
   - Run: `./gradlew.bat assembleRelease` to confirm clean release packaging.
