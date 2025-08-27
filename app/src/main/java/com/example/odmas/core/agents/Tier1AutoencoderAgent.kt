package com.example.odmas.core.agents

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.example.odmas.utils.LogFileLogger
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Tier-1 Autoencoder Agent (TFLite):
 * - Loads a tiny autoencoder model from assets/autoencoder_model.tflite
 * - Computes reconstruction error (MSE) as the anomaly "error"
 * - Learns baseline mean/std of error online (≥100 samples) for probability conversion in Fusion
 *
 * Notes:
 * - Keeps the same public API so Fusion and SecurityManager continue to work unchanged.
 * - If model cannot be loaded, falls back to a safe pass-through (copy input to output) to avoid crashes.
 */
class Tier1AutoencoderAgent(private val context: Context) {

    private var interpreter: Interpreter? = null

    // Baseline statistics for reconstruction error (learned online)
    private var baselineMean: Double = 0.0
    private var baselineStd: Double = 1.0
    private var isBaselineEstablished: Boolean = false

    private val errorHistory = mutableListOf<Double>()
    private val maxErrorHistorySize = 1000

    companion object {
        private const val TAG = "Tier1Autoencoder"
        private const val MODEL_FILENAME = "autoencoder_model.tflite"
        private const val INPUT_SIZE = 10 // feature count
        private const val MIN_BASELINE_SAMPLES = 100
    }

    /**
     * Initialize the autoencoder interpreter from assets.
     */
    fun initializeModel(): Boolean {
        return try {
            val model = loadModelFile(context, MODEL_FILENAME)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            interpreter = Interpreter(model, options)
            LogFileLogger.log(TAG, "TFLite autoencoder initialized")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "initializeModel failed: ${t.message}", t)
            LogFileLogger.log(TAG, "initializeModel failed: ${t.message}", t)
            // Keep interpreter null; computeReconstructionError will fallback to pass-through
            false
        }
    }

    /**
     * Compute reconstruction error for a 10-dim feature vector.
     * If interpreter is unavailable, returns a small safe value while still updating the baseline buffer.
     */
    fun computeReconstructionError(features: DoubleArray): Double? {
        if (features.size != INPUT_SIZE) return null

        val output = FloatArray(INPUT_SIZE)
        val error: Double = try {
            val tflite = interpreter
            if (tflite != null) {
                // Model expects shape [1, 10] -> [1, 10]
                val input = arrayOf(FloatArray(INPUT_SIZE) { i -> features[i].toFloat() })
                val out = Array(1) { FloatArray(INPUT_SIZE) }
                tflite.run(input, out)
                // Compute MSE between input and output
                var e = 0.0
                for (i in 0 until INPUT_SIZE) {
                    val diff = features[i] - out[0][i].toDouble()
                    e += diff * diff
                    output[i] = out[0][i]
                }
                e / INPUT_SIZE.toDouble()
            } else {
                // Pass-through fallback (copy input to output, error ≈ 0)
                0.0
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TFLite run failed: ${t.message}", t)
            LogFileLogger.log(TAG, "TFLite run failed: ${t.message}", t)
            // On failure, return a neutral small error to avoid breaking pipeline
            0.0
        }

        updateBaseline(error)
        return error
    }

    fun setBaselineStats(mean: Double, std: Double) {
        baselineMean = mean
        baselineStd = std
        isBaselineEstablished = true
    }

    fun getBaselineStats(): Pair<Double, Double>? {
        return if (isBaselineEstablished) baselineMean to baselineStd else null
    }

    fun isBaselineReady(): Boolean = isBaselineEstablished

    fun resetBaseline() {
        errorHistory.clear()
        baselineMean = 0.0
        baselineStd = 1.0
        isBaselineEstablished = false
    }

    fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        } finally {
            interpreter = null
        }
    }

    private fun updateBaseline(error: Double) {
        errorHistory.add(error)
        if (errorHistory.size > maxErrorHistorySize) {
            errorHistory.removeAt(0)
        }
        if (errorHistory.size >= MIN_BASELINE_SAMPLES) {
            baselineMean = errorHistory.average()
            val variance = errorHistory.map { (it - baselineMean) * (it - baselineMean) }.average()
            baselineStd = sqrt(variance).coerceAtLeast(1e-9)
            isBaselineEstablished = true
        }
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            val channel: FileChannel = fis.channel
            val startOffset = afd.startOffset
            val declaredLength = afd.declaredLength
            return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}
