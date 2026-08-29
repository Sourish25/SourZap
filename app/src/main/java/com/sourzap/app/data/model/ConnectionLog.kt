package com.sourzap.app.data.model

data class ConnectionLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val domain: String,
    val port: Int,
    val protocol: String, // TLS, HTTP, DNS, QUIC
    val technique: String, // e.g. "FAKE+SPLIT", "DISORDER", "DoH", "QUIC_BLOCKED"
    val bytesTransferred: Long = 0L,
    val latencyMs: Long = 0L,
    val isBlockedBypassed: Boolean = true
) {
    fun formattedBytes(): String {
        return when {
            bytesTransferred >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytesTransferred / (1024.0 * 1024.0))
            bytesTransferred >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytesTransferred / 1024.0)
            else -> "$bytesTransferred B"
        }
    }
}