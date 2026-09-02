package com.sourzap.app.torrent.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForegroundServiceNotification(TorrentSessionStats())
        val app = application as? SourZapApp ?: return
        val manager = app.torrentEngineManager
        if (!manager.isSessionRunning()) {
            manager.startSession(this)
        }
        observeSessionStats()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channelId = getString(R.string.torrent_channel_id)
                val channelName = getString(R.string.torrent_channel_name)
                val channelDesc = getString(R.string.torrent_channel_desc)
                val channel = android.app.NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = channelDesc
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? SourZapApp
        val manager = app?.torrentEngineManager

        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                manager?.pauseAll()
                releaseLocks()
            }
            ACTION_RESUME_ALL -> {
                manager?.resumeAll()
            }
            ACTION_STOP_SERVICE -> {
                releaseLocks()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {}
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForegroundServiceNotification(TorrentSessionStats())
        return START_STICKY
    }

    private var lastNotificationTime = 0L

    @Synchronized
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SourZap:TorrentDownloadWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24-hour safety timeout
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "SourZap:TorrentDownloadWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    private fun observeSessionStats() {
        val app = application as? SourZapApp ?: return
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            app.torrentEngineManager.observeStats().collectLatest { stats ->
                val isActivelyTransferring = stats.activeTorrents > 0 || stats.seedingTorrents > 0
                if (isActivelyTransferring) {
                    acquireLocks()
                } else {
                    releaseLocks()
                }

                val now = System.currentTimeMillis()
                if (now - lastNotificationTime >= 1000L) {
                    lastNotificationTime = now
                    updateNotification(stats)
                }
            }
        }
    }

    private fun startForegroundServiceNotification(stats: TorrentSessionStats) {
        try {
            ensureNotificationChannel()
            val notification = buildNotification(stats)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {}
    }

    private fun updateNotification(stats: TorrentSessionStats) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(stats))
    }

    fun buildNotification(stats: TorrentSessionStats): Notification {
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

        val stopIntent = Intent(this, TorrentDownloadService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent,
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

        val progressPercent = (stats.aggregateProgress * 100).toInt().coerceIn(0, 100)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progressPercent, false)
            .addAction(0, "Pause All", pausePendingIntent)
            .addAction(0, "Resume All", resumePendingIntent)
            .addAction(0, "Dismiss", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        statsJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TorrentDownloadService"
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.sourzap.app.torrent.START"
        const val ACTION_PAUSE_ALL = "com.sourzap.app.torrent.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.sourzap.app.torrent.RESUME_ALL"
        const val ACTION_STOP_SERVICE = "com.sourzap.app.torrent.STOP"

        fun start(context: Context) {
            try {
                val intent = Intent(context, TorrentDownloadService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w("TorrentDownloadService", "Unable to start foreground service: ${e.message}")
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
