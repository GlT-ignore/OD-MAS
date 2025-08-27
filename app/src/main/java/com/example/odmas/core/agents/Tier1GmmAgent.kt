package com.example.odmas.core.agents

import android.content.Context
import com.example.odmas.core.Modality
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tier-1 GMM Agent (pure Kotlin, diagonal covariance, per-modality).
 *
 * Trains tiny diagonal-covariance GMMs on-device from the 2-minute baseline window.
 * Supports three modalities (TOUCH, MOTION, TYPING), each with its own 10-dim feature space,
 * preprocessing (clip + transform + robust standardization via median/MAD),
 * and a K=2 mixture trained by EM.
 *
 * API used by SecurityManager:
 * - initializeModel(): Boolean
 * - addBaselineSample(features, modality)
 * - trainAllIfNeeded()
 * - computeNll(features, modality): Double?    // negative log-likelihood for standardized features
 * - getAggregateBaselineStats(): Pair<Double, Double>? // mean/std of training NLL across ready modalities
 * - isAnyModalityReady(): Boolean
 * - resetBaseline(), close()
 */
class Tier1GmmAgent(private val context: Context) {

    private val minSamplesPerModality = 100
    private val maxBaselineSamplesPerModality = 5000
    private val kComponents = 2
    private val maxIterations = 20
    private val relLLThreshold = 1e-3
    private val varFloor = 1e-4
    private val weightFloor = 0.01
    private val eps = 1e-6

    // Baseline buffers (raw samples, 10-dim)
    private val baselineBuffers: MutableMap<Modality, MutableList<DoubleArray>> = mutableMapOf(
        Modality.TOUCH to mutableListOf(),
        Modality.MOTION to mutableListOf(),
        Modality.TYPING to mutableListOf()
    )

    // Trained per-modality models + preprocessing params
    private val models: MutableMap<Modality, GmmModel?> = mutableMapOf(
        Modality.TOUCH to null,
        Modality.MOTION to null,
        Modality.TYPING to null
    )
    private val preproc: MutableMap<Modality, PreprocParams?> = mutableMapOf(
        Modality.TOUCH to null,
        Modality.MOTION to null,
        Modality.TYPING to null
    )

    // Aggregate baseline of NLL (for Fusion z-score; computed from training NLLs)
    @Volatile private var aggMeanNll: Double? = null
    @Volatile private var aggStdNll: Double? = null

    fun initializeModel(): Boolean {
        // Nothing to load; will train after 2-min baseline from in-memory buffers
        return true
    }

    fun addBaselineSample(features: DoubleArray, modality: Modality) {
        val m = when (modality) {
            Modality.TOUCH, Modality.MOTION, Modality.TYPING -> modality
            else -> return
        }
        if (features.size != 10) return
        val buf = baselineBuffers[m] ?: return
        if (buf.size < maxBaselineSamplesPerModality) {
            buf.add(features.copyOf())
        } else {
            // Keep a reservoir: simple eviction of oldest
            buf.removeAt(0)
            buf.add(features.copyOf())
        }
    }

    fun trainAllIfNeeded() {
        for (m in listOf(Modality.TOUCH, Modality.MOTION, Modality.TYPING)) {
            if (models[m] == null) {
                maybeTrainModality(m)
            }
        }
        // After any trainings, recompute aggregate baseline stats across modalities
        recomputeAggregateBaseline()
    }

    fun computeNll(features: DoubleArray, modality: Modality): Double? {
        if (features.size != 10) return null
        val m = when (modality) {
            Modality.TOUCH, Modality.MOTION, Modality.TYPING -> modality
            else -> return null
        }
        // Lazy-train if needed
        if (models[m] == null) {
            maybeTrainModality(m)
            if (models[m] == null) return null
        }
        val model = models[m] ?: return null
        val prep = preproc[m] ?: return null

        val z = preprocessOne(features, prep)
        return -logSumExpMixture(z, model)
    }

    fun getAggregateBaselineStats(): Pair<Double, Double>? {
        val mu = aggMeanNll
        val sd = aggStdNll
        return if (mu != null && sd != null && sd.isFinite()) mu to sd else null
    }

    fun isAnyModalityReady(): Boolean {
        return models.values.any { it != null }
    }

    fun resetBaseline() {
        baselineBuffers.values.forEach { it.clear() }
        models.keys.forEach { models[it] = null }
        preproc.keys.forEach { preproc[it] = null }
        aggMeanNll = null
        aggStdNll = null
    }

    fun close() {
        // Nothing to close; keep for API symmetry
    }

    // ---------------------------
    // Internal: model + preproc
    // ---------------------------

    private data class GmmModel(
        val weights: DoubleArray,      // K
        val means: Array<DoubleArray>, // K × d
        val variances: Array<DoubleArray>, // K × d (diagonal)
        val trainingNll: DoubleArray   // N training NLLs (for calibration/baseline)
    )

    private data class PreprocParams(
        val boundsMin: DoubleArray,   // d
        val boundsMax: DoubleArray,   // d
        val transforms: Array<Transform>, // d
        val medians: DoubleArray,     // d (post-transform)
        val mads: DoubleArray         // d (post-transform)
    )

    private enum class Transform {
        NONE, LOG1P, SQRT
    }

    private fun maybeTrainModality(modality: Modality) {
        val raw = baselineBuffers[modality] ?: return
        if (raw.size < minSamplesPerModality) return

        // 1) Build modality-specific preprocessing params (bounds+transforms)
        val (minB, maxB, trans) = defaultBoundsAndTransforms(modality)
        // 2) Compute robust stats on transformed & clipped samples
        val transformed = Array(raw.size) { DoubleArray(10) }
        for (i in raw.indices) {
            transformed[i] = transformAndClip(raw[i], minB, maxB, trans)
        }
        val med = medianPerDim(transformed)
        val mad = madPerDim(transformed, med)
        val prep = PreprocParams(minB, maxB, trans, med, mad)
        preproc[modality] = prep

        // 3) Standardize
        val Z = Array(transformed.size) { DoubleArray(10) }
        for (i in transformed.indices) {
            Z[i] = robustStandardize(transformed[i], med, mad)
        }

        // 4) Train GMM (K=2) in standardized space via EM
        val model = trainDiagonalGmm(Z, kComponents, maxIterations)
        models[modality] = model
    }

    private fun recomputeAggregateBaseline() {
        val allNll = mutableListOf<Double>()
        for (m in listOf(Modality.TOUCH, Modality.MOTION, Modality.TYPING)) {
            val model = models[m] ?: continue
            for (v in model.trainingNll) allNll.add(v)
        }
        if (allNll.isEmpty()) {
            aggMeanNll = null
            aggStdNll = null
            return
        }
        val mean = allNll.average()
        var varSum = 0.0
        for (v in allNll) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / max(1, allNll.size))
        aggMeanNll = mean
        aggStdNll = max(std, 1e-9)
    }

    // ---------------------------
    // Preprocessing helpers
    // ---------------------------

    private data class BoundsAndTransforms(
        val minB: DoubleArray,
        val maxB: DoubleArray,
        val trans: Array<Transform>
    )

    // Default bounds + transforms per modality and feature index
    private fun defaultBoundsAndTransforms(modality: Modality): BoundsAndTransforms {
        val minB = DoubleArray(10)
        val maxB = DoubleArray(10)
        val trans = Array(10) { Transform.NONE }

        when (modality) {
            Modality.TOUCH -> {
                // Based on research prompt table
                set(minB, maxB, trans, 0, 0.01, 3.0, Transform.LOG1P) // dwell
                set(minB, maxB, trans, 1, 0.0, 3.0, Transform.LOG1P)  // flight
                set(minB, maxB, trans, 2, 0.1, 1.0, Transform.NONE)   // pressureMean
                set(minB, maxB, trans, 3, 0.1, 1.0, Transform.NONE)   // sizeMean
                set(minB, maxB, trans, 4, 0.1, 100.0, Transform.LOG1P)// speed
                set(minB, maxB, trans, 5, 0.0, 10.0, Transform.SQRT)  // curvature
                set(minB, maxB, trans, 6, 0.0, 0.5, Transform.SQRT)   // pressureVar
                set(minB, maxB, trans, 7, 0.0, 0.5, Transform.SQRT)   // sizeVar
                set(minB, maxB, trans, 8, 0.0, 10.0, Transform.SQRT)  // rhythm
                set(minB, maxB, trans, 9, 0.1, 1000.0, Transform.LOG1P)// distance
            }
            Modality.MOTION -> {
                set(minB, maxB, trans, 0, 0.0, 50.0, Transform.SQRT)  // accel mag
                set(minB, maxB, trans, 1, 0.0, 50.0, Transform.SQRT)  // accel var
                set(minB, maxB, trans, 2, 0.0, 50.0, Transform.SQRT)  // accel peak
                set(minB, maxB, trans, 3, 0.0, 50.0, Transform.SQRT)  // gyro mag
                set(minB, maxB, trans, 4, 0.0, 50.0, Transform.SQRT)  // gyro var
                set(minB, maxB, trans, 5, 0.0, 50.0, Transform.SQRT)  // gyro peak
                set(minB, maxB, trans, 6, 0.0, 100.0, Transform.LOG1P)// motion intensity
                set(minB, maxB, trans, 7, 0.0, 20.0, Transform.SQRT)  // tremor
                set(minB, maxB, trans, 8, 0.0, 20.0, Transform.SQRT)  // orientation change
                set(minB, maxB, trans, 9, 0.0, 1.0, Transform.NONE)   // placeholder
            }
            Modality.TYPING -> {
                set(minB, maxB, trans, 0, 0.01, 3.0, Transform.LOG1P) // dwell
                set(minB, maxB, trans, 1, 0.0, 3.0, Transform.LOG1P)  // flight
                set(minB, maxB, trans, 2, 0.1, 1.0, Transform.NONE)   // keyPressureMean
                set(minB, maxB, trans, 3, 0.1, 1.0, Transform.NONE)   // keySizeMean
                set(minB, maxB, trans, 4, 0.1, 100.0, Transform.LOG1P)// keySpeed
                set(minB, maxB, trans, 5, 0.0, 10.0, Transform.SQRT)  // keyCurvature
                set(minB, maxB, trans, 6, 0.0, 0.5, Transform.SQRT)   // keyPressureVar
                set(minB, maxB, trans, 7, 0.0, 0.5, Transform.SQRT)   // keySizeVar
                set(minB, maxB, trans, 8, 0.0, 10.0, Transform.SQRT)  // rhythm variance
                set(minB, maxB, trans, 9, 0.1, 1000.0, Transform.LOG1P)// distance proxy
            }
            else -> {
                // default neutral (should not be used)
                for (i in 0 until 10) {
                    minB[i] = -1e9; maxB[i] = 1e9; trans[i] = Transform.NONE
                }
            }
        }
        return BoundsAndTransforms(minB, maxB, trans)
    }

    private fun set(minB: DoubleArray, maxB: DoubleArray, trans: Array<Transform>, idx: Int, lo: Double, hi: Double, t: Transform) {
        minB[idx] = lo
        maxB[idx] = hi
        trans[idx] = t
    }

    private fun transformAndClip(x: DoubleArray, minB: DoubleArray, maxB: DoubleArray, trans: Array<Transform>): DoubleArray {
        val out = DoubleArray(10)
        for (i in 0 until 10) {
            var v = x[i]
            v = v.coerceIn(minB[i], maxB[i])
            v = when (trans[i]) {
                Transform.LOG1P -> ln(1.0 + v)
                Transform.SQRT -> sqrt(max(0.0, v))
                else -> v
            }
            out[i] = v
        }
        return out
    }

    private fun robustStandardize(x: DoubleArray, med: DoubleArray, mad: DoubleArray): DoubleArray {
        val out = DoubleArray(10)
        for (i in 0 until 10) {
            val s = 1.4826 * mad[i] + eps
            var z = (x[i] - med[i]) / s
            // clip to avoid extreme values
            if (z > 4.0) z = 4.0
            if (z < -4.0) z = -4.0
            out[i] = z
        }
        return out
    }

    private fun medianPerDim(X: Array<DoubleArray>): DoubleArray {
        val d = 10
        val med = DoubleArray(d)
        for (j in 0 until d) {
            val col = DoubleArray(X.size) { i -> X[i][j] }
            med[j] = median(col)
        }
        return med
    }

    private fun madPerDim(X: Array<DoubleArray>, med: DoubleArray): DoubleArray {
        val d = 10
        val mads = DoubleArray(d)
        for (j in 0 until d) {
            val absDev = DoubleArray(X.size) { i -> abs(X[i][j] - med[j]) }
            mads[j] = median(absDev).coerceAtLeast(1e-9)
        }
        return mads
    }

    private fun median(arr: DoubleArray): Double {
        val a = arr.copyOf()
        a.sort()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else 0.5 * (a[n / 2 - 1] + a[n / 2])
    }

    // ---------------------------
    // GMM EM (diagonal) in standardized space
    // ---------------------------

    private fun trainDiagonalGmm(Z: Array<DoubleArray>, K: Int, maxIter: Int): GmmModel {
        val N = Z.size
        val d = 10

        // Init means with 2-sample seeding
        val means = Array(K) { DoubleArray(d) }
        val idx0 = 0
        val idx1 = max(1, N / 2)
        means[0] = Z[idx0].copyOf()
        means[1] = Z[idx1].copyOf()

        val variances = Array(K) { DoubleArray(d) { 1.0 } }
        // Global variance init
        val gvar = DoubleArray(d) { j ->
            var sum = 0.0
            var sumsq = 0.0
            for (i in 0 until N) {
                val v = Z[i][j]
                sum += v
                sumsq += v * v
            }
            val mu = sum / N
            val v = (sumsq / N) - mu * mu
            max(v, varFloor)
        }
        for (k in 0 until K) for (j in 0 until d) variances[k][j] = gvar[j]

        val weights = DoubleArray(K) { 1.0 / K }

        var prevLL = Double.NEGATIVE_INFINITY
        val nllList = DoubleArray(N) { 0.0 }

        repeat(maxIter) {
            // E-step
            val logResp = Array(N) { DoubleArray(K) }
            for (i in 0 until N) {
                for (k in 0 until K) {
                    logResp[i][k] = ln(weights[k] + eps) + logGaussianDiag(Z[i], means[k], variances[k])
                }
                val lse = logSumExp(logResp[i])
                for (k in 0 until K) {
                    logResp[i][k] -= lse
                }
                // store NLL per sample (for training set distribution)
                nllList[i] = -(lse)
            }

            // M-step
            val Nk = DoubleArray(K) { 0.0 }
            for (k in 0 until K) {
                var s = 0.0
                for (i in 0 until N) s += exp(logResp[i][k])
                Nk[k] = s
            }

            for (k in 0 until K) {
                weights[k] = max(Nk[k] / N, weightFloor)
                // means
                for (j in 0 until d) {
                    var num = 0.0
                    for (i in 0 until N) {
                        num += exp(logResp[i][k]) * Z[i][j]
                    }
                    means[k][j] = if (Nk[k] > 0.0) num / Nk[k] else 0.0
                }
                // variances (diagonal)
                for (j in 0 until d) {
                    var num = 0.0
                    for (i in 0 until N) {
                        val diff = Z[i][j] - means[k][j]
                        num += exp(logResp[i][k]) * (diff * diff)
                    }
                    variances[k][j] = max(if (Nk[k] > 0.0) num / Nk[k] else gvar[j], varFloor)
                }
            }

            // Convergence check on log-likelihood
            var ll = 0.0
            for (i in 0 until N) {
                val mix = lnMix(Z[i], weights, means, variances)
                ll += mix
            }
            val rel = if (prevLL.isFinite()) abs((ll - prevLL) / (prevLL + eps)) else Double.POSITIVE_INFINITY
            prevLL = ll
            if (rel < relLLThreshold) return GmmModel(weights.copyOf(), clone2D(means), clone2D(variances), nllList.copyOf())
        }

        return GmmModel(weights.copyOf(), clone2D(means), clone2D(variances), nllList.copyOf())
    }

    private fun clone2D(a: Array<DoubleArray>): Array<DoubleArray> {
        return Array(a.size) { i -> a[i].copyOf() }
    }

    private fun logGaussianDiag(z: DoubleArray, mean: DoubleArray, varDiag: DoubleArray): Double {
        // −1/2 [ Σ_j ((z - μ)^2 / σ^2 + ln σ^2) + d ln(2π) ]
        var quad = 0.0
        var logdet = 0.0
        for (j in 0 until z.size) {
            val v = max(varDiag[j], varFloor)
            val diff = z[j] - mean[j]
            quad += (diff * diff) / v
            logdet += ln(v)
        }
        return -0.5 * (quad + logdet + z.size * LN_2PI)
    }

    private fun lnMix(z: DoubleArray, w: DoubleArray, mu: Array<DoubleArray>, varDiag: Array<DoubleArray>): Double {
        val K = w.size
        val logTerms = DoubleArray(K) { k -> ln(w[k] + eps) + logGaussianDiag(z, mu[k], varDiag[k]) }
        return logSumExp(logTerms)
    }

    private fun logSumExp(x: DoubleArray): Double {
        var m = Double.NEGATIVE_INFINITY
        for (v in x) if (v > m) m = v
        var s = 0.0
        for (v in x) s += exp(v - m)
        return m + ln(s + eps)
    }

    private fun logSumExpMixture(z: DoubleArray, model: GmmModel): Double {
        val K = model.weights.size
        val logTerms = DoubleArray(K) { k -> ln(model.weights[k] + eps) + logGaussianDiag(z, model.means[k], model.variances[k]) }
        return logSumExp(logTerms)
    }

    private fun preprocessOne(x: DoubleArray, prep: PreprocParams): DoubleArray {
        val clippedTransformed = transformAndClip(x, prep.boundsMin, prep.boundsMax, prep.transforms)
        return robustStandardize(clippedTransformed, prep.medians, prep.mads)
    }

    companion object {
        private const val LN_2PI = 1.8378770664093453 // ln(2π)
    }
}