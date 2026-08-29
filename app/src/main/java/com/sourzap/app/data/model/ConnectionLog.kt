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
)