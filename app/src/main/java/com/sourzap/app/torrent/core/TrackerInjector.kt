package com.sourzap.app.torrent.core

import org.libtorrent4j.TorrentInfo
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Production Tracker Auto-Injection Subsystem.
 * Automatically injects curated Port-443 HTTPS trackers into any loaded torrent or magnet URI
 * to ensure robust peer discovery in restricted firewall environments.
 */
object TrackerInjector {

    val HTTPS_PORT_443_TRACKERS = listOf(
        "https://tracker.tamersunion.org:443/announce",
        "https://tracker.loligirl.cn:443/announce",
        "https://tr.burnabyhighstar.com:443/announce",
        "https://tracker.renfei.net:443/announce",
        "https://tracker.coalition.ovh:443/announce",
        "https://tracker.gbitt.info:443/announce",
        "https://tracker.moeking.me:443/announce",
        "https://tr.ready4.icu:443/announce",
        "https://tracker.imgoingto.icu:443/announce",
        "https://tracker.nitrix.me:443/announce",
        "https://open.tracker.ink:443/announce",
        "https://tracker.vectornetwork.me:443/announce",
        "https://tracker.yemeksepeti.top:443/announce",
        "https://tracker.lilithraws.org:443/announce",
        "https://t.240407.xyz:443/announce",
        "https://tracker.cloudit.top:443/announce",
        "https://tracker.foreverpirates.co:443/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://tracker.zhuqiy.com:443/announce",
        "https://tracker.gcrensei.club:443/announce",
        "https://tracker.ipfsscan.io:443/announce",
        "https://tracker.leechshield.link:443/announce"
    )

    /**
     * Injects curated Port-443 HTTPS trackers.
     * Note: In libtorrent4j, trackers are attached directly to active TorrentHandle instances
     * in handleTorrentAdded() to prevent mutating native SWIG torrent_info objects before download.
     */
    fun injectIntoTorrentInfo(torrentInfo: TorrentInfo) {
        // No-op to preserve immutable TorrentInfo integrity before sessionManager.download
    }

    fun injectTrackers(magnetUri: String): String {
        val trimmed = magnetUri.trim()
        if (!trimmed.startsWith("magnet:?")) {
            return trimmed
        }

        val queryPart = trimmed.substring("magnet:?".length)
        val params = queryPart.split("&").filter { it.isNotEmpty() }

        val existingTrackers = mutableSetOf<String>()
        for (param in params) {
            if (param.startsWith("tr=")) {
                val rawVal = param.substring(3)
                val decoded = runCatching { URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name()) }.getOrDefault(rawVal)
                existingTrackers.add(normalizeTrackerUrl(decoded))
            }
        }

        val sb = StringBuilder(trimmed)
        for (tracker in HTTPS_PORT_443_TRACKERS) {
            val norm = normalizeTrackerUrl(tracker)
            if (!existingTrackers.contains(norm)) {
                val encoded = URLEncoder.encode(tracker, StandardCharsets.UTF_8.name())
                sb.append("&tr=").append(encoded)
                existingTrackers.add(norm)
            }
        }

        return sb.toString()
    }

    fun getAugmentedTrackers(existingTrackers: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (tr in existingTrackers) {
            val sanitized = tr.trim()
            if (sanitized.isNotEmpty()) {
                val norm = normalizeTrackerUrl(sanitized)
                if (seen.add(norm)) {
                    result.add(sanitized)
                }
            }
        }

        for (tracker in HTTPS_PORT_443_TRACKERS) {
            val norm = normalizeTrackerUrl(tracker)
            if (seen.add(norm)) {
                result.add(tracker)
            }
        }

        return result
    }

    private fun normalizeTrackerUrl(url: String): String {
        return url.trim().lowercase().removeSuffix("/")
    }
}
