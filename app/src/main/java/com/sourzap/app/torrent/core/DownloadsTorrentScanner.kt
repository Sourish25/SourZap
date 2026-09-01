package com.sourzap.app.torrent.core

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Metadata representation of a .torrent file discovered in device storage or MediaStore.
 */
data class DiscoveredTorrentFile(
    val name: String,
    val size: Long,
    val lastModified: Long,
    val uri: Uri? = null,
    val file: File? = null
) {
    /**
     * Safely reads the binary contents of the discovered torrent file.
     * Tries filesystem reading first if file handle is available, then ContentResolver stream.
     */
    fun readBytes(context: Context): ByteArray? {
        return try {
            if (file != null && file.exists() && file.isFile) {
                file.readBytes()
            } else if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Human-readable formatted file size.
     */
    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = size / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
        }
}

/**
 * Scans Android device storage and MediaStore for .torrent files.
 * Provides unified, scoped-storage-compliant discovery across API 26–35+.
 */
object DownloadsTorrentScanner {

    /**
     * Asynchronously scans candidate download directories and MediaStore for BitTorrent files.
     * Deduplicates by lowercased filename (preferring the latest lastModified) and sorts descending by date.
     */
    suspend fun scanDownloads(context: Context): List<DiscoveredTorrentFile> = withContext(Dispatchers.IO) {
        val discoveredMap = mutableMapOf<String, DiscoveredTorrentFile>()

        // 1. Query MediaStore.Downloads on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                val selectionArgs = arrayOf("%.torrent")
                val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Downloads.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        try {
                            val id = if (idCol != -1) cursor.getLong(idCol) else -1L
                            val name = if (nameCol != -1) cursor.getString(nameCol) else null
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                            val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L

                            if (!name.isNullOrBlank() && name.endsWith(".torrent", ignoreCase = true)) {
                                val contentUri = if (id != -1L) ContentUris.withAppendedId(collection, id) else null
                                val lastModifiedMs = if (dateSec > 0L) dateSec * 1000L else System.currentTimeMillis()
                                val item = DiscoveredTorrentFile(
                                    name = name.trim(),
                                    size = size,
                                    lastModified = lastModifiedMs,
                                    uri = contentUri,
                                    file = null
                                )
                                mergeDiscoveredFile(discoveredMap, item)
                            }
                        } catch (_: Throwable) {
                            // Ignore individual row extraction errors
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore MediaStore query errors (e.g. permissions or test environments)
            }
        }

        // 2. Scan Candidate Filesystem Directories
        val candidateDirs = mutableListOf<File?>()
        try {
            val downloadType = try { Environment.DIRECTORY_DOWNLOADS } catch (_: Throwable) { null } ?: "Download"
            candidateDirs.add(Environment.getExternalStoragePublicDirectory(downloadType))
        } catch (_: Throwable) {}
        try {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null) {
                candidateDirs.add(File(extDir, "Download"))
                candidateDirs.add(File(extDir, "Downloads"))
            }
        } catch (_: Throwable) {}
        try {
            val downloadType = try { Environment.DIRECTORY_DOWNLOADS } catch (_: Throwable) { null } ?: "Download"
            candidateDirs.add(context.getExternalFilesDir(downloadType))
        } catch (_: Throwable) {}
        try {
            candidateDirs.add(TorrentStorageHelper.getSaveDirectory(context))
        } catch (_: Throwable) {}
        try {
            candidateDirs.add(context.getExternalFilesDir(null))
        } catch (_: Throwable) {}
        try {
            candidateDirs.add(context.filesDir)
        } catch (_: Throwable) {}

        for (candidateDir in candidateDirs) {
            if (candidateDir == null) continue
            scanDirectoryRecursively(candidateDir, discoveredMap, maxDepth = 2)
        }

        // 3. Convert map to sorted list (descending by lastModified)
        discoveredMap.values.sortedByDescending { it.lastModified }
    }

    /**
     * Scans a directory for .torrent files up to a bounded depth.
     */
    private fun scanDirectoryRecursively(
        dir: File,
        discoveredMap: MutableMap<String, DiscoveredTorrentFile>,
        maxDepth: Int
    ) {
        if (maxDepth < 0) return
        try {
            if (!dir.exists() || !dir.isDirectory) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                try {
                    if (child.isFile && child.name.endsWith(".torrent", ignoreCase = true)) {
                        val item = DiscoveredTorrentFile(
                            name = child.name.trim(),
                            size = child.length(),
                            lastModified = child.lastModified(),
                            uri = Uri.fromFile(child),
                            file = child
                        )
                        mergeDiscoveredFile(discoveredMap, item)
                    } else if (child.isDirectory && maxDepth > 0) {
                        scanDirectoryRecursively(child, discoveredMap, maxDepth - 1)
                    }
                } catch (_: Throwable) {
                    // Ignore individual file errors
                }
            }
        } catch (_: Throwable) {
            // Ignore directory access errors
        }
    }

    /**
     * Merges a discovered torrent file into the result map, keeping the most recently modified entry.
     */
    private fun mergeDiscoveredFile(
        map: MutableMap<String, DiscoveredTorrentFile>,
        item: DiscoveredTorrentFile
    ) {
        val key = item.name.lowercase().trim()
        val existing = map[key]
        if (existing == null || item.lastModified >= existing.lastModified) {
            map[key] = item
        }
    }
}
