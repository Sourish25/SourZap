package com.sourzap.app.service

import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.data.model.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object TrafficMonitor {

    private const val MAX_LOGS = 50
    private const val MAX_SPEED_SAMPLES = 20

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<ConnectionLog>>(emptyList())
    val recentLogs: StateFlow<List<ConnectionLog>> = _recentLogs.asStateFlow()

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val sessionRxBytes = AtomicLong(0L)
    private val sessionTxBytes = AtomicLong(0L)
    private val totalLifetimeRxBytes = AtomicLong(0L)
    private val totalLifetimeTxBytes = AtomicLong(0L)
    private val activeConnectionCounter = AtomicInteger(0)
    private val totalPacketsCounter = AtomicLong(0L)
    private val lastSecPackets = AtomicInteger(0)

    private val speedHistory = ArrayDeque<Float>(MAX_SPEED_SAMPLES + 5)
    private val logBuffer = ArrayDeque<ConnectionLog>(MAX_LOGS)
    private val logLock = Any()
    private val speedLock = Any()

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startMonitoring() {
        _isVpnActive.value = true
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastRx = sessionRxBytes.get()
            var lastTx = sessionTxBytes.get()

            while (isActive) {
                delay(1000)
                val currentRx = sessionRxBytes.get()
                val currentTx = sessionTxBytes.get()

                val rxSpeed = (currentRx - lastRx).coerceAtLeast(0L)
                val txSpeed = (currentTx - lastTx).coerceAtLeast(0L)
                lastRx = currentRx
                lastTx = currentTx

                val speedKbps = ((rxSpeed + txSpeed) * 8f) / 1000f
                val speedList = synchronized(speedLock) {
                    if (speedHistory.size >= MAX_SPEED_SAMPLES) {
                        speedHistory.removeFirst()
                    }
                    speedHistory.addLast(speedKbps)
                    speedHistory.toList()
                }

                val pps = lastSecPackets.getAndSet(0)

                _stats.update {
                    it.copy(
                        downloadSpeedBps = rxSpeed,
                        uploadSpeedBps = txSpeed,
                        sessionDownloadBytes = currentRx,
                        sessionUploadBytes = currentTx,
                        totalDownloadBytes = totalLifetimeRxBytes.get() + currentRx,
                        totalUploadBytes = totalLifetimeTxBytes.get() + currentTx,
                        activeConnections = activeConnectionCounter.get().coerceAtLeast(0),
                        totalPacketsProcessed = totalPacketsCounter.get(),
                        packetsPerSecond = pps,
                        recentSpeedHistory = speedList
                    )
                }
            }
        }
    }

    fun stopMonitoring() {
        _isVpnActive.value = false
        monitorJob?.cancel()
        monitorJob = null
        _stats.update {
            it.copy(
                downloadSpeedBps = 0L,
                uploadSpeedBps = 0L,
                activeConnections = 0,
                packetsPerSecond = 0
            )
        }
    }

    fun recordRxBytes(bytes: Long) {
        if (bytes <= 0) return
        sessionRxBytes.addAndGet(bytes)
        totalPacketsCounter.incrementAndGet()
        lastSecPackets.incrementAndGet()
    }

    fun recordTxBytes(bytes: Long) {
        if (bytes <= 0) return
        sessionTxBytes.addAndGet(bytes)
        totalPacketsCounter.incrementAndGet()
        lastSecPackets.incrementAndGet()
    }

    fun onConnectionOpened() {
        activeConnectionCounter.incrementAndGet()
    }

    fun onConnectionClosed() {
        activeConnectionCounter.updateAndGet { current -> maxOf(0, current - 1) }
    }

    fun addConnectionLog(log: ConnectionLog) {
        val snapshot = synchronized(logLock) {
            if (logBuffer.size >= MAX_LOGS) {
                logBuffer.removeLast() // drop oldest
            }
            logBuffer.addFirst(log) // add newest at top (index 0)
            logBuffer.toList()
        }
        _recentLogs.value = snapshot
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.clear()
        }
        _recentLogs.value = emptyList()
    }

    fun resetSession() {
        sessionRxBytes.set(0L)
        sessionTxBytes.set(0L)
        lastSecPackets.set(0)
        synchronized(speedLock) {
            speedHistory.clear()
        }
        _stats.update {
            it.copy(
                sessionDownloadBytes = 0L,
                sessionUploadBytes = 0L,
                recentSpeedHistory = emptyList()
            )
        }
    }
}