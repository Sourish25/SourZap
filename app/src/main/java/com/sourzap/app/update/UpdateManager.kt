package com.sourzap.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sourzap.app.service.core.DohResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

    fun checkForUpdates(currentVersion: String): Flow<UpdateState> = flow {
        emit(UpdateState.Checking)
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
                emit(UpdateState.Error("Unable to reach GitHub update server"))
                return@flow
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
                emit(UpdateState.UpToDate(null))
                return@flow
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

            if (isNewer) {
                emit(UpdateState.Available(releaseInfo))
            } else {
                emit(UpdateState.UpToDate(releaseInfo))
            }
        } catch (e: Exception) {
            emit(UpdateState.Error(e.message ?: "Failed to check for updates"))
        }
    }.flowOn(Dispatchers.IO)

    fun downloadAndPrepareApk(downloadUrl: String): Flow<UpdateState> = flow {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val updatesDir = File(baseDir, "updates").apply { mkdirs() }
        val targetApk = File(updatesDir, "SourZap-update.apk")
        if (targetApk.exists()) targetApk.delete()

        emit(UpdateState.Downloading(0.01f, 0L, 1L))

        var bytesDownloaded = 0L
        var totalLength = -1L
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
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
                            targetApk.setReadable(true, false)
                            emit(UpdateState.ReadyToInstall(targetApk))
                            return@flow
                        }
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }

                    val body = response.body ?: throw Exception("Empty response body")
                    val contentLen = body.contentLength()
                    if (totalLength <= 0) {
                        totalLength = if (contentLen > 0) contentLen else -1L
                    }

                    val append = (bytesDownloaded > 0 && response.code == 206)
                    val outputStream = FileOutputStream(targetApk, append)
                    val inputStream: InputStream = body.byteStream()
                    val buffer = ByteArray(65536)

                    try {
                        var read = inputStream.read(buffer)
                        while (read != -1) {
                            outputStream.write(buffer, 0, read)
                            bytesDownloaded += read

                            val progress = if (totalLength > 0) {
                                (bytesDownloaded.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
                            } else 0.5f

                            emit(UpdateState.Downloading(progress, bytesDownloaded, totalLength))
                            read = inputStream.read(buffer)
                        }
                        outputStream.flush()
                    } finally {
                        try { outputStream.close() } catch (_: Exception) {}
                        try { inputStream.close() } catch (_: Exception) {}
                    }

                    if (totalLength <= 0 || bytesDownloaded >= totalLength) {
                        // Verify APK integrity (ZIP magic header: 0x50, 0x4B, 0x03, 0x04)
                        if (validateApkIntegrity(targetApk)) {
                            targetApk.setReadable(true, false)
                            emit(UpdateState.ReadyToInstall(targetApk))
                            return@flow
                        } else {
                            throw Exception("Corrupt APK package downloaded")
                        }
                    }
                }
            } catch (e: Exception) {
                if (attempts >= maxAttempts) {
                    emit(UpdateState.Error("Download interrupted: ${e.localizedMessage}"))
                    return@flow
                }
                kotlinx.coroutines.delay(800)
            }
        }

        if (validateApkIntegrity(targetApk)) {
            targetApk.setReadable(true, false)
            emit(UpdateState.ReadyToInstall(targetApk))
        } else {
            emit(UpdateState.Error("Download could not be completed"))
        }
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

    private fun validateApkIntegrity(file: File): Boolean {
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
}