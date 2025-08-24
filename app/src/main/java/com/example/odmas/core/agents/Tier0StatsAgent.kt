package com.example.odmas.core.agents

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tier-0 Stats Agent: Fast statistical anomaly detection using Mahalanobis distance
 * 
 * Features:
 * - Rolling mean and covariance computation
 * - Mahalanobis distance calculation
 * - 3-second windows with 50% overlap
 * - Real-time processing for immediate response
 */
class Tier0StatsAgent {
    
    private val featureBuffer = mutableListOf<DoubleArray>()
    private val windowSize = 30 // 3 seconds at 10Hz sampling
    private val overlapSize = 15 // 50% overlap
    
    private var meanVector: DoubleArray? = null
    private var covarianceMatrix: Array<DoubleArray>? = null
    private var isBaselineEstablished = false
    
    companion object {
        private const val MIN_BASELINE_SAMPLES = 50 // Minimum samples for baseline
        private const val FEATURE_COUNT = 10 // Number of features per sample
    }
    
    /**
     * Add a new feature vector to the buffer
     * @param features Feature vector (touch, motion, etc.)
     */
    fun addFeatures(features: DoubleArray): Unit {
        if (features.size != FEATURE_COUNT) {
            throw IllegalArgumentException("Expected $FEATURE_COUNT features, got ${features.size}")
        }
        
        featureBuffer.add(features)
        
        // Maintain buffer size
        if (featureBuffer.size > windowSize * 2) {
            featureBuffer.removeAt(0)
        }
        
        // Update baseline if we have enough samples
        if (!isBaselineEstablished && featureBuffer.size >= MIN_BASELINE_SAMPLES) {
            establishBaseline()
        }
    }
    
    /**
     * Compute Mahalanobis distance for current window
     * @return Squared Mahalanobis distance, or null if baseline not ready
     */
    fun computeMahalanobisDistance(): Double? {
        if (!isBaselineEstablished || featureBuffer.size < windowSize) {
            return null
        }
        
        // Get current window features
        val currentWindow = featureBuffer.takeLast(windowSize)
        val windowMean = computeMean(currentWindow)
        
        // Compute Mahalanobis distance
        val diff = DoubleArray(FEATURE_COUNT) { i ->
            windowMean[i] - (meanVector ?: return null)[i]
        }
        
        val covariance = covarianceMatrix ?: return null
        val mahalanobisSquared = computeMahalanobisSquared(diff, covariance)
        
        return mahalanobisSquared
    }
    
    /**
     * Get current window features for Tier-1 processing
     * @return Current window features, or null if not enough data
     */
    fun getCurrentWindowFeatures(): DoubleArray? {
        if (featureBuffer.size < windowSize) {
            return null
        }
        
        val window = featureBuffer.takeLast(windowSize)
        return computeMean(window)
    }
    
    /**
     * Check if baseline is established
     */
    fun isBaselineReady(): Boolean = isBaselineEstablished
    
    /**
     * Reset baseline (for new user or session)
     */
    fun resetBaseline(): Unit {
        featureBuffer.clear()
        meanVector = null
        covarianceMatrix = null
        isBaselineEstablished = false
    }
    
    /**
     * Get baseline statistics for Tier-1
     */
    fun getBaselineStats(): BaselineStats? {
        if (!isBaselineEstablished) {
            return null
        }
        
        return BaselineStats(
            mean = meanVector?.copyOf() ?: return null,
            covariance = covarianceMatrix?.map { it.copyOf() }?.toTypedArray() ?: return null
        )
    }
    
    private fun establishBaseline(): Unit {
        if (featureBuffer.size < MIN_BASELINE_SAMPLES) {
            return
        }
        
        // Compute mean vector
        meanVector = computeMean(featureBuffer)
        
        // Compute covariance matrix
        covarianceMatrix = computeCovarianceMatrix(featureBuffer, meanVector!!)
        
        isBaselineEstablished = true
    }
    
    private fun computeMean(features: List<DoubleArray>): DoubleArray {
        val mean = DoubleArray(FEATURE_COUNT) { 0.0 }
        
        for (featureVector in features) {
            for (i in 0 until FEATURE_COUNT) {
                mean[i] += featureVector[i]
            }
        }
        
        for (i in 0 until FEATURE_COUNT) {
            mean[i] /= features.size
        }
        
        return mean
    }
    
    private fun computeCovarianceMatrix(features: List<DoubleArray>, mean: DoubleArray): Array<DoubleArray> {
        val covariance = Array(FEATURE_COUNT) { DoubleArray(FEATURE_COUNT) { 0.0 } }
        
        for (featureVector in features) {
            for (i in 0 until FEATURE_COUNT) {
                for (j in 0 until FEATURE_COUNT) {
                    val diffI = featureVector[i] - mean[i]
                    val diffJ = featureVector[j] - mean[j]
                    covariance[i][j] += diffI * diffJ
                }
            }
        }
        
        val n = features.size.toDouble()
        for (i in 0 until FEATURE_COUNT) {
            for (j in 0 until FEATURE_COUNT) {
                covariance[i][j] /= n
            }
        }
        
        // Add small regularization to ensure matrix is invertible
        for (i in 0 until FEATURE_COUNT) {
            covariance[i][i] += 1e-6
        }
        
        return covariance
    }
    
    private fun computeMahalanobisSquared(diff: DoubleArray, covariance: Array<DoubleArray>): Double {
        // Solve: diff^T * inv(covariance) * diff
        // Using Cholesky decomposition for efficiency
        
        val cholesky = computeCholeskyDecomposition(covariance)
        val y = solveLowerTriangular(cholesky, diff)
        
        return y.sumOf { it * it }
    }
    
    private fun computeCholeskyDecomposition(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val L = Array(n) { DoubleArray(n) { 0.0 } }
        
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = 0.0
                
                if (j == i) {
                    for (k in 0 until j) {
                        sum += L[j][k] * L[j][k]
                    }
                    L[j][j] = sqrt(matrix[j][j] - sum)
                } else {
                    for (k in 0 until j) {
                        sum += L[i][k] * L[j][k]
                    }
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
            var sum = 0.0
            for (j in 0 until i) {
                sum += L[i][j] * y[j]
            }
            y[i] = (b[i] - sum) / L[i][i]
        }
        
        return y
    }
}

/**
 * Baseline statistics data class
 */
data class BaselineStats(
    val mean: DoubleArray,
    val covariance: Array<DoubleArray>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as BaselineStats
        
        if (!mean.contentEquals(other.mean)) return false
        if (!covariance.contentDeepEquals(other.covariance)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = mean.contentHashCode()
        result = 31 * result + covariance.contentDeepHashCode()
        return result
    }
}
