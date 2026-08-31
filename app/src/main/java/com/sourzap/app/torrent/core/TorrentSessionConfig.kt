package com.sourzap.app.torrent.core

import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.settings_pack

/**
 * High-performance, anti-censorship BitTorrent session configuration.
 *
 * Configures pure TCP transport (disabling uTP to defeat firewall UDP throttling),
 * full-payload RC4 protocol encryption (eliminating plaintext BitTorrent signatures to defeat DPI),
 * and high-throughput swarm saturation parameters.
 */
data class TorrentSessionConfig(
    // 1. Anti-Firewall & Anti-DPI Pure TCP
    val enableIncomingUtp: Boolean = false,
    val enableOutgoingUtp: Boolean = false,
    val enableIncomingTcp: Boolean = true,
    val enableOutgoingTcp: Boolean = true,

    // 2. Full RC4 Protocol Encryption
    val outEncPolicy: Int = ENC_POLICY_FORCED,
    val inEncPolicy: Int = ENC_POLICY_FORCED,
    val allowedEncLevel: Int = ENC_LEVEL_RC4,
    val preferRc4: Boolean = true,

    // 3. High Throughput Swarm Tuning
    val connectionsLimit: Int = 500,
    val maxPeerlistSize: Int = 4000,
    val torrentConnectBoost: Int = 60,
    val maxOutRequestQueue: Int = 1500,
    val requestTimeout: Int = 10,
    val wholePiecesThreshold: Int = 20,
    val cacheSize: Int = 64 * 1024 * 1024,
    val sendSocketBufferSize: Int = 1048576, // 1 MB
    val recvSocketBufferSize: Int = 2097152, // 2 MB
    val aioThreads: Int = 4,

    // 4. Peer Discovery
    val enableDht: Boolean = true,
    val enableLsd: Boolean = true,
    val enablePex: Boolean = true,

    // 5. Active Limits & User Agent
    val activeDownloads: Int = 20,
    val activeSeeds: Int = 20,
    val activeLimit: Int = 40,
    val userAgent: String = "SourZap/2.5.0 libtorrent4j/2.1.0"
) {
    /**
     * Builds and returns a new [SettingsPack] with all anti-censorship and throughput options applied.
     */
    fun createSettingsPack(): SettingsPack {
        val pack = SettingsPack()
        applyTo(pack)
        return pack
    }

    /**
     * Applies this configuration to an existing [SettingsPack].
     */
    fun applyTo(pack: SettingsPack) {
        // Pure TCP enforcement
        pack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), enableIncomingUtp)
        pack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), enableOutgoingUtp)
        pack.setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), enableIncomingTcp)
        pack.setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), enableOutgoingTcp)

        // Protocol encryption
        pack.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), outEncPolicy)
        pack.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), inEncPolicy)
        pack.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(), allowedEncLevel)
        pack.setBoolean(settings_pack.bool_types.prefer_rc4.swigValue(), preferRc4)

        // Swarm saturation & socket buffers
        pack.setInteger(settings_pack.int_types.connections_limit.swigValue(), connectionsLimit)
        pack.setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), maxPeerlistSize)
        pack.setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), torrentConnectBoost)
        pack.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), maxOutRequestQueue)
        pack.setInteger(settings_pack.int_types.request_timeout.swigValue(), requestTimeout)
        pack.setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), wholePiecesThreshold)
        pack.setInteger(settings_pack.int_types.send_socket_buffer_size.swigValue(), sendSocketBufferSize)
        pack.setInteger(settings_pack.int_types.recv_socket_buffer_size.swigValue(), recvSocketBufferSize)
        pack.setInteger(settings_pack.int_types.aio_threads.swigValue(), aioThreads)
        pack.setInteger(settings_pack.int_types.active_downloads.swigValue(), activeDownloads)
        pack.setInteger(settings_pack.int_types.active_seeds.swigValue(), activeSeeds)
        pack.setInteger(settings_pack.int_types.active_limit.swigValue(), activeLimit)

        // Discovery
        pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), enableDht)
        pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), enableLsd)

        // Identity
        pack.setString(settings_pack.string_types.user_agent.swigValue(), userAgent)
    }

    companion object {
        const val ENC_POLICY_DISABLED = 0
        const val ENC_POLICY_ENABLED = 1
        const val ENC_POLICY_FORCED = 2

        const val ENC_LEVEL_PLAINTEXT = 1
        const val ENC_LEVEL_RC4 = 2
        const val ENC_LEVEL_BOTH = 3

        val DEFAULT = TorrentSessionConfig()
    }
}
