package com.sourzap.app.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.sourzap.app.R
import com.sourzap.app.torrent.model.TorrentItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val isUpdateAvailable: Boolean,
    val publishedAt: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val release: AppReleaseInfo) : UpdateState()
    data class UpToDate(val release: AppReleaseInfo? = null) : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var activeCheckJob: Job? = null
    private val updateLock = Any()

    // Direct static IP fallbacks for GitHub and all release CDN endpoints
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
            // 1. Try system DNS first
            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: Exception) {}

            // 2. Direct static IP fallback to bypass ISP DNS blocks / poisoning
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

                    if (!isNewer) {
                        try {
                            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                            val updatesDir = File(baseDir, "updates")
                            if (updatesDir.exists()) {
                                updatesDir.listFiles()?.forEach { it.delete() }
                            }
                        } catch (_: Exception) {}
                    }

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
                return // Already downloading
            }
            _updateState.value = UpdateState.Downloading(0.01f, 0L, 1L)
            showProgressNotification(0.01f, 0L, 1L)
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

                var lastNotifyTime = 0L
                var lastNotifyProgress = -1f

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
                                        showCompletedNotification(targetApk)
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

                                    val now = System.currentTimeMillis()
                                    if (now - lastNotifyTime >= 500L || Math.abs(progress - lastNotifyProgress) >= 0.01f) {
                                        lastNotifyTime = now
                                        lastNotifyProgress = progress
                                        showProgressNotification(progress, bytesDownloaded, totalLength)
                                    }

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
                                    showCompletedNotification(targetApk)
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
                            dismissNotification()
                            return@launch
                        }
                        delay(800)
                    }
                }

                if (targetApk.exists() && validateApkIntegrity(targetApk)) {
                    targetApk.setReadable(true, false)
                    _updateState.value = UpdateState.ReadyToInstall(targetApk)
                    showCompletedNotification(targetApk)
                } else {
                    _updateState.value = UpdateState.Error("Download could not be completed")
                    dismissNotification()
                }
            }
        }
    }

    fun downloadAndInstallUpdate(downloadUrl: String) {
        startDownload(downloadUrl)
    }

    fun cancelDownload() {
        synchronized(updateLock) {
            activeDownloadJob?.cancel()
            activeDownloadJob = null
            _updateState.value = UpdateState.Idle
            dismissNotification()
        }
    }

    fun cancelUpdate() {
        cancelDownload()
    }

    fun buildProgressNotification(progress: Float, downloadedBytes: Long, totalBytes: Long): NotificationCompat.Builder {
        val channelId = try {
            context.getString(R.string.update_channel_id)
        } catch (_: Throwable) {
            "sourzap_update_channel"
        }

        val cancelIntent = Intent(context, UpdateCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_UPDATE
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            10,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressPercent = (progress * 100).toInt().coerceIn(0, 100)
        val formattedDownloaded = TorrentItem.formatFileSize(downloadedBytes)
        val formattedTotal = if (totalBytes > 0) TorrentItem.formatFileSize(totalBytes) else "-- MB"
        val contentText = if (totalBytes > 0) {
            "$formattedDownloaded / $formattedTotal ($progressPercent%)"
        } else {
            "$formattedDownloaded downloaded"
        }

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading SourZap Update")
            .setContentText(contentText)
            .setProgress(100, progressPercent, totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancelPendingIntent)
    }

    private fun showProgressNotification(progress: Float, downloadedBytes: Long, totalBytes: Long) {
        try {
            val builder = buildProgressNotification(progress, downloadedBytes, totalBytes)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID_UPDATE, builder.build())
        } catch (_: Throwable) {}
    }

    fun buildCompletedNotification(apkFile: File): NotificationCompat.Builder {
        val channelId = try {
            context.getString(R.string.update_channel_id)
        } catch (_: Throwable) {
            "sourzap_update_channel"
        }

        val installIntent = getInstallIntent(apkFile)
        val installPendingIntent = PendingIntent.getActivity(
            context,
            11,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SourZap Update Ready")
            .setContentText("Download complete • Tap to install")
            .setContentIntent(installPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Install", installPendingIntent)
    }

    private fun showCompletedNotification(apkFile: File) {
        try {
            val builder = buildCompletedNotification(apkFile)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID_UPDATE, builder.build())
        } catch (_: Throwable) {}
    }

    fun dismissNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID_UPDATE)
        } catch (_: Throwable) {}
    }

    fun getInstallIntent(apkFile: File): Intent {
        val apkUri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
        } catch (_: Throwable) {
            Uri.fromFile(apkFile)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    // Cold flow adapters for backwards compatibility or tests
    fun checkForUpdatesFlow(currentVersion: String): Flow<UpdateState> = flow {
        checkForUpdates(currentVersion)
        _updateState.collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    fun downloadAndPrepareApk(downloadUrl: String): Flow<UpdateState> = flow {
        startDownload(downloadUrl)
        _updateState.collect { emit(it) }
    }.flowOn(Dispatchers.IO)

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
            val installIntent = getInstallIntent(apkFile)
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
                    // Standard ZIP/APK Magic Header PK\x03\x04 (0x50, 0x4B, 0x03, 0x04)
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

    companion object {
        const val NOTIFICATION_ID_UPDATE = 1003
        const val ACTION_CANCEL_UPDATE = "com.sourzap.app.ACTION_CANCEL_UPDATE"
    }
}