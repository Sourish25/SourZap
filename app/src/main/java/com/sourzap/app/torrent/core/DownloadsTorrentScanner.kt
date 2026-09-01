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

        // 1. Query MediaStore.Files and MediaStore.Downloads
        val mediaCollections = mutableListOf<Uri>()
        try {
            mediaCollections.add(MediaStore.Files.getContentUri("external"))
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                mediaCollections.add(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL))
            } catch (_: Throwable) {}
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.torrent' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.TORRENT' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.Torrent' OR " +
                "${MediaStore.MediaColumns.MIME_TYPE} = 'application/x-bittorrent' OR " +
                "${MediaStore.MediaColumns.MIME_TYPE} = 'application/x-torrent'"
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        for (collection in mediaCollections) {
            try {
                // First attempt: filtered query
                var count = 0
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        try {
                            val id = if (idCol != -1) cursor.getLong(idCol) else -1L
                            val name = if (nameCol != -1) cursor.getString(nameCol) else null
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                            val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                            val mime = if (mimeCol != -1) cursor.getString(mimeCol) else null

                            if (!name.isNullOrBlank()) {
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
                                count++
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // Fallback: If filtered query returned 0 items, query recent 200 items and filter in Kotlin
                if (count == 0) {
                    context.contentResolver.query(
                        collection,
                        projection,
                        null,
                        null,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT 200"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                        while (cursor.moveToNext()) {
                            try {
                                val id = if (idCol != -1) cursor.getLong(idCol) else -1L
                                val name = if (nameCol != -1) cursor.getString(nameCol) else null
                                val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                                val dateSec = if (dateCol != -1) cursor.getLong(dateCol) else 0L
                                val mime = if (mimeCol != -1) cursor.getString(mimeCol) else null

                                if (!name.isNullOrBlank() && (
                                    name.endsWith(".torrent", ignoreCase = true) ||
                                    name.contains(".torrent", ignoreCase = true) ||
                                    mime == "application/x-bittorrent" ||
                                    mime == "application/x-torrent"
                                )) {
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
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ignore MediaStore query errors (e.g. permissions or test environments)
            }
        }

        // 2. Scan Candidate Filesystem Directories
        val candidateDirs = mutableListOf<File>()
        val paths = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads",
            "/sdcard/Download",
            "/sdcard/Downloads",
            "/storage/emulated/0/SourZap"
        )
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists() && f.isDirectory) {
                    candidateDirs.add(f)
                }
            } catch (_: Throwable) {}
        }
        try {
            val downloadType = try { Environment.DIRECTORY_DOWNLOADS } catch (_: Throwable) { null } ?: "Download"
            val pubDir = Environment.getExternalStoragePublicDirectory(downloadType)
            if (pubDir != null && pubDir.exists()) candidateDirs.add(pubDir)
        } catch (_: Throwable) {}
        try {
            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                candidateDirs.add(File(extDir, "Download"))
                candidateDirs.add(File(extDir, "Downloads"))
            }
        } catch (_: Throwable) {}
        try {
            val downloadType = try { Environment.DIRECTORY_DOWNLOADS } catch (_: Throwable) { null } ?: "Download"
            val appDl = context.getExternalFilesDir(downloadType)
            if (appDl != null && appDl.exists()) candidateDirs.add(appDl)
        } catch (_: Throwable) {}
        try {
            val saveDir = TorrentStorageHelper.getSaveDirectory(context)
            if (saveDir.exists()) candidateDirs.add(saveDir)
        } catch (_: Throwable) {}
        try {
            val appExt = context.getExternalFilesDir(null)
            if (appExt != null && appExt.exists()) candidateDirs.add(appExt)
        } catch (_: Throwable) {}
        try {
            if (context.filesDir.exists()) candidateDirs.add(context.filesDir)
        } catch (_: Throwable) {}

        for (candidateDir in candidateDirs) {
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
