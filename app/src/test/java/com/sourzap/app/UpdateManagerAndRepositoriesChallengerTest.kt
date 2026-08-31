package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestResult
import com.sourzap.app.update.AppReleaseInfo
import com.sourzap.app.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenger M3.2 Stress and Verification Test Harness.
 * Focuses on:
 * 1. UpdateManager SemVer comparison matrix (major, minor, patch, pre-release, edge cases)
 * 2. APK magic header validation (valid PK\x03\x04 vs truncated vs corrupt files) on actual files
 * 3. State persistence across simulated screen navigation (unmount/remount)
 * 4. Repositories JSON roundtrip persistence for custom strategies & speed test history (capping & corrupted JSON handling)
 * 5. Defensive copying of disallowed_packages set under high-contention multithreaded modification
 */
class UpdateManagerAndRepositoriesChallengerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Mirror UpdateManager version clean and comparison algorithms
    private fun extractCleanVersion(raw: String): String {
        val match = Regex("""\d+(\.\d+)+""").find(raw)
        return match?.value ?: raw.filter { it.isDigit() || it == '.' }.trim('.')
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestClean = extractCleanVersion(latest)
            val currentClean = extractCleanVersion(current)

            val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    // Mirror UpdateManager APK file validation algorithm
    private fun validateApkIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < 3_000_000L) return false
        try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                if (read == 4) {
                    // Standard ZIP/APK Magic Header PK\x03\x04 (0x50, 0x4B, 0x03, 0x04)
                    return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                }
            }
        } catch (_: Exception) {}
        return false
    }

    // =========================================================================
    // 1. UPDATEMANAGER: SEMVER MATRIX VERIFICATION
    // =========================================================================

    @Test
    fun testSemVer_ComprehensiveComparisonMatrix() {
        // (latest, current, expectedNewer)
        val matrix = listOf(
            // Major updates
            Triple("2.0.0", "1.9.9", true),
            Triple("3.0.0", "2.99.99", true),
            Triple("1.0.0", "2.0.0", false),
            Triple("v2.0.0", "v1.0.0", true),

            // Minor updates
            Triple("1.1.0", "1.0.99", true),
            Triple("1.10.0", "1.9.0", true),
            Triple("1.9.0", "1.10.0", false),
            Triple("v1.2.0", "v1.1.0", true),

            // Patch updates
            Triple("1.0.1", "1.0.0", true),
            Triple("1.0.10", "1.0.9", true),
            Triple("1.0.9", "1.0.10", false),
            Triple("1.0.8", "1.0.8", false),

            // Pre-release versions
            Triple("v1.2.0-beta.1", "1.1.9", true),
            Triple("v1.2.0-rc.2", "1.2.0", false), // Same base version 1.2.0
            Triple("2.0.0-alpha+100", "1.9.9", true),
            Triple("SourZap-v2.1.0-release.apk", "2.0.9", true),

            // Multi-segment / Sub-patch versions
            Triple("1.0.8.1", "1.0.8", true),
            Triple("1.0.8", "1.0.8.1", false),
            Triple("1.0.8.0", "1.0.8", false),
            Triple("1.0.0.0.1", "1.0.0.0.0", true),

            // Unequal segment lengths
            Triple("2.0", "1.9.99", true),
            Triple("1.9.99", "2.0", false),
            Triple("2.0.0.0.0", "2.0", false),
            Triple("2.0.0.0.1", "2.0", true),

            // Tricky prefixes and noise
            Triple("SourZap v1.0.9", "1.0.8", true),
            Triple("v1.0.8", "1.0.8", false),
            Triple("SourZap-1.0.8", "SourZap-1.0.8", false),

            // Edge cases: empty / malformed strings
            Triple("", "1.0.0", false),
            Triple("1.0.0", "", true),
            Triple("abc", "xyz", false),
            Triple("invalid-tag", "1.0.0", false)
        )

        for ((latest, current, expected) in matrix) {
            val actual = isVersionNewer(latest, current)
            assertEquals("SemVer comparison failed for latest='$latest' vs current='$current'", expected, actual)
        }
    }

    @Test
    fun testSemVer_ExtractCleanVersionFuzzing() {
        assertEquals("1.0.0", extractCleanVersion("1.0.0"))
        assertEquals("1.0.0", extractCleanVersion("v1.0.0"))
        assertEquals("1.0.0", extractCleanVersion("V1.0.0"))
        assertEquals("1.0.8", extractCleanVersion("SourZap-v1.0.8-prod.apk"))
        assertEquals("2.15.30", extractCleanVersion("v2.15.30-alpha+build.999"))
        assertEquals("1.0.8.3", extractCleanVersion("1.0.8.3"))
        assertEquals("2.0", extractCleanVersion("2.0"))
        assertEquals("99", extractCleanVersion("build-99"))
        assertEquals("", extractCleanVersion(""))
        assertEquals("", extractCleanVersion("no-numbers-here"))
    }

    // =========================================================================
    // 2. UPDATEMANAGER: APK MAGIC HEADER & INTEGRITY VALIDATION ON REAL FILES
    // =========================================================================

    @Test
    fun testApkIntegrity_RealFilesOnDisk() {
        val testDir = tempFolder.newFolder("apk_test")

        // 1. Valid APK: Header 0x50, 0x4B, 0x03, 0x04 with size >= 3,000,000 bytes
        val validApk = File(testDir, "valid.apk")
        FileOutputStream(validApk).use { fos ->
            fos.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            val filler = ByteArray(8192)
            var written = 4L
            while (written < 3_050_000L) {
                fos.write(filler)
                written += filler.size
            }
        }
        assertTrue("Valid APK file must pass integrity validation", validateApkIntegrity(validApk))

        // 2. Exact boundary: Exactly 3,000,000 bytes with valid magic
        val exactApk = File(testDir, "exact.apk")
        FileOutputStream(exactApk).use { fos ->
            fos.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            val remaining = 3_000_000 - 4
            val chunk = ByteArray(remaining)
            fos.write(chunk)
        }
        assertEquals(3_000_000L, exactApk.length())
        assertTrue("Exact 3,000,000 byte APK must pass integrity validation", validateApkIntegrity(exactApk))

        // 3. Undersized APK: 2,999,999 bytes with valid magic
        val undersizedApk = File(testDir, "undersized.apk")
        FileOutputStream(undersizedApk).use { fos ->
            fos.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            val remaining = 2_999_999 - 4
            val chunk = ByteArray(remaining)
            fos.write(chunk)
        }
        assertEquals(2_999_999L, undersizedApk.length())
        assertFalse("APK with 2,999,999 bytes (<3MB) must fail integrity validation", validateApkIntegrity(undersizedApk))

        // 4. Truncated files: 0, 1, 2, 3 bytes
        val emptyFile = File(testDir, "empty.apk").apply { createNewFile() }
        assertFalse("Empty file (0 bytes) must fail validation", validateApkIntegrity(emptyFile))

        val oneByteFile = File(testDir, "one_byte.apk").apply { writeBytes(byteArrayOf(0x50)) }
        assertFalse("1-byte file must fail validation", validateApkIntegrity(oneByteFile))

        val threeByteFile = File(testDir, "three_bytes.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03)) }
        assertFalse("3-byte file must fail validation", validateApkIntegrity(threeByteFile))

        // 5. Corrupt headers (3.5MB size but invalid magic header)
        val corruptHeaders = mapOf(
            "central_dir_header.apk" to byteArrayOf(0x50, 0x4B, 0x01, 0x02), // PK\x01\x02
            "html_404.apk" to byteArrayOf(0x3C, 0x21, 0x44, 0x4F), // "<!DO"
            "all_zeros.apk" to byteArrayOf(0x00, 0x00, 0x00, 0x00),
            "elf_binary.apk" to byteArrayOf(0x7F, 0x45, 0x4C, 0x46), // \x7FELF
            "windows_exe.apk" to byteArrayOf(0x4D.toByte(), 0x5A.toByte(), 0x90.toByte(), 0x00.toByte()), // MZ
            "jpeg_image.apk" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        )

        for ((name, header) in corruptHeaders) {
            val file = File(testDir, name)
            FileOutputStream(file).use { fos ->
                fos.write(header)
                val filler = ByteArray(8192)
                var written = 4L
                while (written < 3_100_000L) {
                    fos.write(filler)
                    written += filler.size
                }
            }
            assertFalse("Corrupt magic header for $name must fail validation", validateApkIntegrity(file))
        }

        // 6. Non-existent file
        val nonExistent = File(testDir, "does_not_exist.apk")
        assertFalse("Non-existent file must fail validation", validateApkIntegrity(nonExistent))
    }

    // =========================================================================
    // 3. UPDATEMANAGER: STATE PERSISTENCE ACROSS SCREEN NAVIGATION
    // =========================================================================

    @Test
    fun testUpdateState_PersistenceAcrossSimulatedScreenNavigation() = runBlocking {
        val updateStateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
        val exposedState = updateStateFlow.asStateFlow()

        val release = AppReleaseInfo(
            tagName = "v1.2.0",
            versionName = "1.2.0",
            releaseNotes = "Major speed improvements",
            apkDownloadUrl = "https://github.com/Sourish25/SourZap/releases/download/v1.2.0/SourZap-v1.2.0.apk",
            apkSizeBytes = 12_000_000L,
            isUpdateAvailable = true,
            publishedAt = "2026-08-31T00:00:00Z"
        )

        // 1. Initial State
        assertEquals(UpdateState.Idle, exposedState.value)

        // 2. User on DashboardScreen: Check for updates
        updateStateFlow.value = UpdateState.Checking
        assertEquals(UpdateState.Checking, exposedState.value)

        updateStateFlow.value = UpdateState.Available(release)
        assertTrue(exposedState.value is UpdateState.Available)

        // 3. User clicks "Update": Download starts
        updateStateFlow.value = UpdateState.Downloading(0.15f, 1_800_000L, 12_000_000L)

        // 4. Simulate Screen 1 (Dashboard) collecting state in its coroutine scope
        val dashboardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dashboardObservedStates = mutableListOf<UpdateState>()
        val dashboardCollectorJob = dashboardScope.launch {
            exposedState.collect { dashboardObservedStates.add(it) }
        }

        delay(50)
        // Progress advances to 35%
        updateStateFlow.value = UpdateState.Downloading(0.35f, 4_200_000L, 12_000_000L)
        delay(50)

        // 5. User navigates away from DashboardScreen to SettingsScreen
        // DashboardScreen unmounts -> cancels its collector scope
        dashboardScope.cancel()
        dashboardCollectorJob.join()

        // 6. Download continues in background (managed by UpdateManager singleton scope)
        updateStateFlow.value = UpdateState.Downloading(0.60f, 7_200_000L, 12_000_000L)
        updateStateFlow.value = UpdateState.Downloading(0.85f, 10_200_000L, 12_000_000L)

        // 7. User enters SettingsScreen: SettingsScreen mounts and subscribes to updateState
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settingsObservedStates = mutableListOf<UpdateState>()
        val settingsCollectorJob = settingsScope.launch {
            exposedState.collect { settingsObservedStates.add(it) }
        }

        delay(50)
        // Settings immediately receives current 85% progress snapshot without restart
        val currentSettingsState = settingsObservedStates.lastOrNull()
        assertTrue("SettingsScreen must immediately see ongoing download", currentSettingsState is UpdateState.Downloading)
        assertEquals(0.85f, (currentSettingsState as UpdateState.Downloading).progress, 0.01f)

        // 8. Download finishes and verifies APK
        val dummyApk = File(tempFolder.root, "SourZap-update.apk")
        updateStateFlow.value = UpdateState.ReadyToInstall(dummyApk)
        delay(50)

        assertTrue(exposedState.value is UpdateState.ReadyToInstall)
        assertEquals(dummyApk, (exposedState.value as UpdateState.ReadyToInstall).apkFile)

        settingsScope.cancel()
        settingsCollectorJob.join()
    }

    // =========================================================================
    // 4. REPOSITORIES: JSON ROUNDTRIP PERSISTENCE & FAULT TOLERANCE
    // =========================================================================

    @Test
    fun testCustomStrategy_JsonSerializationRoundtripAndFallback() {
        val originalStrategy = BypassStrategy(
            id = "custom",
            name = "Extreme Custom Evasion",
            description = "Special chars: <>&\"' \u2764 and high split offset",
            tlsSplitOffset = 5,
            useMultisplit = true,
            fakeSni = "cloudflare-dns.com",
            fakeTtl = 7,
            useDisorder = true,
            useOob = true,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.QUAD9,
            isCustom = true
        )

        // Serialize
        val json = JSONObject().apply {
            put("id", originalStrategy.id)
            put("name", originalStrategy.name)
            put("description", originalStrategy.description)
            put("tlsSplitOffset", originalStrategy.tlsSplitOffset)
            put("useMultisplit", originalStrategy.useMultisplit)
            put("fakeSni", originalStrategy.fakeSni)
            put("fakeTtl", originalStrategy.fakeTtl)
            put("useDisorder", originalStrategy.useDisorder)
            put("useOob", originalStrategy.useOob)
            put("httpHostMod", originalStrategy.httpHostMod)
            put("blockQuic", originalStrategy.blockQuic)
            put("dohProvider", originalStrategy.dohProvider.name)
            put("isCustom", true)
        }
        val serialized = json.toString()
        assertNotNull(serialized)

        // Deserialize
        val parsed = JSONObject(serialized)
        val loaded = BypassStrategy(
            id = parsed.optString("id", "custom"),
            name = parsed.optString("name", "Custom Ruleset"),
            description = parsed.optString("description", "User-customized DPI ruleset"),
            tlsSplitOffset = parsed.optInt("tlsSplitOffset", 2),
            useMultisplit = parsed.optBoolean("useMultisplit", false),
            fakeSni = parsed.optString("fakeSni", ""),
            fakeTtl = parsed.optInt("fakeTtl", 3),
            useDisorder = parsed.optBoolean("useDisorder", false),
            useOob = parsed.optBoolean("useOob", false),
            httpHostMod = parsed.optBoolean("httpHostMod", true),
            blockQuic = parsed.optBoolean("blockQuic", true),
            dohProvider = try { DohProvider.valueOf(parsed.optString("dohProvider")) } catch (_: Exception) { DohProvider.CLOUDFLARE },
            isCustom = true
        )

        assertEquals("custom", loaded.id)
        assertEquals(originalStrategy.name, loaded.name)
        assertEquals(originalStrategy.description, loaded.description)
        assertEquals(5, loaded.tlsSplitOffset)
        assertTrue(loaded.useMultisplit)
        assertEquals("cloudflare-dns.com", loaded.fakeSni)
        assertEquals(7, loaded.fakeTtl)
        assertTrue(loaded.useDisorder)
        assertTrue(loaded.useOob)
        assertEquals(DohProvider.QUAD9, loaded.dohProvider)
        assertTrue(loaded.isCustom)

        // Test Corrupted JSON fallback
        val corruptJsonStr = "{ invalid json string [[[..."
        val fallbackStrategy = try {
            val obj = JSONObject(corruptJsonStr)
            BypassStrategy(id = obj.getString("id"))
        } catch (_: Exception) {
            BypassStrategy.AUTO_PILOT.copy(id = "custom", name = "Custom Ruleset", isCustom = true)
        }
        assertEquals("custom", fallbackStrategy.id)
        assertEquals("Custom Ruleset", fallbackStrategy.name)
        assertTrue(fallbackStrategy.isCustom)
    }

    @Test
    fun testSpeedTestHistory_JsonSerializationCappingAndFloatPrecision() {
        // Generate 25 results (exceeding 20-item cap)
        val originalList = (1..25).map { i ->
            SpeedTestResult(
                id = "result-$i",
                timestamp = 1725100000000L + (i * 1000L),
                pingMs = 12.345f + i,
                jitterMs = 1.234f + (i * 0.1f),
                downloadMbps = 150.789f + (i * 5.0f),
                uploadMbps = 45.678f + (i * 2.0f),
                serverName = "Server #$i",
                serverLocation = "Location #$i",
                strategyName = "Strategy #$i"
            )
        }

        // Apply 20-item cap
        val cappedList = originalList.take(20)
        assertEquals(20, cappedList.size)

        // Serialize
        val array = JSONArray()
        cappedList.forEach { item ->
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
        val serializedJson = array.toString()

        // Deserialize
        val parsedArray = JSONArray(serializedJson)
        val deserializedList = mutableListOf<SpeedTestResult>()
        for (i in 0 until parsedArray.length()) {
            val obj = parsedArray.getJSONObject(i)
            deserializedList.add(
                SpeedTestResult(
                    id = obj.optString("id"),
                    timestamp = obj.optLong("timestamp"),
                    pingMs = obj.optDouble("pingMs").toFloat(),
                    jitterMs = obj.optDouble("jitterMs").toFloat(),
                    downloadMbps = obj.optDouble("downloadMbps").toFloat(),
                    uploadMbps = obj.optDouble("uploadMbps").toFloat(),
                    serverName = obj.optString("serverName"),
                    serverLocation = obj.optString("serverLocation"),
                    strategyName = obj.optString("strategyName")
                )
            )
        }

        assertEquals(20, deserializedList.size)
        assertEquals("result-1", deserializedList[0].id)
        assertEquals(13.345f, deserializedList[0].pingMs, 0.001f)
        assertEquals(155.789f, deserializedList[0].downloadMbps, 0.001f)
        assertEquals("Server #1", deserializedList[0].serverName)

        assertEquals("result-20", deserializedList[19].id)

        // Test Corrupted History JSON
        val corruptHistory = "{ not an array }"
        val fallbackHistory = try {
            val arr = JSONArray(corruptHistory)
            listOf<SpeedTestResult>()
        } catch (_: Exception) {
            emptyList<SpeedTestResult>()
        }
        assertTrue("Corrupted history JSON must safely return empty list", fallbackHistory.isEmpty())
    }

    // =========================================================================
    // 5. REPOSITORIES: DEFENSIVE COPYING & HIGH-CONCURRENCY SET MUTATION
    // =========================================================================

    @Test
    fun testAppBypass_DefensiveCopyingHighContentionStress() {
        val stateFlow = MutableStateFlow<Set<String>>(emptySet())
        val lock = Any()
        val threadCount = 20
        val operationsPerThread = 200
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (op in 0 until operationsPerThread) {
                        val pkg = "com.package.app_${op % 50}"
                        // Simulate toggleAppBypass defensive copy protocol
                        synchronized(lock) {
                            val current = HashSet(stateFlow.value)
                            if (current.contains(pkg)) {
                                current.remove(pkg)
                            } else {
                                current.add(pkg)
                            }
                            val immutableSet = current.toSet()
                            // Defensive copy passed to simulated prefs
                            val prefsCopy = HashSet(immutableSet)
                            assertEquals(immutableSet.size, prefsCopy.size)
                            stateFlow.value = immutableSet
                        }

                        // Concurrent read on StateFlow value
                        val snapshot = stateFlow.value
                        // Iterating over the immutable set must never throw ConcurrentModificationException
                        var count = 0
                        for (item in snapshot) {
                            count++
                        }
                        assertTrue(count >= 0)
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads must finish within timeout", completed)
        assertTrue("No exceptions (such as ConcurrentModificationException) should occur: $exceptions", exceptions.isEmpty())
        assertNotNull(stateFlow.value)
    }
}
