package com.sourzap.app.torrent.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sourzap.app.SourZapApp
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.service.TorrentDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * BroadcastReceiver for programmatic torrent operations and automated verification.
 * Supports adding torrents via magnet URI or .torrent file path, pausing, and resuming.
 */
class TorrentActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val app = (context.applicationContext as? SourZapApp) ?: SourZapApp.instance
        val torrentManager = app.torrentEngineManager

        Log.i(TAG, "TorrentActionReceiver received action: $action")

        when (action) {
            ACTION_ADD_TORRENT -> {
                val magnet = intent.getStringExtra("magnet")?.trim()
                    ?: intent.getStringExtra("url")?.trim()
                val filePath = intent.getStringExtra("file")?.trim()
                val name = intent.getStringExtra("name")?.trim()

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val saveDir = TorrentStorageHelper.getSaveDirectory(context)
                        if (!magnet.isNullOrBlank()) {
                            Log.i(TAG, "Adding torrent from magnet: $magnet")
                            val source = TorrentSource.Magnet(magnet, name ?: "Automated Test Torrent")
                            val infoHash = torrentManager.addTorrent(source, saveDir)
                            TorrentDownloadService.start(context)
                            Log.i(TAG, "Successfully added magnet torrent with infoHash: $infoHash")
                        } else if (!filePath.isNullOrBlank()) {
                            val file = File(filePath)
                            if (file.exists() && file.canRead()) {
                                Log.i(TAG, "Adding torrent from file: $filePath")
                                val bytes = file.readBytes()
                                val source = TorrentSource.FileContent(bytes, name ?: file.name)
                                val infoHash = torrentManager.addTorrent(source, saveDir)
                                TorrentDownloadService.start(context)
                                Log.i(TAG, "Successfully added file torrent with infoHash: $infoHash")
                            } else {
                                Log.e(TAG, "Torrent file not found or unreadable: $filePath")
                            }
                        } else {
                            Log.w(TAG, "No magnet or file provided in ADD_TORRENT broadcast")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error adding torrent in TorrentActionReceiver: ${e.message}", e)
                    }
                }
            }
            ACTION_PAUSE_ALL -> {
                Log.i(TAG, "Pausing all torrents via broadcast")
                torrentManager.pauseAll()
            }
            ACTION_RESUME_ALL -> {
                Log.i(TAG, "Resuming all torrents via broadcast")
                torrentManager.resumeAll()
                TorrentDownloadService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "TorrentActionReceiver"
        const val ACTION_ADD_TORRENT = "com.sourzap.app.torrent.ADD_TEST_TORRENT"
        const val ACTION_PAUSE_ALL = "com.sourzap.app.torrent.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.sourzap.app.torrent.RESUME_ALL"
    }
}
