package com.sourzap.app.data.model

enum class DohProvider(
    val displayName: String,
    val url: String,
    val bootstrapIp: String
) {
    CLOUDFLARE("Cloudflare DoH", "https://1.1.1.1/dns-query", "1.1.1.1"),
    GOOGLE("Google DoH", "https://dns.google/dns-query", "8.8.8.8"),
    QUAD9("Quad9 DoH (Secure)", "https://dns.quad9.net/dns-query", "9.9.9.9"),
    ADGUARD("AdGuard (AdBlock)", "https://dns.adguard-dns.com/dns-query", "94.140.14.14")
}