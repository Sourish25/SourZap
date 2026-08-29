package com.sourzap.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sourzap.app.data.repository.SettingsRepository
import com.sourzap.app.data.repository.StrategyRepository
import com.sourzap.app.speedtest.SpeedTestEngine

class SourZapApp : Application() {

    lateinit var strategyRepository: StrategyRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var speedTestEngine: SpeedTestEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        strategyRepository = StrategyRepository(this)
        settingsRepository = SettingsRepository(this)
        speedTestEngine = SpeedTestEngine(settingsRepository, strategyRepository)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.vpn_channel_id),
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: SourZapApp
            private set
    }
}