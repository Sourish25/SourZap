package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class LocalDpiProxyServer(
    private val vpnService: VpnService,
    private val scope: CoroutineScope
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val proxyDispatcher = Dispatchers.IO.limitedParallelism(64)
    var port: Int = 0
        private set

    companion object {
        /**
         * Parses authority strings into (host, port).
         * Correctly handles:
         * - Bracketed IPv6 with port: "[2001:db8::1]:8080" -> ("2001:db8::1", 8080)
         * - Bracketed IPv6 without port: "[2001:db8::1]" -> ("2001:db8::1", defaultPort)
         * - Unbracketed IPv6: "2001:db8::1" -> ("2001:db8::1", defaultPort)
         * - Hostname / IPv4 with port: "example.com:8080" -> ("example.com", 8080)
         * - Hostname / IPv4 without port: "example.com" -> ("example.com", defaultPort)
         */
        fun parseHostAndPort(rawAuthority: String, defaultPort: Int): Pair<String, Int> {
            val trimmed = rawAuthority.trim()
            if (trimmed.isEmpty()) return Pair("", defaultPort)

            if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                if (closeBracket != -1) {
                    val host = trimmed.substring(1, closeBracket).trim()
                    val afterBracket = trimmed.substring(closeBracket + 1)
                    val port = if (afterBracket.startsWith(":")) {
                        afterBracket.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                    } else {
                        defaultPort
                    }
                    return Pair(host, port)
                }
            }

            val colonCount = trimmed.count { it == ':' }
            if (colonCount >= 2) {
                // Unbracketed IPv6 literal
                return Pair(trimmed, defaultPort)
            }

            if (colonCount == 1) {
                val host = trimmed.substringBefore(":").trim()
                val port = trimmed.substringAfter(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                return Pair(host, port)
            }

            return Pair(trimmed, defaultPort)
        }

        /**
         * Robustly normalizes proxy-style absolute URIs to origin-form relative paths.
         * Handles tracker URLs with unescaped raw binary bytes in query parameters (e.g. info_hash),
         * bracketed IPv6 hosts, query parameters without paths, and missing paths without throwing URISyntaxException.
         */
        fun normalizeUriPath(fullUrl: String): String {
            val trimmed = fullUrl.trim()
            if (trimmed.isEmpty()) return "/"
            if (trimmed.startsWith("/")) return trimmed

            val schemeEnd = trimmed.indexOf("://")
            val authorityAndPath = if (schemeEnd != -1) {
                trimmed.substring(schemeEnd + 3)
            } else if (trimmed.startsWith("//")) {
                trimmed.substring(2)
            } else {
                trimmed
            }

            val searchStart = if (authorityAndPath.startsWith("[")) {
                val closeBracket = authorityAndPath.indexOf(']')
                if (closeBracket != -1) closeBracket + 1 else 0
            } else {
                0
            }

            var slashIndex = -1
            var isQueryOnly = false
            for (i in searchStart until authorityAndPath.length) {
                val c = authorityAndPath[i]
                if (c == '/') {
                    slashIndex = i
                    break
                }
                if (c == '?' || c == '#') {
                    slashIndex = i
                    isQueryOnly = true
                    break
                }
            }

            return when {
                slashIndex == -1 -> "/"
                isQueryOnly -> "/" + authorityAndPath.substring(slashIndex)
                else -> authorityAndPath.substring(slashIndex)
            }
        }

        /**
         * Determines if a host string is an IPv4 or IPv6 address literal to bypass DNS resolution.
         */
        fun isIpLiteral(host: String): Boolean {
            val trimmed = host.trim()
            if (trimmed.contains(':')) return true
            if (trimmed.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) return true
            return false
        }
    }

    fun start(): Int {
        val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        port = server.localPort
        isRunning.set(true)

        scope.launch(proxyDispatcher) {
            while (isRunning.get() && scope.isActive) {
                try {
                    val clientSocket = server.accept()
                    scope.launch(proxyDispatcher) {
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
                soTimeout = 15000           // 15s socket timeout
                trafficClass = 0x08         // IPTOS_THROUGHPUT
                setPerformancePreferences(0, 1, 2)
            }

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Read initial request line & headers using HttpParser.findHeaderBoundary
            val headerBuffer = ByteArrayPool.obtain16k()
            val tempBuf = ByteArrayPool.obtain4k()
            var totalRead = 0

            val initialHeaderBytes: ByteArray
            val initialBodyBytes: ByteArray
            val headerStr: String
            val firstLine: String
            val firstLineEnd: Int

            try {
                while (totalRead < 8192) {
                    val count = clientIn.read(tempBuf, 0, minOf(1024, 8192 - totalRead))
                    if (count <= 0) break
                    System.arraycopy(tempBuf, 0, headerBuffer, totalRead, count)
                    totalRead += count

                    if (HttpParser.findHeaderBoundary(headerBuffer, totalRead) != null) break
                }

                if (totalRead == 0) return

                val boundary = HttpParser.findHeaderBoundary(headerBuffer, totalRead)
                val headerEnd = if (boundary != null) boundary.first + boundary.second else totalRead

                initialHeaderBytes = headerBuffer.copyOfRange(0, headerEnd)
                initialBodyBytes = if (totalRead > headerEnd) headerBuffer.copyOfRange(headerEnd, totalRead) else ByteArray(0)

                headerStr = String(initialHeaderBytes, Charsets.ISO_8859_1)
                firstLineEnd = headerStr.indexOf("\r\n").let { if (it == -1) headerStr.indexOf("\n") else it }
                firstLine = if (firstLineEnd != -1) headerStr.substring(0, firstLineEnd) else headerStr
            } finally {
                ByteArrayPool.recycle16k(headerBuffer)
                ByteArrayPool.recycle4k(tempBuf)
            }

            if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                // --- HTTPS & Messaging CONNECT Tunneling ---
                val parts = firstLine.trim().split(Regex("\\s+"))
                if (parts.size < 2) return

                val target = parts[1]
                val (targetHost, targetPort) = parseHostAndPort(target, 443)
                if (targetHost.isEmpty()) return

                // Resolve targetHost with DoH if not IP literal to prevent ISP DNS blocking
                val targetIp = try {
                    if (isIpLiteral(targetHost)) {
                        InetAddress.getByName(targetHost)
                    } else {
                        val targetIps = DohResolver.resolve(targetHost)
                        targetIps.firstOrNull() ?: InetAddress.getByName(targetHost)
                    }
                } catch (_: Exception) {
                    InetAddress.getByName(targetHost)
                }

                // Connect to remote upstream with protected socket
                val upstream = Socket().apply {
                    receiveBufferSize = 2097152 // 2 MB Turbo Video Buffer
                    sendBufferSize = 1048576    // 1 MB Send Buffer
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 15000
                    trafficClass = 0x08
                    setPerformancePreferences(0, 1, 2)
                }
                upstreamSocket = upstream

                vpnService.protect(upstream)
                upstream.connect(InetSocketAddress(targetIp, targetPort), 6000)

                // Respond 200 Connection Established to Android client app
                val response200 = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                clientOut.write(response200)
                clientOut.flush()

                // Client will now send initial payload (TLS ClientHello, or WhatsApp Noise Handshake)
                val initialBuffer = ByteArrayPool.obtain16k()
                try {
                    val initialLen = clientIn.read(initialBuffer)
                    if (initialLen > 0) {
                        val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                        val upstreamOut = upstream.getOutputStream()
                        val upstreamIn = upstream.getInputStream()

                        val isBt = DpiEngine.isBitTorrentHandshake(initialBuffer, initialLen)
                        val sniResult = if (!isBt) TlsParser.parseClientHello(initialBuffer, initialLen) else TlsParser.SniResult(null, -1, -1, false)

                        if (isBt || sniResult.isClientHello) {
                            var appliedTechnique = "DIRECT"
                            val logDomain = when {
                                isBt -> "BitTorrent Swarm"
                                sniResult.hostname != null -> sniResult.hostname
                                else -> targetHost
                            }

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
                                    protocol = if (isBt) "BitTorrent" else "TLS",
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

                        // Suspend and pump remaining stream bidirectionally until streams close
                        pumpBidirectional(clientIn, clientOut, upstreamIn, upstreamOut, clientSocket, upstream)
                    }
                } finally {
                    ByteArrayPool.recycle16k(initialBuffer)
                }
            } else {
                // --- Plain HTTP Request ---
                val hostLine = headerStr.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                val rawHost = hostLine?.substringAfter(":")?.trim() ?: ""

                val (hostFromHeader, portFromHeader) = parseHostAndPort(rawHost, 80)
                var targetHost = hostFromHeader
                var targetPort = portFromHeader

                val firstLineParts = firstLine.trim().split(Regex("\\s+"))
                val method = if (firstLineParts.isNotEmpty()) firstLineParts[0] else "GET"
                val rawUri = if (firstLineParts.size >= 2) firstLineParts[1] else "/"
                val httpVersion = if (firstLineParts.size >= 3) firstLineParts[2] else "HTTP/1.1"

                // Fallback host extraction from absolute URI if Host header is missing
                if (targetHost.isEmpty() && (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true))) {
                    val uriWithoutScheme = rawUri.substringAfter("://").substringBefore("/")
                    val (extractedHost, extractedPort) = parseHostAndPort(uriWithoutScheme, 80)
                    targetHost = extractedHost
                    targetPort = extractedPort
                }

                if (targetHost.isNotEmpty()) {
                    val targetIp = try {
                        if (isIpLiteral(targetHost)) {
                            InetAddress.getByName(targetHost)
                        } else {
                            val targetIps = DohResolver.resolve(targetHost)
                            targetIps.firstOrNull() ?: InetAddress.getByName(targetHost)
                        }
                    } catch (_: Exception) {
                        InetAddress.getByName(targetHost)
                    }

                    val upstream = Socket().apply {
                        receiveBufferSize = 1048576
                        sendBufferSize = 524288
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = 15000
                        trafficClass = 0x08
                        setPerformancePreferences(0, 1, 2)
                    }
                    upstreamSocket = upstream

                    vpnService.protect(upstream)
                    upstream.connect(InetSocketAddress(targetIp, targetPort), 6000)

                    val upstreamOut = upstream.getOutputStream()
                    val upstreamIn = upstream.getInputStream()

                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

                    // Normalize proxy-style absolute URIs safely without regex replacement
                    var finalHeaderStr = headerStr
                    if (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true)) {
                        val normalizedPath = normalizeUriPath(rawUri)
                        val newFirstLine = "$method $normalizedPath $httpVersion"
                        val restOfHeaders = if (firstLineEnd != -1) headerStr.substring(firstLineEnd) else ""
                        finalHeaderStr = newFirstLine + restOfHeaders
                    }

                    var outgoingHeaderBytes = finalHeaderStr.toByteArray(Charsets.ISO_8859_1)

                    if (strategy.httpHostMod) {
                        outgoingHeaderBytes = HttpParser.desyncHttpPayload(outgoingHeaderBytes, outgoingHeaderBytes.size)
                    }

                    // Assemble outgoing header + preserved binary initial body bytes
                    val fullOutgoingBuffer = ByteArray(outgoingHeaderBytes.size + initialBodyBytes.size)
                    System.arraycopy(outgoingHeaderBytes, 0, fullOutgoingBuffer, 0, outgoingHeaderBytes.size)
                    if (initialBodyBytes.isNotEmpty()) {
                        System.arraycopy(initialBodyBytes, 0, fullOutgoingBuffer, outgoingHeaderBytes.size, initialBodyBytes.size)
                    }

                    upstreamOut.write(fullOutgoingBuffer)
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
        var clientJob: Job? = null
        var upstreamJob: Job? = null

        clientJob = scope.launch(proxyDispatcher) {
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
                upstreamJob?.cancel()
                try { clientSocket.close() } catch (_: Exception) {}
                try { upstreamSocket.close() } catch (_: Exception) {}
            }
        }

        upstreamJob = scope.launch(proxyDispatcher) {
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
                clientJob?.cancel()
                try { clientSocket.close() } catch (_: Exception) {}
                try { upstreamSocket.close() } catch (_: Exception) {}
            }
        }

        try {
            clientJob.join()
            upstreamJob.join()
        } catch (_: Exception) {
        } finally {
            clientJob.cancel()
            upstreamJob.cancel()
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket.close() } catch (_: Exception) {}
        }
    }
}