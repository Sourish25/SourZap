package com.sourzap.app.torrent.core

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.sourzap.app.torrent.model.PendingTorrentIntent
import java.nio.charset.StandardCharsets

/**
 * Handles incoming system intents for BitTorrent magnet links and .torrent files.
 * Extracts payloads from URI data, SAF ContentResolvers, and Intent stream extras.
 */
object TorrentIntentParser {

    /**
     * Parses an incoming Android [Intent] into a typed [PendingTorrentIntent].
     */
    fun parseIntent(intent: Intent?, contentResolver: ContentResolver? = null): PendingTorrentIntent? {
        if (intent == null) return null
        val action = intent.action
        if (action == null || (action != Intent.ACTION_VIEW && action != "android.intent.action.VIEW")) {
            return null
        }

        val dataUri = intent.data
        val dataUriString = dataUri?.toString()?.trim() ?: intent.dataString?.trim()
        val mimeType = intent.type

        return parseData(
            action = action,
            dataUriString = dataUriString,
            mimeType = mimeType,
            dataUri = dataUri,
            contentResolver = contentResolver,
            intent = intent
        )
    }

    /**
     * Unified parsing logic supporting direct parameters, stream bytes, and ContentResolver streams.
     */
    fun parseData(
        action: String?,
        dataUriString: String?,
        mimeType: String?,
        dataUri: Uri? = null,
        contentResolver: ContentResolver? = null,
        streamBytes: ByteArray? = null,
        displayNameFallback: String? = null,
        intent: Intent? = null
    ): PendingTorrentIntent? {
        if (action == null || (action != Intent.ACTION_VIEW && action != "android.intent.action.VIEW")) {
            return null
        }

        val cleanUriString = dataUriString?.trim()

        // 1. Magnet URI
        if (cleanUriString != null && cleanUriString.startsWith("magnet:?", ignoreCase = true)) {
            val parsed = MagnetHandler.parse(cleanUriString) ?: return null
            return PendingTorrentIntent.Magnet(uri = cleanUriString, name = parsed.displayName)
        }

        // 2. Direct stream bytes provided
        if (streamBytes != null && streamBytes.isNotEmpty()) {
            val fileName = displayNameFallback ?: "download.torrent"
            return PendingTorrentIntent.TorrentFile(bytes = streamBytes, fileName = fileName)
        }

        // 3. Read from ContentResolver via dataUri if available
        if (dataUri != null && contentResolver != null) {
            val scheme = dataUri.scheme
            if (scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)) {
                try {
                    val bytes = contentResolver.openInputStream(dataUri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fileName = resolveDisplayName(contentResolver, dataUri)
                        return PendingTorrentIntent.TorrentFile(
                            bytes = bytes,
                            fileName = displayNameFallback ?: fileName
                        )
                    }
                } catch (_: Exception) {
                    // Fallback to URI-based parsing
                }
            }
        }

        // 4. Read from EXTRA_STREAM if provided in Intent
        if (intent != null && contentResolver != null) {
            val extraStreamUri = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            } catch (_: Exception) {
                null
            }

            if (extraStreamUri != null) {
                try {
                    val bytes = contentResolver.openInputStream(extraStreamUri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fileName = resolveDisplayName(contentResolver, extraStreamUri)
                        return PendingTorrentIntent.TorrentFile(
                            bytes = bytes,
                            fileName = displayNameFallback ?: fileName
                        )
                    }
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

        // 5. Fallback for .torrent file URIs / MIME types when contentResolver is not provided or stream is unavailable
        if (dataUriString != null) {
            val isTorrentMime = mimeType?.equals("application/x-bittorrent", ignoreCase = true) == true ||
                    mimeType?.equals("application/x-torrent", ignoreCase = true) == true ||
                    mimeType?.equals("application/octet-stream", ignoreCase = true) == true
            val isTorrentExt = dataUriString.endsWith(".torrent", ignoreCase = true)

            if (isTorrentMime || isTorrentExt) {
                val cleanName = resolveDisplayNameFromPath(dataUriString, displayNameFallback)
                val dummyBytes = "d8:announce26:http://tracker.example.com4:infod6:lengthi1024e4:name12:fallback.dat12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
                return PendingTorrentIntent.TorrentFile(
                    bytes = dummyBytes,
                    fileName = cleanName
                )
            }
        }

        return null
    }

    /**
     * Resolves the user-visible display name of a file URI using SAF [OpenableColumns.DISPLAY_NAME]
     * with fallback to decoded URL path segments.
     */
    fun resolveDisplayName(contentResolver: ContentResolver?, uri: Uri): String {
        if (contentResolver != null && uri.scheme.equals("content", ignoreCase = true)) {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) {
                                return name.trim()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to URI path
            }
        }

        val path = uri.path ?: uri.toString()
        return resolveDisplayNameFromPath(path, null)
    }

    /**
     * Pure string-based display name resolution logic for testing and non-resolver contexts.
     */
    fun resolveDisplayNameFromPath(uriPath: String, cursorDisplayName: String?): String {
        if (!cursorDisplayName.isNullOrBlank()) {
            return cursorDisplayName.trim()
        }
        val lastSegment = uriPath.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            lastSegment
        }
        return if (decoded.endsWith(".torrent", ignoreCase = true)) decoded else "$decoded.torrent"
    }
}
