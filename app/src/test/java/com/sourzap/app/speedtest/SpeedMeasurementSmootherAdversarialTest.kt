package com.sourzap.app.speedtest

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Adversarial stress test suite for SpeedMeasurementSmoother and UI Animation Damping.
 * Specifically verifies:
 * 1. Trimmed mean edge cases:
 *    - Fewer than 4 samples (0, 1, 2, 3 samples, with and without dropWarmUp)
 *    - Identical samples (10 samples, 100 samples, all zeros)
 *    - Extreme outliers at 10x speed (1000 Mbps spikes against 100 Mbps baseline)
 * 2. Outlier damping & clamping in real-time smoother:
 *    - 10x speed burst spike (> 5 Mbps threshold) clamped to 2.2x
 *    - Total socket stall clamped to 0.40x
 *    - Threshold boundary behavior at <= 5 Mbps (no clamping) vs > 5 Mbps (clamped)
 * 3. Static verification of UI Animation Damping:
 *    - Confirms ExpressiveComponents.kt and SpeedTestScreen.kt enforce Spring.DampingRatioNoBouncy
 */
class SpeedMeasurementSmootherAdversarialTest {

    private lateinit var smoother: SpeedMeasurementSmoother

    @Before
    fun setup() {
        smoother = SpeedMeasurementSmoother(windowDurationMs = 1000L, defaultAlpha = 0.32f)
    }

    // =========================================================================
    // 1. Trimmed Mean Edge Cases: Fewer than 4 samples
    // =========================================================================

    @Test
    fun testTrimmedMean_ZeroSamples_ReturnsZero() {
        val result = SpeedMeasurementSmoother.computeTrimmedMean(emptyList())
        assertEquals(0f, result, 0.0001f)

        val resultWithWarmUp = SpeedMeasurementSmoother.computeTrimmedMean(emptyList(), dropWarmUp = 4)
        assertEquals(0f, resultWithWarmUp, 0.0001f)
    }

    @Test
    fun testTrimmedMean_SingleSample_ReturnsExactSample() {
        val result = SpeedMeasurementSmoother.computeTrimmedMean(listOf(42.5f))
        assertEquals(42.5f, result, 0.0001f)

        // Even if dropWarmUp = 4, size (1) <= dropWarmUp + 2 (6), so does not drop and avoids crash
        val resultWithWarmUp = SpeedMeasurementSmoother.computeTrimmedMean(listOf(42.5f), dropWarmUp = 4)
        assertEquals(42.5f, resultWithWarmUp, 0.0001f)
    }

    @Test
    fun testTrimmedMean_TwoSamples_ReturnsArithmeticMean() {
        val samples = listOf(30f, 70f)
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples)
        assertEquals(50f, result, 0.0001f)

        val resultWithWarmUp = SpeedMeasurementSmoother.computeTrimmedMean(samples, dropWarmUp = 4)
        assertEquals(50f, resultWithWarmUp, 0.0001f)
    }

    @Test
    fun testTrimmedMean_ThreeSamples_ReturnsArithmeticMean() {
        val samples = listOf(20f, 60f, 100f)
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples)
        assertEquals(60f, result, 0.0001f)

        val resultWithWarmUp = SpeedMeasurementSmoother.computeTrimmedMean(samples, dropWarmUp = 4)
        assertEquals(60f, resultWithWarmUp, 0.0001f)
    }

    @Test
    fun testTrimmedMean_FourSamples_ReturnsArithmeticMean() {
        val samples = listOf(10f, 30f, 50f, 70f)
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples)
        assertEquals(40f, result, 0.0001f)

        val resultWithWarmUp = SpeedMeasurementSmoother.computeTrimmedMean(samples, dropWarmUp = 4)
        assertEquals(40f, resultWithWarmUp, 0.0001f)
    }

    // =========================================================================
    // 2. Trimmed Mean Edge Cases: Identical samples
    // =========================================================================

    @Test
    fun testTrimmedMean_TenIdenticalSamples_ReturnsExactValue() {
        val samples = List(10) { 75.0f }
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples, trimRatio = 0.15f)
        assertEquals(75.0f, result, 0.0001f)
    }

    @Test
    fun testTrimmedMean_HundredIdenticalSamples_ReturnsExactValue() {
        val samples = List(100) { 128.5f }
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples, trimRatio = 0.15f)
        assertEquals(128.5f, result, 0.0001f)
    }

    @Test
    fun testTrimmedMean_AllZeros_ReturnsZero() {
        val samples = List(20) { 0.0f }
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples, trimRatio = 0.15f)
        assertEquals(0.0f, result, 0.0001f)
    }

    // =========================================================================
    // 3. Trimmed Mean Edge Cases: Extreme Outliers at 10x Speed
    // =========================================================================

    @Test
    fun testTrimmedMean_TenXOutlierSpikes_CompletelyTrimmed() {
        // 20 samples: 16 samples around 100 Mbps, 2 samples at 10x speed (1000 Mbps), 2 samples near 0 (stalls)
        // With trimRatio = 0.15f on 20 samples: trimCount = (20 * 0.15) = 3 samples trimmed from top and bottom.
        // Top 3 trimmed: [1000, 1000, 102]
        // Bottom 3 trimmed: [5, 10, 98]
        // Sustained 14 samples: tightly around 100 Mbps.
        val samples = listOf(
            5f, 10f, // low outliers
            98f, 99f, 100f, 100f, 101f, 100f, 99f, 101f, 100f, 100f, 102f, 99f, 100f, 101f, // 14 steady samples
            1000f, 1000f // 10x extreme outlier spikes
        )
        val result = SpeedMeasurementSmoother.computeTrimmedMean(samples, trimRatio = 0.15f)

        // Without trimming, the arithmetic mean would be ~186 Mbps!
        // With 15% trimming, 10x outliers are eliminated and result remains within 99.5 - 100.5 Mbps.
        assertEquals(100.07f, result, 0.5f)
        assertTrue("Result must be strictly immune to 10x spike (was $result)", result < 105f)
    }

    // =========================================================================
    // 4. Outlier Clamping in Real-Time Smoother
    // =========================================================================

    @Test
    fun testRealTimeSmoother_TenXSpikeClampedToTwoPointTwo() {
        // Establish baseline of 50 Mbps
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)
        for (i in 1..10) {
            cumulativeBytes += 937_500L // 50 Mbps per 150ms
            smoother.addSample(i * 150L, cumulativeBytes)
        }

        val baselineSpeed = smoother.getSmoothedSpeed()
        assertEquals(50f, baselineSpeed, 1.5f)

        // Inject 10x spike: 9.375MB in 150ms (~500 Mbps instantaneous)
        val spikeBytes = 9_375_000L
        cumulativeBytes += spikeBytes
        val smoothedAfterSpike = smoother.addSample(11 * 150L, cumulativeBytes)

        // Clamped rate must be bounded by 50 * 2.20 = 110 Mbps.
        // New EMA = 0.32 * 110 + 0.68 * 50 ≈ 69.2 Mbps.
        // Without clamping, raw window rate would be ~150-300 Mbps, pushing smoothed to > 100 Mbps.
        assertTrue(
            "Smoothed speed after 10x spike must be clamped to <= 72 Mbps (was $smoothedAfterSpike)",
            smoothedAfterSpike <= 72f
        )
        assertTrue("Smoothed speed must still register increase (was $smoothedAfterSpike)", smoothedAfterSpike >= 65f)
    }

    @Test
    fun testRealTimeSmoother_ZeroDropoutClampedToZeroPointForty() {
        // Establish baseline of 50 Mbps
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)
        for (i in 1..10) {
            cumulativeBytes += 937_500L // 50 Mbps per 150ms
            smoother.addSample(i * 150L, cumulativeBytes)
        }

        val baselineSpeed = smoother.getSmoothedSpeed()
        assertEquals(50f, baselineSpeed, 1.5f)

        // Inject full stall of 1200ms with 0 bytes (older samples prune)
        val smoothedAfterStall = smoother.addSample(10 * 150L + 1200L, cumulativeBytes)

        // Clamped rate: bounded by 50 * 0.40 = 20 Mbps.
        // New EMA = 0.32 * 20 + 0.68 * 50 ≈ 40.4 Mbps.
        // Without clamping, raw window rate = 0 Mbps, pushing smoothed down to 34.0 Mbps.
        assertTrue(
            "Smoothed speed after sudden stall must be clamped to >= 39 Mbps (was $smoothedAfterStall)",
            smoothedAfterStall >= 39f
        )
    }

    @Test
    fun testRealTimeSmoother_BelowFiveMbps_NotClamped() {
        // Under 5 Mbps, connection is just starting; clamping should not throttle initial ramp up
        var cumulativeBytes = 0L
        smoother.addSample(0L, 0L)

        // Sample 1: 1 Mbps (18,750 bytes in 150ms)
        cumulativeBytes += 18_750L
        val s1 = smoother.addSample(150L, cumulativeBytes)
        assertEquals(1.0f, s1, 0.1f)

        // Sample 2: jump to 4 Mbps (cumulative 93,750 bytes at 300ms)
        cumulativeBytes += 75_000L
        val s2 = smoother.addSample(300L, cumulativeBytes)
        assertTrue("Sample 2 should smoothly transition without clamping throttling", s2 > 1.0f)

        // Sample 3: jump with 400,000 bytes at 450ms
        cumulativeBytes += 400_000L
        val s3 = smoother.addSample(450L, cumulativeBytes)

        // Without clamping (since s2 = 1.975 <= 5 Mbps): raw window rate = 8.778 Mbps
        // EMA: 0.32 * 8.778 + 0.68 * 1.975 = 4.15 Mbps
        // If clamped to 2.2x: clamped rate would be 4.345 Mbps -> EMA would be only 2.73 Mbps.
        // Therefore s3 ≈ 4.15 Mbps proves clamping was NOT applied!
        assertEquals(4.15f, s3, 0.1f)
        assertTrue("Unclamped jump must significantly exceed clamped ceiling of 2.73 Mbps", s3 > 3.5f)
    }

    // =========================================================================
    // 5. Verification of UI Animation Damping Ratios
    // =========================================================================

    @Test
    fun testComposeSpringDampingRatioNoBouncy_IsCriticallyDamped() {
        // Jetpack Compose definition: DampingRatioNoBouncy = 1.0f (critically damped, no overshoot)
        assertEquals(1.0f, Spring.DampingRatioNoBouncy, 0.0001f)
    }

    @Test
    fun testUIComponents_SourceFiles_UseDampingRatioNoBouncy() {
        val expressiveComponentsFile = listOf(
            File("src/main/java/com/sourzap/app/ui/components/ExpressiveComponents.kt"),
            File("app/src/main/java/com/sourzap/app/ui/components/ExpressiveComponents.kt")
        ).firstOrNull { it.exists() } ?: File("src/main/java/com/sourzap/app/ui/components/ExpressiveComponents.kt")

        val speedTestScreenFile = listOf(
            File("src/main/java/com/sourzap/app/ui/speedtest/SpeedTestScreen.kt"),
            File("app/src/main/java/com/sourzap/app/ui/speedtest/SpeedTestScreen.kt")
        ).firstOrNull { it.exists() } ?: File("src/main/java/com/sourzap/app/ui/speedtest/SpeedTestScreen.kt")

        assertTrue("ExpressiveComponents.kt must exist", expressiveComponentsFile.exists())
        assertTrue("SpeedTestScreen.kt must exist", speedTestScreenFile.exists())

        val expressiveContent = expressiveComponentsFile.readText()
        val speedTestContent = speedTestScreenFile.readText()

        // Verify ExpressiveSpeedGauge uses DampingRatioNoBouncy
        assertTrue(
            "ExpressiveComponents.kt must use Spring.DampingRatioNoBouncy",
            expressiveContent.contains("DampingRatioNoBouncy")
        )

        // Verify SpeedTestScreen.kt uses DampingRatioNoBouncy
        assertTrue(
            "SpeedTestScreen.kt must use Spring.DampingRatioNoBouncy",
            speedTestContent.contains("DampingRatioNoBouncy")
        )

        // Verify NO usage of DampingRatioMediumBouncy or DampingRatioHighBouncy in SpeedTestScreen
        assertFalse(
            "SpeedTestScreen.kt must NOT use DampingRatioMediumBouncy",
            speedTestContent.contains("DampingRatioMediumBouncy")
        )
        assertFalse(
            "SpeedTestScreen.kt must NOT use DampingRatioHighBouncy",
            speedTestContent.contains("DampingRatioHighBouncy")
        )
    }
}
