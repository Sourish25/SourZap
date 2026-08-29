package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-Performance Local Transparent Proxy Server for Android VpnService.
 * Seamlessly accepts HTTP/HTTPS traffic from all Android applications (Chrome, YouTube, WhatsApp, DuckDuckGo, system apps),
 * applies Zapret DPI desynchronization on upstream connections, and proxies bidirectional streams at Gigabit line speed.
 */
class LocalDpiProxyServer(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    var port: Int = 0
        private set

    fun start(): Int {
        val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        port = server.localPort
        isRunning.set(true)

        scope.launch(Dispatchers.IO) {
            while (isRunning.get() && scope.isActive) {
                try {
                    val clientSocket = server.accept()
                    scope.launch(Dispatchers.IO) {
                        handleClientConnection(clientSocket)
                    }
                } catch (_: Exception) {
                    if (!isRunning.get()) break
                }
            }
        }
        return port
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private suspend fun handleClientConnection(clientSocket: Socket) {
        TrafficMonitor.onConnectionOpened()
        var upstreamSocket: Socket? = null

        try {
            clientSocket.apply {
                receiveBufferSize = 2097152 // 2 MB Client Receive Buffer
                sendBufferSize = 2097152    // 2 MB Client Send Buffer
                tcpNoDelay = true
                keepAlive = true
                soTimeout = 0               // Persistent keepalive
                trafficClass = 0x08         // IPTOS_THROUGHPUT
                setPerformancePreferences(0, 1, 2)
            }

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Read the initial request line/headers using chunked buffer (eliminates 800+ JNI context switches)
            val headerBuffer = ByteArray(8192)
            var totalRead = 0
            val tempBuf = ByteArray(1024)

            while (totalRead < headerBuffer.size) {
                val count = clientIn.read(tempBuf)
                if (count <= 0) break
                System.arraycopy(tempBuf, 0, headerBuffer, totalRead, count)
                totalRead += count

                // Check for \r\n\r\n
                var foundEnd = false
                for (i in 3 until totalRead) {
                    if (headerBuffer[i - 3] == '\r'.code.toByte() &&
                        headerBuffer[i - 2] == '\n'.code.toByte() &&
                        headerBuffer[i - 1] == '\r'.code.toByte() &&
                        headerBuffer[i] == '\n'.code.toByte()
                    ) {
                        foundEnd = true
                        break
                    }
                }
                if (foundEnd) break
            }

            if (totalRead == 0) return

            val headerStr = String(headerBuffer, 0, totalRead, Charsets.US_ASCII)
            val firstLine = headerStr.lineSequence().firstOrNull() ?: ""

            if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                // --- HTTPS & Messaging CONNECT Tunneling (WhatsApp, Chrome, YouTube, Discord) ---
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                val target = parts[1]
                val targetHost = target.substringBefore(":")
                val targetPort = target.substringAfter(":", "443").toIntOrNull() ?: 443

                // Resolve targetHost with DoH to prevent ISP DNS blocking & timeouts
                val targetIps = DohResolver.resolve(targetHost)
                val targetIp = targetIps.firstOrNull() ?: java.net.InetAddress.getByName(targetHost)

                // Connect to remote upstream with protected socket
                val upstream = Socket().apply {
                    receiveBufferSize = 2097152 // 2 MB Turbo Video Buffer for 4K/8K Media
                    sendBufferSize = 1048576    // 1 MB Send Buffer
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 0               // Persistent keepalive
                    trafficClass = 0x08         // IPTOS_THROUGHPUT
                    setPerformancePreferences(0, 1, 2)
                }
                upstreamSocket = upstream

                vpnService.protect(upstream)
                upstream.connect(InetSocketAddress(targetIp, targetPort), 6000)

                // Respond 200 Connection Established to Android client app
                val response200 = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII)
                clientOut.write(response200)
                clientOut.flush()

                // Client will now send initial payload (TLS ClientHello, or WhatsApp Noise Handshake)
                val initialBuffer = ByteArray(16384)
                val initialLen = clientIn.read(initialBuffer)
                if (initialLen > 0) {
                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                    val upstreamOut = upstream.getOutputStream()
                    val upstreamIn = upstream.getInputStream()

                    val sniResult = TlsParser.parseClientHello(initialBuffer, initialLen)

                    if (sniResult.isClientHello) {
                        // Standard TLS Handshake -> Apply Zapret DPI desync
                        var appliedTechnique = "DIRECT"
                        val logDomain = sniResult.hostname ?: targetHost

                        DpiEngine.desyncAndSend(
                            socket = upstream,
                            outputStream = upstreamOut,
                            payload = initialBuffer,
                            length = initialLen,
                            strategy = strategy,
                            onTechniqueApplied = { appliedTechnique = it }
                        )

                        TrafficMonitor.addConnectionLog(
                            ConnectionLog(
                                domain = logDomain,
                                port = targetPort,
                                protocol = "TLS",
                                technique = appliedTechnique,
                                bytesTransferred = initialLen.toLong()
                            )
                        )
                    } else {
                        // Non-TLS / WhatsApp Noise Protocol Handshake -> Passthrough cleanly
                        upstreamOut.write(initialBuffer, 0, initialLen)
                        upstreamOut.flush()

                        TrafficMonitor.addConnectionLog(
                            ConnectionLog(
                                domain = targetHost,
                                port = targetPort,
                                protocol = "NOISE_STREAM",
                                technique = "PASSTHROUGH",
                                bytesTransferred = initialLen.toLong()
                            )
                        )
                    }

                    // Suspend and pump remaining stream bidirectionally until stream closes
                    pumpBidirectional(clientIn, clientOut, upstreamIn, upstreamOut, clientSocket, upstream)
                }
            } else {
                // --- Plain HTTP Request ---
                val hostLine = headerStr.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                val targetHost = hostLine?.substringAfter(":")?.trim()?.substringBefore(":") ?: ""
                val targetPort = 80

                if (targetHost.isNotEmpty()) {
                    val targetIps = DohResolver.resolve(targetHost)
                    val targetIp = targetIps.firstOrNull() ?: java.net.InetAddress.getByName(targetHost)

                    val upstream = Socket().apply {
                        receiveBufferSize = 1048576
                        sendBufferSize = 524288
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = 0
                        trafficClass = 0x08
                        setPerformancePreferences(0, 1, 2)
                    }
                    upstreamSocket = upstream

                    vpnService.protect(upstream)
                    upstream.connect(InetSocketAddress(targetIp, targetPort), 6000)

                    val upstreamOut = upstream.getOutputStream()
                    val upstreamIn = upstream.getInputStream()

                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                    val desyncedHeader = if (strategy.httpHostMod) {
                        HttpParser.desyncHttpPayload(headerBuffer, totalRead)
                    } else {
                        headerBuffer.copyOfRange(0, totalRead)
                    }

                    upstreamOut.write(desyncedHeader)
                    upstreamOut.flush()

                    TrafficMonitor.addConnectionLog(
                        ConnectionLog(
                            domain = targetHost,
                            port = targetPort,
                            protocol = "HTTP",
                            technique = if (strategy.httpHostMod) "HTTP_HOST_CASE" else "DIRECT",
                            bytesTransferred = totalRead.toLong()
                        )
                    )

                    // Suspend and pump stream bidirectionally
                    pumpBidirectional(clientIn, clientOut, upstreamIn, upstreamOut, clientSocket, upstream)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
            TrafficMonitor.onConnectionClosed()
        }
    }

    private suspend fun pumpBidirectional(
        clientIn: InputStream,
        clientOut: OutputStream,
        upstreamIn: InputStream,
        upstreamOut: OutputStream,
        clientSocket: Socket,
        upstreamSocket: Socket
    ) {
        val clientJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArrayPool.obtainStreamBuffer()
            try {
                var len = clientIn.read(buf)
                while (len != -1 && isRunning.get()) {
                    if (len > 0) {
                        upstreamOut.write(buf, 0, len)
                        TrafficMonitor.recordTxBytes(len.toLong())
                    }
                    len = clientIn.read(buf)
                }
            } catch (_: Exception) {
            } finally {
                ByteArrayPool.recycleStreamBuffer(buf)
                try { upstreamSocket.shutdownOutput() } catch (_: Exception) {}
                try { upstreamSocket.close() } catch (_: Exception) {}
            }
        }

        val upstreamJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArrayPool.obtainStreamBuffer()
            try {
                var len = upstreamIn.read(buf)
                while (len != -1 && isRunning.get()) {
                    if (len > 0) {
                        clientOut.write(buf, 0, len)
                        TrafficMonitor.recordRxBytes(len.toLong())
                    }
                    len = upstreamIn.read(buf)
                }
            } catch (_: Exception) {
            } finally {
                ByteArrayPool.recycleStreamBuffer(buf)
                try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }

        try {
            clientJob.join()
        } catch (_: Exception) {}
        try {
            upstreamJob.join()
        } catch (_: Exception) {}
    }
}