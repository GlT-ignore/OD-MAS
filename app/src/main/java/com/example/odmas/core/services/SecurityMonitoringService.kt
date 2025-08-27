package com.example.odmas.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.odmas.MainActivity
import com.example.odmas.R
import com.example.odmas.core.SecurityManager
import com.example.odmas.core.sensors.MotionSensorCollector
import androidx.datastore.preferences.core.*
import com.example.odmas.core.data.securityDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.example.odmas.utils.LogFileLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import com.example.odmas.core.Modality

/**
 * Foreground service for continuous security monitoring
 * 
 * Responsibilities:
 * - Run continuous sensor monitoring
 * - Process security events in background
 * - Maintain notification for Android 14+ compliance
 */
// Use shared singleton definition to avoid duplicate instances

class SecurityMonitoringService : LifecycleService() {
    
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        LogFileLogger.log(TAG, "Coroutine error: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + crashHandler)
    private lateinit var securityManager: SecurityManager
    private lateinit var motionCollector: MotionSensorCollector
    private var touchDataReceiver: BroadcastReceiver? = null
    private var typingDataReceiver: BroadcastReceiver? = null
    private var commandReceiver: BroadcastReceiver? = null
    private var wasLearning: Boolean? = null
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "security_monitoring"
        private const val CHANNEL_NAME = "Security Monitoring"
        private const val TAG = "SecurityMonitoringService"
        
        // DataStore keys for persisting security state
        private val SESSION_RISK_KEY = doublePreferencesKey("session_risk")
        private val RISK_LEVEL_KEY = stringPreferencesKey("risk_level")
        private val IS_ESCALATED_KEY = booleanPreferencesKey("is_escalated")
        private val TRUST_CREDITS_KEY = intPreferencesKey("trust_credits")
        private val LAST_UPDATE_KEY = longPreferencesKey("last_update")
        private val IS_LEARNING_KEY = booleanPreferencesKey("is_learning")
        private val BASELINE_PROGRESS_KEY = intPreferencesKey("baseline_progress")
        private val BASELINE_BOUNDS_KEY = stringPreferencesKey("baseline_bounds")
        // Added: persist agent readiness so UI can reflect live status
        private val TIER0_READY_KEY = booleanPreferencesKey("tier0_ready")
        private val TIER1_READY_KEY = booleanPreferencesKey("tier1_ready")
        // Calibration staged UI keys
        private val CAL_STAGE_KEY = stringPreferencesKey("cal_stage")
        private val CAL_MOTION_COUNT_KEY = intPreferencesKey("cal_motion_count")
        private val CAL_MOTION_TARGET_KEY = intPreferencesKey("cal_motion_target")
        private val CAL_TOUCH_COUNT_KEY = intPreferencesKey("cal_touch_count")
        private val CAL_TOUCH_TARGET_KEY = intPreferencesKey("cal_touch_target")
        private val CAL_TYPING_COUNT_KEY = intPreferencesKey("cal_typing_count")
        private val CAL_TYPING_TARGET_KEY = intPreferencesKey("cal_typing_target")

        // Overlay control actions for Accessibility overlay
        private const val ACTION_SHOW_OVERLAY = "com.example.odmas.SHOW_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.example.odmas.HIDE_OVERLAY"
    }
    
    override fun onCreate() {
        super.onCreate()
        LogFileLogger.init(this)
        LogFileLogger.log(TAG, "SecurityMonitoringService created")
        createNotificationChannel()
        initializeSecurity()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start monitoring
        startMonitoring()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        LogFileLogger.log(TAG, "Service onDestroy")
        stopMonitoring()
        serviceScope.cancel()
        unregisterReceivers()
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    private fun createNotificationChannel(): Unit {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Continuous security monitoring"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        } else {
            // Update allowed fields without deleting the channel (required for FG services)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                existing.importance
            ).apply {
                description = "Continuous security monitoring"
                setShowBadge(false)
            }
            // Re-register updates (safe no-op for immutable fields like importance)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OD-MAS Active")
            .setContentText("Monitoring device security")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showCalibrationStageNotification(stage: String, state: com.example.odmas.core.SecurityState) {
        val (title, text) = when (stage) {
            "MOTION" -> "Calibration: Motion" to "Step 1/3 • Hold the device naturally and keep steady (${state.motionCount}/${state.motionTarget})"
            "TOUCH" -> "Calibration: Touch" to "Step 2/3 • Tap randomly ~${state.touchTarget} times anywhere on screen (${state.touchCount}/${state.touchTarget})"
            "TYPING" -> "Calibration: Typing" to "Step 3/3 • Type in the app text field to reach ~${state.typingTarget} keys (${state.typingCount}/${state.typingTarget})"
            else -> "Calibration" to "In progress"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID + 2, notification)
    }
    
    private fun initializeSecurity(): Unit {
        securityManager = SecurityManager(this)
        motionCollector = MotionSensorCollector(this)
        
        serviceScope.launch {
            val initialized = securityManager.initialize()
            if (initialized) {
                motionCollector.startMonitoring()
                observeSecurityState()
                registerReceivers()
            }
        }
    }
    
    /**
     * Observe security state and persist to DataStore for UI consumption
     */
    private var lastCalStage: String? = null

    private var prevEscalatedFlag: Boolean = false

    private fun observeSecurityState(): Unit {
        serviceScope.launch {
            try {
                securityManager.securityState.collect { securityState ->
                    // Persist current state to DataStore
                    persistSecurityState(securityState)
                    
                    // Update notification with current status
                    val statusText = if (securityState.isLearning) {
                        val pct = (100 - securityState.baselineProgressSec).coerceIn(0, 100)
                        "Calibration ${pct}% · ${securityState.calibrationStage} " +
                                "(M ${securityState.motionCount}/${securityState.motionTarget}, " +
                                "T ${securityState.touchCount}/${securityState.touchTarget}, " +
                                "K ${securityState.typingCount}/${securityState.typingTarget})"
                    } else {
                        when (securityState.riskLevel) {
                            com.example.odmas.core.agents.RiskLevel.LOW -> "Monitoring - All Good"
                            com.example.odmas.core.agents.RiskLevel.MEDIUM -> "Monitoring - Medium Risk"
                            com.example.odmas.core.agents.RiskLevel.HIGH -> "Monitoring - High Risk"
                            com.example.odmas.core.agents.RiskLevel.CRITICAL -> "Monitoring - Critical Risk"
                        }
                    }
                    try {
                        updateNotification(statusText)
                    } catch (e: Exception) {
                        LogFileLogger.log(TAG, "updateNotification failed: ${e.message}", e)
                    }

                    // Control accessibility overlay on escalation transitions
                    if (securityState.isEscalated && !prevEscalatedFlag) {
                        val intent = Intent(ACTION_SHOW_OVERLAY)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                    } else if (!securityState.isEscalated && prevEscalatedFlag) {
                        val intent = Intent(ACTION_HIDE_OVERLAY)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                    }
                    prevEscalatedFlag = securityState.isEscalated

                    // Fire guidance per stage transition
                    if (securityState.isLearning) {
                        val stage = securityState.calibrationStage
                        if (lastCalStage == null || lastCalStage != stage) {
                            try {
                                showCalibrationStageNotification(stage, securityState)
                            } catch (e: Exception) {
                                LogFileLogger.log(TAG, "showCalibrationStageNotification failed: ${e.message}", e)
                            }
                            lastCalStage = stage
                        }
                    }

                    // Fire one-shot notification when learning completes
                    if (wasLearning == null) {
                        wasLearning = securityState.isLearning
                    } else {
                        if (wasLearning == true && !securityState.isLearning) {
                            LogFileLogger.log(TAG, "Baseline learning completed: posting ready notification")
                            try {
                                showBaselineReadyNotification()
                            } catch (e: Exception) {
                                LogFileLogger.log(TAG, "showBaselineReadyNotification failed: ${e.message}", e)
                            }
                        }
                        wasLearning = securityState.isLearning
                    }
                    
                    android.util.Log.d(TAG, "Security state persisted: risk=${securityState.sessionRisk}, level=${securityState.riskLevel}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error observing security state: ${e.message}")
            }
        }
    }
    
    /**
     * Register broadcast receivers for touch and typing data coming from the accessibility service
     */
    private fun registerReceivers(): Unit {
        if (touchDataReceiver == null) {
            touchDataReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.example.odmas.TOUCH_DATA") {
                        val features: DoubleArray? = intent.getDoubleArrayExtra("features")
                        features?.let {
                            // Feed analytics pipeline
                            securityManager.processSensorData(it, Modality.TOUCH)
                            // Forward reliably to UI (separate action to avoid receiver loop in service)
                            val uiIntent = Intent("com.example.odmas.TOUCH_DATA_UI").setPackage(packageName)
                            uiIntent.putExtra("features", it)
                            sendBroadcast(uiIntent)
                        }
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    touchDataReceiver,
                    IntentFilter("com.example.odmas.TOUCH_DATA"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(touchDataReceiver, IntentFilter("com.example.odmas.TOUCH_DATA"))
            }
        }
        if (typingDataReceiver == null) {
            typingDataReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.example.odmas.TYPING_DATA") {
                        val dwell: Long = intent.getLongExtra("dwellTime", 0L)
                        val flight: Long = intent.getLongExtra("flightTime", 0L)
                        val isSpace: Boolean = intent.getBooleanExtra("isSpace", false)
                        android.util.Log.d(TAG, "TYPING_DATA rx dwell=${dwell} flight=${flight} isSpace=$isSpace")
                        val features = generateTypingFeatureVector(dwell, flight)
                        // Feed Tier-0 with 10D vector
                        securityManager.processSensorData(features, Modality.TYPING)
                        // Feed Tier-1 typing calibration with finer-grained timing info
                        securityManager.onTypingEvent(dwell, flight, isSpace)
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    typingDataReceiver,
                    IntentFilter("com.example.odmas.TYPING_DATA"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(typingDataReceiver, IntentFilter("com.example.odmas.TYPING_DATA"))
            }
        }
        if (commandReceiver == null) {
            commandReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.example.odmas.SECURITY_COMMAND") {
                        when (intent.getStringExtra("command")) {
                            "BIOMETRIC_SUCCESS" -> securityManager.onBiometricSuccess()
                            "BIOMETRIC_FAILURE" -> securityManager.onBiometricFailure()
                            "RESET" -> securityManager.reset()
                            "ENABLE_DEMO" -> securityManager.enableDemoMode()
                            "DISABLE_DEMO" -> securityManager.disableDemoMode()
                            // Backward compatibility with older UI
                            "SEED_DEMO" -> securityManager.enableDemoMode()
                        }
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    commandReceiver,
                    IntentFilter("com.example.odmas.SECURITY_COMMAND"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(commandReceiver, IntentFilter("com.example.odmas.SECURITY_COMMAND"))
            }
        }
    }
    
    private fun unregisterReceivers(): Unit {
        touchDataReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        typingDataReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        commandReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        touchDataReceiver = null
        typingDataReceiver = null
        commandReceiver = null
    }
    
    /**
     * Map typing timings into a 10-dim feature vector compatible with Tier-0/Tier-1
     */
    private fun generateTypingFeatureVector(dwellTimeMs: Long, flightTimeMs: Long): DoubleArray {
        val dwellSec: Double = (dwellTimeMs.coerceAtLeast(0L).toDouble() / 1000.0).coerceIn(0.0, 1.5)
        val flightSec: Double = (flightTimeMs.coerceAtLeast(0L).toDouble() / 1000.0).coerceIn(0.0, 1.5)
        val speed: Double = if (flightSec > 0) 1.0 / flightSec else 0.0 // keys per second
        val rhythmVariance: Double = (Math.random() * 0.05)
        val pressureMean: Double = 0.6
        val sizeMean: Double = 0.7
        val curvature: Double = 0.05
        val pressureVar: Double = 0.05
        val sizeVar: Double = 0.05
        val distance: Double = 0.3
        return doubleArrayOf(
            dwellSec,            // 0
            flightSec,           // 1
            pressureMean,        // 2
            sizeMean,            // 3
            speed,               // 4
            curvature,           // 5
            pressureVar,         // 6
            sizeVar,             // 7
            rhythmVariance,      // 8
            distance             // 9
        )
    }

    /**
     * Persist security state to DataStore
     */
    private suspend fun persistSecurityState(securityState: com.example.odmas.core.SecurityState): Unit {
        try {
            applicationContext.securityDataStore.edit { preferences ->
                preferences[SESSION_RISK_KEY] = securityState.sessionRisk
                preferences[RISK_LEVEL_KEY] = securityState.riskLevel.name
                preferences[IS_ESCALATED_KEY] = securityState.isEscalated
                preferences[TRUST_CREDITS_KEY] = securityState.trustCredits
                // Added readiness flags so UI AgentStatus can update
                preferences[TIER0_READY_KEY] = securityState.tier0Ready
                preferences[TIER1_READY_KEY] = securityState.tier1Ready
                preferences[LAST_UPDATE_KEY] = System.currentTimeMillis()
                preferences[IS_LEARNING_KEY] = securityState.isLearning
                preferences[BASELINE_PROGRESS_KEY] = securityState.baselineProgressSec
                preferences[BASELINE_BOUNDS_KEY] = securityState.baselineBounds
                // Calibration staged UI fields
                preferences[CAL_STAGE_KEY] = securityState.calibrationStage
                preferences[CAL_MOTION_COUNT_KEY] = securityState.motionCount
                preferences[CAL_MOTION_TARGET_KEY] = securityState.motionTarget
                preferences[CAL_TOUCH_COUNT_KEY] = securityState.touchCount
                preferences[CAL_TOUCH_TARGET_KEY] = securityState.touchTarget
                preferences[CAL_TYPING_COUNT_KEY] = securityState.typingCount
                preferences[CAL_TYPING_TARGET_KEY] = securityState.typingTarget
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error persisting security state: ${e.message}")
        }
    }
    
    private fun startMonitoring(): Unit {
        serviceScope.launch {
            // Monitor motion sensor data
            motionCollector.motionFeatures.collect { motionFeatures ->
                motionFeatures?.let { features ->
                    val featureVector = motionCollector.getFeatureVector()
                    if (featureVector != null) {
                        securityManager.processSensorData(featureVector, Modality.MOTION)
                    }
                }
            }
        }
    }
    
    private fun stopMonitoring(): Unit {
        motionCollector.stopMonitoring()
        securityManager.cleanup()
    }
    
    /**
     * Update notification with current security status
     */
    fun updateNotification(status: String): Unit {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OD-MAS Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showBaselineReadyNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Baseline ready")
                .setContentText("Behavioral baseline learning complete")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            LogFileLogger.log(TAG, "showBaselineReadyNotification error: ${e.message}", e)
        }
    }
}
