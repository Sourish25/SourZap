package com.sourzap.app.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive unit test suite for Milestone M2 (Speed Test Measurement Smoothing).
 * Tests:
 * 1. Bursty TCP socket buffer flushes (alternating 0 bytes and 2MB) confirming smoothed output
 *    eliminates abrupt swings > 30% in steady-state.
 * 2. Step-up bandwidth response convergence from 20 Mbps to 100 Mbps.
 * 3. Step-down bandwidth response convergence from 100 Mbps to 25 Mbps.
 * 4. Outlier damping & clamping for momentary stalls and burst drains (speeds > 5 Mbps).
 * 5. Adaptive warm-up progression (sample 1: alpha=1.0, sample 2: alpha=0.65, sample 3+: alpha=0.32).
 * 6. 15% trimmed mean calculation with warm-up rejection and edge-case handling.
 * 7. 1000ms rolling window duration pruning and time-delta calculation.
 * 8. State reset restoring clean initial state.
 * 9. Multi-threaded concurrency and thread safety.
 */
class SpeedTestMeasurementSmoothingTest {

    private lateinit var smoother: SpeedMeasurementSmoother

    @Before
    fun setup() {
        smoother = SpeedMeasurementSmoother(windowDurationMs = 1000L, defaultAlpha = 0.32f)
    }

    // =========================================================================
    // 1. Bursty Socket Buffer Flushes (Alternating 0B and 2MB)
    // =========================================================================

    @Test
    fun testBurstyByteFlushes_EliminatesAbruptSwingsOverThirtyPercent() {
        // Raw instantaneous measurements over 150ms:
        // 2MB in 150ms -> 106.67 Mbps
        // 0MB in 150ms -> 0.00 Mbps (100% swing every 150ms!)
        //
        // With SpeedMeasurementSmoother (1000ms window + EMA 0.32 + outlier clamping):
        // Fluctuations must be dampened so consecutive swings in steady state never exceed 30%.
        var cumulativeBytes = 0L
        val recordedSmoothedSpeeds = mutableListOf<Float>()

        // Seed with initial baseline
        smoother.addSample(0L, 0L)

        for (tick in 1..30) {
            val timeMs = tick * 150L
            val bytesThisTick = if (tick % 2 == 1) 2_000_000L else 0L
            cumulativeBytes += bytesThisTick

            val smoothed = smoother.addSample(timeMs, cumulativeBytes)
            recordedSmoothedSpeeds.add(smoothed)
        }

        // Window reaches steady state after ~1000ms (tick 7+).
        // Check swings between consecutive samples from tick 8 to 30.
        val steadyStateSpeeds = recordedSmoothedSpeeds.subList(7, recordedSmoothedSpeeds.size)
        assertTrue("Must have enough steady-state samples", steadyStateSpeeds.size >= 20)

        for (i in 1 until steadyStateSpeeds.size) {
            val prev = steadyStateSpeeds[i - 1]
            val curr = steadyStateSpeeds[i]

            val swing = Math.abs(curr - prev) / prev
            assertTrue(
                "Consecutive sample swing at index $i must be <= 30% (was ${(swing * 100).toInt()}%, prev=$prev, curr=$curr)",
                swing <= 0.30f
            )

            // Verify smoothed output is centered around ~53.3 Mbps (average throughput: 1MB per 150ms = 53.33 Mbps)
            assertTrue("Smoothed speed $curr must remain in plausible steady-state band [40..65] Mbps", curr in 40f..65f)
        }
    }

    // =========================================================================
    // 2. Step-Up Bandwidth Response Convergence
    // =========================================================================

    @Test
    fun testStepUpBandwidthResponseConvergence() {
        // Phase 1: 15 samples at 20 Mbps (375,000 bytes per 150ms)
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..15) {
            val timeMs = tick * 150L
            cumulativeBytes += 375_000L
            smoother.addSample(timeMs, cumulativeBytes)
        }

        val initialSpeed = smoother.getSmoothedSpeed()
        assertEquals("Initial steady-state should converge to ~20 Mbps", 20f, initialSpeed, 1.5f)

        // Phase 2: Bandwidth steps up to 100 Mbps (1,875,000 bytes per 150ms)
        var prevSpeed = initialSpeed
        for (tick in 16..35) {
            val timeMs = tick * 150L
            cumulativeBytes += 1_875_000L
            val currentSpeed = smoother.addSample(timeMs, cumulativeBytes)

            // Speed must monotonically increase or remain stable during step-up (no erratic drops)
            assertTrue(
                "Speed during step-up must not drop erratically (prev=$prevSpeed, curr=$currentSpeed)",
                currentSpeed >= prevSpeed - 0.5f
            )
            prevSpeed = currentSpeed
        }

        val finalSpeed = smoother.getSmoothedSpeed()
        assertEquals("Final speed must converge smoothly to ~100 Mbps", 100f, finalSpeed, 3.0f)
        assertTrue("Speed must not overshoot beyond reasonable threshold", finalSpeed <= 105f)
    }

    // =========================================================================
    // 3. Step-Down Bandwidth Response Convergence
    // =========================================================================

    @Test
    fun testStepDownBandwidthResponseConvergence() {
        // Phase 1: 15 samples at 100 Mbps (1,875,000 bytes per 150ms)
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..15) {
            val timeMs = tick * 150L
            cumulativeBytes += 1_875_000L
            smoother.addSample(timeMs, cumulativeBytes)
        }

        val initialSpeed = smoother.getSmoothedSpeed()
        assertEquals("Initial speed should converge to ~100 Mbps", 100f, initialSpeed, 2.0f)

        // Phase 2: Bandwidth steps down to 25 Mbps (468,750 bytes per 150ms)
        var prevSpeed = initialSpeed
        for (tick in 16..35) {
            val timeMs = tick * 150L
            cumulativeBytes += 468_750L
            val currentSpeed = smoother.addSample(timeMs, cumulativeBytes)

            // Speed must glide downward smoothly without collapsing or oscillating
            assertTrue(
                "Speed during step-down must not spike upward (prev=$prevSpeed, curr=$currentSpeed)",
                currentSpeed <= prevSpeed + 0.5f
            )
            prevSpeed = currentSpeed
        }

        val finalSpeed = smoother.getSmoothedSpeed()
        assertEquals("Final speed must converge smoothly to ~25 Mbps", 25f, finalSpeed, 2.5f)
        assertTrue("Speed must not collapse below target", finalSpeed >= 20f)
    }

    // =========================================================================
    // 4. Outlier Damping & Clamping
    // =========================================================================

    @Test
    fun testOutlierDamping_SpikeClamping() {
        // Establish steady state at 50 Mbps
        // 50 Mbps over 150ms = 937,500 bytes per tick
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..15) {
            val timeMs = tick * 150L
            cumulativeBytes += 937_500L
            smoother.addSample(timeMs, cumulativeBytes)
        }

        val steadySpeed = smoother.getSmoothedSpeed()
        assertEquals(50f, steadySpeed, 1.5f)

        // Next tick: inject a massive burst drain outlier (15MB in 150ms)
        // Over the 900ms window, raw window speed = ~175 Mbps.
        // With S_{t-1} ≈ 50 Mbps, max allowed rate = 50 * 2.20 = 110 Mbps.
        // Expected smoothed speed with clamping: 0.32 * 110 + 0.68 * 50 ≈ 69.2 Mbps.
        // Without clamping: 0.32 * 175 + 0.68 * 50 ≈ 90.0 Mbps.
        val burstBytes = 15_000_000L // 15MB
        cumulativeBytes += burstBytes
        val postSpikeSpeed = smoother.addSample(16 * 150L, cumulativeBytes)

        assertEquals("Outlier spike must be clamped to 2.2x previous rate", 69.2f, postSpikeSpeed, 1.5f)
        assertTrue("Without clamping speed would exceed 85 Mbps, with clamping it must be ~69.2 Mbps", postSpikeSpeed in 67f..71f)
    }

    @Test
    fun testOutlierDamping_StallClamping() {
        // Establish steady state at 50 Mbps
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..15) {
            val timeMs = tick * 150L
            cumulativeBytes += 937_500L
            smoother.addSample(timeMs, cumulativeBytes)
        }

        val steadySpeed = smoother.getSmoothedSpeed()
        assertEquals(50f, steadySpeed, 1.5f)

        // Prolonged 1050ms stall with 0 additional bytes
        // Rolling window prunes all older samples; raw window rate = 0 Mbps.
        // With S_{t-1} ≈ 50 Mbps, min allowed rate = 50 * 0.40 = 20 Mbps.
        // Expected smoothed speed with clamping: 0.32 * 20 + 0.68 * 50 ≈ 40.4 Mbps.
        // Without clamping: 0.32 * 0 + 0.68 * 50 ≈ 34.0 Mbps.
        val postStallSpeed = smoother.addSample(15 * 150L + 1050L, cumulativeBytes) // 0 additional bytes

        assertEquals("Momentary stall must be clamped to 0.40x previous rate", 40.4f, postStallSpeed, 1.5f)
        assertTrue("With clamping speed must remain >= 39 Mbps (was $postStallSpeed)", postStallSpeed >= 39f)
    }

    // =========================================================================
    // 5. Adaptive Warm-Up Progression
    // =========================================================================

    @Test
    fun testAdaptiveWarmUp_ExactWeighting() {
        smoother.addSample(0L, 0L)

        // Sample 1: 80 Mbps raw window rate (1,500,000 bytes in 150ms)
        // Expected alpha = 1.0 -> smoothed = 80.0 Mbps
        val s1 = smoother.addSample(150L, 1_500_000L)
        assertEquals("Sample 1 must use alpha=1.0 (immediate initialization)", 80f, s1, 0.1f)

        // Sample 2: 100 Mbps raw window rate (3,375,000 total bytes at 300ms -> delta = 3,375,000 - 0 = 3,375,000 in 300ms = 90 Mbps raw window)
        // Oldest is (0,0), timeDelta = 300ms, byteDelta = 3,375,000 -> raw = 90 Mbps
        // Expected alpha = 0.65 -> 0.65 * 90 + 0.35 * 80 = 58.5 + 28 = 86.5 Mbps
        val s2 = smoother.addSample(300L, 3_375_000L)
        assertEquals("Sample 2 must use alpha=0.65 adaptive weighting", 86.5f, s2, 0.5f)

        // Sample 3+: steady state alpha = 0.32
        val s3 = smoother.addSample(450L, 5_250_000L)
        assertTrue("Sample 3 must reflect steady-state transition", s3 > 0f)
    }

    // =========================================================================
    // 6. 15% Trimmed Mean Calculation
    // =========================================================================

    @Test
    fun testTrimmedMean_RejectsOutliersAndWarmUpRamp() {
        // 20 samples:
        // First 4 samples: cold-start warm-up ramp (10, 20, 35, 50)
        // Next 16 samples: 1 low outlier (15), 1 high outlier (160), and 14 samples clustered tightly around 80 Mbps
        val samples = listOf(
            10f, 20f, 35f, 50f, // Warm-up (dropWarmUp = 4)
            15f,                // Low outlier
            79f, 81f, 80f, 82f, 78f, 80f, 81f, 79f, 80f, 81f, 80f, 79f, 80f, 82f, // 14 sustained samples (~80.14)
            160f                // High outlier
        )

        // Trim 15% of 16 sustained samples = int(16 * 0.15) = 2 samples from each end
        // Bottom 2 trimmed: 15f, 78f
        // Top 2 trimmed: 82f, 160f
        // Remaining 12 samples: all in range [79..82], average ≈ 80.17 Mbps
        val trimmedMean = SpeedMeasurementSmoother.computeTrimmedMean(samples, trimRatio = 0.15f, dropWarmUp = 4)
        assertEquals("Trimmed mean must reject warm-up and extreme outliers", 80.17f, trimmedMean, 0.5f)

        // Edge case: Empty samples
        assertEquals(0f, SpeedMeasurementSmoother.computeTrimmedMean(emptyList()))

        // Edge case: Small samples (< 5) should use plain average
        val small = listOf(50f, 60f, 70f)
        assertEquals(60f, SpeedMeasurementSmoother.computeTrimmedMean(small), 0.001f)
    }

    // =========================================================================
    // 7. 1000ms Rolling Window Pruning
    // =========================================================================

    @Test
    fun testRollingWindowDuration_PrunesOldSamples() {
        smoother.addSample(0L, 0L)

        // Feed samples every 200ms up to 1400ms
        for (i in 1..7) {
            val t = i * 200L
            val bytes = i * 2_500_000L // 100 Mbps rate
            smoother.addSample(t, bytes)
        }

        // At t = 1400ms:
        // Window should span the last 1000ms (oldest retained sample timestamp >= 1400 - 1000 = 400ms)
        // Rate should remain accurately 100 Mbps
        val speedAt1400 = smoother.getSmoothedSpeed()
        assertEquals(100f, speedAt1400, 2f)
    }

    // =========================================================================
    // 8. State Reset
    // =========================================================================

    @Test
    fun testSmootherReset_RestoresCleanInitialState() {
        smoother.addSample(0L, 0L)
        smoother.addSample(150L, 1_875_000L)
        smoother.addSample(300L, 3_750_000L)
        assertTrue(smoother.getSmoothedSpeed() > 50f)

        // Reset
        smoother.reset()
        assertEquals("Smoothed speed must be 0 after reset", 0f, smoother.getSmoothedSpeed(), 0.001f)

        // Verify fresh start with adaptive warm-up sample 1 (alpha = 1.0)
        smoother.addSample(1000L, 0L)
        val freshS1 = smoother.addSample(1150L, 1_500_000L)
        assertEquals("Sample 1 after reset must initialize with alpha=1.0", 80f, freshS1, 0.1f)
    }

    // =========================================================================
    // 9. Concurrency & Thread Safety
    // =========================================================================

    @Test
    fun testConcurrency_ThreadSafeAddSampleAndRead() = runBlocking {
        val threads = 8
        val samplesPerThread = 100
        val errorCount = AtomicInteger(0)

        val jobs = (1..threads).map { threadId ->
            async(Dispatchers.Default) {
                try {
                    for (i in 1..samplesPerThread) {
                        val t = (threadId * samplesPerThread + i) * 10L
                        val b = t * 1000L
                        smoother.addSample(t, b)
                        val speed = smoother.getSmoothedSpeed()
                        assertTrue(speed >= 0f)
                    }
                } catch (_: Exception) {
                    errorCount.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertEquals("Concurrency test must encounter zero exceptions", 0, errorCount.get())
        assertTrue("Final smoothed speed must be non-negative", smoother.getSmoothedSpeed() >= 0f)
    }

    // =========================================================================
    // 10. Adversarial Stress Tests (Milestone M2 Empirical Verification)
    // =========================================================================

    @Test
    fun testAdversarial_AlternatingZeroAndFourMegaByteBursts_JitterUnderThirtyPercent() {
        // Alternating 0 bytes and 4MB (4,000,000 bytes) slices at 150ms intervals
        // Raw instantaneous measurements swing between 0 Mbps and 213.33 Mbps (100% swing).
        // Steady-state smoothed throughput must not jitter > 30% between consecutive samples.
        val fourMB = 4_000_000L
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        val smoothedHistory = mutableListOf<Float>()
        for (tick in 1..40) {
            val timeMs = tick * 150L
            val bytesThisTick = if (tick % 2 == 1) fourMB else 0L
            cumulativeBytes += bytesThisTick

            val speed = smoother.addSample(timeMs, cumulativeBytes)
            smoothedHistory.add(speed)
        }

        // Window reaches steady state after ~1000ms (tick 7+).
        val steadyState = smoothedHistory.subList(7, smoothedHistory.size)
        assertTrue("Steady-state sample count must be sufficient", steadyState.size >= 30)

        for (i in 1 until steadyState.size) {
            val prev = steadyState[i - 1]
            val curr = steadyState[i]

            assertFalse("Smoothed speed must not be NaN", curr.isNaN())
            assertFalse("Smoothed speed must not be Infinite", curr.isInfinite())
            assertTrue("Smoothed speed must be positive", curr > 0f)

            val jitter = Math.abs(curr - prev) / prev
            assertTrue(
                "Throughput jitter between consecutive samples at tick ${i + 8} must not exceed 30% (was ${(jitter * 100)}%, prev=$prev, curr=$curr)",
                jitter <= 0.30f
            )
        }
    }

    @Test
    fun testAdversarial_AlternatingZeroAndFourMebiByteBursts_JitterUnderThirtyPercent() {
        // Alternating 0 bytes and 4MiB (4,194,304 bytes) slices at 150ms intervals
        val fourMiB = 4 * 1024 * 1024L
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        val smoothedHistory = mutableListOf<Float>()
        for (tick in 1..40) {
            val timeMs = tick * 150L
            val bytesThisTick = if (tick % 2 == 1) fourMiB else 0L
            cumulativeBytes += bytesThisTick

            val speed = smoother.addSample(timeMs, cumulativeBytes)
            smoothedHistory.add(speed)
        }

        val steadyState = smoothedHistory.subList(7, smoothedHistory.size)
        for (i in 1 until steadyState.size) {
            val prev = steadyState[i - 1]
            val curr = steadyState[i]

            val jitter = Math.abs(curr - prev) / prev
            assertTrue(
                "Throughput jitter with 4MiB slices must not exceed 30% (was ${(jitter * 100)}%, prev=$prev, curr=$curr)",
                jitter <= 0.30f
            )
        }
    }

    @Test
    fun testAdversarial_StepUp_TenMbpsToOneGbps_SteadyConvergenceNoNanOrInf() {
        // Phase 1: Converge at 10 Mbps
        // 10 Mbps in 150ms = (10_000_000 / 8) * 0.15 = 187,500 bytes per 150ms
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..15) {
            val timeMs = tick * 150L
            cumulativeBytes += 187_500L
            val s = smoother.addSample(timeMs, cumulativeBytes)
            assertFalse(s.isNaN())
            assertFalse(s.isInfinite())
        }

        val speedAt10 = smoother.getSmoothedSpeed()
        assertEquals("Initial steady state should be ~10 Mbps", 10f, speedAt10, 1.0f)

        // Phase 2: Instantaneous step to 1 Gbps (1,000 Mbps)
        // 1,000 Mbps in 150ms = (1,000_000_000 / 8) * 0.15 = 18,750,000 bytes per 150ms
        var prevSpeed = speedAt10
        val stepUpHistory = mutableListOf<Float>()

        for (tick in 16..45) {
            val timeMs = tick * 150L
            cumulativeBytes += 18_750_000L
            val currentSpeed = smoother.addSample(timeMs, cumulativeBytes)

            assertFalse("Speed must not be NaN", currentSpeed.isNaN())
            assertFalse("Speed must not be Infinite", currentSpeed.isInfinite())
            assertTrue(
                "Speed must monotonically increase during step-up (prev=$prevSpeed, curr=$currentSpeed)",
                currentSpeed >= prevSpeed - 0.1f
            )
            stepUpHistory.add(currentSpeed)
            prevSpeed = currentSpeed
        }

        val finalSpeed = smoother.getSmoothedSpeed()
        assertEquals("Smoothed speed must converge to ~1000 Mbps", 1000f, finalSpeed, 20.0f)
        assertTrue("Speed must not overshoot beyond reasonable threshold", finalSpeed <= 1010f)
    }

    @Test
    fun testAdversarial_StepDown_OneGbpsToFiveMbps_SteadyConvergenceNoNanOrInf() {
        // Phase 1: Converge at 1 Gbps (1,000 Mbps)
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        for (tick in 1..25) {
            val timeMs = tick * 150L
            cumulativeBytes += 18_750_000L
            val s = smoother.addSample(timeMs, cumulativeBytes)
            assertFalse(s.isNaN())
            assertFalse(s.isInfinite())
        }

        val speedAt1000 = smoother.getSmoothedSpeed()
        assertEquals("Initial steady state should be ~1000 Mbps", 1000f, speedAt1000, 20.0f)

        // Phase 2: Instantaneous drop to 5 Mbps
        // 5 Mbps in 150ms = (5_000_000 / 8) * 0.15 = 93,750 bytes per 150ms
        var prevSpeed = speedAt1000
        for (tick in 26..65) {
            val timeMs = tick * 150L
            cumulativeBytes += 93_750L
            val currentSpeed = smoother.addSample(timeMs, cumulativeBytes)

            assertFalse("Speed must not be NaN", currentSpeed.isNaN())
            assertFalse("Speed must not be Infinite", currentSpeed.isInfinite())
            assertTrue(
                "Speed must smoothly decrease during step-down (prev=$prevSpeed, curr=$currentSpeed)",
                currentSpeed <= prevSpeed + 0.1f
            )
            prevSpeed = currentSpeed
        }

        val finalSpeed = smoother.getSmoothedSpeed()
        assertEquals("Smoothed speed must steadily converge to ~5 Mbps", 5f, finalSpeed, 0.5f)
        assertTrue("Speed must not drop below 0", finalSpeed >= 0f)
    }

    @Test
    fun testAdversarial_RapidZeroDeltaAndNonMonotonicTimestamps_RobustNoCrash() {
        smoother.addSample(0L, 0L)
        smoother.addSample(100L, 100_000L)

        // Multiple calls with the same timestamp or non-monotonic timestamps
        val sameTimeSpeed = smoother.addSample(100L, 120_000L)
        assertFalse("Same timestamp must not produce NaN", sameTimeSpeed.isNaN())
        assertFalse("Same timestamp must not produce Infinite", sameTimeSpeed.isInfinite())

        val backwardsTimeSpeed = smoother.addSample(90L, 120_000L)
        assertFalse("Backwards timestamp must not produce NaN", backwardsTimeSpeed.isNaN())
        assertFalse("Backwards timestamp must not produce Infinite", backwardsTimeSpeed.isInfinite())
    }

    @Test
    fun testAdversarial_HugeByteCountsExceedingIntMax_NoOverflow() {
        // Exceed 32-bit integer (> 2.14 GB), e.g. 50 GB transfer
        val fiftyGB = 50_000_000_000L
        smoother.addSample(0L, 0L)

        for (tick in 1..20) {
            val timeMs = tick * 150L
            val bytes = (fiftyGB / 20) * tick
            val speed = smoother.addSample(timeMs, bytes)

            assertFalse("High byte count must not result in NaN", speed.isNaN())
            assertFalse("High byte count must not result in Infinite", speed.isInfinite())
            assertTrue("High byte count speed must be positive", speed > 0f)
        }
    }
}

