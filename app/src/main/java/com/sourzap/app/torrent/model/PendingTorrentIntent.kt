package com.sourzap.app.torrent.model

/**
 * Encapsulates an incoming deep-link or intent payload targeting the BitTorrent downloader.
 */
sealed class PendingTorrentIntent {
    data class Magnet(
        val uri: String,
        val name: String? = null
    ) : PendingTorrentIntent()

    data class TorrentFile(
        val bytes: ByteArray,
        val fileName: String
    ) : PendingTorrentIntent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TorrentFile
            if (!bytes.contentEquals(other.bytes)) return false
            if (fileName != other.fileName) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + fileName.hashCode()
            return result
        }
    }
}
