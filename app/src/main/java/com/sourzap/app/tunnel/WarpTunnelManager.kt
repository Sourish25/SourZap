package com.sourzap.app.tunnel

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class WarpTunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object WarpTunnelManager {

    private const val TAG = "WarpTunnelManager"
    private const val TUNNEL_NAME = "SourZapWarp"

    private val _tunnelState = MutableStateFlow(WarpTunnelState.DISCONNECTED)
    val tunnelState: StateFlow<WarpTunnelState> = _tunnelState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(state: Tunnel.State) {
            Log.i(TAG, "WireGuard tunnel onStateChange: $state")
            _tunnelState.value = when (state) {
                Tunnel.State.UP -> WarpTunnelState.CONNECTED
                Tunnel.State.DOWN -> WarpTunnelState.DISCONNECTED
                Tunnel.State.TOGGLE -> WarpTunnelState.CONNECTING
            }
        }
    }

    private fun getBackend(context: Context): GoBackend {
        if (backend == null) {
            backend = GoBackend(context.applicationContext)
        }
        return backend!!
    }

    suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            _tunnelState.value = WarpTunnelState.CONNECTING
            _lastError.value = null

            val account = WarpAccountManager.getOrCreateConfig(context)

            val ifaceBuilder = Interface.Builder()
                .parsePrivateKey(account.privateKey)
                .parseAddresses(account.assignedIpv4)
                .parseDnsServers("1.1.1.1")

            if (!account.assignedIpv6.isNullOrBlank()) {
                try {
                    ifaceBuilder.parseAddresses(account.assignedIpv6)
                } catch (_: Throwable) {}
            }

            val iface = ifaceBuilder.build()

            val peer = Peer.Builder()
                .parsePublicKey(account.peerPublicKey)
                .parseEndpoint(account.endpoint) // Unrestricted UDP Port 53 endpoint
                .parseAllowedIPs("0.0.0.0/0")
                .setPersistentKeepalive(25)
                .build()

            val config = Config.Builder()
                .setInterface(iface)
                .addPeer(peer)
                .build()

            val goBackend = getBackend(context)
            goBackend.setState(tunnel, Tunnel.State.UP, config)
            _tunnelState.value = WarpTunnelState.CONNECTED
            Log.i(TAG, "Cloudflare WARP WireGuard tunnel established successfully on port 53")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to establish Cloudflare WARP tunnel", e)
            _lastError.value = e.message ?: "Tunnel setup failed"
            _tunnelState.value = WarpTunnelState.ERROR
            false
        }
    }

    suspend fun disconnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val goBackend = getBackend(context)
            goBackend.setState(tunnel, Tunnel.State.DOWN, null)
            _tunnelState.value = WarpTunnelState.DISCONNECTED
            Log.i(TAG, "Cloudflare WARP WireGuard tunnel disconnected")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Error disconnecting WireGuard tunnel", e)
            _tunnelState.value = WarpTunnelState.DISCONNECTED
            false
        }
    }

    fun isConnected(): Boolean {
        return _tunnelState.value == WarpTunnelState.CONNECTED
    }
}
