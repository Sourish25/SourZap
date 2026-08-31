package com.sourzap.app.torrent.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sourzap.app.MainActivity
import com.sourzap.app.R
import com.sourzap.app.SourZapApp
import com.sourzap.app.torrent.model.TorrentSessionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service that maintains active BitTorrent swarm connections in the background
 * and displays real-time download progress and speed metrics in the notification shade.
 */
class TorrentDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statsJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? SourZapApp ?: return
        val manager = app.torrentEngineManager
        if (!manager.isSessionRunning()) {
            manager.startSession(this)
        }
        observeSessionStats()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? SourZapApp
        val manager = app?.torrentEngineManager

        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                manager?.pauseAll()
            }
            ACTION_RESUME_ALL -> {
                manager?.resumeAll()
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundServiceNotification(TorrentSessionStats())
        return START_STICKY
    }

    private fun observeSessionStats() {
        val app = application as? SourZapApp ?: return
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            app.torrentEngineManager.observeStats().collectLatest { stats ->
                updateNotification(stats)
            }
        }
    }

    private fun startForegroundServiceNotification(stats: TorrentSessionStats) {
        val notification = buildNotification(stats)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(stats: TorrentSessionStats) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(stats))
    }

    private fun buildNotification(stats: TorrentSessionStats): Notification {
        val channelId = getString(R.string.torrent_channel_id)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, TorrentDownloadService::class.java).apply {
            action = ACTION_PAUSE_ALL
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, TorrentDownloadService::class.java).apply {
            action = ACTION_RESUME_ALL
        }
        val resumePendingIntent = PendingIntent.getService(
            this, 2, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (stats.activeTorrents > 0) {
            "SourZap Downloader: ${stats.activeTorrents} Active (${stats.formattedDownloadSpeed})"
        } else {
            "SourZap Downloader"
        }

        val content = if (stats.activeTorrents > 0 || stats.seedingTorrents > 0) {
            "↓ ${stats.formattedDownloadSpeed} • ↑ ${stats.formattedUploadSpeed} • Peers connected"
        } else {
            "All transfers paused • Tap to open"
        }

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Pause All", pausePendingIntent)
            .addAction(0, "Resume All", resumePendingIntent)
            .build()
    }

    override fun onDestroy() {
        statsJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.sourzap.app.torrent.START"
        const val ACTION_PAUSE_ALL = "com.sourzap.app.torrent.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.sourzap.app.torrent.RESUME_ALL"
        const val ACTION_STOP_SERVICE = "com.sourzap.app.torrent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TorrentDownloadService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TorrentDownloadService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
