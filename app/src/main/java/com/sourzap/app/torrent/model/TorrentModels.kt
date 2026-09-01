package com.sourzap.app.torrent.model

import java.util.Locale

/**
 * Represents the current lifecycle state of a torrent transfer.
 */
enum class TorrentState {
    CHECKING,
    DOWNLOADING,
    SEEDING,
    PAUSED,
    FINISHED,
    ERROR,
    ALLOCATING,
    METADATA;

    val isRunning: Boolean
        get() = this == DOWNLOADING || this == SEEDING || this == METADATA || this == ALLOCATING || this == CHECKING

    val isCompleted: Boolean
        get() = this == FINISHED || this == SEEDING
}

/**
 * File/Piece download priority level.
 */
enum class Priority(val value: Int) {
    IGNORE(0),
    LOW(1),
    NORMAL(4),
    HIGH(7);

    fun toLibtorrentPriority(): org.libtorrent4j.Priority {
        return org.libtorrent4j.Priority.fromSwig(value)
    }

    companion object {
        fun fromValue(value: Int): Priority {
            return when {
                value <= 0 -> IGNORE
                value in 1..3 -> LOW
                value in 4..6 -> NORMAL
                else -> HIGH
            }
        }

        fun fromLibtorrent(p: org.libtorrent4j.Priority): Priority {
            return fromValue(p.swig().toInt())
        }
    }
}

/**
 * Filter categories for torrent listing in the UI.
 */
enum class TorrentFilter {
    ALL,
    DOWNLOADING,
    SEEDING,
    PAUSED,
    COMPLETED;

    fun matches(item: TorrentItem): Boolean {
        return when (this) {
            ALL -> true
            DOWNLOADING -> item.state == TorrentState.DOWNLOADING ||
                    item.state == TorrentState.ALLOCATING ||
                    item.state == TorrentState.METADATA
            SEEDING -> item.state == TorrentState.SEEDING
            PAUSED -> item.state == TorrentState.PAUSED
            COMPLETED -> item.state == TorrentState.FINISHED ||
                    item.state == TorrentState.SEEDING ||
                    item.progress >= 1.0f
        }
    }
}

/**
 * Source representation for adding a new torrent.
 */
sealed interface TorrentSource {
    data class Magnet(
        val uri: String,
        val displayName: String? = null
    ) : TorrentSource

    data class FileContent(
        val bytes: ByteArray,
        val name: String = ""
    ) : TorrentSource {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FileContent
            if (!bytes.contentEquals(other.bytes)) return false
            if (name != other.name) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + name.hashCode()
            return result
        }
    }

    data class FilePath(
        val path: String
    ) : TorrentSource
}

/**
 * Detailed representation of an individual file inside a multi-file torrent.
 */
data class TorrentFileItem(
    val index: Int,
    val path: String,
    val size: Long,
    val downloadedBytes: Long = 0L,
    val progress: Float = 0.0f,
    val priority: Priority = Priority.NORMAL
) {
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    val isSkipped: Boolean
        get() = priority == Priority.IGNORE
}

/**
 * Bitmap and progress information of torrent pieces.
 */
data class TorrentPieceInfo(
    val pieceCount: Int,
    val piecesCompleted: Int,
    val pieceBitfield: BooleanArray = BooleanArray(0)
) {
    val completionRatio: Float
        get() = if (pieceCount > 0) piecesCompleted.toFloat() / pieceCount.toFloat() else 0.0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TorrentPieceInfo
        if (pieceCount != other.pieceCount) return false
        if (piecesCompleted != other.piecesCompleted) return false
        if (!pieceBitfield.contentEquals(other.pieceBitfield)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pieceCount
        result = 31 * result + piecesCompleted
        result = 31 * result + pieceBitfield.contentHashCode()
        return result
    }
}

/**
 * Connected swarm peer details.
 */
data class TorrentPeerInfo(
    val ip: String,
    val port: Int,
    val client: String = "",
    val downSpeed: Long = 0L,
    val upSpeed: Long = 0L,
    val progress: Float = 0.0f,
    val flags: String = ""
)

/**
 * Main state representation for a single torrent transfer in the session.
 */
data class TorrentItem(
    val id: String,
    val name: String,
    val state: TorrentState,
    val progress: Float,
    val downloadSpeed: Long,
    val uploadSpeed: Long,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val uploadedBytes: Long,
    val numSeeds: Int,
    val numPeers: Int,
    val totalSeeds: Int = 0,
    val totalPeers: Int = 0,
    val etaSeconds: Long = -1L,
    val shareRatio: Float = 0.0f,
    val savePath: String = "",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val isSequential: Boolean = false,
    val files: List<TorrentFileItem> = emptyList(),
    val error: String? = null,
    val pieces: TorrentPieceInfo? = null
) {
    val isCompleted: Boolean
        get() = state.isCompleted || progress >= 1.0f

    val progressPercent: Int
        get() = (progress * 100).toInt().coerceIn(0, 100)

    val formattedProgress: String
        get() = String.format(Locale.US, "%.1f%%", progress * 100f)

    val formattedDownloadSpeed: String
        get() = formatBytesPerSec(downloadSpeed)

    val formattedUploadSpeed: String
        get() = formatBytesPerSec(uploadSpeed)

    val formattedTotalSize: String
        get() = formatFileSize(totalBytes)

    val formattedDownloadedSize: String
        get() = formatFileSize(downloadedBytes)

    val formattedEta: String
        get() = formatEtaDuration(etaSeconds)

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024.0 && unitIndex < units.size - 1) {
                size /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) "${bytes} B" else String.format(Locale.US, "%.2f %s", size, units[unitIndex])
        }

        fun formatBytesPerSec(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 B/s"
            val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
            var speed = bytesPerSec.toDouble()
            var unitIndex = 0
            while (speed >= 1024.0 && unitIndex < units.size - 1) {
                speed /= 1024.0
                unitIndex++
            }
            return if (unitIndex == 0) "${bytesPerSec} B/s" else String.format(Locale.US, "%.1f %s", speed, units[unitIndex])
        }

        fun formatEtaDuration(seconds: Long): String {
            if (seconds < 0 || seconds >= 86400 * 365) return "∞"
            if (seconds == 0L) return "0s"
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return when {
                hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
                minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
                else -> String.format(Locale.US, "%ds", secs)
            }
        }
    }
}

/**
 * Aggregated live statistics for the active BitTorrent session.
 */
data class TorrentSessionStats(
    val totalDownloadSpeed: Long = 0L,
    val totalUploadSpeed: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val activeTorrents: Int = 0,
    val pausedTorrents: Int = 0,
    val seedingTorrents: Int = 0,
    val dhtNodes: Long = 0L,
    val totalBytes: Long = 0L,
    val aggregateProgress: Float = 0.0f
) {
    val formattedDownloadSpeed: String
        get() = TorrentItem.formatBytesPerSec(totalDownloadSpeed)

    val formattedUploadSpeed: String
        get() = TorrentItem.formatBytesPerSec(totalUploadSpeed)

    val formattedProgress: String
        get() = String.format(Locale.US, "%.1f%%", aggregateProgress * 100f)

    val progressPercent: Int
        get() = (aggregateProgress * 100).toInt().coerceIn(0, 100)
}
