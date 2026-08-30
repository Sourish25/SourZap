package com.sourzap.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class StrategyRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_strategies", Context.MODE_PRIVATE)

    private val _currentStrategy = MutableStateFlow(BypassStrategy.AUTO_PILOT)
    val currentStrategy: StateFlow<BypassStrategy> = _currentStrategy.asStateFlow()

    private val _customStrategy = MutableStateFlow(BypassStrategy.AUTO_PILOT)
    val customStrategy: StateFlow<BypassStrategy> = _customStrategy.asStateFlow()

    fun selectStrategy(strategy: BypassStrategy) {
        _currentStrategy.value = strategy
    }

    fun updateCustomStrategy(strategy: BypassStrategy) {
        _customStrategy.value = strategy
        _currentStrategy.value = strategy
    }
}

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_settings", Context.MODE_PRIVATE)

    private val _themePreset = MutableStateFlow(prefs.getString("theme_preset", "DYNAMIC") ?: "DYNAMIC")
    val themePreset: StateFlow<String> = _themePreset.asStateFlow()

    private val _darkModePref = MutableStateFlow(prefs.getString("dark_mode_pref", "SYSTEM") ?: "SYSTEM")
    val darkModePref: StateFlow<String> = _darkModePref.asStateFlow()

    private val _bypassLan = MutableStateFlow(prefs.getBoolean("bypass_lan", true))
    val bypassLan: StateFlow<Boolean> = _bypassLan.asStateFlow()

    private val _autoConnectOnBoot = MutableStateFlow(prefs.getBoolean("auto_connect", false))
    val autoConnectOnBoot: StateFlow<Boolean> = _autoConnectOnBoot.asStateFlow()

    private val _speedTestHistory = MutableStateFlow<List<SpeedTestResult>>(loadSpeedHistory())
    val speedTestHistory: StateFlow<List<SpeedTestResult>> = _speedTestHistory.asStateFlow()

    private val _disallowedPackages = MutableStateFlow<Set<String>>(loadDisallowedPackages())
    val disallowedPackages: StateFlow<Set<String>> = _disallowedPackages.asStateFlow()

    fun setThemePreset(preset: String) {
        prefs.edit().putString("theme_preset", preset).apply()
        _themePreset.value = preset
    }

    fun setDarkModePref(pref: String) {
        prefs.edit().putString("dark_mode_pref", pref).apply()
        _darkModePref.value = pref
    }

    fun setBypassLan(enabled: Boolean) {
        prefs.edit().putBoolean("bypass_lan", enabled).apply()
        _bypassLan.value = enabled
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect", enabled).apply()
        _autoConnectOnBoot.value = enabled
    }

    fun toggleAppBypass(packageName: String) {
        val current = _disallowedPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        prefs.edit().putStringSet("disallowed_packages", current).apply()
        _disallowedPackages.value = current
    }

    fun isAppBypassed(packageName: String): Boolean {
        return _disallowedPackages.value.contains(packageName)
    }

    fun saveSpeedTestResult(result: SpeedTestResult) {
        _speedTestHistory.update { (listOf(result) + it).take(20) }
        saveSpeedHistory(_speedTestHistory.value)
    }

    private fun loadDisallowedPackages(): Set<String> {
        return prefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()
    }

    private fun loadSpeedHistory(): List<SpeedTestResult> {
        return listOf(
            SpeedTestResult(
                pingMs = 18.5f,
                jitterMs = 2.1f,
                downloadMbps = 84.6f,
                uploadMbps = 42.1f,
                serverName = "Cloudflare Anycast",
                serverLocation = "Ultra-Fast Edge",
                strategyName = "YouTube Turbo Fix"
            )
        )
    }

    private fun saveSpeedHistory(list: List<SpeedTestResult>) {
        prefs.edit().putInt("speed_test_count", list.size).apply()
    }
}