# M3 Exploration Report: SpeedTestEngine UI State Lifecycle & Memory Leak Elimination

**Target Component**: `SpeedTestEngine.kt`, `SpeedTestState.kt`, `SpeedTestScreen.kt`  
**Milestone**: M3 (UI State Lifecycle & Memory Leak Elimination)  
**Author**: `explorer_m3_1`  
**Date**: 2026-08-31  

---

## 1. Observation

A comprehensive source code and execution path audit of `SpeedTestEngine.kt`, `SpeedTestResult.kt`, `SpeedTestScreen.kt`, and related telemetry infrastructure revealed critical coroutine cancellation failures, OkHttp socket/stream leaks, data race hazards, and state clobbering issues.

### 1.1 Detailed Code Audit & Defect Identification

#### Defect 1: Unassigned `currentJob` Variable Renders `cancelTest()` Ineffective
- **Location**: `SpeedTestEngine.kt:37` & `SpeedTestEngine.kt:247-257`
  ```kotlin
  // Line 37
  private var currentJob: Job? = null

  // Lines 247-257
  fun cancelTest() {
      currentJob?.cancel()
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
- **Observation**: `currentJob` is declared as a private field but is **never assigned** in `runSpeedTest()`. When a user taps the "CANCEL TEST" button on `SpeedTestScreen`, `cancelTest()` executes `currentJob?.cancel()`, which evaluates to a no-op on `null`. The background speed test coroutines continue running to completion.

#### Defect 2: Untracked OkHttp `Call` References & Uncancelled Sockets Leaking Bandwidth and Threads
- **Location**: `SpeedTestEngine.kt:66` (Ping Phase) and `SpeedTestEngine.kt:149` (Download Phase)
  ```kotlin
  // Line 66
  httpClient.newCall(req).execute().use { res -> ... }

  // Line 149
  httpClient.newCall(req).execute().use { response ->
      val input: InputStream? = response.body?.byteStream()
      if (input != null) {
          var read = input.read(buffer)
          while (read != -1 && isActive && System.currentTimeMillis() - downloadStartTime < downloadDurationTargetMs) {
              totalBytesReceived.addAndGet(read.toLong())
              read = input.read(buffer)
          }
      }
  }
  ```
- **Observation**: `httpClient.newCall(req)` creates an OkHttp `Call` that is executed directly without retaining a reference to the `Call` object.
- When coroutine cancellation occurs, Java's blocking socket stream read (`input.read(buffer)`) or connection handshake does **not** interrupt or close automatically unless `Call.cancel()` or `Socket.close()` is explicitly invoked.
- OkHttp's dispatcher and worker threads remain blocked in native I/O waiting for 25MB HTTP responses across up to 4 parallel connections (100MB total). This wastes device battery, exhausts cellular/WiFi bandwidth, and holds open socket file descriptors in the `ConnectionPool`.

#### Defect 3: Download Worker Coroutines Catch and Swallow `CancellationException`, Actively Fighting Cancellation
- **Location**: `SpeedTestEngine.kt:159-165`
  ```kotlin
  } catch (_: Exception) {
      // Offline simulated high-throughput fallback
      for (step in 1..6) {
          totalBytesReceived.addAndGet(3_000_000L)
          delay(150)
      }
  } finally {
      ByteArrayPool.recycleStreamBuffer(buffer)
  }
  ```
- **Observation**: The download worker uses `catch (_: Exception)`. In Kotlin Coroutines, `CancellationException` is a subclass of `IllegalStateException` (which inherits from `Exception`).
- When a coroutine is cancelled, Kotlin throws `CancellationException`. The `catch (_: Exception)` block catches it and proceeds into a 6-step loop delaying 150ms per iteration (900ms total), adding 18MB of fake data to `totalBytesReceived`. This actively defies structured coroutine cancellation and keeps worker threads alive.

#### Defect 4: Outer `runSpeedTest()` Catches `CancellationException` and Mutates State to `FAILED`
- **Location**: `SpeedTestEngine.kt:237-244`
  ```kotlin
  } catch (e: Exception) {
      _state.update {
          it.copy(
              phase = SpeedTestPhase.FAILED,
              statusMessage = "Test completed with fallback data"
          )
      }
  }
  ```
- **Observation**: When `runSpeedTest()` is cancelled by the caller's scope (e.g. navigating away from `SpeedTestScreen`), any `delay()` or `awaitAll()` throws `CancellationException`. The outer `catch (e: Exception)` catches this exception and sets `phase = SpeedTestPhase.FAILED` with "Test completed with fallback data".
- Cancellation is not a failure; setting `FAILED` causes erroneous error states in the UI and clobbers `IDLE` / `CANCELLED` state.

#### Defect 5: Data Race & Concurrent Modification on `downloadSpeedSamples`
- **Location**: `SpeedTestEngine.kt:101`, `SpeedTestEngine.kt:125`, `SpeedTestEngine.kt:176`
  ```kotlin
  val downloadSpeedSamples = mutableListOf<Float>() // Line 101
  ...
  downloadSpeedSamples.add(currentSpeedMbps)         // Line 125 (inside monitorJob)
  ...
  downloadSpeedSamples.takeLast(12).average().toFloat() // Line 176 (inside parent coroutine)
  ```
- **Observation**: `downloadSpeedSamples` is an unsynchronized `ArrayList`. It is modified concurrently by `monitorJob` running on a worker thread and read by the parent coroutine after `monitorJob.cancel()`. If `monitorJob` is executing `add()` during cancellation, reading `takeLast()` can throw `ConcurrentModificationException` or produce corrupted data.

#### Defect 6: Missing Re-entrancy / Single-Flight Mutex Guard
- **Location**: `SpeedTestEngine.kt:46`
  ```kotlin
  suspend fun runSpeedTest() = withContext(Dispatchers.IO) { ... }
  ```
- **Observation**: There is no mutex or atomic state check protecting `runSpeedTest()`. If the user rapidly taps the "START SPEED TEST" button or triggers the test from multiple entry points, multiple concurrent speed test instances run simultaneously, contending for `ByteArrayPool` buffers, creating dozens of OkHttp streams, and clobbering `_state`.

#### Defect 7: Screen Navigation Disconnect & Coroutine Scope Leaks
- **Location**: `SpeedTestScreen.kt:87`, `193`, `320`
  ```kotlin
  val scope = rememberCoroutineScope()
  ...
  scope.launch { speedEngine.runSpeedTest() }
  ```
- **Observation**: The speed test is launched from `rememberCoroutineScope()`, which is cancelled when the user navigates away from `SpeedTestScreen` (e.g., via the bottom navigation dock to "dashboard" or "traffic").
- Because of Defects 2, 3, and 4, scope cancellation attempts to abort `runSpeedTest()`, but the blocked OkHttp sockets are not cancelled, download workers enter 900ms delay loops, and the state becomes `FAILED`. The background HTTP downloads keep consuming system sockets until socket timeout or data completion.

#### Defect 8: Absence of Explicit `CANCELLED` State in `SpeedTestPhase`
- **Location**: `SpeedTestResult.kt:20-27`
  ```kotlin
  enum class SpeedTestPhase {
      IDLE,
      PING,
      DOWNLOAD,
      UPLOAD,
      COMPLETED,
      FAILED
  }
  ```
- **Observation**: `SpeedTestPhase` lacks a dedicated `CANCELLED` state. While returning to `IDLE` on reset is desirable, having clear lifecycle differentiation prevents UI flickering and enables precise unit test assertions.

---

## 2. Logic Chain

The causal relationships between observed defects and system failures are structured as follows:

```
[User triggers Speed Test]
       │
       ▼
[runSpeedTest() executes with untracked currentJob & untracked OkHttp Calls]
       │
       ├───> [User clicks "CANCEL TEST" or navigates away]
       │            │
       │            ├──> currentJob?.cancel() is a NO-OP (currentJob is null)
       │            │
       │            ├──> OkHttp Call.execute() remains blocked on Socket I/O
       │            │    └──> TCP sockets remain open; 100MB download payload continues streaming
       │            │
       │            ├──> downloadWorkers catch CancellationException as generic Exception
       │            │    └──> Workers execute 900ms fallback loop instead of terminating
       │            │
       │            └──> Outer catch (e: Exception) sets phase = FAILED
       │                 └──> UI flashes error state "Test completed with fallback data"
       │
       └───> [User rapidly clicks "START" multiple times]
                    │
                    └──> Multiple runSpeedTest() coroutines run concurrently
                         └──> StateFlow clobbered, buffer pool exhausted, data races on ArrayList
```

### Specific Inferences:
1. **Socket Lifetime & Resource Leak**: Standard JVM `InputStream.read()` on a socket does not respond to Thread/Coroutine interrupts unless the socket is closed or `Call.cancel()` is called. Without tracking `Call` references in a thread-safe registry, sockets are guaranteed to leak until remote EOF or socket timeout (8 seconds).
2. **Cancellation Propagation**: In structured concurrency, catching `CancellationException` without re-throwing breaks the cooperative cancellation protocol. Catching it in `downloadWorkers` and simulating fake traffic directly violates coroutine safety.
3. **Thread Safety**: Multiple coroutines mutating `downloadSpeedSamples` without synchronization violates Kotlin memory model safety on multi-core ARM/x86 architectures.

---

## 3. Caveats

1. **Simulated vs Real Network Modes**: SourZap includes fallback simulation logic when no network connection or CDN endpoint is reachable. The redesign must preserve graceful offline fallback for genuine network errors (`IOException`, `SocketTimeoutException`) while strictly differentiating them from intentional user cancellation (`CancellationException`).
2. **Compose Lifecycle Scope**: In `SpeedTestScreen`, `rememberCoroutineScope()` is tied to the composable lifecycle. If the user navigates away, the scope cancels. The engine must cleanly abort all network sockets immediately and reset state to `IDLE` / `CANCELLED` within milliseconds.
3. **OkHttpClient Connection Pool**: OkHttp reuses pooled connections. Cancelling active calls must close in-flight streams without corrupting the shared `ConnectionPool(16, 5, TimeUnit.MINUTES)`.

---

## 4. Conclusion & Proposed Implementation Plan

### 4.1 Architectural Design

The redesigned `SpeedTestEngine` introduces:
1. **Thread-Safe Active `Call` Registry**: `private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())` tracks every active Ping, Download, and Upload `Call`.
2. **Immediate Socket Abort via `cancelAllActiveCalls()`**: Iterates through all registered `Call` references and invokes `call.cancel()`, followed by `httpClient.dispatcher.cancelAll()`.
3. **Proper Job Capture & Single-Flight Mutex**: `private val testMutex = Mutex()` prevents duplicate concurrent runs; `currentJob = coroutineContext.job` enables explicit cancellation.
4. **Strict `CancellationException` Re-Throwing**: Both worker-level and engine-level `try/catch` blocks re-throw `CancellationException` immediately.
5. **Guaranteed Buffer Recycling**: All `ByteArrayPool` buffers are obtained inside `try` and recycled inside `finally` blocks.
6. **Thread-Safe Metrics Telemetry**: `CopyOnWriteArrayList<Float>` or synchronized collections for speed samples.
7. **Atomic State Transitions**: `withContext(NonCancellable)` ensures cleanup and state reset cannot be interrupted.

### 4.2 Proposed Code Replacements

#### Target File 1: `com/sourzap/app/data/model/SpeedTestResult.kt`
```kotlin
package com.sourzap.app.data.model

data class SpeedTestResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val pingMs: Float = 0f,
    val jitterMs: Float = 0f,
    val downloadMbps: Float = 0f,
    val uploadMbps: Float = 0f,
    val serverName: String = "Cloudflare Edge",
    val serverLocation: String = "Closest CDN Node",
    val strategyName: String = "YouTube Turbo Fix"
) {
    fun formattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}

enum class SpeedTestPhase {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val currentPingMs: Float = 0f,
    val currentJitterMs: Float = 0f,
    val currentDownloadMbps: Float = 0f,
    val currentUploadMbps: Float = 0f,
    val progress: Float = 0f, // 0.0 to 1.0
    val activeGaugeSpeedMbps: Float = 0f,
    val statusMessage: String = "Ready to test speed",
    val recentResult: SpeedTestResult? = null
)
```

#### Target File 2: `com/sourzap/app/speedtest/SpeedTestEngine.kt`
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class SpeedTestEngine(
    private val settingsRepository: SettingsRepository,
    private val strategyRepository: StrategyRepository
) {
    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    @Volatile
    private var currentJob: Job? = null
    private val runMutex = Mutex()

    // Active OkHttp calls registry for deterministic socket cancellation
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    // High-Throughput HTTP Client with connection pooling
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun runSpeedTest() = withContext(Dispatchers.IO) {
        if (!runMutex.tryLock()) {
            // Already running; avoid re-entrant concurrent execution
            return@withContext
        }

        currentJob = coroutineContext.job

        try {
            _state.value = SpeedTestState(
                phase = SpeedTestPhase.PING,
                statusMessage = "Measuring Ping & Jitter..."
            )

            // Phase 1: High-Precision Ping & Jitter (multi-probe)
            val pingResults = mutableListOf<Float>()
            val pingUrls = listOf(
                "https://1.1.1.1/cdn-cgi/trace",
                "https://dns.google/resolve?name=google.com",
                "https://speed.cloudflare.com/__down?bytes=0"
            )

            for (url in pingUrls) {
                if (!coroutineContext.isActive) throw CancellationException("Speed test cancelled during ping")
                val start = System.nanoTime()
                try {
                    val req = Request.Builder().url(url).build()
                    executeTrackedCall(req) { res ->
                        val durationMs = (System.nanoTime() - start) / 1_000_000f
                        if (res.isSuccessful) {
                            pingResults.add(durationMs)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
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

            if (!coroutineContext.isActive) throw CancellationException("Speed test cancelled after ping")

            _state.update {
                it.copy(
                    currentPingMs = avgPing,
                    currentJitterMs = jitter,
                    progress = 0.20f,
                    phase = SpeedTestPhase.DOWNLOAD,
                    statusMessage = "Testing Multi-Stream Download Speed..."
                )
            }

            // Phase 2: Turbo Multi-Stream Parallel Download Test (up to 4 concurrent streams)
            val totalBytesReceived = AtomicLong(0L)
            val downloadStartTime = System.currentTimeMillis()
            val downloadDurationTargetMs = 4500L
            val downloadSpeedSamples = CopyOnWriteArrayList<Float>()

            val downloadUrls = listOf(
                "https://speed.cloudflare.com/__down?bytes=25000000", // 25MB
                "https://speed.cloudflare.com/__down?bytes=25000000", // 25MB
                "https://speed.cloudflare.com/__down?bytes=10000000", // 10MB
                "https://speed.cloudflare.com/__down?bytes=10000000"  // 10MB
            )

            coroutineScope {
                // Monitor coroutine
                val monitorJob = launch {
                    var lastSampleTime = System.currentTimeMillis()
                    var lastSampleBytes = 0L

                    while (isActive && System.currentTimeMillis() - downloadStartTime < downloadDurationTargetMs) {
                        delay(150)
                        val now = System.currentTimeMillis()
                        val currentBytes = totalBytesReceived.get()
                        val elapsed = (now - lastSampleTime).coerceAtLeast(1)
                        val deltaBytes = (currentBytes - lastSampleBytes).coerceAtLeast(0L)

                        val currentSpeedMbps = ((deltaBytes * 8f) / (elapsed / 1000f)) / 1_000_000f
                        if (currentSpeedMbps > 0) {
                            downloadSpeedSamples.add(currentSpeedMbps)
                            val overallProgress = 0.20f + (0.55f * ((now - downloadStartTime).toFloat() / downloadDurationTargetMs).coerceIn(0f, 1f))

                            _state.update {
                                it.copy(
                                    currentDownloadMbps = currentSpeedMbps,
                                    activeGaugeSpeedMbps = currentSpeedMbps,
                                    progress = overallProgress.coerceIn(0.20f, 0.75f),
                                    statusMessage = String.format("Turbo Download: %.1f Mbps", currentSpeedMbps)
                                )
                            }
                        }

                        lastSampleBytes = currentBytes
                        lastSampleTime = now
                    }
                }

                // Parallel download streams
                val downloadWorkers = downloadUrls.map { url ->
                    async(Dispatchers.IO) {
                        val buffer = ByteArrayPool.obtainStreamBuffer()
                        val req = Request.Builder().url(url).build()
                        val call = httpClient.newCall(req)
                        activeCalls.add(call)

                        try {
                            call.execute().use { response ->
                                val input: InputStream? = response.body?.byteStream()
                                if (input != null) {
                                    var read = input.read(buffer)
                                    while (read != -1 && isActive && (System.currentTimeMillis() - downloadStartTime < downloadDurationTargetMs)) {
                                        totalBytesReceived.addAndGet(read.toLong())
                                        read = input.read(buffer)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            // Offline simulated high-throughput fallback for genuine network failures
                            if (isActive && !coroutineContext.job.isCancelled) {
                                for (step in 1..6) {
                                    if (!isActive) break
                                    totalBytesReceived.addAndGet(3_000_000L)
                                    delay(150)
                                }
                            }
                        } finally {
                            activeCalls.remove(call)
                            ByteArrayPool.recycleStreamBuffer(buffer)
                        }
                    }
                }

                try {
                    downloadWorkers.awaitAll()
                } finally {
                    monitorJob.cancel()
                }
            }

            if (!coroutineContext.isActive) throw CancellationException("Speed test cancelled after download")

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
                if (!coroutineContext.isActive) throw CancellationException("Speed test cancelled during upload")
                val baseUpload = (finalDownloadMbps * 0.48f).coerceAtLeast(20f)
                val currentUpload = (baseUpload + ((-3..6).random().toFloat())).coerceAtLeast(10f)
                uploadSpeedSamples.add(currentUpload)

                val overallProgress = 0.75f + (0.25f * (step / 8f))
                _state.update {
                    it.copy(
                        currentUploadMbps = currentUpload,
                        activeGaugeSpeedMbps = currentUpload,
                        progress = overallProgress.coerceIn(0.75f, 1.0f),
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
            withContext(NonCancellable) {
                cancelAllActiveCalls()
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.IDLE,
                        progress = 0f,
                        activeGaugeSpeedMbps = 0f,
                        statusMessage = "Ready to test speed"
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                cancelAllActiveCalls()
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.FAILED,
                        statusMessage = "Test completed with fallback data"
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                cancelAllActiveCalls()
                currentJob = null
                runMutex.unlock()
            }
        }
    }

    private inline fun executeTrackedCall(request: Request, block: (okhttp3.Response) -> Unit) {
        val call = httpClient.newCall(request)
        activeCalls.add(call)
        try {
            call.execute().use { response ->
                block(response)
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun cancelAllActiveCalls() {
        val iterator = activeCalls.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            try {
                call.cancel()
            } catch (_: Exception) {}
            iterator.remove()
        }
        try {
            httpClient.dispatcher.cancelAll()
        } catch (_: Exception) {}
    }

    fun cancelTest() {
        val jobToCancel = currentJob
        jobToCancel?.cancel()
        cancelAllActiveCalls()
        _state.update {
            it.copy(
                phase = SpeedTestPhase.IDLE,
                progress = 0f,
                activeGaugeSpeedMbps = 0f,
                statusMessage = "Ready to test speed"
            )
        }
    }
}
```

#### Target File 3: `com/sourzap/app/ui/speedtest/SpeedTestScreen.kt` Integration
- Update `isRunning` check:
  ```kotlin
  val isRunning = state.phase != SpeedTestPhase.IDLE && 
                  state.phase != SpeedTestPhase.COMPLETED && 
                  state.phase != SpeedTestPhase.FAILED && 
                  state.phase != SpeedTestPhase.CANCELLED
  ```
- Add clean lifecycle disposal via `DisposableEffect`:
  ```kotlin
  DisposableEffect(Unit) {
      onDispose {
          if (speedEngine.state.value.phase != SpeedTestPhase.COMPLETED) {
              speedEngine.cancelTest()
          }
      }
  }
  ```

---

### 4.3 Corner Cases & Failure Modes Matrix

| Scenario / Edge Case | Failure Mode in Current Code | Behavior in Proposed Design |
|----------------------|------------------------------|-----------------------------|
| **User taps "Cancel Test" during Download** | `currentJob` is null; OkHttp sockets keep downloading 100MB; workers delay 900ms. | `cancelTest()` cancels `currentJob` and calls `call.cancel()` on all 4 sockets; streams abort immediately; state resets to `IDLE` within <10ms. |
| **User switches tabs during Ping/Download** | `rememberCoroutineScope` cancels; `catch (e: Exception)` catches it and sets `phase = FAILED`. | `CancellationException` re-thrown; `NonCancellable` block clears sockets and resets state to `IDLE`. |
| **Rapid Double-Tapping "START"** | Spawns 2 concurrent `runSpeedTest()` coroutines, clobbering StateFlow. | `runMutex.tryLock()` immediately drops the redundant second invocation. |
| **Complete Network Drop / Airplane Mode** | Throws `UnknownHostException` / `SocketException`. | Caught cleanly in `catch (e: IOException)`; uses fallback simulation or sets `FAILED` without crashing or hanging. |
| **Buffer Exhaustion under Interruption** | Buffers might not be recycled if cancellation occurs between `obtain` and `recycle`. | `obtainStreamBuffer()` placed inside `try/finally` so `recycleStreamBuffer()` is guaranteed on cancellation or error. |
| **Extreme Latency / Stalled Server Connection** | Hangs indefinitely or up to 8s read timeout. | Worker checks `(now - downloadStartTime < downloadDurationTargetMs)`; `cancelTest()` breaks stalled socket read via `call.cancel()`. |

---

## 5. Verification Method & Test Strategy

### 5.1 Automated Unit Test Plan (`SpeedTestEngineTest.kt`)
A comprehensive unit test suite should be created at `app/src/test/java/com/sourzap/app/SpeedTestEngineTest.kt` verifying:

1. **`testRunSpeedTest_CompleteLifecycle()`**:
   - Executes `engine.runSpeedTest()` to completion.
   - Asserts state transitions through `PING -> DOWNLOAD -> UPLOAD -> COMPLETED`.
   - Asserts `progress == 1.0f`, `currentDownloadMbps > 0`, `recentResult != null`.
   - Asserts history entry is saved in `SettingsRepository`.

2. **`testCancelTest_ImmediateSocketAbortAndStateReset()`**:
   - Launches `engine.runSpeedTest()` in a test coroutine scope.
   - Waits for `DOWNLOAD` phase.
   - Calls `engine.cancelTest()`.
   - Asserts all active OkHttp calls are cancelled (`call.isCanceled() == true`).
   - Asserts state immediately returns to `SpeedTestPhase.IDLE` with `progress == 0f` and `activeGaugeSpeedMbps == 0f`.
   - Asserts all `ByteArrayPool` buffers are recycled.

3. **`testConcurrentInvocations_SingleFlightExclusivity()`**:
   - Launches 5 concurrent coroutines calling `engine.runSpeedTest()`.
   - Verifies that only one test executes and `runMutex` prevents re-entrancy.

4. **`testCoroutineScopeCancellation_PropagatesCleanly()`**:
   - Launches `engine.runSpeedTest()` inside a cancellable Job.
   - Cancels the Job during download phase.
   - Verifies that `CancellationException` is not swallowed as `FAILED`, and state cleans up to `IDLE`.

### 5.2 Build & Test Commands
```bash
# Run unit tests to verify 100% test pass
./gradlew.bat testDebugUnitTest

# Verify release compilation
./gradlew.bat assembleRelease
```
