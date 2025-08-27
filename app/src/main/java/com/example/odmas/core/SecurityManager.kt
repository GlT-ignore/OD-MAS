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

/**
 * Security Manager: Orchestrates all agents for behavioral anomaly detection
 * 
 * Responsibilities:
 * - Coordinate Tier-0 and Tier-1 agents
 * - Manage fusion and policy decisions
 * - Handle biometric verification
 * - Maintain session state
 */
class SecurityManager(private val context: Context) {
    
    // Agents
    private val tier0Agent = Tier0StatsAgent()
    // New Tier‑1 behavioral agent (per-spec models for Motion/Touch/Typing)
    private val tier1Agent = Tier1BehaviorAgent(context)
    private val fusionAgent = FusionAgent()
    private val policyAgent = PolicyAgent()
    
    // Chaquopy Python ML Integration
    private val chaquopyManager = ChaquopyBehavioralManager.getInstance(context)
    
    // State management
    private val _securityState = MutableStateFlow(SecurityState())
    val securityState: StateFlow<SecurityState> = _securityState.asStateFlow()
    
    private val job = kotlinx.coroutines.SupervisorJob()
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        LogFileLogger.log(TAG, "Coroutine error: ${t.message}", t)
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Default + job + crashHandler)
    private var isInitialized = false
    private var learningStartTime: Long = 0L
    // Keep last feature vector seen to allow 3s risk updates even before Tier-0 window is ready
    private var lastFeatures: DoubleArray? = null
    private var lastModality: com.example.odmas.core.Modality? = null
    // Periodic ticker to push UI updates (learning countdown, running stats) even without sensor events
    private var stateTickerJob: kotlinx.coroutines.Job? = null
    // Periodic window processor to compute risk every 3 seconds
    private var windowProcessorJob: kotlinx.coroutines.Job? = null
    // Demo mode synthetic feeder
    private var demoModeJob: kotlinx.coroutines.Job? = null
    private var isDemoMode: Boolean = false
    
    companion object {
        private const val PROCESSING_INTERVAL_MS = 3000L // 3-second windows
        private const val LEARNING_DURATION_MS = 120000L // 2 minutes baseline learning
        private const val TAG = "SecurityManager"
    }
    
    /**
     * Initialize the security manager
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting security manager initialization...")
                LogFileLogger.log(TAG, "Initialization started")
                
                // Initialize Chaquopy Python ML
                val chaquopyInitialized = chaquopyManager.initialize()
                Log.d(TAG, "Chaquopy Python ML initialization: $chaquopyInitialized")
                
                // Initialize Tier-1 behavioral agent
                val tier1Initialized = tier1Agent.initializeModel()
                Log.d(TAG, "Tier-1 agent initialization: $tier1Initialized")
                // Don't fail if Tier-1 fails - we can still work with Tier-0
                
                // Initialize fusion agent
                fusionAgent.initializeSession()
                Log.d(TAG, "Fusion agent initialized")
                
                // Reset policy agent
                policyAgent.reset()
                Log.d(TAG, "Policy agent reset")
                
                // Start Chaquopy monitoring
                if (chaquopyInitialized) {
                    chaquopyManager.startMonitoring()
                    Log.d(TAG, "Chaquopy monitoring started")
                }
                
                isInitialized = true
                learningStartTime = System.currentTimeMillis()
                LogFileLogger.log(TAG, "Learning window started at $learningStartTime")
                updateSecurityState()
                // Start ticker to update learning countdown and running stats every second
                stateTickerJob?.cancel()
                stateTickerJob = coroutineScope.launch {
                    while (isInitialized) {
                        try {
                            updateSecurityState()
                        } catch (t: Throwable) {
                            Log.e(TAG, "State ticker error: ${t.message}", t)
                            LogFileLogger.log(TAG, "State ticker error: ${t.message}", t)
                        }
                        kotlinx.coroutines.delay(1000L)
                    }
                }
                // Start periodic processing every 3 seconds to update risk meter and drive biometrics
                windowProcessorJob?.cancel()
                windowProcessorJob = coroutineScope.launch {
                    while (isInitialized) {
                        try {
                            processWindowAndUpdateRisk()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Window processor error: ${t.message}", t)
                            LogFileLogger.log(TAG, "Window processor error: ${t.message}", t)
                        }
                        kotlinx.coroutines.delay(PROCESSING_INTERVAL_MS)
                    }
                }
                Log.d(TAG, "Security manager initialization completed successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization: ${e.message}", e)
                // Fallback init: continue with learning window and periodic processors
                isInitialized = true
                learningStartTime = System.currentTimeMillis()
                LogFileLogger.log(TAG, "Fallback init: starting learning window at $learningStartTime")
                updateSecurityState()

                // Try to start Chaquopy monitoring if possible
                runCatching { chaquopyManager.startMonitoring() }

                // Start ticker to update learning countdown and running stats every second
                stateTickerJob?.cancel()
                stateTickerJob = coroutineScope.launch {
                    while (isInitialized) {
                        try {
                            updateSecurityState()
                        } catch (t: Throwable) {
                            Log.e(TAG, "State ticker error: ${t.message}", t)
                            LogFileLogger.log(TAG, "State ticker error: ${t.message}", t)
                        }
                        kotlinx.coroutines.delay(1000L)
                    }
                }
                // Start periodic processing every 3 seconds to update risk meter and drive biometrics
                windowProcessorJob?.cancel()
                windowProcessorJob = coroutineScope.launch {
                    while (isInitialized) {
                        try {
                            processWindowAndUpdateRisk()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Window processor error: ${t.message}", t)
                            LogFileLogger.log(TAG, "Window processor error: ${t.message}", t)
                        }
                        kotlinx.coroutines.delay(PROCESSING_INTERVAL_MS)
                    }
                }
                Log.d(TAG, "Security manager initialized with fallback mode")
                true
            }
        }
    }
    
    /**
     * Process new sensor data with modality hint
     * @param features Feature vector from sensors
     * @param modality Modality (TOUCH, MOTION, TYPING, UNKNOWN)
     */
    fun processSensorData(features: DoubleArray, modality: com.example.odmas.core.Modality): Unit {
        if (!isInitialized) {
            Log.w(TAG, "Security manager not initialized, ignoring sensor data")
            return
        }

        Log.d(TAG, "Processing sensor data: ${features.contentToString()} (modality=$modality)")

        coroutineScope.launch {
            try {
                // Learning window gating: collect features but force risk=0 until learning ends
                // New calibration gating: require minimal coverage per modality, not fixed time
                // Calibration is complete only when all modalities are ready (MOTION, TOUCH, TYPING)
                val learningDone = tier1Agent.isCalibrated()
                if (!learningDone) {
                    // Keep baselines filling but do not compute or escalate risk
                    tier0Agent.addFeatures(features, modality)
                    tier1Agent.submitCalibrationSample(features, modality)
                    lastFeatures = features
                    lastModality = modality
                    updateSecurityState(0.0, PolicyAction.Monitor)
                    return@launch
                }

                // Process with Chaquopy Python ML
                val chaquopyResult = chaquopyManager.analyzeBehavior(features)
                Log.d(TAG, "Chaquopy analysis: Risk=${chaquopyResult.riskScore}%, Confidence=${chaquopyResult.confidence}%")
                LogFileLogger.log(TAG, "Chaquopy risk=${chaquopyResult.riskScore}, conf=${chaquopyResult.confidence}")

                // Add features to Tier-0 agent
                tier0Agent.addFeatures(features, modality)
                // Cache last feature vector and modality for periodic risk when Tier-0 window is not yet ready
                lastFeatures = features
                lastModality = modality

                // Process Tier-0 risk
                val mahalanobisDistance = tier0Agent.computeMahalanobisDistance()
                if (mahalanobisDistance != null) {
                    Log.d(TAG, "Tier-0 Mahalanobis distance: $mahalanobisDistance")
                    val tier0Risk = fusionAgent.processTier0Risk(mahalanobisDistance)
                    Log.d(TAG, "Tier-0 risk: $tier0Risk")

                    // Check if Tier-1 should run
                    var tier1Risk: Double? = null
                    if (fusionAgent.shouldRunTier1(tier0Risk)) {
                        Log.d(TAG, "Running Tier-1 agent")
                        // Compute per‑modality Tier‑1 probabilities and fuse (motion lower weight when stationary)
                        val touchW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.TOUCH)
                        val motionW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.MOTION)
                        val typingW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.TYPING)

                        var motionScore: Double? = null
                        var touchScore: Double? = null
                        var typingScore: Double? = null

                        try { if (motionW != null) motionScore = tier1Agent.computeTier1Probability(motionW, com.example.odmas.core.Modality.MOTION) } catch (_: Throwable) {}
                        try { if (touchW != null) touchScore = tier1Agent.computeTier1Probability(touchW, com.example.odmas.core.Modality.TOUCH) } catch (_: Throwable) {}
                        try { if (typingW != null) typingScore = tier1Agent.computeTier1Probability(typingW, com.example.odmas.core.Modality.TYPING) } catch (_: Throwable) {}

                        // Context approximation
                        val isStationary = motionW?.getOrNull(0)?.let { kotlin.math.abs(it - 9.8) < 0.8 } ?: false
                        val continuousTouching = (lastModality == com.example.odmas.core.Modality.TOUCH)

                        val weighted = mutableListOf<Pair<Double, Double>>() // score, weight
                        motionScore?.let { s -> weighted.add(s to if (isStationary) 0.2 else 0.1) }
                        touchScore?.let { s -> weighted.add(s to if (continuousTouching) 0.5 else 0.3) }
                        typingScore?.let { s -> weighted.add(s to 0.7) }

                        val sumW = weighted.sumOf { it.second }
                        tier1Risk = if (sumW > 0.0) weighted.sumOf { it.first * it.second } / sumW else null

                        // Detailed Tier‑1 logging
                        Log.d(TAG, "Tier-1 per-modality scores (on event): motion=${motionScore ?: -1.0}, touch=${touchScore ?: -1.0}, typing=${typingScore ?: -1.0}, fused=${tier1Risk ?: -1.0}")

                        if (tier1Risk != null) fusionAgent.markTier1Run()
                    }

                    // Fuse risks to a unified 0–100 scale
                    val fusedRisk = fusionAgent.fuseRisks(tier0Risk, tier1Risk)
                    val sessionRisk = if (chaquopyResult.confidence > 80f) {
                        // Blend Chaquopy (0–100) with fused (0–100) to avoid unit mismatch
                        0.5 * fusedRisk + 0.5 * chaquopyResult.riskScore.toDouble()
                    } else {
                        fusedRisk
                    }
                    Log.d(TAG, "Fused session risk (final): $sessionRisk")
                    LogFileLogger.log(TAG, "Fused session risk: $sessionRisk, tier0Ready=${tier0Agent.isBaselineReady()}, tier1Ready=${tier1Agent.isAnyModalityReady()}")

                    // Process policy
                    val policyAction = policyAgent.processSessionRisk(sessionRisk)
                    Log.d(TAG, "Policy action: $policyAction")

                    // Update state
                    updateSecurityState(sessionRisk, policyAction)

                    // Handle escalation
                    if (policyAction == PolicyAction.Escalate) {
                        Log.d(TAG, "Handling escalation")
                        handleEscalation()
                    }
                } else {
                    Log.d(TAG, "Tier-0 baseline not ready yet")
                    // Fallback: drive session risk using Chaquopy while Tier-0 baseline/window isn't ready
                    val sessionRisk = chaquopyResult.riskScore.toDouble()
                    val policyAction = policyAgent.processSessionRisk(sessionRisk)
                    updateSecurityState(sessionRisk, policyAction)
                    if (policyAction == PolicyAction.Escalate) {
                        handleEscalation()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "processSensorData error: ${t.message}", t)
                LogFileLogger.log(TAG, "processSensorData error: ${t.message}", t)
            }
        }
    }
    
    /**
     * Handle biometric verification success
     */
    fun onBiometricSuccess(): Unit {
        policyAgent.onBiometricSuccess()
        updateSecurityState()
    }
    
    /**
     * Handle biometric verification failure
     */
    fun onBiometricFailure(): Unit {
        policyAgent.onBiometricFailure()
        updateSecurityState()
    }
    
    /**
     * Reset security state (for new session or user)
     */
    fun reset(): Unit {
        // Stop demo feeder if active
        isDemoMode = false
        runCatching { demoModeJob?.cancel() }
        demoModeJob = null

        tier0Agent.resetBaseline()
        tier1Agent.resetBaseline()
        fusionAgent.initializeSession()
        policyAgent.reset()
        learningStartTime = System.currentTimeMillis()
        updateSecurityState()
    }
    
    /**
     * Seed baselines quickly for demo mode so agents show ready immediately
     */
    fun seedDemoBaseline(): Unit {
        // Generate synthetic feature vectors and feed Tier‑1 baseline per modality
        repeat(120) {
            val features = DoubleArray(10) { Math.random() * 0.5 + 0.25 }
            val modality = when (it % 3) {
                0 -> com.example.odmas.core.Modality.TOUCH
                1 -> com.example.odmas.core.Modality.MOTION
                else -> com.example.odmas.core.Modality.TYPING
            }
            tier0Agent.addFeatures(features, modality)
            tier1Agent.addBaselineSample(features, modality)
        }
        // Finalize models if thresholds met
        tier1Agent.trainAllIfNeeded()
        updateSecurityState()
    }
    
    /**
     * Get current security status
     */
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
    
    /**
     * Clean up resources
     */
    fun cleanup(): Unit {
        tier1Agent.close()
        // Stop periodic jobs
        runCatching { stateTickerJob?.cancel() }
        stateTickerJob = null
        runCatching { windowProcessorJob?.cancel() }
        windowProcessorJob = null
        runCatching { demoModeJob?.cancel() }
        demoModeJob = null
        isDemoMode = false
        job.cancel()
    }
    
    private fun updateSecurityState(
        sessionRisk: Double = _securityState.value.sessionRisk,
        policyAction: PolicyAction = PolicyAction.Monitor
    ): Unit {
        val policyState = policyAgent.getCurrentState()

        // New learning: coverage-based calibration (not fixed time)
        val tier0Ready = tier0Agent.isBaselineReady()
        // Learning continues until all 3 modalities complete calibration
        val learningDone = tier1Agent.isCalibrated()
        val isLearning = !learningDone
        // Use baselineProgressSec as remaining percent 0..100 for UI progress bar.
        val remainingSec = if (learningDone) 0 else (100 - tier1Agent.getCalibrationProgressPercent()).coerceIn(0, 100)

        // Per‑modality calibration counters and stage
        val (motionCount, motionTarget) = tier1Agent.getMotionProgress()
        val (touchCount, touchTarget) = tier1Agent.getTouchProgress()
        val (typingCount, typingTarget) = tier1Agent.getTypingProgress()
        val calStage = tier1Agent.getCurrentCalibrationStage().name

        // Show live window stats (mean/std) for UI table across modalities (Touch, Motion, Typing)
        val touchStats = tier0Agent.getWindowStatsFor(com.example.odmas.core.Modality.TOUCH)
        val motionStats = tier0Agent.getWindowStatsFor(com.example.odmas.core.Modality.MOTION)
        val typingStats = tier0Agent.getWindowStatsFor(com.example.odmas.core.Modality.TYPING)
        val boundsStr = formatBoundsAll(touchStats, motionStats, typingStats)
        
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
            baselineProgressSec = remainingSec,
            baselineBounds = boundsStr,
            calibrationStage = calStage,
            motionCount = motionCount,
            motionTarget = motionTarget,
            touchCount = touchCount,
            touchTarget = touchTarget,
            typingCount = typingCount,
            typingTarget = typingTarget
        )
    }

    private fun formatBounds(mean: DoubleArray, std: DoubleArray): String {
        val n = kotlin.math.min(mean.size, std.size)
        val parts = ArrayList<String>(n)
        for (i in 0 until n) {
            val mStr = java.lang.String.format(java.util.Locale.US, "%.3f", mean[i])
            val sStr = java.lang.String.format(java.util.Locale.US, "%.3f", std[i].coerceAtLeast(1e-9))
            parts.add("$mStr|$sStr")
        }
        return parts.joinToString(",")
    }

    /**
     * Serialize 30 entries (10 per modality) as CSV "mean|std" tokens in order:
     * Touch[0..9], Motion[0..9], Typing[0..9]. Missing modalities are filled with 0.000|0.000.
     */
    private fun formatBoundsAll(
        touch: Pair<DoubleArray, DoubleArray>?,
        motion: Pair<DoubleArray, DoubleArray>?,
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
                // pad if less than 10
                if (n < 10) repeat(10 - n) { tokens.add("0.000|0.000") }
            }
        }
        appendPair(touch)
        appendPair(motion)
        appendPair(typing)
        return tokens.joinToString(",")
    }
    
    private fun handleEscalation(): Unit {
        // This will trigger the UI to show biometric prompt
        // The actual biometric handling is done in the UI layer
        updateSecurityState()
    }

    /**
     * Periodically compute risk from current buffers and update state.
     * Runs every PROCESSING_INTERVAL_MS (~3s).
     */
    private suspend fun processWindowAndUpdateRisk() {
        try {
            // Enforce calibration coverage: update progress but keep risk at 0 until ready
            val learningDone = tier1Agent.isCalibrated()
            if (!learningDone) {
                updateSecurityState(0.0, PolicyAction.Monitor)
                return
            }

            // Process Tier-0 risk if baseline/window available
            val mahalanobisDistance = tier0Agent.computeMahalanobisDistance()
            if (mahalanobisDistance != null) {
                val tier0Risk = fusionAgent.processTier0Risk(mahalanobisDistance)

                // Optionally run Tier-1 periodically or when Tier-0 is high
                var tier1Risk: Double? = null
                if (fusionAgent.shouldRunTier1(tier0Risk)) {
                    // Compute per-modality Tier‑1 probabilities and fuse (motion lower weight when stationary)
                    val touchW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.TOUCH)
                    val motionW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.MOTION)
                    val typingW = tier0Agent.getWindowFeaturesFor(com.example.odmas.core.Modality.TYPING)

                    var motionScore: Double? = null
                    var touchScore: Double? = null
                    var typingScore: Double? = null

                    try { if (motionW != null) motionScore = tier1Agent.computeTier1Probability(motionW, com.example.odmas.core.Modality.MOTION) } catch (_: Throwable) {}
                    try { if (touchW != null) touchScore = tier1Agent.computeTier1Probability(touchW, com.example.odmas.core.Modality.TOUCH) } catch (_: Throwable) {}
                    try { if (typingW != null) typingScore = tier1Agent.computeTier1Probability(typingW, com.example.odmas.core.Modality.TYPING) } catch (_: Throwable) {}

                    // Context approximation
                    val isStationary = motionW?.getOrNull(0)?.let { kotlin.math.abs(it - 9.8) < 0.8 } ?: false
                    val continuousTouching = (lastModality == com.example.odmas.core.Modality.TOUCH)

                    val weighted = mutableListOf<Pair<Double, Double>>() // score, weight
                    motionScore?.let { s -> weighted.add(s to if (isStationary) 0.2 else 0.1) }
                    touchScore?.let { s -> weighted.add(s to if (continuousTouching) 0.5 else 0.3) }
                    typingScore?.let { s -> weighted.add(s to 0.7) }

                    val sumW = weighted.sumOf { it.second }
                    tier1Risk = if (sumW > 0.0) weighted.sumOf { it.first * it.second } / sumW else null

                    // Detailed Tier‑1 logging
                    Log.d(TAG, "Tier-1 per-modality scores (periodic): motion=${motionScore ?: -1.0}, touch=${touchScore ?: -1.0}, typing=${typingScore ?: -1.0}, fused=${tier1Risk ?: -1.0}")

                    if (tier1Risk != null) fusionAgent.markTier1Run()
                }

                // Fuse risks (0–100) and update policy/state
                val fusedRisk = fusionAgent.fuseRisks(tier0Risk, tier1Risk)
                val policyAction = policyAgent.processSessionRisk(fusedRisk)
                updateSecurityState(fusedRisk, policyAction)

                if (policyAction == PolicyAction.Escalate) {
                    handleEscalation()
                }
            } else {
                // No Tier-0 window yet; derive a temporary session risk from available features via Chaquopy
                val windowFeatures = tier0Agent.getCurrentWindowFeatures()
                val featuresForNow = windowFeatures ?: lastFeatures
                if (featuresForNow != null) {
                    val chaquopyResult = chaquopyManager.analyzeBehavior(featuresForNow)
                    val sessionRisk = chaquopyResult.riskScore.toDouble()
                    val policyAction = policyAgent.processSessionRisk(sessionRisk)
                    updateSecurityState(sessionRisk, policyAction)
                    if (policyAction == PolicyAction.Escalate) {
                        handleEscalation()
                    }
                } else {
                    // Still nudge UI state to keep timer/bounds fresh
                    updateSecurityState()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "processWindowAndUpdateRisk error: ${t.message}", t)
            LogFileLogger.log(TAG, "processWindowAndUpdateRisk error: ${t.message}", t)
        }
    }

    /**
     * Enable demo mode: start synthetic feature streaming to drive risk updates.
     * Seeds baseline if not ready to ensure Tier-0 window becomes usable.
     */
    fun enableDemoMode(): Unit {
        isDemoMode = true
        demoModeJob?.cancel()

        if (!tier0Agent.isBaselineReady()) {
            // Seed a plausible baseline quickly
            seedDemoBaseline()
        }

        demoModeJob = coroutineScope.launch {
            var t = 0.0
            while (isDemoMode && isInitialized) {
                // Generate smooth but varied synthetic features
                val features = generateDemoFeatures(t)
                processSensorData(features)
                t += 0.25
                kotlinx.coroutines.delay(500L)
            }
        }
        updateSecurityState()
    }

    /**
     * Disable demo mode and stop synthetic streaming.
     */
    fun disableDemoMode(): Unit {
        isDemoMode = false
        runCatching { demoModeJob?.cancel() }
        demoModeJob = null
        updateSecurityState()
    }

    /**
     * Create a plausible 10D feature vector around learned baseline with noise.
     */
    private fun generateDemoFeatures(t: Double): DoubleArray {
        // Base around 0.4 ± 0.2 with some correlated oscillations
        fun w(i: Int, phase: Double = 0.0): Double {
            val base = 0.4 + 0.15 * kotlin.math.sin(t + i * 0.3 + phase)
            val noise = (Math.random() - 0.5) * 0.1
            return (base + noise).coerceIn(0.0, 1.0)
        }
        return doubleArrayOf(
            w(0),        // x
            w(1, 0.2),   // y
            w(2),        // pressure
            w(3, -0.1),  // size
            w(4),        // dwell
            w(5, 0.4),   // velocity
            w(6, -0.3),  // curvature
            w(7) * 0.1,  // pressure var (small)
            w(8) * 0.1,  // size var (small)
            w(9)         // distance
        )
    }
    /**
     * Backward-compatible overload without modality (treated as UNKNOWN).
     */
    fun processSensorData(features: DoubleArray): Unit = processSensorData(features, com.example.odmas.core.Modality.UNKNOWN)

    /**
     * Feed fine-grained typing event timing for Tier‑1 calibration/scoring.
     */
    fun onTypingEvent(dwellMs: Long, flightMs: Long, isSpace: Boolean) {
        tier1Agent.submitTypingTiming(dwellMs, flightMs, isSpace)
    }
}

/**
 * Security state data class
 */
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
    // Learning UI support
    val isLearning: Boolean = true,
    // We reuse this as remaining percent (0..100) for calibration
    val baselineProgressSec: Int = 120,
    // CSV of "mean|std" per feature (10 entries): e.g., "0.123|0.045,..."
    val baselineBounds: String = "",
    // Calibration staged guidance
    val calibrationStage: String = "MOTION",
    val motionCount: Int = 0,
    val motionTarget: Int = 4,
    val touchCount: Int = 0,
    val touchTarget: Int = 30,
    val typingCount: Int = 0,
    val typingTarget: Int = 100
)

/**
 * Security status for external components
 */
data class SecurityStatus(
    val isMonitoring: Boolean,
    val sessionRisk: Double,
    val riskLevel: RiskLevel,
    val isEscalated: Boolean,
    val trustCredits: Int,
    val tier0Ready: Boolean,
    val tier1Ready: Boolean
)
