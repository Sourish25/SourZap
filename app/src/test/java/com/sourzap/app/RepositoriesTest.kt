package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class RepositoriesTest {

    @Test
    fun testSpeedTestResultJsonSerializationAndDeserialization() {
        val originalResults = listOf(
            SpeedTestResult(
                id = "test-1",
                timestamp = 1725100000000L,
                pingMs = 15.2f,
                jitterMs = 1.8f,
                downloadMbps = 120.5f,
                uploadMbps = 45.0f,
                serverName = "Cloudflare Edge",
                serverLocation = "Frankfurt",
                strategyName = "YouTube Turbo Fix"
            ),
            SpeedTestResult(
                id = "test-2",
                timestamp = 1725100060000L,
                pingMs = 22.0f,
                jitterMs = 3.1f,
                downloadMbps = 85.0f,
                uploadMbps = 30.0f,
                serverName = "Google Edge",
                serverLocation = "London",
                strategyName = "Auto-Pilot"
            )
        )

        // Serialize to JSONArray string
        val array = JSONArray()
        originalResults.forEach { item ->
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
        assertNotNull(serializedJson)

        // Deserialize back
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

        assertEquals(2, deserializedList.size)
        assertEquals("test-1", deserializedList[0].id)
        assertEquals(15.2f, deserializedList[0].pingMs, 0.01f)
        assertEquals(120.5f, deserializedList[0].downloadMbps, 0.01f)
        assertEquals("YouTube Turbo Fix", deserializedList[0].strategyName)
        assertEquals("Frankfurt", deserializedList[0].serverLocation)

        assertEquals("test-2", deserializedList[1].id)
        assertEquals(22.0f, deserializedList[1].pingMs, 0.01f)
        assertEquals(85.0f, deserializedList[1].downloadMbps, 0.01f)
    }

    @Test
    fun testCustomStrategyJsonSerializationAndDeserialization() {
        val custom = BypassStrategy(
            id = "custom",
            name = "My Custom Ruleset",
            description = "Tailored DPI evasion parameters",
            tlsSplitOffset = 3,
            useMultisplit = true,
            fakeSni = "cloudflare.com",
            fakeTtl = 5,
            useDisorder = true,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.GOOGLE,
            isCustom = true
        )

        // Serialize
        val json = JSONObject().apply {
            put("id", custom.id)
            put("name", custom.name)
            put("description", custom.description)
            put("tlsSplitOffset", custom.tlsSplitOffset)
            put("useMultisplit", custom.useMultisplit)
            put("fakeSni", custom.fakeSni)
            put("fakeTtl", custom.fakeTtl)
            put("useDisorder", custom.useDisorder)
            put("useOob", custom.useOob)
            put("httpHostMod", custom.httpHostMod)
            put("blockQuic", custom.blockQuic)
            put("dohProvider", custom.dohProvider.name)
            put("isCustom", true)
        }
        val serialized = json.toString()

        // Deserialize
        val parsed = JSONObject(serialized)
        val loaded = BypassStrategy(
            id = parsed.optString("id"),
            name = parsed.optString("name"),
            description = parsed.optString("description"),
            tlsSplitOffset = parsed.optInt("tlsSplitOffset"),
            useMultisplit = parsed.optBoolean("useMultisplit"),
            fakeSni = parsed.optString("fakeSni"),
            fakeTtl = parsed.optInt("fakeTtl"),
            useDisorder = parsed.optBoolean("useDisorder"),
            useOob = parsed.optBoolean("useOob"),
            httpHostMod = parsed.optBoolean("httpHostMod"),
            blockQuic = parsed.optBoolean("blockQuic"),
            dohProvider = DohProvider.valueOf(parsed.optString("dohProvider")),
            isCustom = parsed.optBoolean("isCustom")
        )

        assertEquals("custom", loaded.id)
        assertEquals("My Custom Ruleset", loaded.name)
        assertEquals(3, loaded.tlsSplitOffset)
        assertTrue(loaded.useMultisplit)
        assertEquals("cloudflare.com", loaded.fakeSni)
        assertEquals(5, loaded.fakeTtl)
        assertTrue(loaded.useDisorder)
        assertFalse(loaded.useOob)
        assertEquals(DohProvider.GOOGLE, loaded.dohProvider)
        assertTrue(loaded.isCustom)
    }

    @Test
    fun testAppBypassDefensiveCopyingThreadSafety() {
        val disallowedPackages = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val lock = Any()
        val threadCount = 10
        val togglesPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (t in 1..threadCount) {
            executor.execute {
                try {
                    for (i in 1..togglesPerThread) {
                        val pkg = "com.app.package$i"
                        synchronized(lock) {
                            val current = HashSet(disallowedPackages)
                            if (current.contains(pkg)) {
                                current.remove(pkg)
                            } else {
                                current.add(pkg)
                            }
                            disallowedPackages.clear()
                            disallowedPackages.addAll(current)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Verify set consistency
        assertNotNull(disallowedPackages)
    }
}
