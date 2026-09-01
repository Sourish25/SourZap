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
    // 1. Dual Transport: Enables both TCP and uTP for maximum swarm penetration
    val enableIncomingUtp: Boolean = true,
    val enableOutgoingUtp: Boolean = true,
    val enableIncomingTcp: Boolean = true,
    val enableOutgoingTcp: Boolean = true,

    // 2. Adaptive Protocol Encryption: Prefers RC4 stream encryption to evade DPI, while allowing PE/Plaintext fallback so 100% of swarm peers can connect
    val outEncPolicy: Int = ENC_POLICY_ENABLED,
    val inEncPolicy: Int = ENC_POLICY_ENABLED,
    val allowedEncLevel: Int = ENC_LEVEL_BOTH,
    val preferRc4: Boolean = true,

    // 3. High Throughput & Rapid Peer Acquisition (Aria2-like Swarm Aggressiveness)
    val connectionsLimit: Int = 500,
    val maxPeerlistSize: Int = 4000,
    val torrentConnectBoost: Int = 100,
    val connectionSpeed: Int = 80,
    val peerConnectTimeout: Int = 5,
    val maxOutRequestQueue: Int = 1500,
    val requestTimeout: Int = 8,
    val wholePiecesThreshold: Int = 20,
    val cacheSize: Int = 64 * 1024 * 1024,
    val sendSocketBufferSize: Int = 1048576, // 1 MB
    val recvSocketBufferSize: Int = 2097152, // 2 MB
    val aioThreads: Int = 4,

    // 4. Parallel Tracker Saturation (Announce to ALL Trackers & Tiers concurrently)
    val announceToAllTrackers: Boolean = true,
    val announceToAllTiers: Boolean = true,
    val trackerCompletionTimeout: Int = 10,
    val trackerReceiveTimeout: Int = 8,
    val stopTrackerTimeout: Int = 2,

    // 5. Peer Discovery & DHT Bootstrap Nodes
    val enableDht: Boolean = true,
    val enableLsd: Boolean = true,
    val enablePex: Boolean = true,
    val dhtBootstrapNodes: String = "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.aelitis.com:6881,dht.libtorrent.org:25401",

    // 6. Active Limits & Identity
    val activeDownloads: Int = 20,
    val activeSeeds: Int = 20,
    val activeLimit: Int = 40,
    val userAgent: String = "SourZap/2.6.7 libtorrent4j/2.1.0"
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
        // Dual transport (TCP + uTP)
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
        pack.setInteger(settings_pack.int_types.connection_speed.swigValue(), connectionSpeed)
        pack.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), peerConnectTimeout)
        pack.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), maxOutRequestQueue)
        pack.setInteger(settings_pack.int_types.request_timeout.swigValue(), requestTimeout)
        pack.setInteger(settings_pack.int_types.whole_pieces_threshold.swigValue(), wholePiecesThreshold)
        pack.setInteger(settings_pack.int_types.send_socket_buffer_size.swigValue(), sendSocketBufferSize)
        pack.setInteger(settings_pack.int_types.recv_socket_buffer_size.swigValue(), recvSocketBufferSize)
        pack.setInteger(settings_pack.int_types.aio_threads.swigValue(), aioThreads)
        pack.setInteger(settings_pack.int_types.active_downloads.swigValue(), activeDownloads)
        pack.setInteger(settings_pack.int_types.active_seeds.swigValue(), activeSeeds)
        pack.setInteger(settings_pack.int_types.active_limit.swigValue(), activeLimit)

        // Parallel Tracker Announcement
        pack.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), announceToAllTrackers)
        pack.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), announceToAllTiers)
        pack.setInteger(settings_pack.int_types.tracker_completion_timeout.swigValue(), trackerCompletionTimeout)
        pack.setInteger(settings_pack.int_types.tracker_receive_timeout.swigValue(), trackerReceiveTimeout)
        pack.setInteger(settings_pack.int_types.stop_tracker_timeout.swigValue(), stopTrackerTimeout)

        // Discovery
        pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), enableDht)
        pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), enableLsd)
        pack.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), dhtBootstrapNodes)

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
