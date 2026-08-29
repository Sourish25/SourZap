package com.sourzap.app.data.model

data class TrafficStats(
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val activeConnections: Int = 0,
    val totalPacketsProcessed: Long = 0L,
    val packetsPerSecond: Int = 0,
    val recentSpeedHistory: List<Float> = emptyList() // Last 20 data points in KB/s for live wave
) {
    fun formattedDownloadSpeed(): String = formatSpeed(downloadSpeedBps)
    fun formattedUploadSpeed(): String = formatSpeed(uploadSpeedBps)
    fun formattedSessionDownload(): String = formatBytes(sessionDownloadBytes)
    fun formattedSessionUpload(): String = formatBytes(sessionUploadBytes)
    fun formattedTotalDownload(): String = formatBytes(totalDownloadBytes)
    fun formattedTotalUpload(): String = formatBytes(totalUploadBytes)

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            val bits = bytesPerSec * 8
            return when {
                bits >= 1_000_000_000 -> String.format("%.2f Gbps", bits / 1_000_000_000.0)
                bits >= 1_000_000 -> String.format("%.1f Mbps", bits / 1_000_000.0)
                bits >= 1_000 -> String.format("%.0f Kbps", bits / 1_000.0)
                else -> " bps"
            }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
                bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
                bytes >= 1_024 -> String.format("%.0f KB", bytes / 1024.0)
                else -> " B"
            }
        }
    }
}