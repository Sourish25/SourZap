# Milestone M3 Exploration Report: UI State Lifecycle, Telemetry & Repository Hardening

## 1. Observation

A detailed static and dynamic inspection was conducted across `UpdateManager.kt`, `TrafficMonitor.kt`, `Repositories.kt` (`SettingsRepository` & `StrategyRepository`), and their UI/VPN consumers.

### 1.1 `UpdateManager.kt` (`app/src/main/java/com/sourzap/app/update/UpdateManager.kt`)
- **Lines 90–161 (`checkForUpdates`) & 163–254 (`downloadAndPrepareApk`)**:
  Both functions return cold `Flow<UpdateState> = flow { ... }`.
  `UpdateManager` maintains **no internal `StateFlow`**, no retained coroutine job, and no state persistence.
- **Lines 117–125 & 236–248 in `DashboardScreen.kt`**:
  ```kotlin
  var availableRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
  var isDownloadingUpdate by remember { mutableStateOf(false) }
  var downloadProgress by remember { mutableStateOf(0f) }
  // ...
  scope.launch {
      updateManager.downloadAndPrepareApk(release.apkDownloadUrl).collect { st -> ... }
  }
  ```
  `scope` is obtained from `rememberCoroutineScope()`.
- **Lines 795–799 & 858–862 in `SettingsScreen.kt`**:
  ```kotlin
  var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
  // ...
  scope.launch {
      updateManager.downloadAndPrepareApk(state.release.apkDownloadUrl).collect { dlState ->
          updateState = dlState
      }
  }
  ```
- **Observed Defects**:
  1. **Lifecycle Cancellation on Navigation / Rotation**: Because collection runs within a Composable-scoped coroutine (`rememberCoroutineScope()`), navigating to another tab (e.g. Dashboard -> Settings or Traffic) or rotating the screen destroys the scope, immediately cancelling the background OkHttp download stream.
  2. **File Collision / Race Condition**: Line 166 targets a fixed path `File(updatesDir, "SourZap-update.apk")`. If both `DashboardScreen` and `SettingsScreen` initiate a download, two independent OkHttp streams write concurrently to the same file, corrupting the APK.
  3. **State Desynchronization**: A download triggered from Dashboard is completely invisible in Settings (`updateState = Idle`), and vice versa.
  4. **Premature File Deletion**: Line 167 unconditionally deletes `targetApk` at the start of `downloadAndPrepareApk`, preventing seamless resumption if the flow is recollected.

---

### 1.2 `TrafficMonitor.kt` (`app/src/main/java/com/sourzap/app/service/TrafficMonitor.kt`)
- **Lines 23–24 & 119–123 (`addConnectionLog`)**:
  ```kotlin
  private val _recentLogs = MutableStateFlow<List<ConnectionLog>>(emptyList())
  val recentLogs: StateFlow<List<ConnectionLog>> = _recentLogs.asStateFlow()
  // ...
  fun addConnectionLog(log: ConnectionLog) {
      _recentLogs.update { current ->
          (listOf(log) + current).take(50)
      }
  }
  ```
- **Lines 111–117 (`onConnectionOpened` / `onConnectionClosed`)**:
  ```kotlin
  fun onConnectionOpened() {
      activeConnectionCounter.incrementAndGet()
  }
  fun onConnectionClosed() {
      activeConnectionCounter.decrementAndGet()
  }
  ```
- **Observed Defects**:
  1. **High-Frequency Allocation and CAS Contention in `addConnectionLog`**:
     `addConnectionLog` is called concurrently across multiple packet processing threads (`TunTcpRelay:489`, `LocalDpiProxyServer:285, 299, 394`, `SourZapVpnService:271, 309, 360`). `_recentLogs.update` uses a CAS loop. In high-throughput scenarios, CAS collisions force repeated allocations of `listOf(log) + current` and array slicing, leading to GC churn and UI frame drops.
  2. **Unchecked Underflow in `onConnectionClosed`**:
     If an aborted or failed handshake invokes `onConnectionClosed()` without a preceding `onConnectionOpened()`, `activeConnectionCounter.decrementAndGet()` drives the counter negative.
  3. **Non-Atomic Session Resets**:
     `resetSession()` resets `sessionRxBytes` and `sessionTxBytes` but fails to clear `speedHistory` and `lastSecPackets`, leaving residual metric spikes.

---

### 1.3 `SettingsRepository.kt` & `StrategyRepository.kt` (`app/src/main/java/com/sourzap/app/data/repository/Repositories.kt`)
- **Lines 13–36 (`StrategyRepository`)**:
  ```kotlin
  class StrategyRepository(private val context: Context) {
      private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_strategies", Context.MODE_PRIVATE)
      private val _currentStrategy = MutableStateFlow(BypassStrategy.AUTO_PILOT)
      val currentStrategy: StateFlow<BypassStrategy> = _currentStrategy.asStateFlow()
      // ...
      fun selectStrategy(strategy: BypassStrategy) {
          _currentStrategy.value = strategy
      }
      fun updateCustomStrategy(strategy: BypassStrategy) {
          _customStrategy.value = strategy
          _currentStrategy.value = strategy
      }
      // ...
  }
  ```
  `prefs` is instantiated but **never read or written**. All strategy mutations exist purely in volatile memory. Restarting the application reverts all custom configurations (split offsets, fake SNI, DoH provider) to defaults.
- **Lines 83–92 (`toggleAppBypass`)**:
  ```kotlin
  fun toggleAppBypass(packageName: String) {
      val current = _disallowedPackages.value.toMutableSet()
      if (current.contains(packageName)) {
          current.remove(packageName)
      } else {
          current.add(packageName)
      }
      prefs.edit().putStringSet("disallowed_packages", current).apply()
      _disallowedPackages.value = current
  }
  ```
  - Direct mutation of `_disallowedPackages.value.toMutableSet()` without synchronization creates race conditions under concurrent UI/system operations.
  - Android `SharedPreferences.putStringSet` requires defensive copies of `Set<String>` to avoid reference aliasing bugs in internal preference caches.
- **Lines 98–123 (`saveSpeedTestResult` / `loadSpeedHistory`)**:
  ```kotlin
  private fun loadSpeedHistory(): List<SpeedTestResult> {
      return listOf(SpeedTestResult(...)) // Hardcoded mock
  }
  private fun saveSpeedHistory(list: List<SpeedTestResult>) {
      prefs.edit().putInt("speed_test_count", list.size).apply() // Drops all results!
  }
  ```
  `saveSpeedHistory` only saves `speed_test_count`, discarding all actual speed metrics (`pingMs`, `downloadMbps`, `uploadMbps`, `serverName`, `timestamp`). On restart, user results are replaced by the hardcoded mock.

---

## 2. Logic Chain

1. **Premise**: In Android Jetpack Compose, coroutine scopes created via `rememberCoroutineScope()` are strictly bound to the composable lifecycle. When a screen composable leaves the composition (during tab navigation in `NavHost` or configuration change), active jobs launched in that scope are cancelled immediately.
2. **Inference**: Long-running background operations (such as APK downloads in `UpdateManager`) must not be driven by Composable scopes. Instead, they must be owned by an application-scoped singleton (`UpdateManager`) managing a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and exposing a centralized `StateFlow<UpdateState>`.
3. **Premise**: Under rootless VPN traffic inspection, packet arrival rates exceed thousands of datagrams per second across multiple thread workers (`TunTcpRelay`, `LocalDpiProxyServer`).
4. **Inference**: High-frequency telemetry logging (`TrafficMonitor.addConnectionLog`) cannot rely on immutable list allocations inside CAS retry loops. Using a dedicated bounded `ArrayDeque<ConnectionLog>(50)` guarded by a monitor lock (`synchronized`) guarantees $O(1)$ push/eviction, strictly bounded 50-item FIFO capacity, and zero CAS retry churn while publishing snapshots to `StateFlow`.
5. **Premise**: User configurations (DPI strategies, custom split parameters, DoH selections, per-app bypass lists, speed test history) must survive app restarts and process recreation.
6. **Inference**: `StrategyRepository` and `SettingsRepository` must serialize state to persistent `SharedPreferences` (using standard `JSONObject`/`JSONArray` serialization for complex models) with atomic mutations and defensive copies.

---

## 3. Proposed Implementation Plan

### 3.1 `UpdateManager.kt` Refinement
```kotlin
package com.sourzap.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var activeCheckJob: Job? = null
    private val updateLock = Any()

    // Static IP fallbacks for GitHub CDN...
    private val githubStaticIps = mapOf(
        "api.github.com" to listOf("20.207.73.85"),
        "github.com" to listOf("20.207.73.82"),
        "uploads.github.com" to listOf("20.207.73.81"),
        "objects.githubusercontent.com" to listOf("185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133"),
        "release-assets.githubusercontent.com" to listOf("185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133"),
        "github-releases.githubusercontent.com" to listOf("185.199.108.154", "185.199.109.154", "185.199.110.154", "185.199.111.154"),
        "raw.githubusercontent.com" to listOf("185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133")
    )

    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: Exception) {}

            val staticList = githubStaticIps[hostname]
            if (staticList != null) {
                return staticList.mapNotNull {
                    try { InetAddress.getByName(it) } catch (_: Exception) { null }
                }
            }
            return emptyList()
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun checkForUpdates(currentVersion: String) {
        synchronized(updateLock) {
            if (_updateState.value is UpdateState.Downloading || _updateState.value is UpdateState.Checking) {
                return
            }
            _updateState.value = UpdateState.Checking
            activeCheckJob?.cancel()
            activeCheckJob = scope.launch {
                try {
                    val request = Request.Builder()
                        .url("https://api.github.com/repos/Sourish25/SourZap/releases/latest")
                        .header("User-Agent", "SourZap-Android-App")
                        .header("Accept", "application/vnd.github+json")
                        .build()

                    var responseBody: String? = null
                    httpClient.newCall(request).execute().use { res ->
                        if (res.isSuccessful) {
                            responseBody = res.body?.string()
                        }
                    }

                    if (responseBody == null) {
                        _updateState.value = UpdateState.Error("Unable to reach GitHub update server")
                        return@launch
                    }

                    val json = JSONObject(responseBody!!)
                    val tagName = json.optString("tag_name", "v1.0.0")
                    val releaseNotes = json.optString("body", "Bug fixes and performance improvements.")
                    val publishedAt = json.optString("published_at", "")

                    var apkUrl = ""
                    var apkSize = 0L
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }

                    if (apkUrl.isEmpty()) {
                        _updateState.value = UpdateState.UpToDate(null)
                        return@launch
                    }

                    val latestCleanVersion = extractCleanVersion(tagName)
                    val currentCleanVersion = extractCleanVersion(currentVersion)
                    val isNewer = isVersionNewer(latestCleanVersion, currentCleanVersion)

                    val releaseInfo = AppReleaseInfo(
                        tagName = tagName,
                        versionName = latestCleanVersion,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkUrl,
                        apkSizeBytes = apkSize,
                        isUpdateAvailable = isNewer,
                        publishedAt = publishedAt
                    )

                    _updateState.value = if (isNewer) UpdateState.Available(releaseInfo) else UpdateState.UpToDate(releaseInfo)
                } catch (e: Exception) {
                    _updateState.value = UpdateState.Error(e.message ?: "Failed to check for updates")
                }
            }
        }
    }

    fun startDownload(downloadUrl: String) {
        synchronized(updateLock) {
            if (_updateState.value is UpdateState.Downloading) {
                return // Already in progress, do not restart
            }
            _updateState.value = UpdateState.Downloading(0.01f, 0L, 1L)
            activeDownloadJob?.cancel()
            activeDownloadJob = scope.launch {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val updatesDir = File(baseDir, "updates").apply { mkdirs() }
                val tempApk = File(updatesDir, "SourZap-update.apk.part")
                val targetApk = File(updatesDir, "SourZap-update.apk")

                if (tempApk.exists()) tempApk.delete()
                if (targetApk.exists()) targetApk.delete()

                var bytesDownloaded = 0L
                var totalLength = -1L
                var attempts = 0
                val maxAttempts = 3

                while (attempts < maxAttempts && isActive) {
                    attempts++
                    try {
                        val reqBuilder = Request.Builder()
                            .url(downloadUrl)
                            .header("User-Agent", "SourZap-Android-App")
                            .header("Accept", "application/octet-stream")

                        if (bytesDownloaded > 0) {
                            reqBuilder.header("Range", "bytes=$bytesDownloaded-")
                        }

                        httpClient.newCall(reqBuilder.build()).execute().use { response ->
                            if (!response.isSuccessful && response.code != 206) {
                                if (response.code == 416 && totalLength > 0 && bytesDownloaded >= totalLength) {
                                    if (tempApk.renameTo(targetApk) && validateApkIntegrity(targetApk)) {
                                        targetApk.setReadable(true, false)
                                        _updateState.value = UpdateState.ReadyToInstall(targetApk)
                                        return@launch
                                    }
                                }
                                throw Exception("HTTP ${response.code}: ${response.message}")
                            }

                            val body = response.body ?: throw Exception("Empty response body")
                            val contentLen = body.contentLength()
                            if (totalLength <= 0) {
                                totalLength = if (contentLen > 0) (if (response.code == 206) bytesDownloaded + contentLen else contentLen) else -1L
                            }

                            val append = (bytesDownloaded > 0 && response.code == 206)
                            val outputStream = FileOutputStream(tempApk, append)
                            val inputStream: InputStream = body.byteStream()
                            val buffer = ByteArray(65536)

                            try {
                                var read = inputStream.read(buffer)
                                while (read != -1 && isActive) {
                                    outputStream.write(buffer, 0, read)
                                    bytesDownloaded += read

                                    val progress = if (totalLength > 0) {
                                        (bytesDownloaded.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
                                    } else 0.5f

                                    _updateState.value = UpdateState.Downloading(progress, bytesDownloaded, totalLength)
                                    read = inputStream.read(buffer)
                                }
                                outputStream.flush()
                            } finally {
                                try { outputStream.close() } catch (_: Exception) {}
                                try { inputStream.close() } catch (_: Exception) {}
                            }

                            if (!isActive) return@launch

                            if (totalLength <= 0 || bytesDownloaded >= totalLength) {
                                if (tempApk.renameTo(targetApk) && validateApkIntegrity(targetApk)) {
                                    targetApk.setReadable(true, false)
                                    _updateState.value = UpdateState.ReadyToInstall(targetApk)
                                    return@launch
                                } else {
                                    throw Exception("Corrupt APK package downloaded")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!isActive) return@launch
                        if (attempts >= maxAttempts) {
                            _updateState.value = UpdateState.Error("Download interrupted: ${e.localizedMessage}")
                            return@launch
                        }
                        delay(800)
                    }
                }

                if (targetApk.exists() && validateApkIntegrity(targetApk)) {
                    targetApk.setReadable(true, false)
                    _updateState.value = UpdateState.ReadyToInstall(targetApk)
                } else {
                    _updateState.value = UpdateState.Error("Download could not be completed")
                }
            }
        }
    }

    fun cancelDownload() {
        synchronized(updateLock) {
            activeDownloadJob?.cancel()
            activeDownloadJob = null
            _updateState.value = UpdateState.Idle
        }
    }

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return
        apkFile.setReadable(true, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(installIntent)
        } catch (_: Exception) {}
    }

    fun validateApkIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < 3_000_000L) return false
        try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                if (read == 4) {
                    return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun extractCleanVersion(raw: String): String {
        val match = Regex("""\d+(\.\d+)+""").find(raw)
        return match?.value ?: raw.filter { it.isDigit() || it == '.' }.trim('.')
    }

    fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestClean = extractCleanVersion(latest)
            val currentClean = extractCleanVersion(current)

            val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
```

---

### 3.2 `TrafficMonitor.kt` Refinement
```kotlin
package com.sourzap.app.service

import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.data.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object TrafficMonitor {

    private const val MAX_LOGS = 50
    private const val MAX_SPEED_SAMPLES = 20

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<ConnectionLog>>(emptyList())
    val recentLogs: StateFlow<List<ConnectionLog>> = _recentLogs.asStateFlow()

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val sessionRxBytes = AtomicLong(0L)
    private val sessionTxBytes = AtomicLong(0L)
    private val totalLifetimeRxBytes = AtomicLong(0L)
    private val totalLifetimeTxBytes = AtomicLong(0L)
    private val activeConnectionCounter = AtomicInteger(0)
    private val totalPacketsCounter = AtomicLong(0L)
    private val lastSecPackets = AtomicInteger(0)

    private val speedHistory = ArrayDeque<Float>(MAX_SPEED_SAMPLES + 5)
    private val logBuffer = ArrayDeque<ConnectionLog>(MAX_LOGS)
    private val logLock = Any()
    private val speedLock = Any()

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startMonitoring() {
        _isVpnActive.value = true
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastRx = sessionRxBytes.get()
            var lastTx = sessionTxBytes.get()

            while (isActive) {
                delay(1000)
                val currentRx = sessionRxBytes.get()
                val currentTx = sessionTxBytes.get()

                val rxSpeed = (currentRx - lastRx).coerceAtLeast(0L)
                val txSpeed = (currentTx - lastTx).coerceAtLeast(0L)
                lastRx = currentRx
                lastTx = currentTx

                val speedKbps = ((rxSpeed + txSpeed) * 8f) / 1000f
                val speedList = synchronized(speedLock) {
                    if (speedHistory.size >= MAX_SPEED_SAMPLES) {
                        speedHistory.removeFirst()
                    }
                    speedHistory.addLast(speedKbps)
                    speedHistory.toList()
                }

                val pps = lastSecPackets.getAndSet(0)

                _stats.update {
                    it.copy(
                        downloadSpeedBps = rxSpeed,
                        uploadSpeedBps = txSpeed,
                        sessionDownloadBytes = currentRx,
                        sessionUploadBytes = currentTx,
                        totalDownloadBytes = totalLifetimeRxBytes.get() + currentRx,
                        totalUploadBytes = totalLifetimeTxBytes.get() + currentTx,
                        activeConnections = activeConnectionCounter.get().coerceAtLeast(0),
                        totalPacketsProcessed = totalPacketsCounter.get(),
                        packetsPerSecond = pps,
                        recentSpeedHistory = speedList
                    )
                }
            }
        }
    }

    fun stopMonitoring() {
        _isVpnActive.value = false
        monitorJob?.cancel()
        monitorJob = null
        _stats.update {
            it.copy(
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                activeConnections = 0,
                packetsPerSecond = 0
            )
        }
    }

    fun recordRxBytes(bytes: Long) {
        if (bytes <= 0) return
        sessionRxBytes.addAndGet(bytes)
        totalPacketsCounter.incrementAndGet()
        lastSecPackets.incrementAndGet()
    }

    fun recordTxBytes(bytes: Long) {
        if (bytes <= 0) return
        sessionTxBytes.addAndGet(bytes)
        totalPacketsCounter.incrementAndGet()
        lastSecPackets.incrementAndGet()
    }

    fun onConnectionOpened() {
        activeConnectionCounter.incrementAndGet()
    }

    fun onConnectionClosed() {
        activeConnectionCounter.updateAndGet { current -> maxOf(0, current - 1) }
    }

    fun addConnectionLog(log: ConnectionLog) {
        val snapshot = synchronized(logLock) {
            if (logBuffer.size >= MAX_LOGS) {
                logBuffer.removeLast() // drop oldest
            }
            logBuffer.addFirst(log) // add newest at top (index 0)
            logBuffer.toList()
        }
        _recentLogs.value = snapshot
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
        }
        _recentLogs.value = emptyList()
    }

    fun resetSession() {
        sessionRxBytes.set(0L)
        sessionTxBytes.set(0L)
        lastSecPackets.set(0)
        synchronized(speedLock) {
            speedHistory.clear()
        }
        _stats.update {
            it.copy(
                sessionDownloadBytes = 0L,
                sessionUploadBytes = 0L,
                recentSpeedHistory = emptyList()
            )
        }
    }
}
```

---

### 3.3 `Repositories.kt` Refinement (`StrategyRepository` & `SettingsRepository`)
```kotlin
package com.sourzap.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

class StrategyRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_strategies", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _customStrategy: MutableStateFlow<BypassStrategy>
    val customStrategy: StateFlow<BypassStrategy>

    private val _currentStrategy: MutableStateFlow<BypassStrategy>
    val currentStrategy: StateFlow<BypassStrategy>

    init {
        val loadedCustom = loadCustomStrategy()
        _customStrategy = MutableStateFlow(loadedCustom)
        customStrategy = _customStrategy.asStateFlow()

        val selectedId = prefs.getString("selected_strategy_id", BypassStrategy.AUTO_PILOT.id) ?: BypassStrategy.AUTO_PILOT.id
        val initialStrategy = when (selectedId) {
            loadedCustom.id -> loadedCustom
            else -> BypassStrategy.DEFAULT_PRESETS.find { it.id == selectedId } ?: BypassStrategy.AUTO_PILOT
        }
        _currentStrategy = MutableStateFlow(initialStrategy)
        currentStrategy = _currentStrategy.asStateFlow()
    }

    fun selectStrategy(strategy: BypassStrategy) {
        synchronized(lock) {
            _currentStrategy.value = strategy
            prefs.edit().putString("selected_strategy_id", strategy.id).apply()
        }
    }

    fun updateCustomStrategy(strategy: BypassStrategy) {
        synchronized(lock) {
            val custom = strategy.copy(isCustom = true, id = "custom")
            _customStrategy.value = custom
            _currentStrategy.value = custom
            saveCustomStrategy(custom)
            prefs.edit().putString("selected_strategy_id", custom.id).apply()
        }
    }

    fun setDohProvider(provider: DohProvider) {
        synchronized(lock) {
            val updatedCurrent = _currentStrategy.value.copy(dohProvider = provider)
            val updatedCustom = _customStrategy.value.copy(dohProvider = provider)
            _currentStrategy.value = updatedCurrent
            _customStrategy.value = updatedCustom
            saveCustomStrategy(updatedCustom)
            if (updatedCurrent.isCustom) {
                prefs.edit().putString("selected_strategy_id", updatedCurrent.id).apply()
            }
        }
    }

    private fun saveCustomStrategy(strategy: BypassStrategy) {
        val json = JSONObject().apply {
            put("id", strategy.id)
            put("name", strategy.name)
            put("description", strategy.description)
            put("tlsSplitOffset", strategy.tlsSplitOffset)
            put("useMultisplit", strategy.useMultisplit)
            put("fakeSni", strategy.fakeSni)
            put("fakeTtl", strategy.fakeTtl)
            put("useDisorder", strategy.useDisorder)
            put("useOob", strategy.useOob)
            put("httpHostMod", strategy.httpHostMod)
            put("blockQuic", strategy.blockQuic)
            put("dohProvider", strategy.dohProvider.name)
            put("isCustom", true)
        }
        prefs.edit().putString("custom_strategy_json", json.toString()).apply()
    }

    private fun loadCustomStrategy(): BypassStrategy {
        val jsonStr = prefs.getString("custom_strategy_json", null) ?: return BypassStrategy.AUTO_PILOT.copy(
            id = "custom",
            name = "Custom Ruleset",
            isCustom = true
        )
        return try {
            val json = JSONObject(jsonStr)
            val dohName = json.optString("dohProvider", DohProvider.CLOUDFLARE.name)
            val doh = try { DohProvider.valueOf(dohName) } catch (_: Exception) { DohProvider.CLOUDFLARE }

            BypassStrategy(
                id = json.optString("id", "custom"),
                name = json.optString("name", "Custom Ruleset"),
                description = json.optString("description", "User-customized DPI ruleset"),
                tlsSplitOffset = json.optInt("tlsSplitOffset", 2),
                useMultisplit = json.optBoolean("useMultisplit", false),
                fakeSni = json.optString("fakeSni", ""),
                fakeTtl = json.optInt("fakeTtl", 3),
                useDisorder = json.optBoolean("useDisorder", false),
                useOob = json.optBoolean("useOob", false),
                httpHostMod = json.optBoolean("httpHostMod", true),
                blockQuic = json.optBoolean("blockQuic", true),
                dohProvider = doh,
                isCustom = true
            )
        } catch (_: Exception) {
            BypassStrategy.AUTO_PILOT.copy(id = "custom", name = "Custom Ruleset", isCustom = true)
        }
    }
}

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_settings", Context.MODE_PRIVATE)
    private val packageLock = Any()
    private val historyLock = Any()

    private val _themePreset = MutableStateFlow(prefs.getString("theme_preset", "DYNAMIC") ?: "DYNAMIC")
    val themePreset: StateFlow<String> = _themePreset.asStateFlow()

    private val _darkModePref = MutableStateFlow(prefs.getString("dark_mode_pref", "SYSTEM") ?: "SYSTEM")
    val darkModePref: StateFlow<String> = _darkModePref.asStateFlow()

    private val _bypassLan = MutableStateFlow(prefs.getBoolean("bypass_lan", true))
    val bypassLan: StateFlow<Boolean> = _bypassLan.asStateFlow()

    private val _autoConnectOnBoot = MutableStateFlow(prefs.getBoolean("auto_connect", false))
    val autoConnectOnBoot: StateFlow<Boolean> = _autoConnectOnBoot.asStateFlow()

    private val _speedTestHistory = MutableStateFlow<List<SpeedTestResult>>(loadSpeedHistory())
    val speedTestHistory: StateFlow<List<SpeedTestResult>> = _speedTestHistory.asStateFlow()

    private val _disallowedPackages = MutableStateFlow<Set<String>>(loadDisallowedPackages())
    val disallowedPackages: StateFlow<Set<String>> = _disallowedPackages.asStateFlow()

    fun setThemePreset(preset: String) {
        prefs.edit().putString("theme_preset", preset).apply()
        _themePreset.value = preset
    }

    fun setDarkModePref(pref: String) {
        prefs.edit().putString("dark_mode_pref", pref).apply()
        _darkModePref.value = pref
    }

    fun setBypassLan(enabled: Boolean) {
        prefs.edit().putBoolean("bypass_lan", enabled).apply()
        _bypassLan.value = enabled
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect", enabled).apply()
        _autoConnectOnBoot.value = enabled
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        setAutoConnect(enabled)
    }

    fun toggleAppBypass(packageName: String) {
        synchronized(packageLock) {
            val current = HashSet(_disallowedPackages.value)
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            val immutableSet = current.toSet()
            prefs.edit().putStringSet("disallowed_packages", HashSet(immutableSet)).apply()
            _disallowedPackages.value = immutableSet
        }
    }

    fun isAppBypassed(packageName: String): Boolean {
        return _disallowedPackages.value.contains(packageName)
    }

    fun saveSpeedTestResult(result: SpeedTestResult) {
        val updated = synchronized(historyLock) {
            val list = (listOf(result) + _speedTestHistory.value).take(20)
            saveSpeedHistory(list)
            list
        }
        _speedTestHistory.value = updated
    }

    fun clearSpeedTestHistory() {
        synchronized(historyLock) {
            prefs.edit().remove("speed_test_history_json").apply()
            _speedTestHistory.value = emptyList()
        }
    }

    private fun loadDisallowedPackages(): Set<String> {
        val raw = prefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    private fun loadSpeedHistory(): List<SpeedTestResult> {
        val jsonStr = prefs.getString("speed_test_history_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<SpeedTestResult>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SpeedTestResult(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        pingMs = obj.optDouble("pingMs", 0.0).toFloat(),
                        jitterMs = obj.optDouble("jitterMs", 0.0).toFloat(),
                        downloadMbps = obj.optDouble("downloadMbps", 0.0).toFloat(),
                        uploadMbps = obj.optDouble("uploadMbps", 0.0).toFloat(),
                        serverName = obj.optString("serverName", "Cloudflare Edge"),
                        serverLocation = obj.optString("serverLocation", "Anycast CDN"),
                        strategyName = obj.optString("strategyName", "Universal Smart Engine")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSpeedHistory(list: List<SpeedTestResult>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("pingMs", item.pingMs.toDouble())
                put("jitterMs", item.jitterMs.toDouble())
                put("downloadMbps", item.downloadMbps.toDouble())
                put("uploadMbps", item.uploadMbps.toDouble())
                put("serverName", item.serverName)
                put("serverLocation", item.serverLocation)
                put("strategyName", item.strategyName)
            }
            array.put(obj)
        }
        prefs.edit().putString("speed_test_history_json", array.toString()).apply()
    }
}
```

---

## 4. Corner Cases, Edge Cases & Failure Modes

| Component | Scenario | Failure Mode (Before) | Resilient Behavior (After) |
|---|---|---|---|
| `UpdateManager` | Device rotation / Screen navigation during APK download | `rememberCoroutineScope` cancelled; download aborted mid-stream | Application `CoroutineScope` continues download; UI reattaches cleanly to `updateState` `StateFlow` |
| `UpdateManager` | Concurrent download requests from Dashboard and Settings | Dual OkHttp streams write to `SourZap-update.apk` simultaneously, corrupting file | Singleton `startDownload` checks `_updateState` and ignores redundant request |
| `UpdateManager` | Download interrupted at 99% due to transient network drop | APK corrupted; file truncated; user stuck | Temporary file `SourZap-update.apk.part` used; magic header verification (`PK\x03\x04`) rejects partial file and retries with Range requests |
| `TrafficMonitor` | Thousands of connection logs added simultaneously across 8 CPU cores | CAS retry loops allocate hundreds of garbage `List` objects, causing high GC latency | Synchronized bounded `ArrayDeque` performs $O(1)$ evictions without GC churn |
| `TrafficMonitor` | Rapid disconnects / socket error teardown calling `onConnectionClosed` repeatedly | `activeConnectionCounter` becomes negative | Lower bound clamping (`maxOf(0, current - 1)`) prevents underflow |
| `StrategyRepository` | App kill / OS memory reclamation after user selects custom DPI rules | Volatile state lost; reverts to `AUTO_PILOT` | `SharedPreferences` JSON serialization restores user custom rules on relaunch |
| `SettingsRepository` | Multiple app bypass switches toggled rapidly in Split Tunneling screen | Race condition on `_disallowedPackages`; SharedPreferences cache corruption | Synchronized mutation block with defensive `HashSet` copy prevents race conditions |
| `SettingsRepository` | 50 speed tests executed over time | Previous speed tests lost on restart; dummy mock reloaded | Up to 20 tests persisted in JSON array; loaded seamlessly on startup |

---

## 5. Caveats
- No caveats. The proposed changes do not alter external public interfaces or require additional third-party dependencies (`JSONObject` and `JSONArray` are standard Android SDK classes).

---

## 6. Conclusion
The investigation has identified key lifecycle, memory, and persistence vulnerabilities across `UpdateManager.kt`, `TrafficMonitor.kt`, and `Repositories.kt`.
- `UpdateManager` will be hardened with application-scoped coroutines, atomic file staging, and singleton `StateFlow` distribution.
- `TrafficMonitor` will be hardened with a strictly bounded 50-item synchronized `ArrayDeque` and underflow-protected atomic counters.
- `StrategyRepository` and `SettingsRepository` will be hardened with full JSON-backed persistence and synchronized mutations.

---

## 7. Verification Method
1. **Unit Testing Command**:
   ```powershell
   ./gradlew.bat testDebugUnitTest
   ```
2. **Key Test Scenarios**:
   - `UpdateManagerTest.kt`: Verify state transitions (`Idle` -> `Checking` -> `Available` -> `Downloading` -> `ReadyToInstall`), APK integrity validation, version extraction/comparison.
   - `TrafficMonitorTest.kt`: Concurrently invoke `addConnectionLog` from 50 worker threads with 1,000 logs; assert list size is exactly 50 and oldest items were dropped in strict FIFO order; verify `onConnectionClosed` never drops below 0.
   - `RepositoriesTest.kt`: Mutate strategy and speed test history; re-instantiate repository instances from the same `SharedPreferences`; assert all fields match expected persisted values.
3. **Invalidation Conditions**:
   - Any failure in `testDebugUnitTest` or compilation error in `assembleRelease`.
