package com.example.odmas.core

import android.content.Context
import android.util.Log
import com.example.odmas.core.agents.*
import com.example.odmas.core.chaquopy.ChaquopyBehavioralManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.odmas.utils.LogFileLogger
import kotlinx.coroutines.CoroutineExceptionHandler

class SecurityManager(private val context: Context) {
    private val tier0Agent = Tier0StatsAgent()
    private val tier1Agent = Tier1BehaviorAgent(context)
    private val fusionAgent = FusionAgent()
    private val policyAgent = PolicyAgent()
    private val chaquopyManager = ChaquopyBehavioralManager.getInstance(context)
    private val _securityState = MutableStateFlow(SecurityState())
    val securityState: StateFlow<SecurityState> = _securityState.asStateFlow()
    private val job = kotlinx.coroutines.SupervisorJob()
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        LogFileLogger.log(TAG, "Coroutine error: ${t.message}", t)
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job + crashHandler)
    private var isInitialized = false
    private var lastFeatures: DoubleArray? = null
    private var lastModality: Modality? = null
    private var stateTickerJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val PROCESSING_INTERVAL_MS = 3000L
        private const val TAG = "SecurityManager"
        @Volatile private var instance: SecurityManager? = null
        fun getInstance(context: Context): SecurityManager {
            val appCtx = context.applicationContext
            val current = instance
            if (current != null) return current
            return synchronized(this) {
                instance ?: SecurityManager(appCtx).also { instance = it }
            }
        }
    }

    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting security manager initialization...")
                LogFileLogger.init(context)
                LogFileLogger.log(TAG, "Initialization started")
                val chaquopyInitialized = chaquopyManager.initialize()
                Log.d(TAG, "Chaquopy Python ML initialization: $chaquopyInitialized")
                val tier1Initialized = tier1Agent.initializeModel()
                Log.d(TAG, "Tier-1 agent initialization: $tier1Initialized")
                fusionAgent.initializeSession()
                Log.d(TAG, "Fusion agent initialized")
                policyAgent.reset()
                Log.d(TAG, "Policy agent reset")
                if (chaquopyInitialized) {
                    chaquopyManager.startMonitoring()
                    Log.d(TAG, "Chaquopy monitoring started")
                }
                isInitialized = true
                updateSecurityState()
                Log.d(TAG, "Security manager initialization completed successfully")
                // Periodic ticker to refresh calibration progress/risk UI
                stateTickerJob?.cancel()
                stateTickerJob = coroutineScope.launch {
                    while (isInitialized) {
                        runCatching { updateSecurityState() }
                        kotlinx.coroutines.delay(1000L)
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization: ${e.message}", e)
                isInitialized = true
                updateSecurityState()
                Log.d(TAG, "Security manager initialized with fallback mode")
                stateTickerJob?.cancel()
                stateTickerJob = coroutineScope.launch {
                    while (isInitialized) {
                        runCatching { updateSecurityState() }
                        kotlinx.coroutines.delay(1000L)
                    }
                }
                true
            }
        }
    }

    fun processSensorData(features: DoubleArray): Unit = processSensorData(features, Modality.UNKNOWN)

    fun processSensorData(features: DoubleArray, modality: Modality): Unit {
        if (!isInitialized) {
            Log.w(TAG, "Security manager not initialized, ignoring sensor data")
            return
        }
        Log.d(TAG, "Processing sensor data: ${features.contentToString()} (modality=$modality)")
        coroutineScope.launch {
            try {
                val learningDone = tier1Agent.isCalibrated()
                if (!learningDone) {
                    tier0Agent.addFeatures(features, modality)
                    tier1Agent.submitCalibrationSample(features, modality)
                    tier1Agent.trainAllIfNeeded()
                    lastFeatures = features
                    lastModality = modality
                    updateSecurityState(0.0, PolicyAction.Monitor)
                    return@launch
                }
                val chaquopyResult = chaquopyManager.analyzeBehavior(features)
                Log.d(TAG, "Chaquopy analysis: Risk=${chaquopyResult.riskScore}%, Confidence=${chaquopyResult.confidence}%")
                LogFileLogger.log(TAG, "Chaquopy risk=${chaquopyResult.riskScore}, conf=${chaquopyResult.confidence}")
                tier0Agent.addFeatures(features, modality)
                lastFeatures = features
                lastModality = modality
                val mahalanobisDistance = tier0Agent.computeMahalanobisDistance()
                if (mahalanobisDistance != null) {
                    Log.d(TAG, "Tier-0 Mahalanobis distance: $mahalanobisDistance")
                    val tier0Risk = fusionAgent.processTier0Risk(mahalanobisDistance)
                    Log.d(TAG, "Tier-0 risk: $tier0Risk")
                    var tier1Risk: Double? = null
                    if (fusionAgent.shouldRunTier1(tier0Risk)) {
                        val touchW = tier0Agent.getWindowFeaturesFor(Modality.TOUCH)
                        val typingW = tier0Agent.getWindowFeaturesFor(Modality.TYPING)
                        var touchScore: Double? = null
                        var typingScore: Double? = null
                        try { if (touchW != null) touchScore = tier1Agent.computeTier1Probability(touchW, Modality.TOUCH) } catch (_: Throwable) {}
                        try { if (typingW != null) typingScore = tier1Agent.computeTier1Probability(typingW, Modality.TYPING) } catch (_: Throwable) {}
                        val continuousTouching = (lastModality == Modality.TOUCH)
                        val weighted = mutableListOf<Pair<Double, Double>>()
                        touchScore?.let { s -> weighted.add(s to if (continuousTouching) 0.6 else 0.4) }
                        typingScore?.let { s -> weighted.add(s to 0.6) }
                        val sumW = weighted.sumOf { it.second }
                        tier1Risk = if (sumW > 0.0) weighted.sumOf { it.first * it.second } / sumW else null
                        Log.d(TAG, "Tier-1 scores: touch=${touchScore ?: -1.0}, typing=${typingScore ?: -1.0}, fused=${tier1Risk ?: -1.0}")
                        if (tier1Risk != null) fusionAgent.markTier1Run()
                    }
                    val fusedRisk = fusionAgent.fuseRisks(tier0Risk, tier1Risk)
                    val sessionRisk = if (chaquopyResult.confidence > 80f) {
                        0.5 * fusedRisk + 0.5 * chaquopyResult.riskScore.toDouble()
                    } else {
                        fusedRisk
                    }
                    Log.d(TAG, "Fused session risk (final): $sessionRisk")
                    LogFileLogger.log(TAG, "Fused session risk: $sessionRisk, tier0Ready=${tier0Agent.isBaselineReady()}, tier1Ready=${tier1Agent.isAnyModalityReady()}")
                    val policyAction = policyAgent.processSessionRisk(sessionRisk)
                    updateSecurityState(sessionRisk, policyAction)
                    if (policyAction == PolicyAction.Escalate) handleEscalation()
                } else {
                    val sessionRisk = chaquopyResult.riskScore.toDouble()
                    val policyAction = policyAgent.processSessionRisk(sessionRisk)
                    updateSecurityState(sessionRisk, policyAction)
                    if (policyAction == PolicyAction.Escalate) handleEscalation()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "processSensorData error: ${t.message}", t)
                LogFileLogger.log(TAG, "processSensorData error: ${t.message}", t)
            }
        }
    }

    fun onBiometricSuccess(): Unit {
        policyAgent.onBiometricSuccess()
        updateSecurityState()
    }

    fun onBiometricFailure(): Unit {
        policyAgent.onBiometricFailure()
        updateSecurityState()
    }

    fun reset(): Unit {
        tier0Agent.resetBaseline()
        tier1Agent.resetBaseline()
        fusionAgent.initializeSession()
        policyAgent.reset()
        updateSecurityState()
    }

    fun seedDemoBaseline(): Unit {
        repeat(120) {
            val features = DoubleArray(10) { Math.random() * 0.5 + 0.25 }
            val modality = if (it % 2 == 0) Modality.TOUCH else Modality.TYPING
            tier0Agent.addFeatures(features, modality)
            tier1Agent.addBaselineSample(features, modality)
        }
        tier1Agent.trainAllIfNeeded()
        updateSecurityState()
    }

    fun getCurrentStatus(): SecurityStatus {
        val state = _securityState.value
        return SecurityStatus(
            isMonitoring = isInitialized,
            sessionRisk = state.sessionRisk,
            riskLevel = state.riskLevel,
            isEscalated = state.isEscalated,
            trustCredits = state.trustCredits,
            tier0Ready = tier0Agent.isBaselineReady(),
            tier1Ready = tier1Agent.isAnyModalityReady()
        )
    }

    fun cleanup(): Unit {
        tier1Agent.close()
        runCatching { stateTickerJob?.cancel() }
        job.cancel()
    }

    /**
     * Receive fine-grained typing timing from UI to help calibration when a11y is not available.
     */
    fun onTypingEvent(dwellMs: Long, flightMs: Long, isSpace: Boolean) {
        tier1Agent.submitTypingTiming(dwellMs, flightMs, isSpace)
    }

    private fun updateSecurityState(
        sessionRisk: Double = _securityState.value.sessionRisk,
        policyAction: PolicyAction = PolicyAction.Monitor
    ): Unit {
        val policyState = policyAgent.getCurrentState()

        val tier0Ready = tier0Agent.isBaselineReady()
        val learningDone = tier1Agent.isCalibrated()
        val isLearning = !learningDone
        val remainingPercent = if (learningDone) 0 else (100 - tier1Agent.getCalibrationProgressPercent()).coerceIn(0, 100)

        val (touchCount, touchTarget) = tier1Agent.getTouchProgress()
        val (typingCount, typingTarget) = tier1Agent.getTypingProgress()
        val calStage = tier1Agent.getCurrentCalibrationStage().name

        val touchStats = tier0Agent.getWindowStatsFor(Modality.TOUCH)
        val typingStats = tier0Agent.getWindowStatsFor(Modality.TYPING)
        val boundsStr = formatBoundsAll(touchStats, typingStats)

        _securityState.value = SecurityState(
            sessionRisk = sessionRisk,
            riskLevel = policyState.riskLevel,
            isEscalated = policyState.isEscalated,
            trustCredits = policyState.trustCredits,
            consecutiveHighRisk = policyState.consecutiveHighRisk,
            consecutiveLowRisk = policyState.consecutiveLowRisk,
            policyAction = policyAction,
            tier0Ready = tier0Ready,
            tier1Ready = tier1Agent.isAnyModalityReady(),
            isLearning = isLearning,
            baselineProgressSec = remainingPercent,
            baselineBounds = boundsStr,
            calibrationStage = calStage,
            touchCount = touchCount,
            touchTarget = touchTarget,
            typingCount = typingCount,
            typingTarget = typingTarget
        )
    }

    private fun handleEscalation(): Unit {
        updateSecurityState()
    }

    private fun formatBoundsAll(
        touch: Pair<DoubleArray, DoubleArray>?,
        typing: Pair<DoubleArray, DoubleArray>?
    ): String {
        val tokens = ArrayList<String>(30)
        fun appendPair(pair: Pair<DoubleArray, DoubleArray>?) {
            if (pair == null) {
                repeat(10) { tokens.add("0.000|0.000") }
            } else {
                val (mean, std) = pair
                val n = kotlin.math.min(10, kotlin.math.min(mean.size, std.size))
                for (i in 0 until n) {
                    val mStr = java.lang.String.format(java.util.Locale.US, "%.3f", mean[i])
                    val sStr = java.lang.String.format(java.util.Locale.US, "%.3f", std[i].coerceAtLeast(1e-9))
                    tokens.add("$mStr|$sStr")
                }
                if (n < 10) repeat(10 - n) { tokens.add("0.000|0.000") }
            }
        }
        appendPair(touch)
        appendPair(typing)
        return tokens.joinToString(",")
    }
}

data class SecurityState(
    val sessionRisk: Double = 0.0,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val isEscalated: Boolean = false,
    val trustCredits: Int = 3,
    val consecutiveHighRisk: Int = 0,
    val consecutiveLowRisk: Int = 0,
    val policyAction: PolicyAction = PolicyAction.Monitor,
    val tier0Ready: Boolean = false,
    val tier1Ready: Boolean = false,
    // Learning / calibration UI support
    val isLearning: Boolean = true,
    // Remaining percent (0..100)
    val baselineProgressSec: Int = 100,
    // CSV of mean|std tokens for 30 entries (10 per modality)
    val baselineBounds: String = "",
    // Calibration staged guidance
    val calibrationStage: String = "TOUCH",
    val touchCount: Int = 0,
    val touchTarget: Int = 30,
    val typingCount: Int = 0,
    val typingTarget: Int = 100
)

data class SecurityStatus(
    val isMonitoring: Boolean,
    val sessionRisk: Double,
    val riskLevel: RiskLevel,
    val isEscalated: Boolean,
    val trustCredits: Int,
    val tier0Ready: Boolean,
    val tier1Ready: Boolean
)
