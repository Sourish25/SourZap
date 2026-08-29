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

    private val _currentStrategy = MutableStateFlow(loadCurrentStrategy())
    val currentStrategy: StateFlow<BypassStrategy> = _currentStrategy.asStateFlow()

    private val _customStrategy = MutableStateFlow(loadCustomStrategy())
    val customStrategy: StateFlow<BypassStrategy> = _customStrategy.asStateFlow()

    fun selectStrategy(strategy: BypassStrategy) {
        prefs.edit().putString("selected_strategy_id", strategy.id).apply()
        _currentStrategy.value = strategy
    }

    fun updateCustomStrategy(strategy: BypassStrategy) {
        val custom = strategy.copy(id = "custom_user", isCustom = true, tag = "CUSTOM")
        prefs.edit()
            .putString("custom_name", custom.name)
            .putInt("custom_split_offset", custom.tlsSplitOffset)
            .putBoolean("custom_multisplit", custom.useMultisplit)
            .putString("custom_fake_sni", custom.fakeSni)
            .putInt("custom_fake_ttl", custom.fakeTtl)
            .putBoolean("custom_disorder", custom.useDisorder)
            .putBoolean("custom_oob", custom.useOob)
            .putBoolean("custom_http_mod", custom.httpHostMod)
            .putBoolean("custom_block_quic", custom.blockQuic)
            .putString("custom_doh", custom.dohProvider.name)
            .apply()
        _customStrategy.value = custom
        if (_currentStrategy.value.isCustom) {
            _currentStrategy.value = custom
        }
    }

    private fun loadCurrentStrategy(): BypassStrategy {
        val id = prefs.getString("selected_strategy_id", BypassStrategy.YOUTUBE_TURBO.id)
        if (id == "custom_user") return loadCustomStrategy()
        return BypassStrategy.DEFAULT_PRESETS.firstOrNull { it.id == id } ?: BypassStrategy.YOUTUBE_TURBO
    }

    private fun loadCustomStrategy(): BypassStrategy {
        return BypassStrategy(
            id = "custom_user",
            name = prefs.getString("custom_name", "My Custom Preset") ?: "My Custom Preset",
            description = "Tailored DPI evasion parameters configured by you",
            tag = "CUSTOM",
            iconEmoji = "⚙️",
            tlsSplitOffset = prefs.getInt("custom_split_offset", -1),
            useMultisplit = prefs.getBoolean("custom_multisplit", true),
            fakeSni = prefs.getString("custom_fake_sni", "www.google.com") ?: "www.google.com",
            fakeTtl = prefs.getInt("custom_fake_ttl", 3),
            useDisorder = prefs.getBoolean("custom_disorder", true),
            useOob = prefs.getBoolean("custom_oob", false),
            httpHostMod = prefs.getBoolean("custom_http_mod", true),
            blockQuic = prefs.getBoolean("custom_block_quic", true),
            dohProvider = try {
                DohProvider.valueOf(prefs.getString("custom_doh", DohProvider.CLOUDFLARE.name) ?: DohProvider.CLOUDFLARE.name)
            } catch (_: Exception) { DohProvider.CLOUDFLARE },
            isCustom = true
        )
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

    fun saveSpeedTestResult(result: SpeedTestResult) {
        _speedTestHistory.update { (listOf(result) + it).take(20) }
        saveSpeedHistory(_speedTestHistory.value)
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