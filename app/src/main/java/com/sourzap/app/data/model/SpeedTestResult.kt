package com.sourzap.app.data.model

data class SpeedTestResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val pingMs: Float = 0f,
    val jitterMs: Float = 0f,
    val downloadMbps: Float = 0f,
    val uploadMbps: Float = 0f,
    val serverName: String = "Cloudflare Edge",
    val serverLocation: String = "Closest CDN Node",
    val strategyName: String = "YouTube Turbo Fix"
) {
    fun formattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    companion object {
        fun formatThroughputAutoRanged(mbps: Float): String {
            return when {
                mbps >= 1000f -> String.format(java.util.Locale.US, "%.2f Gbps", mbps / 1000f)
                mbps >= 1f -> String.format(java.util.Locale.US, "%.1f Mbps", mbps)
                mbps >= 0.001f -> String.format(java.util.Locale.US, "%.1f Kbps", mbps * 1000f)
                else -> String.format(java.util.Locale.US, "%.0f bps", mbps * 1_000_000f)
            }
        }
    }
}

enum class SpeedTestPhase {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val currentPingMs: Float = 0f,
    val currentJitterMs: Float = 0f,
    val currentDownloadMbps: Float = 0f,
    val currentUploadMbps: Float = 0f,
    val progress: Float = 0f, // 0.0 to 1.0
    val activeGaugeSpeedMbps: Float = 0f,
    val statusMessage: String = "Ready to test speed",
    val recentResult: SpeedTestResult? = null
)