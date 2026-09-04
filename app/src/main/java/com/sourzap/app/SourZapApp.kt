package com.sourzap.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sourzap.app.data.repository.SettingsRepository
import com.sourzap.app.data.repository.StrategyRepository
import com.sourzap.app.speedtest.SpeedTestEngine
import com.sourzap.app.update.UpdateManager

import com.sourzap.app.torrent.model.PendingTorrentIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SourZapApp : Application() {

    private val _pendingTorrentIntent = MutableStateFlow<PendingTorrentIntent?>(null)
    val pendingTorrentIntent: StateFlow<PendingTorrentIntent?> = _pendingTorrentIntent.asStateFlow()

    fun setPendingTorrentIntent(intent: PendingTorrentIntent?) {
        _pendingTorrentIntent.value = intent
    }

    fun clearPendingTorrentIntent() {
        _pendingTorrentIntent.value = null
    }

    lateinit var strategyRepository: StrategyRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var speedTestEngine: SpeedTestEngine
        private set

    lateinit var updateManager: UpdateManager
        private set

    lateinit var torrentEngineManager: com.sourzap.app.torrent.core.TorrentEngineManager
        private set

    lateinit var torrentProxyRepository: com.sourzap.app.torrent.core.TorrentProxyRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        strategyRepository = StrategyRepository(this)
        settingsRepository = SettingsRepository(this)
        speedTestEngine = SpeedTestEngine(settingsRepository, strategyRepository)
        updateManager = UpdateManager(this)
        torrentProxyRepository = com.sourzap.app.torrent.core.TorrentProxyRepository(this)
        val initialProxyConfig = torrentProxyRepository.config.value
        val initialTorrentConfig = com.sourzap.app.torrent.core.TorrentSessionConfig(proxyConfig = initialProxyConfig)
        torrentEngineManager = com.sourzap.app.torrent.core.TorrentEngineManager.create(initialTorrentConfig)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vpnChannel = NotificationChannel(
                getString(R.string.vpn_channel_id),
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }

            val torrentChannel = NotificationChannel(
                getString(R.string.torrent_channel_id),
                getString(R.string.torrent_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.torrent_channel_desc)
                setShowBadge(false)
            }

            val updateChannel = NotificationChannel(
                getString(R.string.update_channel_id),
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.update_channel_desc)
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(vpnChannel)
            manager?.createNotificationChannel(torrentChannel)
            manager?.createNotificationChannel(updateChannel)
        }
    }

    companion object {
        lateinit var instance: SourZapApp
            private set
    }
}