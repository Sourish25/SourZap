package com.sourzap.app.ui.torrent

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sourzap.app.SourZapApp
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.model.PendingTorrentIntent
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentProxyConfig
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.torrent.service.TorrentDownloadService
import com.sourzap.app.ui.components.AdaptiveContentContainer
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.ExpressiveWavyProgressIndicator
import com.sourzap.app.ui.torrent.PreDownloadSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TorrentScreen() {
    val app = SourZapApp.instance
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val torrentManager = app.torrentEngineManager

    LaunchedEffect(Unit) {
        if (!torrentManager.isSessionRunning()) {
            torrentManager.startSession(context)
        }
    }

    val torrents by torrentManager.observeTorrents().collectAsStateWithLifecycle()
    val stats by torrentManager.observeStats().collectAsStateWithLifecycle()
    val pendingTorrentIntent by app.pendingTorrentIntent.collectAsStateWithLifecycle()
    val proxyConfig by app.torrentProxyRepository.config.collectAsStateWithLifecycle()

    var selectedFilter by remember { mutableStateOf(TorrentFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showProxySheet by remember { mutableStateOf(false) }

    var prefilledMagnet by remember { mutableStateOf("") }
    var prefilledName by remember { mutableStateOf("") }
    var prefilledTorrentBytes by remember { mutableStateOf<ByteArray?>(null) }
    var prefilledTorrentFileName by remember { mutableStateOf("") }

    var preDownloadState by remember { mutableStateOf<PreDownloadState?>(null) }

    LaunchedEffect(pendingTorrentIntent) {
        pendingTorrentIntent?.let { intent ->
            when (intent) {
                is PendingTorrentIntent.Magnet -> {
                    prefilledMagnet = intent.uri
                    prefilledName = intent.name ?: ""
                    prefilledTorrentBytes = null
                    prefilledTorrentFileName = ""
                    showAddDialog = true
                }
                is PendingTorrentIntent.TorrentFile -> {
                    val validation = TorrentFileValidator.validate(intent.bytes)
                    if (validation is TorrentValidationResult.Valid) {
                        val defaultSaveDir = TorrentStorageHelper.getSaveDirectory(context)
                        preDownloadState = PreDownloadState.create(
                            torrentSource = TorrentSource.FileContent(intent.bytes, intent.fileName.ifBlank { validation.name }),
                            name = validation.name,
                            files = validation.files,
                            targetDirectory = defaultSaveDir
                        )
                        showAddDialog = false
                    } else {
                        prefilledMagnet = ""
                        prefilledName = ""
                        prefilledTorrentBytes = intent.bytes
                        prefilledTorrentFileName = intent.fileName
                        showAddDialog = true
                    }
                }
            }
        }
    }

    var torrentToDelete by remember { mutableStateOf<TorrentItem?>(null) }
    var deleteWithFiles by remember { mutableStateOf(false) }
    var inspectingTorrent by remember { mutableStateOf<TorrentItem?>(null) }
    var viewingLogsTorrent by remember { mutableStateOf<TorrentItem?>(null) }

    val filteredTorrents = remember(torrents, selectedFilter, searchQuery) {
        torrents.filter { item ->
            val matchesFilter = selectedFilter.matches(item)
            val matchesQuery = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.id.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    AdaptiveContentContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    TorrentHeader(
                        stats = stats,
                        proxyConfig = proxyConfig,
                        onOpenProxySettings = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showProxySheet = true
                        },
                        onPauseAll = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            torrentManager.pauseAll()
                        },
                        onResumeAll = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            torrentManager.resumeAll()
                            TorrentDownloadService.start(context)
                        }
                    )
                }

                if (proxyConfig.enabled) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (proxyConfig.isSnowflakePreset) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                )
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showProxySheet = true
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (proxyConfig.isSnowflakePreset) {
                                    Text("❄️", fontSize = 16.sp)
                                    Column {
                                        Text(
                                            text = "Snowflake (Orbot) Proxy Active",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Text(
                                            text = "Bypassing ISP port blocks via WebRTC on Port 443",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "${proxyConfig.type} Proxy Active (${proxyConfig.host}:${proxyConfig.port})",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "Routing peers and trackers through custom proxy",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                item {
                    TorrentSessionStatsBanner(stats = stats)
                }

                item {
                    TorrentFilterBar(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        selectedFilter = selectedFilter,
                        onFilterSelected = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = it
                        }
                    )
                }

                if (filteredTorrents.isEmpty()) {
                    item {
                        TorrentEmptyState(
                            hasAnyTorrents = torrents.isNotEmpty(),
                            onAddTorrentClick = {
                                prefilledMagnet = ""
                                prefilledName = ""
                                prefilledTorrentBytes = null
                                prefilledTorrentFileName = ""
                                showAddDialog = true
                            }
                        )
                    }
                } else {
                    items(filteredTorrents, key = { it.id }) { item ->
                        TorrentItemCard(
                            item = item,
                            onTogglePause = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (item.state == TorrentState.PAUSED) {
                                    torrentManager.resumeTorrent(item.id)
                                    TorrentDownloadService.start(context)
                                } else {
                                    torrentManager.pauseTorrent(item.id)
                                }
                            },
                            onInspectFiles = {
                                inspectingTorrent = item
                            },
                            onRecheck = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                torrentManager.recheckTorrent(item.id)
                            },
                            onDelete = {
                                torrentToDelete = item
                                deleteWithFiles = false
                            },
                            onCopyMagnet = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val uri = "magnet:?xt=urn:btih:${item.id}&dn=${Uri.encode(item.name)}"
                                clip?.setPrimaryClip(android.content.ClipData.newPlainText("Magnet URI", uri))
                                Toast.makeText(context, "Magnet URI copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onViewLogs = {
                                viewingLogsTorrent = item
                            }
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    prefilledMagnet = ""
                    prefilledName = ""
                    prefilledTorrentBytes = null
                    prefilledTorrentFileName = ""
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 90.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Torrent", modifier = Modifier.size(24.dp))
                    Text(
                        text = "Add Torrent",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddTorrentDialog(
            initialMagnet = prefilledMagnet,
            initialName = prefilledName,
            initialTorrentBytes = prefilledTorrentBytes,
            initialTorrentFileName = prefilledTorrentFileName,
            onDismiss = {
                showAddDialog = false
                prefilledMagnet = ""
                prefilledName = ""
                prefilledTorrentBytes = null
                prefilledTorrentFileName = ""
                app.clearPendingTorrentIntent()
            },
            onAddMagnet = { magnetUri, customName, saveDir ->
                scope.launch {
                    try {
                        val source = TorrentSource.Magnet(magnetUri, customName)
                        torrentManager.addTorrent(source, saveDir)
                        TorrentDownloadService.start(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Torrent added successfully", Toast.LENGTH_SHORT).show()
                            showAddDialog = false
                            prefilledMagnet = ""
                            prefilledName = ""
                            prefilledTorrentBytes = null
                            prefilledTorrentFileName = ""
                            app.clearPendingTorrentIntent()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error adding torrent: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onAddFile = { fileBytes, fileName, saveDir ->
                val validation = TorrentFileValidator.validate(fileBytes)
                if (validation is TorrentValidationResult.Valid) {
                    preDownloadState = PreDownloadState.create(
                        torrentSource = TorrentSource.FileContent(fileBytes, fileName.ifBlank { validation.name }),
                        name = validation.name,
                        files = validation.files,
                        targetDirectory = saveDir
                    )
                    showAddDialog = false
                } else if (validation is TorrentValidationResult.Invalid) {
                    val errorMsg = if (validation.isHtmlPayload) {
                        "Cannot load .torrent: The file appears to be a web page or error response (HTML/XML/JSON), not a valid .torrent file."
                    } else {
                        "Cannot load .torrent: ${validation.detailedMessage}"
                    }
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (preDownloadState != null) {
        val state = preDownloadState!!
        PreDownloadSelectionDialog(
            state = state,
            onDismiss = {
                preDownloadState = null
                prefilledTorrentBytes = null
                prefilledTorrentFileName = ""
                app.clearPendingTorrentIntent()
            },
            onToggleFile = { fileIndex ->
                preDownloadState = preDownloadState?.toggleFile(fileIndex)
            },
            onSelectAll = {
                preDownloadState = preDownloadState?.selectAll()
            },
            onDeselectAll = {
                preDownloadState = preDownloadState?.deselectAll()
            },
            onChangeSaveDir = { newDir ->
                preDownloadState = preDownloadState?.withTargetDirectory(newDir)
            },
            onConfirmDownload = { confirmedState ->
                scope.launch {
                    try {
                        val priorities = confirmedState.toPriorities()
                        torrentManager.addTorrent(
                            confirmedState.torrentSource,
                            confirmedState.targetDirectory,
                            priorities
                        )
                        TorrentDownloadService.start(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Download started for ${confirmedState.selectedCount} files",
                                Toast.LENGTH_SHORT
                            ).show()
                            preDownloadState = null
                            showAddDialog = false
                            prefilledMagnet = ""
                            prefilledName = ""
                            prefilledTorrentBytes = null
                            prefilledTorrentFileName = ""
                            app.clearPendingTorrentIntent()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Error starting download: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }

    if (torrentToDelete != null) {
        val item = torrentToDelete!!
        AlertDialog(
            onDismissRequest = { torrentToDelete = null },
            title = {
                Text(
                    text = "Delete Torrent?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Are you sure you want to remove \"${item.name}\" from your downloads list?",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteWithFiles = !deleteWithFiles },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteWithFiles,
                            onCheckedChange = { deleteWithFiles = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Also delete downloaded files from storage",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        torrentManager.removeTorrent(item.id, deleteWithFiles)
                        torrentToDelete = null
                        Toast.makeText(context, "Torrent deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { torrentToDelete = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (inspectingTorrent != null) {
        val currentItem = torrents.firstOrNull { it.id == inspectingTorrent!!.id } ?: inspectingTorrent!!
        TorrentFilesDialog(
            item = currentItem,
            onDismiss = { inspectingTorrent = null },
            onSetPriority = { fileIndex, priority ->
                torrentManager.setFilePriority(currentItem.id, fileIndex, priority)
            },
            onSetAllPriorities = { priorities ->
                torrentManager.setFilePriorities(currentItem.id, priorities)
            }
        )
    }

    if (viewingLogsTorrent != null) {
        val currentItem = torrents.firstOrNull { it.id == viewingLogsTorrent!!.id } ?: viewingLogsTorrent!!
        var currentLogs by remember(currentItem.id) { mutableStateOf(torrentManager.getTorrentLogs(currentItem.id)) }

        LaunchedEffect(currentItem.id) {
            while (isActive) {
                currentLogs = torrentManager.getTorrentLogs(currentItem.id)
                delay(1000L)
            }
        }

        TorrentLogsDialog(
            item = currentItem,
            logs = currentLogs,
            onDismiss = { viewingLogsTorrent = null },
            onCopyLogs = { fullLogs ->
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("Torrent Logs", fullLogs))
                Toast.makeText(context, "Diagnostic logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showProxySheet) {
        TorrentProxySheet(
            onDismiss = { showProxySheet = false }
        )
    }
}

@Composable
private fun TorrentHeader(
    stats: TorrentSessionStats,
    proxyConfig: TorrentProxyConfig,
    onOpenProxySettings: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Torrent Downloader",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Fast & encrypted P2P downloads",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenProxySettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (proxyConfig.enabled) {
                            if (proxyConfig.isSnowflakePreset) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    )
            ) {
                if (proxyConfig.enabled && proxyConfig.isSnowflakePreset) {
                    Text("❄️", fontSize = 16.sp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "Proxy & Circumvention",
                        tint = if (proxyConfig.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = onPauseAll,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Icon(
                    Icons.Rounded.Pause,
                    contentDescription = "Pause All",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onResumeAll,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Resume All",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TorrentSessionStatsBanner(stats: TorrentSessionStats) {
    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Download",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stats.formattedDownloadSpeed,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Upload",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stats.formattedUploadSpeed,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(label = "Active", value = "${stats.activeTorrents}")
                StatPill(label = "Seeding", value = "${stats.seedingTorrents}")
                StatPill(label = "Paused", value = "${stats.pausedTorrents}")
                StatPill(label = "DHT Nodes", value = "${stats.dhtNodes}")
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$label:",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TorrentFilterBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedFilter: TorrentFilter,
    onFilterSelected: (TorrentFilter) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search active downloads or hash...", fontSize = 13.5.sp) },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TorrentFilter.values().forEach { filter ->
                val isSelected = filter == selectedFilter
                val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                val textCol = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                val border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = bg,
                    border = border,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onFilterSelected(filter) }
                ) {
                    Text(
                        text = filter.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.5.sp,
                        color = textCol,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TorrentItemCard(
    item: TorrentItem,
    onTogglePause: () -> Unit,
    onInspectFiles: () -> Unit,
    onRecheck: () -> Unit,
    onDelete: () -> Unit,
    onCopyMagnet: () -> Unit,
    onViewLogs: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ExpressiveCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TorrentStateBadge(state = item.state)
                        Text(
                            text = "${item.formattedDownloadedSize} of ${item.formattedTotalSize}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (item.state == TorrentState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (item.state == TorrentState.PAUSED) "Resume" else "Pause",
                            tint = if (item.state == TorrentState.PAUSED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "More actions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("View & Select Files") },
                                onClick = {
                                    menuExpanded = false
                                    onInspectFiles()
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Diagnostic Logs") },
                                onClick = {
                                    menuExpanded = false
                                    onViewLogs()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Force Recheck") },
                                onClick = {
                                    menuExpanded = false
                                    onRecheck()
                                },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Magnet Link") },
                                onClick = {
                                    menuExpanded = false
                                    onCopyMagnet()
                                },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Torrent", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }

            if (!item.error.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            if (item.state == TorrentState.DOWNLOADING) {
                ExpressiveWavyProgressIndicator(
                    progress = item.progress,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.progress.coerceIn(0f, 1f))
                            .background(
                                if (item.state == TorrentState.FINISHED || item.state == TorrentState.SEEDING) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (item.downloadSpeed > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            Text(text = item.formattedDownloadSpeed, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (item.uploadSpeed > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(13.dp))
                            Text(text = item.formattedUploadSpeed, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Text(
                        text = "Seeds: ${item.numSeeds}/${item.totalSeeds} • Peers: ${item.numPeers}/${item.totalPeers}",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = if (item.etaSeconds > 0) "ETA: ${item.formattedEta}" else item.formattedProgress,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TorrentStateBadge(state: TorrentState) {
    val (bgColor, textColor, label) = when (state) {
        TorrentState.DOWNLOADING -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "Downloading")
        TorrentState.SEEDING -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary, "Seeding")
        TorrentState.PAUSED -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "Paused")
        TorrentState.FINISHED -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, "Finished")
        TorrentState.CHECKING -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.primary, "Checking")
        TorrentState.METADATA -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "Fetching Info")
        TorrentState.ALLOCATING -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "Allocating")
        TorrentState.ERROR -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "Error")
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TorrentEmptyState(
    hasAnyTorrents: Boolean,
    onAddTorrentClick: () -> Unit
) {
    ExpressiveCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        shape = RoundedCornerShape(28.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = if (hasAnyTorrents) "No Matching Torrents" else "No Active Torrents",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = if (hasAnyTorrents) "No downloads match the current filter or search criteria."
                else "Add magnet links or .torrent files to download through restricted firewalls with pure TCP & forced RC4 encryption.",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 19.sp
            )

            if (!hasAnyTorrents) {
                Button(
                    onClick = onAddTorrentClick,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Magnet or .torrent", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AddTorrentDialog(
    initialMagnet: String = "",
    initialName: String = "",
    initialTorrentBytes: ByteArray? = null,
    initialTorrentFileName: String = "",
    onDismiss: () -> Unit,
    onAddMagnet: (uri: String, displayName: String?, saveDir: File) -> Unit,
    onAddFile: (bytes: ByteArray, fileName: String, saveDir: File) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var magnetInput by remember(initialMagnet) { mutableStateOf(initialMagnet) }
    var customNameInput by remember(initialName) { mutableStateOf(initialName) }
    var loadedFileBytes by remember(initialTorrentBytes) { mutableStateOf(initialTorrentBytes) }
    var loadedFileName by remember(initialTorrentFileName) { mutableStateOf(initialTorrentFileName) }

    val defaultSaveDir = remember {
        TorrentStorageHelper.getSaveDirectory(context)
    }
    var selectedSaveDir by remember { mutableStateOf(defaultSaveDir) }

    LaunchedEffect(Unit) {
        if (magnetInput.isBlank() && loadedFileBytes == null) {
            val clipText = clipboard.getText()?.text?.trim()
            if (!clipText.isNullOrBlank() && clipText.startsWith("magnet:?")) {
                magnetInput = clipText
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null && bytes.isNotEmpty()) {
                    val validation = TorrentFileValidator.validate(bytes)
                    if (validation is TorrentValidationResult.Invalid) {
                        Toast.makeText(context, "Invalid .torrent file: ${validation.detailedMessage}", Toast.LENGTH_LONG).show()
                    } else {
                        val fileName = TorrentIntentParser.resolveDisplayName(context.contentResolver, uri)
                        onAddFile(bytes, fileName, selectedSaveDir)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read .torrent file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val canConfirm = magnetInput.isNotBlank() || (loadedFileBytes != null && loadedFileBytes!!.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Torrent Download",
                fontWeight = FontWeight.Black,
                fontSize = 19.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (loadedFileBytes != null && loadedFileName.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = loadedFileName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${TorrentItem.formatFileSize(loadedFileBytes!!.size.toLong())} • Ready to download",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    loadedFileBytes = null
                                    loadedFileName = ""
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Remove file",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = magnetInput,
                    onValueChange = {
                        magnetInput = it
                        if (it.isNotBlank()) {
                            loadedFileBytes = null
                            loadedFileName = ""
                        }
                    },
                    label = { Text("Magnet Link (magnet:?...)") },
                    placeholder = { Text("Paste magnet link here") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipText = clipboard.getText()?.text?.trim()
                            if (!clipText.isNullOrBlank()) {
                                magnetInput = clipText
                                loadedFileBytes = null
                                loadedFileName = ""
                            }
                        }) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3
                )

                OutlinedTextField(
                    value = customNameInput,
                    onValueChange = { customNameInput = it },
                    label = { Text("Custom Name (Optional)") },
                    placeholder = { Text("e.g. Linux ISO") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            filePicker.launch(arrayOf("application/x-bittorrent", "application/x-torrent"))
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Open .torrent File", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                            Text("Pick from device storage", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "Saving to: Downloads/SourZap/",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (loadedFileBytes != null && loadedFileBytes!!.isNotEmpty()) {
                        onAddFile(loadedFileBytes!!, loadedFileName.ifBlank { "download.torrent" }, selectedSaveDir)
                    } else if (magnetInput.isNotBlank()) {
                        onAddMagnet(magnetInput.trim(), customNameInput.trim().ifEmpty { null }, selectedSaveDir)
                    } else {
                        Toast.makeText(context, "Please enter a magnet link or pick a .torrent file", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = canConfirm,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Start Download", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(26.dp)
    )
}

@Composable
private fun TorrentFilesDialog(
    item: TorrentItem,
    onDismiss: () -> Unit,
    onSetPriority: (fileIndex: Int, priority: Priority) -> Unit,
    onSetAllPriorities: (priorities: List<Priority>) -> Unit
) {
    val selectedFilesCount = item.files.count { !it.isSkipped }
    val totalFilesCount = item.files.size
    val selectedSize = item.files.filter { !it.isSkipped }.sumOf { it.size }
    val totalSize = item.files.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Torrent Files ($totalFilesCount)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (totalFilesCount > 0) {
                    Text(
                        text = "Selected: $selectedFilesCount/$totalFilesCount files (${TorrentItem.formatFileSize(selectedSize)} / ${TorrentItem.formatFileSize(totalSize)})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            if (item.files.isEmpty()) {
                Text(
                    text = "Metadata is currently being fetched from swarm peers. Files will appear here once metadata completes.",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                onSetAllPriorities(item.files.map { Priority.NORMAL })
                            },
                            enabled = selectedFilesCount < totalFilesCount,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Select All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        TextButton(
                            onClick = {
                                onSetAllPriorities(item.files.map { Priority.IGNORE })
                            },
                            enabled = selectedFilesCount > 0,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Deselect All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(item.files, key = { it.index }) { file ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newPriority = if (file.isSkipped) Priority.NORMAL else Priority.IGNORE
                                        onSetPriority(file.index, newPriority)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.fileName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${TorrentItem.formatFileSize(file.downloadedBytes)} / ${TorrentItem.formatFileSize(file.size)} • ${String.format(java.util.Locale.US, "%.0f%%", file.progress * 100f)}${if (file.isSkipped) " • Skipped" else ""}",
                                            fontSize = 11.sp,
                                            color = if (file.isSkipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Checkbox(
                                        checked = !file.isSkipped,
                                        onCheckedChange = { checked ->
                                            val priority = if (checked) Priority.NORMAL else Priority.IGNORE
                                            onSetPriority(file.index, priority)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun TorrentLogsDialog(
    item: TorrentItem,
    logs: List<String>,
    onDismiss: () -> Unit,
    onCopyLogs: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to latest log entry
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Diagnostics: ${item.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Real-time BitTorrent swarm alerts, tracker replies, and handshake events:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Listening for BitTorrent swarm events...\nTracker announces and peer handshakes will appear here.",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(logs) { log ->
                                val color = when {
                                    log.contains("ERROR", ignoreCase = true) || log.contains("FAILED", ignoreCase = true) -> MaterialTheme.colorScheme.error
                                    log.contains("FINISHED", ignoreCase = true) || log.contains("REPLY", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                                    log.contains("CONNECT", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    text = log,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val fullText = logs.joinToString("\n")
                    onCopyLogs(fullText)
                },
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy Logs")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}
