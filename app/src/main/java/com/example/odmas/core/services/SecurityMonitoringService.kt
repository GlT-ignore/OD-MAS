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
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var securityManager: SecurityManager
    private lateinit var motionCollector: MotionSensorCollector
    private var touchDataReceiver: BroadcastReceiver? = null
    private var typingDataReceiver: BroadcastReceiver? = null
    
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
    }
    
    override fun onCreate() {
        super.onCreate()
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
        stopMonitoring()
        serviceScope.cancel()
        unregisterReceivers()
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    private fun createNotificationChannel(): Unit {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous security monitoring"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
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
            .setSilent(true)
            .build()
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
    private fun observeSecurityState(): Unit {
        serviceScope.launch {
            try {
                securityManager.securityState.collect { securityState ->
                    // Persist current state to DataStore
                    persistSecurityState(securityState)
                    
                    // Update notification with current status
                    val statusText = when (securityState.riskLevel) {
                        com.example.odmas.core.agents.RiskLevel.LOW -> "Monitoring - All Good"
                        com.example.odmas.core.agents.RiskLevel.MEDIUM -> "Monitoring - Medium Risk"
                        com.example.odmas.core.agents.RiskLevel.HIGH -> "Monitoring - High Risk"
                        com.example.odmas.core.agents.RiskLevel.CRITICAL -> "Monitoring - Critical Risk"
                    }
                    updateNotification(statusText)
                    
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
                        features?.let { securityManager.processSensorData(it) }
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
                        val features = generateTypingFeatureVector(dwell, flight)
                        securityManager.processSensorData(features)
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
    }
    
    private fun unregisterReceivers(): Unit {
        touchDataReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        typingDataReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        touchDataReceiver = null
        typingDataReceiver = null
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
                preferences[LAST_UPDATE_KEY] = System.currentTimeMillis()
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
                        securityManager.processSensorData(featureVector)
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
            .setSilent(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
