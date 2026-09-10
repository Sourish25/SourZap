package com.sourzap.app.speedtest

import java.util.ArrayDeque

/**
 * High-precision speed measurement smoother with a 1000ms rolling byte-time window,
 * adaptive Exponential Moving Average (EMA: sample 1 alpha=1.0, sample 2 alpha=0.65, sample 3+ alpha=0.32),
 * outlier damping/clamping ([0.40 * S_{t-1}, 2.20 * S_{t-1}] for speeds > 5 Mbps),
 * and 15% trimmed mean calculation for final throughput reporting.
 */
class SpeedMeasurementSmoother @JvmOverloads constructor(
    val windowDurationMs: Long = 1000L,
    val defaultAlpha: Float = 0.32f,
    alpha: Float? = null
) {
    val alpha: Float = alpha ?: defaultAlpha

    private val window = ArrayDeque<Pair<Long, Long>>() // timestampMs to totalBytes
    @Volatile
    private var smoothedSpeedMbps: Float = 0f
    private var sampleCount: Int = 0
    private val lock = Any()

    /**
     * Records a new sample (timestampMs, totalBytes) and returns the updated smoothed Mbps.
     */
    fun addSample(timestampMs: Long, totalBytes: Long): Float = synchronized(lock) {
        // Auto-seed (0L, 0L) if relative timestamp is passed without an initial baseline
        if (window.isEmpty() && totalBytes > 0L && timestampMs in 1..99_999L) {
            window.addLast(0L to 0L)
        }

        window.addLast(timestampMs to totalBytes)

        // Prune samples older than the rolling window duration
        while (window.size > 2 && (timestampMs - window.first().first) > windowDurationMs) {
            window.removeFirst()
        }

        if (window.size < 2) {
            return smoothedSpeedMbps
        }

        val oldest = window.first()
        val timeDeltaMs = (timestampMs - oldest.first).coerceAtLeast(1L)
        val byteDelta = (totalBytes - oldest.second).coerceAtLeast(0L)

        // Rolling window throughput in Mbps: (bytes * 8) / (seconds * 1,000,000)
        val rawWindowSpeedMbps = ((byteDelta * 8f) / (timeDeltaMs / 1000f)) / 1_000_000f

        sampleCount++
        val effectiveAlpha = this.alpha
        smoothedSpeedMbps = when (sampleCount) {
            1 -> rawWindowSpeedMbps
            2 -> 0.65f * rawWindowSpeedMbps + 0.35f * smoothedSpeedMbps
            else -> {
                // Outlier damping/clamping: for speeds > 5 Mbps, clamp raw window rate within [0.40 * S_{t-1}, 2.20 * S_{t-1}]
                val clampedRate = if (smoothedSpeedMbps > 5f) {
                    rawWindowSpeedMbps.coerceIn(smoothedSpeedMbps * 0.40f, smoothedSpeedMbps * 2.20f)
                } else {
                    rawWindowSpeedMbps
                }
                effectiveAlpha * clampedRate + (1f - effectiveAlpha) * smoothedSpeedMbps
            }
        }

        return smoothedSpeedMbps
    }

    /**
     * Returns the current smoothed speed in Mbps.
     */
    fun getSmoothedSpeed(): Float = synchronized(lock) {
        smoothedSpeedMbps
    }

    /**
     * Resets the smoother state and history.
     */
    fun reset() = synchronized(lock) {
        window.clear()
        smoothedSpeedMbps = 0f
        sampleCount = 0
    }

    companion object {
        /**
         * Computes a 15% trimmed mean of sustained samples, optionally dropping initial warm-up samples,
         * rejecting extreme outlier spikes and cold-start connection ramp artifacts.
         */
        fun computeTrimmedMean(
            samples: List<Float>,
            trimRatio: Float = 0.15f,
            dropWarmUp: Int = 0
        ): Float {
            val sustained = if (dropWarmUp > 0 && samples.size > dropWarmUp + 2) {
                samples.drop(dropWarmUp)
            } else {
                samples
            }
            if (sustained.isEmpty()) return 0f
            if (sustained.size < 5) return sustained.average().toFloat()

            val sorted = sustained.sorted()
            val trimCount = (sorted.size * trimRatio).toInt()
            if (trimCount * 2 >= sorted.size) {
                return sorted.average().toFloat()
            }

            val trimmed = sorted.subList(trimCount, sorted.size - trimCount)
            return if (trimmed.isNotEmpty()) trimmed.average().toFloat() else sorted.average().toFloat()
        }
    }
}
