package com.sourzap.app.torrent.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Storage Helper for BitTorrent downloads.
 * Provides safe, scoped-storage-compliant directories on Android 10+ (API 29–35)
 * eliminating EACCES permission denied errors when writing downloaded chunks.
 */
object TorrentStorageHelper {

    private const val DEFAULT_FOLDER_NAME = "SourZap"

    /**
     * Resolves an app-compatible writable save directory for torrent downloads.
     * Strategy:
     * 1. Primary: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)/SourZap
     * 2. Secondary: context.getExternalFilesDir(null)/SourZap
     * 3. Fallback: context.filesDir/SourZap (internal storage, always writable)
     */
    fun getSaveDirectory(context: Context, subDirName: String = DEFAULT_FOLDER_NAME): File {
        val baseDir: File = try {
            val downloadType = Environment.DIRECTORY_DOWNLOADS ?: "Download"
            val extDownloads = context.getExternalFilesDir(downloadType)
            if (extDownloads != null && isWritableOrCreatable(extDownloads)) {
                extDownloads
            } else {
                val extFiles = context.getExternalFilesDir(null)
                if (extFiles != null && isWritableOrCreatable(extFiles)) {
                    extFiles
                } else {
                    context.filesDir
                }
            }
        } catch (_: Throwable) {
            context.filesDir
        }

        val targetDir = if (subDirName.isNotEmpty()) File(baseDir, subDirName) else baseDir
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * Checks if a directory is writable or can be created.
     */
    fun isWritableOrCreatable(dir: File): Boolean {
        return try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory && dir.canWrite()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns the available free space in bytes for the resolved torrent save directory.
     */
    fun getAvailableFreeSpaceBytes(context: Context): Long {
        return try {
            val saveDir = getSaveDirectory(context)
            saveDir.usableSpace
        } catch (_: Throwable) {
            0L
        }
    }
}
