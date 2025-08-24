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
    private val tier1Agent = Tier1AutoencoderAgent(context)
    private val fusionAgent = FusionAgent()
    private val policyAgent = PolicyAgent()
    
    // Chaquopy Python ML Integration
    private val chaquopyManager = ChaquopyBehavioralManager.getInstance(context)
    
    // State management
    private val _securityState = MutableStateFlow(SecurityState())
    val securityState: StateFlow<SecurityState> = _securityState.asStateFlow()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var isInitialized = false
    
    companion object {
        private const val PROCESSING_INTERVAL_MS = 3000L // 3-second windows
        private const val TAG = "SecurityManager"
    }
    
    /**
     * Initialize the security manager
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting security manager initialization...")
                
                // Initialize Chaquopy Python ML
                val chaquopyInitialized = chaquopyManager.initialize()
                Log.d(TAG, "Chaquopy Python ML initialization: $chaquopyInitialized")
                
                // Initialize Tier-1 autoencoder (make it optional for now)
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
                updateSecurityState()
                Log.d(TAG, "Security manager initialization completed successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization: ${e.message}", e)
                // Still initialize even if there are errors
                isInitialized = true
                updateSecurityState()
                Log.d(TAG, "Security manager initialized with fallback mode")
                true
            }
        }
    }
    
    /**
     * Process new sensor data
     * @param features Feature vector from sensors
     */
    fun processSensorData(features: DoubleArray): Unit {
        if (!isInitialized) {
            Log.w(TAG, "Security manager not initialized, ignoring sensor data")
            return
        }
        
        Log.d(TAG, "Processing sensor data: ${features.contentToString()}")
        
        coroutineScope.launch {
            // Process with Chaquopy Python ML
            val chaquopyResult = chaquopyManager.analyzeBehavior(features)
            Log.d(TAG, "Chaquopy analysis: Risk=${chaquopyResult.riskScore}%, Confidence=${chaquopyResult.confidence}%")
            
            // Add features to Tier-0 agent
            tier0Agent.addFeatures(features)
            
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
                    val windowFeatures = tier0Agent.getCurrentWindowFeatures()
                    if (windowFeatures != null) {
                        val reconstructionError = tier1Agent.computeReconstructionError(windowFeatures)
                        if (reconstructionError != null) {
                            tier1Risk = fusionAgent.processTier1Risk(reconstructionError)
                            Log.d(TAG, "Tier-1 risk: $tier1Risk")
                            fusionAgent.markTier1Run()
                        }
                    }
                }
                
                // Fuse risks (prioritize Chaquopy if available)
                val sessionRisk = if (chaquopyResult.confidence > 80f) {
                    // Use Chaquopy as primary, others as backup
                    (chaquopyResult.riskScore * 0.7 + (tier0Risk * 0.3)).toDouble()
                } else {
                    fusionAgent.fuseRisks(tier0Risk, tier1Risk)
                }
                Log.d(TAG, "Fused session risk (with Chaquopy): $sessionRisk")
                
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
        tier0Agent.resetBaseline()
        tier1Agent.resetBaseline()
        fusionAgent.initializeSession()
        policyAgent.reset()
        updateSecurityState()
    }
    
    /**
     * Seed baselines quickly for demo mode so agents show ready immediately
     */
    fun seedDemoBaseline(): Unit {
        // Generate synthetic feature vectors within typical range
        repeat(60) {
            val features = DoubleArray(10) { Math.random() * 0.5 + 0.25 }
            tier0Agent.addFeatures(features)
        }
        // Seed Tier-1 baseline with plausible mean/std
        tier1Agent.setBaselineStats(mean = 0.15, std = 0.05)
        // Update state to reflect readiness
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
            tier1Ready = tier1Agent.isBaselineReady()
        )
    }
    
    /**
     * Clean up resources
     */
    fun cleanup(): Unit {
        tier1Agent.close()
    }
    
    private fun updateSecurityState(
        sessionRisk: Double = _securityState.value.sessionRisk,
        policyAction: PolicyAction = PolicyAction.Monitor
    ): Unit {
        val policyState = policyAgent.getCurrentState()
        
        _securityState.value = SecurityState(
            sessionRisk = sessionRisk,
            riskLevel = policyState.riskLevel,
            isEscalated = policyState.isEscalated,
            trustCredits = policyState.trustCredits,
            consecutiveHighRisk = policyState.consecutiveHighRisk,
            consecutiveLowRisk = policyState.consecutiveLowRisk,
            policyAction = policyAction,
            tier0Ready = tier0Agent.isBaselineReady(),
            tier1Ready = tier1Agent.isBaselineReady()
        )
    }
    
    private fun handleEscalation(): Unit {
        // This will trigger the UI to show biometric prompt
        // The actual biometric handling is done in the UI layer
        updateSecurityState()
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
    val tier1Ready: Boolean = false
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
