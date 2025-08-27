package com.example.odmas.viewmodels

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.odmas.core.SecurityState
import com.example.odmas.core.agents.RiskLevel
import androidx.datastore.preferences.core.*
import com.example.odmas.core.data.securityDataStore
import com.example.odmas.core.services.SecurityMonitoringService
import com.example.odmas.core.sensors.TouchSensorCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Use shared singleton DataStore

/**
 * Security ViewModel: Observes service state via DataStore and forwards UI events to service
 */
class SecurityViewModel(application: Application) : AndroidViewModel(application) {

    private val touchCollector = TouchSensorCollector()

    private val _uiState = MutableStateFlow(SecurityUIState())
    val uiState: StateFlow<SecurityUIState> = _uiState.asStateFlow()

    private val _biometricPromptState = MutableStateFlow<BiometricPromptState?>(null)
    val biometricPromptState: StateFlow<BiometricPromptState?> = _biometricPromptState.asStateFlow()

    companion object {
        private const val TAG = "SecurityViewModel"

        // DataStore keys (mirror service keys)
        private val SESSION_RISK_KEY = doublePreferencesKey("session_risk")
        private val RISK_LEVEL_KEY = stringPreferencesKey("risk_level")
        private val IS_ESCALATED_KEY = booleanPreferencesKey("is_escalated")
        private val TRUST_CREDITS_KEY = intPreferencesKey("trust_credits")
        private val LAST_UPDATE_KEY = longPreferencesKey("last_update")
        private val IS_LEARNING_KEY = booleanPreferencesKey("is_learning")
        private val BASELINE_PROGRESS_KEY = intPreferencesKey("baseline_progress")
        private val BASELINE_BOUNDS_KEY = stringPreferencesKey("baseline_bounds")
        // Added: readiness flags to mirror service
        private val TIER0_READY_KEY = booleanPreferencesKey("tier0_ready")
        private val TIER1_READY_KEY = booleanPreferencesKey("tier1_ready")
        // Calibration staged keys
        private val CAL_STAGE_KEY = stringPreferencesKey("cal_stage")
        private val CAL_MOTION_COUNT_KEY = intPreferencesKey("cal_motion_count")
        private val CAL_MOTION_TARGET_KEY = intPreferencesKey("cal_motion_target")
        private val CAL_TOUCH_COUNT_KEY = intPreferencesKey("cal_touch_count")
        private val CAL_TOUCH_TARGET_KEY = intPreferencesKey("cal_touch_target")
        private val CAL_TYPING_COUNT_KEY = intPreferencesKey("cal_typing_count")
        private val CAL_TYPING_TARGET_KEY = intPreferencesKey("cal_typing_target")
 
        private const val ACTION_TOUCH = "com.example.odmas.TOUCH_DATA"
        private const val ACTION_TYPING = "com.example.odmas.TYPING_DATA"
        private const val ACTION_COMMAND = "com.example.odmas.SECURITY_COMMAND"
    }

    init {
        observeSecurityStateFromDataStore()
        startBackgroundService()
    }

    private fun observeSecurityStateFromDataStore() {
        viewModelScope.launch {
            getApplication<Application>().securityDataStore.data.collectLatest { preferences ->
                val lastUpdate = preferences[LAST_UPDATE_KEY] ?: 0L
                val sessionRisk = preferences[SESSION_RISK_KEY] ?: 0.0
                val riskLevelName = preferences[RISK_LEVEL_KEY] ?: RiskLevel.LOW.name
                val isEscalated = preferences[IS_ESCALATED_KEY] ?: false
                val trustCredits = preferences[TRUST_CREDITS_KEY] ?: 3
                // Added: read readiness flags for Agent status
                val tier0Ready = preferences[TIER0_READY_KEY] ?: false
                val tier1Ready = preferences[TIER1_READY_KEY] ?: false
 
                val riskLevel = runCatching { RiskLevel.valueOf(riskLevelName) }.getOrElse { RiskLevel.LOW }
 
                val restoredState = SecurityState(
                    sessionRisk = sessionRisk,
                    riskLevel = riskLevel,
                    isEscalated = isEscalated,
                    trustCredits = trustCredits,
                    tier0Ready = tier0Ready,
                    tier1Ready = tier1Ready
                )
 
                val isLearning = preferences[IS_LEARNING_KEY] ?: true
                val baselineProgress = preferences[BASELINE_PROGRESS_KEY] ?: 120
                val boundsStr = preferences[BASELINE_BOUNDS_KEY] ?: ""
                val parsedBounds = parseBounds(boundsStr)

                // Calibration staged fields
                val calStage = preferences[CAL_STAGE_KEY] ?: "MOTION"
                val motionCount = preferences[CAL_MOTION_COUNT_KEY] ?: 0
                val motionTarget = preferences[CAL_MOTION_TARGET_KEY] ?: 4
                val touchCount = preferences[CAL_TOUCH_COUNT_KEY] ?: 0
                val touchTarget = preferences[CAL_TOUCH_TARGET_KEY] ?: 30
                val typingCount = preferences[CAL_TYPING_COUNT_KEY] ?: 0
                val typingTarget = preferences[CAL_TYPING_TARGET_KEY] ?: 100

                val prevEscalated = _uiState.value.securityState.isEscalated

                // Update UI state atomically with new learning/bounds info
                _uiState.value = _uiState.value.copy(
                    isInitialized = true,
                    securityState = restoredState,
                    isLearning = isLearning,
                    baselineProgressSec = baselineProgress,
                    baselineBounds = parsedBounds,
                    calibrationStage = calStage,
                    motionCount = motionCount,
                    motionTarget = motionTarget,
                    touchCount = touchCount,
                    touchTarget = touchTarget,
                    typingCount = typingCount,
                    typingTarget = typingTarget
                )

                if (isEscalated && !prevEscalated) {
                    showBiometricPrompt()
                } else if (!isEscalated && prevEscalated) {
                    hideBiometricPrompt()
                }
            }
        }
    }

    /**
     * Process touch event from UI and forward to service
     */
    fun processTouchEvent(event: android.view.MotionEvent, viewWidth: Int, viewHeight: Int) {
        Log.d(TAG, "Processing touch event: action=${event.action}, x=${event.x}, y=${event.y}")
        touchCollector.processTouchEvent(event, viewWidth, viewHeight)

        val touchFeatures = touchCollector.getFeatureVector()
        if (touchFeatures != null) {
            Log.d(TAG, "Touch features extracted: ${touchFeatures.contentToString()}")
            sendTouchFeaturesToService(touchFeatures)
            touchCollector.clearFeatures()
        }
    }

    private fun sendTouchFeaturesToService(features: DoubleArray) {
        val intent = Intent(ACTION_TOUCH).setPackage(getApplication<Application>().packageName)
        intent.putExtra("features", features)
        getApplication<Application>().sendBroadcast(intent)
    }

    fun onBiometricSuccess() {
        sendCommandToService("BIOMETRIC_SUCCESS")
        hideBiometricPrompt()
    }

    fun onBiometricFailure() {
        sendCommandToService("BIOMETRIC_FAILURE")
        // Keep prompt visible for retry
    }

    fun onBiometricCancelled() {
        sendCommandToService("BIOMETRIC_FAILURE")
        hideBiometricPrompt()
    }

    fun resetSecurity() {
        Log.d(TAG, "Resetting security baseline")
        sendCommandToService("RESET")
        updateUIState()
    }

    fun toggleDemoMode() {
        val currentState = _uiState.value
        val newDemoMode = !currentState.isDemoMode
        Log.d(TAG, "Toggling demo mode: $newDemoMode")
        _uiState.value = currentState.copy(isDemoMode = newDemoMode)

        if (newDemoMode) {
            Log.d(TAG, "Demo mode enabled - starting demo simulation")
            sendCommandToService("ENABLE_DEMO")
        } else {
            Log.d(TAG, "Demo mode disabled - stopping demo simulation")
            sendCommandToService("DISABLE_DEMO")
        }
    }

    private fun simulateSensorData() {
        viewModelScope.launch {
            val simulatedTouchFeatures = doubleArrayOf(0.5, 0.3, 0.8, 0.2, 0.6, 0.4, 0.7, 0.1, 0.9, 0.5)
            val simulatedMotionFeatures = doubleArrayOf(0.8, 0.6, 0.4, 0.7, 0.3, 0.9, 0.2, 0.5, 0.8, 0.1)

            Log.d(TAG, "Sending simulated touch features: ${simulatedTouchFeatures.contentToString()}")
            sendTouchFeaturesToService(simulatedTouchFeatures)

            delay(1000)

            Log.d(TAG, "Sending simulated motion features: ${simulatedMotionFeatures.contentToString()}")
            sendTouchFeaturesToService(simulatedMotionFeatures)
        }
    }
 
    /**
     * Send a synthetic touch feature vector to the background service.
     * Used by Compose UI where MotionEvent isn't available.
     */
    fun sendSyntheticTouchFeatures(
        dwellTimeMs: Long,
        pressure: Float,
        size: Float,
        velocity: Float,
        curvature: Float
    ) {
        // Normalize and construct a 10-dim feature vector consistent with Tier-0/Tier-1
        val dwellSec = (dwellTimeMs.coerceAtLeast(0L).toDouble() / 1000.0).coerceIn(0.0, 2.0)
        val pNorm = pressure.toDouble().coerceIn(0.0, 1.0)
        // Heuristic normalization for display-size based 'size' and 'velocity'
        val sizeNorm = (size.toDouble() / 100.0).coerceIn(0.0, 1.0)
        val velNorm = (velocity.toDouble() / 2000.0).coerceIn(0.0, 1.0)
        val curvNorm = curvature.toDouble().coerceIn(0.0, 1.0)
        val distance = (velNorm * dwellSec).coerceIn(0.0, 1.0)
        // X/Y unknown in Compose tap without MotionEvent; center them
        val xNorm = 0.5
        val yNorm = 0.5
        // Small variances during learning
        val pressureVar = 0.02
        val sizeVar = 0.02
        val features = doubleArrayOf(
            xNorm,          // 0
            yNorm,          // 1
            pNorm,          // 2
            sizeNorm,       // 3
            dwellSec,       // 4
            velNorm,        // 5
            curvNorm,       // 6
            pressureVar,    // 7
            sizeVar,        // 8
            distance        // 9
        )
        sendTouchFeaturesToService(features)
    }

    // Synthetic typing event (fallback for in-app typing field) to ensure calibration progresses
    fun sendSyntheticTypingEvent(dwellMs: Long, flightMs: Long, isSpace: Boolean) {
        val intent = Intent(ACTION_TYPING).setPackage(getApplication<Application>().packageName)
        intent.putExtra("dwellTime", dwellMs)
        intent.putExtra("flightTime", flightMs)
        intent.putExtra("isSpace", isSpace)
        getApplication<Application>().sendBroadcast(intent)
    }
 
     fun deleteLocalData() {
         sendCommandToService("RESET")
         updateUIState()
     }

    private fun sendCommandToService(command: String) {
        val intent = Intent(ACTION_COMMAND).setPackage(getApplication<Application>().packageName)
        intent.putExtra("command", command)
        getApplication<Application>().sendBroadcast(intent)
    }

    private fun showBiometricPrompt() {
        val currentState = _uiState.value
        _biometricPromptState.value = BiometricPromptState(
            isVisible = true,
            reason = getBiometricReason(currentState.securityState.riskLevel)
        )
    }

    private fun hideBiometricPrompt() {
        _biometricPromptState.value = null
    }

    private fun getBiometricReason(riskLevel: RiskLevel): String {
        return when (riskLevel) {
            RiskLevel.CRITICAL -> "Critical security risk detected"
            RiskLevel.HIGH -> "Unusual behavior detected"
            RiskLevel.MEDIUM -> "Behavioral anomaly detected"
            RiskLevel.LOW -> "Security verification required"
        }
    }

    private fun parseBounds(bounds: String): List<FeatureBound> {
        if (bounds.isBlank()) return emptyList()
        val items = bounds.split(",")
        val list = ArrayList<FeatureBound>(items.size)
        items.forEachIndexed { index, token ->
            val parts = token.split("|")
            if (parts.size == 2) {
                val mean = parts[0].toDoubleOrNull()
                val std = parts[1].toDoubleOrNull()
                if (mean != null && std != null) {
                    list.add(FeatureBound(index = index, mean = mean, std = std))
                }
            }
        }
        return list
    }

    private fun updateUIState(
        isInitialized: Boolean = _uiState.value.isInitialized,
        securityState: SecurityState = _uiState.value.securityState
    ) {
        val keepDemoMode = _uiState.value.isDemoMode
        _uiState.value = SecurityUIState(
            isInitialized = isInitialized,
            securityState = securityState.copy(
                trustCredits = if (securityState.trustCredits == 0 && !isInitialized) 3 else securityState.trustCredits
            ),
            isDemoMode = keepDemoMode
        )
    }

    private fun startBackgroundService() {
        Log.d(TAG, "Starting background security service")
        val serviceIntent = Intent(getApplication(), SecurityMonitoringService::class.java)
        getApplication<Application>().startForegroundService(serviceIntent)
    }

    override fun onCleared() {
        super.onCleared()
        // Keep background service running; no direct collectors to stop here
    }
}

/**
 * UI state for security interface
 */
data class SecurityUIState(
    val isInitialized: Boolean = false,
    val securityState: SecurityState = SecurityState(),
    val isDemoMode: Boolean = false,
    // Learning + bounds for UI
    val isLearning: Boolean = true,
    val baselineProgressSec: Int = 120,
    val baselineBounds: List<FeatureBound> = emptyList(),
    // Calibration staged UI
    val calibrationStage: String = "MOTION",
    val motionCount: Int = 0,
    val motionTarget: Int = 4,
    val touchCount: Int = 0,
    val touchTarget: Int = 30,
    val typingCount: Int = 0,
    val typingTarget: Int = 100
)

data class BiometricPromptState(
    val isVisible: Boolean,
    val reason: String
)

data class FeatureBound(
    val index: Int,
    val mean: Double,
    val std: Double
)

