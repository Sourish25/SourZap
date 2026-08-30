package com.sourzap.app.data.model

enum class DohProvider(
    val displayName: String,
    val url: String,
    val bootstrapIp: String,
    val backupIps: List<String> = emptyList(),
    val hostHeader: String = ""
) {
    CLOUDFLARE(
        displayName = "Cloudflare DoH",
        url = "https://1.1.1.1/dns-query",
        bootstrapIp = "1.1.1.1",
        backupIps = listOf("1.0.0.1", "162.159.36.1", "162.159.46.1"),
        hostHeader = "cloudflare-dns.com"
    ),
    GOOGLE(
        displayName = "Google DoH",
        url = "https://dns.google/dns-query",
        bootstrapIp = "8.8.8.8",
        backupIps = listOf("8.8.4.4"),
        hostHeader = "dns.google"
    ),
    QUAD9(
        displayName = "Quad9 DoH (Secure)",
        url = "https://dns.quad9.net/dns-query",
        bootstrapIp = "9.9.9.9",
        backupIps = listOf("149.112.112.112", "149.112.112.11"),
        hostHeader = "dns.quad9.net"
    ),
    ADGUARD(
        displayName = "AdGuard (AdBlock)",
        url = "https://dns.adguard-dns.com/dns-query",
        bootstrapIp = "94.140.14.14",
        backupIps = listOf("94.140.15.15"),
        hostHeader = "dns.adguard-dns.com"
    )
}