package com.sourzap.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class StrategyRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_strategies", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _customStrategy: MutableStateFlow<BypassStrategy>
    val customStrategy: StateFlow<BypassStrategy>

    private val _currentStrategy: MutableStateFlow<BypassStrategy>
    val currentStrategy: StateFlow<BypassStrategy>

    init {
        val loadedCustom = loadCustomStrategy()
        _customStrategy = MutableStateFlow(loadedCustom)
        customStrategy = _customStrategy.asStateFlow()

        val selectedId = prefs.getString("selected_strategy_id", BypassStrategy.AUTO_PILOT.id) ?: BypassStrategy.AUTO_PILOT.id
        val initialStrategy = when (selectedId) {
            loadedCustom.id -> loadedCustom
            else -> BypassStrategy.DEFAULT_PRESETS.find { it.id == selectedId } ?: BypassStrategy.AUTO_PILOT
        }
        _currentStrategy = MutableStateFlow(initialStrategy)
        currentStrategy = _currentStrategy.asStateFlow()
    }

    fun selectStrategy(strategy: BypassStrategy) {
        synchronized(lock) {
            _currentStrategy.value = strategy
            prefs.edit().putString("selected_strategy_id", strategy.id).apply()
        }
    }

    fun updateCustomStrategy(strategy: BypassStrategy) {
        synchronized(lock) {
            val custom = strategy.copy(isCustom = true, id = "custom")
            _customStrategy.value = custom
            _currentStrategy.value = custom
            saveCustomStrategy(custom)
            prefs.edit().putString("selected_strategy_id", custom.id).apply()
        }
    }

    fun setDohProvider(provider: DohProvider) {
        synchronized(lock) {
            val updatedCurrent = _currentStrategy.value.copy(dohProvider = provider)
            val updatedCustom = _customStrategy.value.copy(dohProvider = provider)
            _currentStrategy.value = updatedCurrent
            _customStrategy.value = updatedCustom
            saveCustomStrategy(updatedCustom)
            if (updatedCurrent.isCustom) {
                prefs.edit().putString("selected_strategy_id", updatedCurrent.id).apply()
            }
        }
    }

    private fun saveCustomStrategy(strategy: BypassStrategy) {
        val json = JSONObject().apply {
            put("id", strategy.id)
            put("name", strategy.name)
            put("description", strategy.description)
            put("tlsSplitOffset", strategy.tlsSplitOffset)
            put("useMultisplit", strategy.useMultisplit)
            put("fakeSni", strategy.fakeSni)
            put("fakeTtl", strategy.fakeTtl)
            put("useDisorder", strategy.useDisorder)
            put("useOob", strategy.useOob)
            put("httpHostMod", strategy.httpHostMod)
            put("blockQuic", strategy.blockQuic)
            put("dohProvider", strategy.dohProvider.name)
            put("isCustom", true)
        }
        prefs.edit().putString("custom_strategy_json", json.toString()).apply()
    }

    private fun loadCustomStrategy(): BypassStrategy {
        val jsonStr = prefs.getString("custom_strategy_json", null) ?: return BypassStrategy.AUTO_PILOT.copy(
            id = "custom",
            name = "Custom Ruleset",
            isCustom = true
        )
        return try {
            val json = JSONObject(jsonStr)
            val dohName = json.optString("dohProvider", DohProvider.CLOUDFLARE.name)
            val doh = try { DohProvider.valueOf(dohName) } catch (_: Exception) { DohProvider.CLOUDFLARE }

            BypassStrategy(
                id = json.optString("id", "custom"),
                name = json.optString("name", "Custom Ruleset"),
                description = json.optString("description", "User-customized DPI ruleset"),
                tlsSplitOffset = json.optInt("tlsSplitOffset", 2),
                useMultisplit = json.optBoolean("useMultisplit", false),
                fakeSni = json.optString("fakeSni", ""),
                fakeTtl = json.optInt("fakeTtl", 3),
                useDisorder = json.optBoolean("useDisorder", false),
                useOob = json.optBoolean("useOob", false),
                httpHostMod = json.optBoolean("httpHostMod", true),
                blockQuic = json.optBoolean("blockQuic", true),
                dohProvider = doh,
                isCustom = true
            )
        } catch (_: Exception) {
            BypassStrategy.AUTO_PILOT.copy(id = "custom", name = "Custom Ruleset", isCustom = true)
        }
    }
}

class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sourzap_settings", Context.MODE_PRIVATE)
    private val packageLock = Any()
    private val historyLock = Any()

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

    fun setAutoConnectOnBoot(enabled: Boolean) {
        setAutoConnect(enabled)
    }

    fun toggleAppBypass(packageName: String) {
        synchronized(packageLock) {
            val current = HashSet(_disallowedPackages.value)
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            val immutableSet = current.toSet()
            prefs.edit().putStringSet("disallowed_packages", HashSet(immutableSet)).apply()
            _disallowedPackages.value = immutableSet
        }
    }

    fun isAppBypassed(packageName: String): Boolean {
        return _disallowedPackages.value.contains(packageName)
    }

    fun saveSpeedTestResult(result: SpeedTestResult) {
        val updated = synchronized(historyLock) {
            val list = (listOf(result) + _speedTestHistory.value).take(20)
            saveSpeedHistory(list)
            list
        }
        _speedTestHistory.value = updated
    }

    fun clearSpeedTestHistory() {
        synchronized(historyLock) {
            prefs.edit().remove("speed_test_history_json").apply()
            _speedTestHistory.value = emptyList()
        }
    }

    private fun loadDisallowedPackages(): Set<String> {
        val raw = prefs.getStringSet("disallowed_packages", emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    private fun loadSpeedHistory(): List<SpeedTestResult> {
        val jsonStr = prefs.getString("speed_test_history_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<SpeedTestResult>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SpeedTestResult(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        pingMs = obj.optDouble("pingMs", 0.0).toFloat(),
                        jitterMs = obj.optDouble("jitterMs", 0.0).toFloat(),
                        downloadMbps = obj.optDouble("downloadMbps", 0.0).toFloat(),
                        uploadMbps = obj.optDouble("uploadMbps", 0.0).toFloat(),
                        serverName = obj.optString("serverName", "Cloudflare Edge"),
                        serverLocation = obj.optString("serverLocation", "Anycast CDN"),
                        strategyName = obj.optString("strategyName", "Universal Smart Engine")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSpeedHistory(list: List<SpeedTestResult>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("pingMs", item.pingMs.toDouble())
                put("jitterMs", item.jitterMs.toDouble())
                put("downloadMbps", item.downloadMbps.toDouble())
                put("uploadMbps", item.uploadMbps.toDouble())
                put("serverName", item.serverName)
                put("serverLocation", item.serverLocation)
                put("strategyName", item.strategyName)
            }
            array.put(obj)
        }
        prefs.edit().putString("speed_test_history_json", array.toString()).apply()
    }
}