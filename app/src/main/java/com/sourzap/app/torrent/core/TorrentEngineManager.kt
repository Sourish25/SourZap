package com.sourzap.app.torrent.core

import android.content.Context
import android.util.Log
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.FileStorage
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.PieceFinishedAlert
import org.libtorrent4j.alerts.SessionStatsAlert
import org.libtorrent4j.alerts.StateChangedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentCheckedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.alerts.TorrentPausedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.alerts.TorrentResumedAlert
import org.libtorrent4j.swig.sha1_hash
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import com.sourzap.app.service.core.LocalDpiProxyServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interface contract for the native BitTorrent engine manager.
 */
interface TorrentEngineManager {
    fun startSession(context: Context? = null)
    fun stopSession()
    fun isSessionRunning(): Boolean

    fun addTorrent(torrentSource: TorrentSource, saveDir: File, filePriorities: List<Priority>? = null): String
    fun pauseTorrent(id: String)
    fun resumeTorrent(id: String)
    fun removeTorrent(id: String, deleteFiles: Boolean)
    fun recheckTorrent(id: String)
    fun setFilePriority(id: String, fileIndex: Int, priority: Priority)
    fun setFilePriorities(id: String, priorities: List<Priority>)
    fun getTorrentFiles(id: String): List<TorrentFileItem>
    fun setSequentialDownload(id: String, sequential: Boolean)
    fun getTorrentInfo(id: String): TorrentInfo?
    fun getTorrentLogs(id: String): List<String>

    fun pauseAll()
    fun resumeAll()

    fun observeTorrents(): StateFlow<List<TorrentItem>>
    fun observeStats(): StateFlow<TorrentSessionStats>

    companion object {
        fun create(config: TorrentSessionConfig = TorrentSessionConfig.DEFAULT): TorrentEngineManager {
            return LibtorrentEngineManager(config)
        }
    }
}

/**
 * Production implementation of [TorrentEngineManager] wrapping libtorrent4j [SessionManager].
 */
class LibtorrentEngineManager(
    private val config: TorrentSessionConfig = TorrentSessionConfig.DEFAULT
) : TorrentEngineManager {

    private val sessionManager = SessionManager()
    private val isRunning = AtomicBoolean(false)

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telemetryJob: Job? = null

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    override fun observeTorrents(): StateFlow<List<TorrentItem>> = _torrents.asStateFlow()

    private val _stats = MutableStateFlow(TorrentSessionStats())
    override fun observeStats(): StateFlow<TorrentSessionStats> = _stats.asStateFlow()

    // Store handles and metadata
    private val torrentHandles = ConcurrentHashMap<String, TorrentHandle>()
    private val torrentMetadataMap = ConcurrentHashMap<String, TorrentMetadata>()
    private val pendingPrioritiesMap = ConcurrentHashMap<String, List<Priority>>()

    // Diagnostic logging buffers
    private val torrentLogs = ConcurrentHashMap<String, MutableList<String>>()
    private val globalLogs = Collections.synchronizedList(mutableListOf<String>())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Embedded Local DPI Evasion Proxy
    private var localDpiProxy: LocalDpiProxyServer? = null

    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null // Listen to all alerts

        override fun alert(alert: Alert<*>) {
            try {
                // Record diagnostic log
                val timestamp = try { timeFormatter.format(Date()) } catch (_: Throwable) { "" }
                val typeName = alert.type()?.name ?: "ALERT"
                val alertMsg = try { alert.message() ?: "" } catch (_: Throwable) { "" }
                val logLine = "[$timestamp] [$typeName] $alertMsg"

                synchronized(globalLogs) {
                    if (globalLogs.size >= 150) globalLogs.removeAt(0)
                    globalLogs.add(logLine)
                }

                val tAlert = alert as? TorrentAlert
                val alertInfoHash = try { tAlert?.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                if (alertInfoHash != null) {
                    val list = torrentLogs.getOrPut(alertInfoHash) { Collections.synchronizedList(mutableListOf()) }
                    synchronized(list) {
                        if (list.size >= 300) list.removeAt(0)
                        list.add(logLine)
                    }
                }

                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val a = alert as? AddTorrentAlert ?: return
                        val id = try { a.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                        if (id != null) {
                            handleTorrentAdded(id)
                        }
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val a = alert as? MetadataReceivedAlert ?: return
                        val id = try { a.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                        if (id != null) {
                            handleMetadataReceived(id)
                        }
                    }
                    AlertType.STATE_CHANGED,
                    AlertType.PIECE_FINISHED,
                    AlertType.TORRENT_FINISHED,
                    AlertType.TORRENT_PAUSED,
                    AlertType.TORRENT_RESUMED,
                    AlertType.TORRENT_CHECKED -> {
                        triggerRefresh()
                    }
                    AlertType.TORRENT_REMOVED -> {
                        val a = alert as? TorrentRemovedAlert ?: return
                        val hashHex = try { a.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                        if (hashHex != null) {
                            handleTorrentRemoved(hashHex)
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val a = alert as? TorrentErrorAlert ?: return
                        val id = try { a.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                        val msg = try { a.message() ?: "Torrent error" } catch (_: Throwable) { "Torrent error" }
                        if (id != null) {
                            handleTorrentError(id, msg)
                        }
                    }
                    AlertType.SESSION_STATS -> {
                        val a = alert as? SessionStatsAlert ?: return
                        handleSessionStats(a)
                    }
                    else -> {
                        // Unhandled alert types logged in buffer
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Exception in alertListener dispatch", t)
            }
        }
    }

    override fun startSession(context: Context?) {
        if (isRunning.compareAndSet(false, true)) {
            try {
                sessionManager.addListener(alertListener)
                val settingsPack = config.createSettingsPack()
                val sessionParams = SessionParams(settingsPack)
                sessionManager.start(sessionParams)
                startTelemetryLoop()
                Log.i(TAG, "BitTorrent native session started successfully with direct MSE encryption and dynamic listen interfaces.")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start BitTorrent session", e)
                isRunning.set(false)
            }
        }
    }

    override fun stopSession() {
        if (isRunning.compareAndSet(true, false)) {
            telemetryJob?.cancel()
            telemetryJob = null
            try {
                sessionManager.removeListener(alertListener)
                sessionManager.stop()
                torrentHandles.clear()
                torrentMetadataMap.clear()
                _torrents.value = emptyList()
                _stats.value = TorrentSessionStats()

                // Stop embedded proxy
                try {
                    localDpiProxy?.stop()
                    localDpiProxy = null
                } catch (_: Throwable) {}

                Log.i(TAG, "BitTorrent session stopped cleanly.")
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping BitTorrent session", e)
            }
        }
    }

    override fun getTorrentLogs(id: String): List<String> {
        val specific = torrentLogs[id]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        val global = synchronized(globalLogs) { globalLogs.toList() }
        return (global + specific).distinct().takeLast(300)
    }

    override fun isSessionRunning(): Boolean = isRunning.get()

    override fun addTorrent(
        torrentSource: TorrentSource,
        saveDir: File,
        filePriorities: List<Priority>?
    ): String {
        if (!isSessionRunning()) {
            startSession()
        }

        if (!saveDir.exists()) {
            saveDir.mkdirs()
        }

        return when (torrentSource) {
            is TorrentSource.Magnet -> {
                val rawUri = torrentSource.uri
                val uri = TrackerInjector.injectTrackers(rawUri)
                val customName = torrentSource.displayName
                val infoHash = extractInfoHashFromMagnet(uri)
                val meta = TorrentMetadata(
                    id = infoHash,
                    displayName = customName,
                    savePath = saveDir.absolutePath,
                    addedTimestamp = System.currentTimeMillis()
                )
                torrentMetadataMap[infoHash] = meta
                if (filePriorities != null && filePriorities.isNotEmpty()) {
                    pendingPrioritiesMap[infoHash] = filePriorities
                }

                try {
                    sessionManager.download(uri, saveDir, null)
                } catch (e: LinkageError) {
                    throw e
                } catch (e: Throwable) {
                    val existingHandle = findHandle(infoHash)
                    if (existingHandle != null) {
                        existingHandle.resume()
                    } else {
                        throw IllegalStateException("Failed to queue magnet download in BitTorrent session: ${e.message}", e)
                    }
                }
                triggerRefresh()
                infoHash
            }
            is TorrentSource.FileContent -> {
                val validation = TorrentFileValidator.validate(torrentSource.bytes)
                if (validation is TorrentValidationResult.Invalid) {
                    val message = if (validation.isHtmlPayload) {
                        "Cannot load .torrent file: The file appears to be a web page or error response (HTML/XML/JSON), not a valid .torrent file."
                    } else {
                        "Cannot load .torrent file: ${validation.detailedMessage}"
                    }
                    throw IllegalArgumentException(message)
                }

                val torrentInfo = try {
                    TorrentInfo(torrentSource.bytes)
                } catch (e: Throwable) {
                    throw IllegalArgumentException("Failed to decode .torrent bencode data: ${e.message}", e)
                }

                TrackerInjector.injectIntoTorrentInfo(torrentInfo)

                val infoHash = try {
                    torrentInfo.infoHash().toHex()
                } catch (_: Throwable) {
                    (validation as? TorrentValidationResult.Valid)?.infoHash
                        ?: throw IllegalArgumentException("Failed to extract info-hash from .torrent file")
                }

                val resolvedName = if (torrentSource.name.isNotEmpty()) {
                    torrentSource.name
                } else {
                    try {
                        torrentInfo.files().name()
                    } catch (_: Throwable) {
                        (validation as? TorrentValidationResult.Valid)?.name ?: "download"
                    }
                }

                val meta = TorrentMetadata(
                    id = infoHash,
                    displayName = resolvedName,
                    savePath = saveDir.absolutePath,
                    addedTimestamp = System.currentTimeMillis()
                )
                torrentMetadataMap[infoHash] = meta

                if (filePriorities != null && filePriorities.isNotEmpty()) {
                    pendingPrioritiesMap[infoHash] = filePriorities
                }

                val totalFiles = try { torrentInfo.files().numFiles() } catch (_: Throwable) { torrentInfo.numFiles() }
                val prioritiesArray = filePriorities?.map { it.toLibtorrentPriority() }?.toTypedArray()

                try {
                    if (prioritiesArray != null && prioritiesArray.size == totalFiles) {
                        sessionManager.download(torrentInfo, saveDir, null, prioritiesArray, null, null)
                    } else {
                        sessionManager.download(torrentInfo, saveDir)
                    }
                } catch (e: LinkageError) {
                    throw e
                } catch (e: Throwable) {
                    try {
                        sessionManager.download(torrentInfo, saveDir)
                    } catch (fallbackError: Throwable) {
                        val existingHandle = findHandle(infoHash)
                        if (existingHandle != null) {
                            existingHandle.resume()
                        } else {
                            throw IllegalStateException("Failed to queue .torrent download in BitTorrent session: ${fallbackError.message ?: e.message}", fallbackError)
                        }
                    }
                }

                // If priorities were specified and handle is already created, apply priorities directly
                if (filePriorities != null && filePriorities.isNotEmpty()) {
                    setFilePriorities(infoHash, filePriorities)
                }

                triggerRefresh()
                infoHash
            }
            is TorrentSource.FilePath -> {
                val file = File(torrentSource.path)
                if (!file.exists() || !file.isFile) {
                    throw IllegalArgumentException("Torrent file not found: ${torrentSource.path}")
                }
                val bytes = try {
                    file.readBytes()
                } catch (e: Exception) {
                    throw IllegalArgumentException("Unable to read torrent file: ${e.message}", e)
                }

                val validation = TorrentFileValidator.validate(bytes)
                if (validation is TorrentValidationResult.Invalid) {
                    val message = if (validation.isHtmlPayload) {
                        "Cannot load .torrent file: The file appears to be a web page or error response (HTML/XML/JSON), not a valid .torrent file."
                    } else {
                        "Cannot load .torrent file: ${validation.detailedMessage}"
                    }
                    throw IllegalArgumentException(message)
                }

                val torrentInfo = try {
                    TorrentInfo(file)
                } catch (e: Throwable) {
                    throw IllegalArgumentException("Failed to decode .torrent file from disk: ${e.message}", e)
                }

                TrackerInjector.injectIntoTorrentInfo(torrentInfo)

                val infoHash = try {
                    torrentInfo.infoHash().toHex()
                } catch (_: Throwable) {
                    (validation as? TorrentValidationResult.Valid)?.infoHash
                        ?: throw IllegalArgumentException("Failed to extract info-hash from .torrent file")
                }

                val resolvedName = try {
                    torrentInfo.files().name()
                } catch (_: Throwable) {
                    (validation as? TorrentValidationResult.Valid)?.name ?: file.nameWithoutExtension
                }

                val meta = TorrentMetadata(
                    id = infoHash,
                    displayName = resolvedName,
                    savePath = saveDir.absolutePath,
                    addedTimestamp = System.currentTimeMillis()
                )
                torrentMetadataMap[infoHash] = meta

                if (filePriorities != null && filePriorities.isNotEmpty()) {
                    pendingPrioritiesMap[infoHash] = filePriorities
                }

                val totalFiles = try { torrentInfo.files().numFiles() } catch (_: Throwable) { torrentInfo.numFiles() }
                val prioritiesArray = filePriorities?.map { it.toLibtorrentPriority() }?.toTypedArray()

                try {
                    if (prioritiesArray != null && prioritiesArray.size == totalFiles) {
                        sessionManager.download(torrentInfo, saveDir, null, prioritiesArray, null, null)
                    } else {
                        sessionManager.download(torrentInfo, saveDir)
                    }
                } catch (e: LinkageError) {
                    throw e
                } catch (e: Throwable) {
                    try {
                        sessionManager.download(torrentInfo, saveDir)
                    } catch (fallbackError: Throwable) {
                        val existingHandle = findHandle(infoHash)
                        if (existingHandle != null) {
                            existingHandle.resume()
                        } else {
                            throw IllegalStateException("Failed to queue .torrent download in BitTorrent session: ${fallbackError.message ?: e.message}", fallbackError)
                        }
                    }
                }

                // If priorities were specified and handle is already created, apply priorities directly
                if (filePriorities != null && filePriorities.isNotEmpty()) {
                    setFilePriorities(infoHash, filePriorities)
                }

                triggerRefresh()
                infoHash
            }
        }
    }

    override fun pauseTorrent(id: String) {
        try {
            val handle = findHandle(id) ?: return
            handle.pause()
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    override fun resumeTorrent(id: String) {
        try {
            val handle = findHandle(id) ?: return
            handle.resume()
            try {
                handle.forceReannounce()
            } catch (_: Throwable) {}
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    override fun removeTorrent(id: String, deleteFiles: Boolean) {
        try {
            val handle = findHandle(id)
            if (handle != null) {
                val options = if (deleteFiles) SessionHandle.DELETE_FILES else SessionHandle.DELETE_PARTFILE
                sessionManager.remove(handle, options)
            }
        } catch (_: Throwable) {}
        torrentHandles.remove(id)
        torrentMetadataMap.remove(id)
        handleTorrentRemoved(id)
    }

    override fun recheckTorrent(id: String) {
        try {
            val handle = findHandle(id) ?: return
            handle.forceRecheck()
            try {
                handle.forceReannounce()
            } catch (_: Throwable) {}
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    override fun setFilePriority(id: String, fileIndex: Int, priority: Priority) {
        try {
            val handle = findHandle(id) ?: return
            handle.filePriority(fileIndex, priority.toLibtorrentPriority())
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    override fun setFilePriorities(id: String, priorities: List<Priority>) {
        try {
            val handle = findHandle(id)
            if (handle != null) {
                val libPriorities = priorities.map { it.toLibtorrentPriority() }.toTypedArray()
                try {
                    handle.prioritizeFiles(libPriorities)
                } catch (_: Throwable) {
                    for ((i, p) in priorities.withIndex()) {
                        try {
                            handle.filePriority(i, p.toLibtorrentPriority())
                        } catch (_: Throwable) {}
                    }
                }
            } else {
                pendingPrioritiesMap[id] = priorities
            }
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    override fun getTorrentFiles(id: String): List<TorrentFileItem> {
        val item = _torrents.value.firstOrNull { it.id == id }
        return item?.files ?: emptyList()
    }

    override fun setSequentialDownload(id: String, sequential: Boolean) {
        try {
            val handle = findHandle(id)
            if (handle != null) {
                val tfClass = try {
                    Class.forName("org.libtorrent4j.TorrentFlags")
                } catch (_: Throwable) {
                    Class.forName("org.libtorrent4j.swig.torrent_flags_t")
                }
                val seqFlag = tfClass.fields.firstOrNull {
                    it.name.equals("SEQUENTIAL_DOWNLOAD", ignoreCase = true) ||
                    it.name.equals("sequential_download", ignoreCase = true)
                }?.get(null)
                if (seqFlag != null) {
                    if (sequential) {
                        val setMethod = handle.javaClass.methods.firstOrNull {
                            it.name.equals("setFlags", ignoreCase = true) || it.name.equals("set_flags", ignoreCase = true)
                        }
                        setMethod?.invoke(handle, seqFlag)
                    } else {
                        val unsetMethod = handle.javaClass.methods.firstOrNull {
                            it.name.equals("unsetFlags", ignoreCase = true) || it.name.equals("unset_flags", ignoreCase = true)
                        }
                        unsetMethod?.invoke(handle, seqFlag)
                    }
                }
            }
        } catch (_: Throwable) {}
        val existing = torrentMetadataMap[id] ?: TorrentMetadata(id = id)
        torrentMetadataMap[id] = existing.copy(isSequential = sequential)
        triggerRefresh()
    }

    override fun getTorrentInfo(id: String): TorrentInfo? {
        val handle = findHandle(id) ?: return null
        return try {
            if (handle.status().hasMetadata()) {
                handle.torrentFile()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun pauseAll() {
        val allIds = (torrentMetadataMap.keys + torrentHandles.keys).toSet()
        allIds.forEach { id ->
            try {
                val handle = findHandle(id)
                handle?.pause()
            } catch (_: Throwable) {}
        }
        triggerRefresh()
    }

    override fun resumeAll() {
        val allIds = (torrentMetadataMap.keys + torrentHandles.keys).toSet()
        allIds.forEach { id ->
            try {
                val handle = findHandle(id)
                handle?.resume()
            } catch (_: Throwable) {}
        }
        triggerRefresh()
    }

    private fun findHandle(id: String): TorrentHandle? {
        val sha1 = try {
            Sha1Hash(sha1_hash.from_hex(id))
        } catch (e: Exception) {
            return null
        }
        val found = try {
            sessionManager.find(sha1)
        } catch (_: Throwable) {
            null
        }
        if (found != null) {
            val valid = try { found.isValid } catch (_: Throwable) { false }
            if (valid) {
                torrentHandles[id] = found
                return found
            }
        }
        return null
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = engineScope.launch {
            var cycleCount = 0
            var stalledCycleCount = 0
            while (isActive && isRunning.get()) {
                try {
                    sessionManager.postSessionStats()
                    updateTorrentsAndStats()

                    cycleCount++
                    // Every 15 seconds, pulse active stalled torrents to discover and connect to new peers
                    if (cycleCount % 15 == 0) {
                        var hasStalled = false
                        for ((_, handle) in torrentHandles) {
                            try {
                                val status = handle.status()
                                val isPaused = isTorrentPaused(status)
                                val state = status.state()
                                if (!isPaused && state != TorrentStatus.State.CHECKING_FILES &&
                                    state != TorrentStatus.State.CHECKING_RESUME_DATA &&
                                    status.progress() < 1.0f
                                ) {
                                    if (status.downloadRate() == 0 || status.numPeers() < 3) {
                                        hasStalled = true
                                        // Aggressively force tracker announces across all tiers
                                        try { handle.forceReannounce(0, -1) } catch (_: Throwable) { handle.forceReannounce() }
                                        try { handle.forceDHTAnnounce() } catch (_: Throwable) {}

                                         // Inject global public trackers if not already added
                                         for (tr in PRIORITY_LIVE_TRACKERS) {
                                             try { handle.addTracker(AnnounceEntry(tr)) } catch (_: Throwable) {}
                                         }
                                         for (tr in TrackerInjector.ALL_CURATED_TRACKERS) {
                                             try { handle.addTracker(AnnounceEntry(tr)) } catch (_: Throwable) {}
                                         }
                                     }
                                 }
                             } catch (_: Throwable) {}
                        }

                        if (hasStalled) {
                            stalledCycleCount += 15
                            // If torrents remain stalled at 0 B/s for over 45 seconds, reopen network sockets
                            if (stalledCycleCount >= 45) {
                                stalledCycleCount = 0
                                try {
                                    sessionManager.reopenNetworkSockets()
                                } catch (_: Throwable) {}
                            }
                        } else {
                            stalledCycleCount = 0
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error in telemetry loop", e)
                }
                delay(1000L)
            }
        }
    }

    private fun triggerRefresh() {
        engineScope.launch {
            try {
                updateTorrentsAndStats()
            } catch (e: Throwable) {
                Log.w(TAG, "Error refreshing torrents", e)
            }
        }
    }

    private fun handleTorrentAdded(id: String) {
        try {
            val handle = findHandle(id) ?: return
            if (!torrentMetadataMap.containsKey(id)) {
                val status = try { handle.status() } catch (_: Throwable) { null }
                val hName: String = try { status?.name() ?: "" } catch (_: Throwable) { "" }
                val displayName: String = if (hName.isNotEmpty()) hName else id
                val meta = TorrentMetadata(
                    id = id,
                    displayName = displayName,
                    savePath = "",
                    addedTimestamp = System.currentTimeMillis()
                )
                torrentMetadataMap[id] = meta
            }

            // 1. Auto-inject verified high-capacity live public trackers
            for (tr in PRIORITY_LIVE_TRACKERS) {
                try {
                    handle.addTracker(AnnounceEntry(tr))
                } catch (_: Throwable) {}
            }

            // 2. Also inject curated backup trackers
            for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
                try {
                    handle.addTracker(AnnounceEntry(tr))
                } catch (_: Throwable) {}
            }

            // Immediately force announce across all tracker tiers and DHT
            try { handle.forceReannounce(0, -1) } catch (_: Throwable) {
                try { handle.forceReannounce() } catch (_: Throwable) {}
            }
            try { handle.forceDHTAnnounce() } catch (_: Throwable) {}

            // Apply pending priorities if available
            val pending = pendingPrioritiesMap.remove(id)
            if (pending != null && pending.isNotEmpty()) {
                setFilePriorities(id, pending)
            }

            // Asynchronously pre-resolve tracker hostnames via DoH
            engineScope.launch {
                try {
                    DohTrackerResolver.preResolveTrackers(TrackerInjector.HTTPS_PORT_443_TRACKERS)
                } catch (_: Throwable) {}
            }

            triggerRefresh()
        } catch (e: Throwable) {
            Log.w(TAG, "Error in handleTorrentAdded", e)
        }
    }

    private fun handleMetadataReceived(id: String) {
        try {
            val handle = findHandle(id) ?: return
            val info: TorrentInfo? = try {
                val hasMeta = try { handle.status().hasMetadata() } catch (_: Throwable) { false }
                if (hasMeta) handle.torrentFile() else null
            } catch (_: Throwable) {
                null
            }
            if (info != null) {
                val existing: TorrentMetadata = torrentMetadataMap[id] ?: TorrentMetadata(id = id)
                val infoName: String = try { info.files().name() } catch (_: Throwable) { "" }
                val updated = existing.copy(displayName = if (infoName.isNotEmpty()) infoName else id)
                torrentMetadataMap[id] = updated

                val pending = pendingPrioritiesMap.remove(id)
                if (pending != null && pending.isNotEmpty()) {
                    val libPriorities = pending.map { it.toLibtorrentPriority() }.toTypedArray()
                    try {
                        handle.prioritizeFiles(libPriorities)
                    } catch (_: Throwable) {
                        for ((i, p) in pending.withIndex()) {
                            try {
                                handle.filePriority(i, p.toLibtorrentPriority())
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    private fun handleTorrentRemoved(id: String) {
        torrentHandles.remove(id)
        pendingPrioritiesMap.remove(id)
        _torrents.value = _torrents.value.filter { it.id != id }
    }

    private fun handleTorrentError(id: String, errorMessage: String) {
        try {
            val existing: TorrentMetadata = torrentMetadataMap[id] ?: TorrentMetadata(id = id)
            val updated = existing.copy(error = errorMessage)
            torrentMetadataMap[id] = updated
            triggerRefresh()
        } catch (_: Throwable) {}
    }

    private fun handleSessionStats(alert: SessionStatsAlert) {
        triggerRefresh()
    }

    @Synchronized
    private fun updateTorrentsAndStats() {
        val allIds = (torrentMetadataMap.keys + torrentHandles.keys).toSet()
        val items = mutableListOf<TorrentItem>()

        var totalDownSpeed = 0L
        var totalUpSpeed = 0L
        var totalDownloaded = 0L
        var totalUploaded = 0L
        var totalAllBytes = 0L
        var activeCount = 0
        var pausedCount = 0
        var seedingCount = 0

        for (id in allIds) {
            try {
                val handle = findHandle(id) ?: continue
                val status: TorrentStatus = try { handle.status() } catch (_: Throwable) { continue }
                val state = mapTorrentState(handle, status)
                val hasMeta = try { status.hasMetadata() } catch (_: Throwable) { false }
                val info: TorrentInfo? = if (hasMeta) {
                    try { handle.torrentFile() } catch (_: Throwable) { null }
                } else {
                    null
                }

                val hName: String = try { status.name() ?: "" } catch (_: Throwable) { "" }
                val fallbackName: String = if (hName.isNotEmpty()) hName else id
                val meta: TorrentMetadata = torrentMetadataMap[id] ?: TorrentMetadata(
                    id = id,
                    displayName = fallbackName,
                    savePath = "",
                    addedTimestamp = System.currentTimeMillis()
                )

                val infoName: String? = try { info?.files()?.name() } catch (_: Throwable) { null }
                val name: String = meta.displayName ?: (if (!infoName.isNullOrEmpty()) infoName else fallbackName)
                val progress: Float = try { status.progress() } catch (_: Throwable) { 0f }
                val downRate: Long = try { status.downloadRate().toLong() } catch (_: Throwable) { 0L }
                val upRate: Long = try { status.uploadRate().toLong() } catch (_: Throwable) { 0L }
                val totalDone: Long = try { status.totalDone() } catch (_: Throwable) { 0L }
                val totalSize: Long = try { info?.totalSize() ?: status.total() } catch (_: Throwable) { status.total() }
                val allTimeUpload: Long = try { status.allTimeUpload() } catch (_: Throwable) { 0L }

                totalDownSpeed += downRate
                totalUpSpeed += upRate
                totalDownloaded += totalDone
                totalUploaded += allTimeUpload
                totalAllBytes += totalSize

                when (state) {
                    TorrentState.DOWNLOADING, TorrentState.ALLOCATING, TorrentState.METADATA -> activeCount++
                    TorrentState.PAUSED -> pausedCount++
                    TorrentState.SEEDING -> seedingCount++
                    else -> {}
                }

                val remainingBytes: Long = totalSize - totalDone
                val eta: Long = if (downRate > 0L && remainingBytes > 0L) {
                    remainingBytes / downRate
                } else if (progress >= 1.0f) {
                    0L
                } else {
                    -1L
                }
                val shareRatio: Float = if (totalDone > 0L) allTimeUpload.toFloat() / totalDone.toFloat() else 0.0f

                val files = mutableListOf<TorrentFileItem>()
                if (info != null) {
                    try {
                        val fileStorage: FileStorage = info.files()
                        val numFiles = fileStorage.numFiles()
                        val fileProgress: LongArray? = try { handle.fileProgress() } catch (_: Throwable) { null }
                        for (i in 0 until numFiles) {
                            val p = try { Priority.fromLibtorrent(handle.filePriority(i)) } catch (_: Throwable) { Priority.NORMAL }
                            val bytes: Long = if (fileProgress != null && i < fileProgress.size) fileProgress[i] else 0L
                            val fileSize: Long = try { fileStorage.fileSize(i) } catch (_: Throwable) { 0L }
                            val fileProg: Float = if (fileSize > 0L) (bytes.toFloat() / fileSize.toFloat()).coerceIn(0.0f, 1.0f) else 0.0f
                            files.add(
                                TorrentFileItem(
                                    index = i,
                                    path = try { fileStorage.filePath(i) } catch (_: Throwable) { "" },
                                    size = fileSize,
                                    downloadedBytes = bytes,
                                    progress = fileProg,
                                    priority = p
                                )
                            )
                        }
                    } catch (_: Throwable) {}
                }

                val isSequential = meta.isSequential

                val item = TorrentItem(
                    id = id,
                    name = name,
                    state = state,
                    progress = progress,
                    downloadSpeed = downRate,
                    uploadSpeed = upRate,
                    totalBytes = totalSize,
                    downloadedBytes = totalDone,
                    uploadedBytes = allTimeUpload,
                    numSeeds = try { status.numSeeds() } catch (_: Throwable) { 0 },
                    numPeers = try { status.numPeers() } catch (_: Throwable) { 0 },
                    totalSeeds = try { status.listSeeds() } catch (_: Throwable) { 0 },
                    totalPeers = try { status.listPeers() } catch (_: Throwable) { 0 },
                    etaSeconds = eta,
                    shareRatio = shareRatio,
                    savePath = meta.savePath,
                    addedTimestamp = meta.addedTimestamp,
                    isSequential = isSequential,
                    files = files,
                    error = meta.error,
                    pieces = null
                )
                items.add(item)
            } catch (e: Throwable) {
                Log.w(TAG, "Error extracting torrent stats for handle", e)
            }
        }

        _torrents.value = items
        val dhtNodes = sessionManager.stats()?.dhtNodes() ?: 0L
        val aggProgress = if (totalAllBytes > 0L) {
            (totalDownloaded.toFloat() / totalAllBytes.toFloat()).coerceIn(0.0f, 1.0f)
        } else if (items.isNotEmpty()) {
            val valid = items.filter { it.totalBytes > 0L }
            if (valid.isNotEmpty()) {
                valid.map { it.progress }.average().toFloat().coerceIn(0.0f, 1.0f)
            } else 0.0f
        } else 0.0f

        _stats.value = TorrentSessionStats(
            totalDownloadSpeed = totalDownSpeed,
            totalUploadSpeed = totalUpSpeed,
            totalDownloadedBytes = totalDownloaded,
            totalUploadedBytes = totalUploaded,
            activeTorrents = activeCount,
            pausedTorrents = pausedCount,
            seedingTorrents = seedingCount,
            dhtNodes = dhtNodes,
            totalBytes = totalAllBytes,
            aggregateProgress = aggProgress
        )
    }

    private fun mapTorrentState(handle: TorrentHandle, status: TorrentStatus): TorrentState {
        if (isTorrentPaused(status)) {
            return TorrentState.PAUSED
        }
        return when (status.state()) {
            TorrentStatus.State.CHECKING_FILES,
            TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
            TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
            TorrentStatus.State.FINISHED -> TorrentState.FINISHED
            TorrentStatus.State.SEEDING -> TorrentState.SEEDING
            else -> TorrentState.DOWNLOADING
        }
    }

    private fun isTorrentPaused(status: TorrentStatus): Boolean {
        return try {
            val flags = status.flags()
            val tfClass = try {
                Class.forName("org.libtorrent4j.TorrentFlags")
            } catch (_: Throwable) {
                Class.forName("org.libtorrent4j.swig.torrent_flags_t")
            }
            val pausedFlag = tfClass.fields.firstOrNull {
                it.name.equals("PAUSED", ignoreCase = true) || it.name.equals("paused", ignoreCase = true)
            }?.get(null)
            if (pausedFlag != null) {
                val andMethod = flags.javaClass.methods.firstOrNull { it.name.equals("and_", ignoreCase = true) || it.name.equals("and", ignoreCase = true) }
                val res = andMethod?.invoke(flags, pausedFlag)
                val nonZeroMethod = res?.javaClass?.methods?.firstOrNull { it.name.equals("non_zero", ignoreCase = true) || it.name.equals("nonZero", ignoreCase = true) }
                nonZeroMethod?.invoke(res) as? Boolean ?: false
            } else {
                false
            }
        } catch (_: Throwable) {
            try {
                val m = status.javaClass.getMethod("isPaused")
                m.invoke(status) as? Boolean ?: false
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun extractInfoHashFromMagnet(uri: String): String {
        val parsed = MagnetHandler.parse(uri)
        if (parsed != null && parsed.infoHash.isNotEmpty()) {
            return parsed.infoHash
        }
        val xtMatch = Regex("xt=urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})", RegexOption.IGNORE_CASE).find(uri)
        val candidate = xtMatch?.groupValues?.get(1)
        if (candidate != null) {
            val normalized = MagnetHandler.normalizeInfoHash(candidate)
            if (normalized != null) {
                return normalized
            }
        }
        return candidate?.lowercase() ?: "hash_${System.currentTimeMillis()}"
    }

    private data class TorrentMetadata(
        val id: String,
        val displayName: String? = null,
        val savePath: String = "",
        val addedTimestamp: Long = System.currentTimeMillis(),
        val isSequential: Boolean = false,
        val error: String? = null
    )

    companion object {
        private const val TAG = "TorrentEngineManager"

        val PRIORITY_LIVE_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://explodie.org:6969/announce",
            "http://tracker.opentrackr.org:1337/announce",
            "http://nyaa.tracker.wf:7777/announce",
            "http://open.acgnxtracker.com:80/announce",
            "udp://zer0day.ch:1337/announce",
            "udp://tracker.therarbg.to:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://exodus.desync.com:6969/announce"
        )
    }
}
