package com.example.odmas.core.agents

import com.example.odmas.core.Modality
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/**
 * Tier-0 Stats Agent (multi‑modality):
 * - Maintains per‑modality rolling buffers (Touch, Motion, Typing), each 10‑D
 * - Builds a per‑modality baseline (mean/covariance) during learning
 * - Computes per‑modality Mahalanobis d^2 on current 3s window (last windowSize samples)
 * - Combines available modality d^2 into one Tier‑0 d^2 (logical combining) via max()
 *
 * Notes:
 * - Feature dimension is fixed at 10 for all modalities.
 * - Window size targets ~3s; upstream producers should roughly provide ~10Hz for Motion
 *   and sporadic bursts for Touch/Typing; this agent is robust to irregular cadence by
 *   using sample counts rather than timestamps.
 */
class Tier0StatsAgent {

    // Per‑modality rolling buffers and baselines
    private val touch = ModBuffers()
    private val motion = ModBuffers()
    private val typing = ModBuffers()

    // Store last modality to support "current window features" queries for Tier‑1 handoff
    private var lastModalitySeen: Modality? = null

    private val windowSize = 30  // ~3s at ~10Hz (for Motion); for Touch/Typing, take last up to 30 samples
    private val overlapSize = 15 // 50% overlap (historical, retained for parity)
    private val minBaselineSamples = 50
    private val featureCount = 10

    /**
     * Add a new feature vector to the appropriate modality buffer.
     * Sanitizes non-finite values to avoid NaN/Inf propagation into Tier‑0 window stats and Cholesky.
     */
    fun addFeatures(features: DoubleArray, modality: Modality) {
        require(features.size == featureCount) { "Expected $featureCount features, got ${features.size}" }
        val buf = buffersOf(modality)

        // Defensive: sanitize features to prevent downstream math errors
        val clean = sanitize(features)
        buf.add(clean)
        lastModalitySeen = modality

        // Maintain buffer size per modality (keep up to ~2 windows for overlap computations)
        if (buf.size > windowSize * 2) {
            buf.removeAt(0)
        }

        // Establish baseline when enough samples
        val mb = mod(modality)
        if (!mb.isBaselineEstablished && buf.size >= minBaselineSamples) {
            establishBaselineFor(modality)
        }
    }

    /**
     * Backward‑compatible overload (UNKNOWN modality routes to MOTION for safety).
     */
    fun addFeatures(features: DoubleArray) {
        addFeatures(features, Modality.MOTION)
    }

    /**
     * Compute a combined (logical) Mahalanobis distance squared from all modalities
     * which have a ready baseline and enough samples in the current window.
     *
     * Combining rule:
     * - Compute per‑modality d^2 for available modalities (Touch, Motion, Typing)
     * - Return max(d^2_touch, d^2_motion, d^2_typing) if any present, else null
     */
    fun computeMahalanobisDistance(): Double? {
        val d2Values = mutableListOf<Double>()
        perModalityList().forEach { modality ->
            computeMahalanobisDistance(modality)?.let { d2Values.add(it) }
        }
        if (d2Values.isEmpty()) return null
        return d2Values.maxOrNull()
    }

    /**
     * Compute Mahalanobis d^2 for a specific modality window if baseline/window available.
     */
    private fun computeMahalanobisDistance(modality: Modality): Double? {
        val mb = mod(modality)
        if (!mb.isBaselineEstablished) return null
        val buf = buffersOf(modality)

        // Require a minimal number of recent samples per modality to avoid spurious spikes
        val minSamples = when (modality) {
            Modality.MOTION -> 10
            Modality.TOUCH -> 5
            Modality.TYPING -> 5
            else -> 10
        }
        if (buf.size < minSamples) return null

        val n = min(windowSize, buf.size)
        val window = buf.takeLast(n)
        val windowMean = computeMean(window)
        val baselineMean = mb.meanVector ?: return null
        val covariance = mb.covarianceMatrix ?: return null

        val diff = DoubleArray(featureCount) { i -> windowMean[i] - baselineMean[i] }
        return computeMahalanobisSquared(diff, covariance)
    }

    /**
     * Return current window features for Tier‑1 handoff.
     * Uses the last seen modality window if present; otherwise picks the first available.
     */
    fun getCurrentWindowFeatures(): DoubleArray? {
        val preferred = lastModalitySeen
        if (preferred != null) {
            val w = getWindowMeanFor(preferred)
            if (w != null) return w
        }
        // Fallback: try in order Motion, Touch, Typing (motion tends to be continuous)
        for (m in listOf(Modality.MOTION, Modality.TOUCH, Modality.TYPING)) {
            val w = getWindowMeanFor(m)
            if (w != null) return w
        }
        return null
    }

    private fun getWindowMeanFor(modality: Modality): DoubleArray? {
        val buf = buffersOf(modality)
        if (buf.size < 1) return null
        val n = min(windowSize, buf.size)
        val window = buf.takeLast(n)
        return computeMean(window)
    }

    /**
     * UI: tier‑0 ready if any modality baseline is established.
     */
    fun isBaselineReady(): Boolean {
        return touch.isBaselineEstablished || motion.isBaselineEstablished || typing.isBaselineEstablished
    }

    /**
     * Reset all modality baselines and buffers.
     */
    fun resetBaseline() {
        touch.reset()
        motion.reset()
        typing.reset()
        lastModalitySeen = null
    }

    /**
     * Get running mean/std from the most recent active modality.
     * Priority: last seen modality; fallback order Motion → Touch → Typing.
     */
    fun getRunningStats(): Pair<DoubleArray, DoubleArray>? {
        val preferred = lastModalitySeen
        if (preferred != null) {
            val s = runningStatsOf(preferred)
            if (s != null) return s
        }
        for (m in listOf(Modality.MOTION, Modality.TOUCH, Modality.TYPING)) {
            val s = runningStatsOf(m)
            if (s != null) return s
        }
        return null
    }

    /**
     * Expose running mean/std for a specific modality (for UI/serialization).
     */
    fun getRunningStatsFor(modality: Modality): Pair<DoubleArray, DoubleArray>? {
        return runningStatsOf(modality)
    }

    /**
     * Expose current window mean features (10D) for the given modality, if available.
     */
    fun getWindowFeaturesFor(modality: Modality): DoubleArray? {
        val buf = buffersOf(modality)
        if (buf.size < 1) return null
        val n = min(windowSize, buf.size)
        val window = buf.takeLast(n)
        return computeMean(window)
    }

    /**
     * Window-level mean/std for the given modality (for live UI display).
     */
    fun getWindowStatsFor(modality: Modality): Pair<DoubleArray, DoubleArray>? {
        val buf = buffersOf(modality)
        // Allow single-sample windows so UI table updates immediately on first taps
        if (buf.size < 1) return null
        val n = min(windowSize, buf.size)
        val window = buf.takeLast(n)

        // Robust mean: skip malformed or short vectors defensively
        val mean = DoubleArray(featureCount) { 0.0 }
        var count = 0
        for (fv in window) {
            try {
                if (fv.size >= featureCount) {
                    for (i in 0 until featureCount) {
                        mean[i] += fv[i]
                    }
                    count++
                }
            } catch (_: Throwable) {
                // skip bad entry
            }
        }
        if (count == 0) return null
        for (i in 0 until featureCount) mean[i] /= count.toDouble()

        // Robust std: skip malformed or short vectors
        val variance = DoubleArray(featureCount) { 0.0 }
        var count2 = 0
        for (fv in window) {
            try {
                if (fv.size >= featureCount) {
                    for (i in 0 until featureCount) {
                        val d = fv[i] - mean[i]
                        variance[i] += d * d
                    }
                    count2++
                }
            } catch (_: Throwable) {
                // skip bad entry
            }
        }
        val denom = if (count2 > 0) count2.toDouble() else 1.0
        for (i in 0 until featureCount) {
            variance[i] /= denom
        }
        val std = DoubleArray(featureCount) { i -> sqrt(variance[i]) }
        return mean to std
    }

    // ----------------------------
    // Internal helpers
    // ----------------------------

    private fun runningStatsOf(modality: Modality): Pair<DoubleArray, DoubleArray>? {
        val buf = buffersOf(modality)
        if (buf.size < 2) return null

        val mean = DoubleArray(featureCount) { 0.0 }
        for (v in buf) {
            for (i in 0 until featureCount) mean[i] += v[i]
        }
        for (i in 0 until featureCount) mean[i] /= buf.size.toDouble()

        val variance = DoubleArray(featureCount) { 0.0 }
        for (v in buf) {
            for (i in 0 until featureCount) {
                val d = v[i] - mean[i]
                variance[i] += d * d
            }
        }
        for (i in 0 until featureCount) {
            variance[i] /= max(1.0, buf.size.toDouble())
        }
        val std = DoubleArray(featureCount) { i -> sqrt(variance[i]) }
        return mean to std
    }

    private fun establishBaselineFor(modality: Modality) {
        val buf = buffersOf(modality)
        if (buf.size < minBaselineSamples) return

        val mb = mod(modality)
        mb.meanVector = computeMean(buf)
        mb.covarianceMatrix = computeCovarianceMatrix(buf, mb.meanVector!!)
        mb.isBaselineEstablished = true
    }

    private fun computeMean(features: List<DoubleArray>): DoubleArray {
        val mean = DoubleArray(featureCount) { 0.0 }
        var count = 0
        for (fv in features) {
            try {
                for (i in 0 until featureCount) {
                    mean[i] += fv[i]
                }
                count++
            } catch (_: Throwable) {
                // Skip malformed or null vectors defensively
            }
        }
        val denom = if (count > 0) count.toDouble() else 1.0
        for (i in 0 until featureCount) mean[i] /= denom
        return mean
    }

    private fun computeCovarianceMatrix(features: List<DoubleArray>, mean: DoubleArray): Array<DoubleArray> {
        val covariance = Array(featureCount) { DoubleArray(featureCount) { 0.0 } }
        var nGood = 0
        for (fv in features) {
            try {
                for (i in 0 until featureCount) {
                    val di = fv[i] - mean[i]
                    for (j in 0 until featureCount) {
                        val dj = fv[j] - mean[j]
                        covariance[i][j] += di * dj
                    }
                }
                nGood++
            } catch (_: Throwable) {
                // Skip malformed or null vectors defensively
            }
        }
        val denom = if (nGood > 0) nGood.toDouble() else 1.0
        for (i in 0 until featureCount) for (j in 0 until featureCount) covariance[i][j] /= denom
        // Regularize the diagonal to ensure invertibility; if no good samples, set modest variance
        for (i in 0 until featureCount) covariance[i][i] += if (nGood > 0) 1e-6 else 1e-3
        return covariance
    }

    private fun computeMahalanobisSquared(diff: DoubleArray, covariance: Array<DoubleArray>): Double {
        // Using Cholesky solve: diff^T * inv(cov) * diff = ||y||^2 where L L^T = cov and L y = diff
        val L = cholesky(covariance)
        val y = solveLowerTriangular(L, diff)
        var sum = 0.0
        for (i in y.indices) sum += y[i] * y[i]
        return sum
    }

    private fun cholesky(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val L = Array(n) { DoubleArray(n) { 0.0 } }
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = 0.0
                if (j == i) {
                    for (k in 0 until j) sum += L[j][k] * L[j][k]
                    L[j][j] = sqrt(max(matrix[j][j] - sum, 1e-12))
                } else {
                    for (k in 0 until j) sum += L[i][k] * L[j][k]
                    L[i][j] = (matrix[i][j] - sum) / L[j][j]
                }
            }
        }
        return L
    }

    private fun solveLowerTriangular(L: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = L.size
        val y = DoubleArray(n) { 0.0 }
        for (i in 0 until n) {
            var s = 0.0
            for (j in 0 until i) s += L[i][j] * y[j]
            y[i] = (b[i] - s) / L[i][i]
        }
        return y
    }

    /**
     * Replace NaN/Inf with 0.0 to keep statistics well-defined.
     */
    private fun sanitize(arr: DoubleArray): DoubleArray {
        val out = DoubleArray(featureCount) { 0.0 }
        for (i in 0 until featureCount) {
            val v = arr[i]
            out[i] = if (v.isNaN() || v == Double.POSITIVE_INFINITY || v == Double.NEGATIVE_INFINITY) 0.0 else v
        }
        return out
    }

    private fun perModalityList(): List<Modality> = listOf(Modality.TOUCH, Modality.MOTION, Modality.TYPING)

    private fun buffersOf(modality: Modality): MutableList<DoubleArray> = when (modality) {
        Modality.TOUCH -> touch.buffer
        Modality.MOTION -> motion.buffer
        Modality.TYPING -> typing.buffer
        else -> motion.buffer
    }

    private fun mod(modality: Modality): ModBuffers = when (modality) {
        Modality.TOUCH -> touch
        Modality.MOTION -> motion
        Modality.TYPING -> typing
        else -> motion
    }

    // Container for per‑modality state
    private class ModBuffers {
        val buffer: MutableList<DoubleArray> = mutableListOf()
        var meanVector: DoubleArray? = null
        var covarianceMatrix: Array<DoubleArray>? = null
        var isBaselineEstablished: Boolean = false
        fun reset() {
            buffer.clear()
            meanVector = null
            covarianceMatrix = null
            isBaselineEstablished = false
        }
    }
}
