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
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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

    // Custom DoH DNS for OkHttp: resolves GitHub APIs & CDN assets directly without ISP DNS poisoning
    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                val resolved = runBlocking(Dispatchers.IO) {
                    DohResolver.resolve(hostname)
                }
                if (resolved.isNotEmpty()) resolved else Dns.SYSTEM.lookup(hostname)
            } catch (_: Exception) {
                try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (_: Exception) {
                    when (hostname) {
                        "api.github.com" -> listOf(InetAddress.getByName("20.207.73.85"))
                        "github.com" -> listOf(InetAddress.getByName("20.207.73.82"))
                        "uploads.github.com" -> listOf(InetAddress.getByName("20.207.73.81"))
                        else -> emptyList()
                    }
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

            val latestVersionNormalized = tagName.removePrefix("v").trim()
            val currentVersionNormalized = currentVersion.removePrefix("v").trim()

            val isNewer = isVersionNewer(latestVersionNormalized, currentVersionNormalized)

            val releaseInfo = AppReleaseInfo(
                tagName = tagName,
                versionName = latestVersionNormalized,
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
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetApk = File(updatesDir, "SourZap-update.apk")
        if (targetApk.exists()) targetApk.delete()

        emit(UpdateState.Downloading(0.01f, 0L, 1L))

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "SourZap-Android-App")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(UpdateState.Error("Download failed with HTTP ${response.code}"))
                    return@use
                }

                val body = response.body ?: throw Exception("Empty response body")
                val totalLength = body.contentLength()
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(targetApk)

                val buffer = ByteArray(65536)
                var bytesDownloaded = 0L
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
                outputStream.close()
                inputStream.close()

                emit(UpdateState.ReadyToInstall(targetApk))
            }
        } catch (e: Exception) {
            emit(UpdateState.Error("Download interrupted: ${e.localizedMessage}"))
        }
    }.flowOn(Dispatchers.IO)

    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return

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

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

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