package com.example.odmas.core.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import com.example.odmas.core.sensors.TouchSensorCollector

/**
 * Accessibility Service for intercepting touch and typing events system-wide
 * 
 * This service allows the app to monitor touch patterns and typing dynamics 
 * even when it's not in the foreground. It's essential for real behavioral security monitoring.
 */
class TouchAccessibilityService : AccessibilityService() {
    
    private lateinit var touchCollector: TouchSensorCollector
    
    // Typing pattern tracking
    private var lastKeyDownTime: Long = 0L
    private var lastKeyUpTime: Long = 0L
    private var lastKeyCode: Int = -1
    
    companion object {
        private const val TAG = "TouchAccessibilityService"
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Touch accessibility service connected")
        
        // Configure service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or 
                        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
        
        serviceInfo = info
        
        // Initialize touch collector
        touchCollector = TouchSensorCollector()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event?.let { accessibilityEvent ->
                when (accessibilityEvent.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                        Log.d(TAG, "Touch event detected: CLICK")
                        // Process touch event for behavioral analysis
                        processTouchEvent(accessibilityEvent)
                    }
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                        Log.d(TAG, "Touch event detected: LONG_CLICK")
                        processTouchEvent(accessibilityEvent)
                    }
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                        Log.d(TAG, "Touch interaction started")
                    }
                    AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                        Log.d(TAG, "Touch interaction ended")
                    }
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                        Log.d(TAG, "Text input detected")
                        processTypingEvent(accessibilityEvent)
                    }
                    AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                        if (accessibilityEvent.className?.contains("EditText") == true) {
                            Log.d(TAG, "Text field focused")
                        }
                    }
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                        Log.d(TAG, "Window state changed - new app: ${accessibilityEvent.packageName}")
                        // Generate touch event when switching apps (indicates user interaction)
                        generateSystemTouchEvent()
                    }
                    AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                        Log.d(TAG, "View selected - generating touch event")
                        generateSystemTouchEvent()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event: ${e.message}")
        }
    }
    
    /**
     * Handle key events for typing pattern analysis
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            val currentTime = System.currentTimeMillis()
            
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    lastKeyDownTime = currentTime
                    lastKeyCode = event.keyCode
                    Log.d(TAG, "Key down: ${event.keyCode}")
                }
                KeyEvent.ACTION_UP -> {
                    if (lastKeyCode == event.keyCode && lastKeyDownTime > 0) {
                        val dwellTime = currentTime - lastKeyDownTime
                        val flightTime = if (lastKeyUpTime > 0) lastKeyDownTime - lastKeyUpTime else 0L
                        
                        Log.d(TAG, "Key up: ${event.keyCode}, dwell: ${dwellTime}ms, flight: ${flightTime}ms")
                        
                        // Create typing features and send to security manager
                        sendTypingDataToSecurityManager(dwellTime, flightTime)
                        
                        lastKeyUpTime = currentTime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing key event: ${e.message}")
        }
        
        return super.onKeyEvent(event)
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Touch accessibility service interrupted")
    }
    
    private fun processTouchEvent(event: AccessibilityEvent) {
        // Extract touch information and send to security manager
        // This is a simplified version - in a real implementation you'd extract
        // more detailed touch data from the accessibility event
        
        val touchFeatures = doubleArrayOf(
            System.currentTimeMillis().toDouble() % 1000 / 1000.0, // Time-based feature
            (event.source?.hashCode() ?: 0).toDouble() % 100 / 100.0, // Source-based feature
            (event.eventTime % 1000).toDouble() / 1000.0, // Event time feature
            (event.packageName?.hashCode() ?: 0).toDouble() % 100 / 100.0, // Package feature
            Math.random(), // Random feature for demo
            Math.random(),
            Math.random(),
            Math.random(),
            Math.random(),
            Math.random()
        )
        
        // Send to security manager via broadcast or shared state
        sendTouchDataToSecurityManager(touchFeatures)
    }
    
    private fun processTypingEvent(event: AccessibilityEvent) {
        // Extract typing pattern information from text change events
        val textLength = event.text?.toString()?.length ?: 0
        val currentTime = System.currentTimeMillis()
        
        // Estimate typing speed based on text changes
        val typingFeatures = doubleArrayOf(
            textLength.toDouble() / 10.0, // Text length normalized
            (currentTime % 10000).toDouble() / 10000.0, // Time-based feature
            Math.random(), // Simulated timing variance
            Math.random(), // Simulated rhythm pattern
            Math.random(), // Additional pattern features
            Math.random(),
            Math.random(),
            Math.random(),
            Math.random(),
            Math.random()
        )
        
        sendTypingDataToSecurityManager(0L, 0L) // Placeholder for now
    }
    
    private fun sendTouchDataToSecurityManager(features: DoubleArray) {
        // Send touch data to the security manager
        Log.d(TAG, "Sending touch features to security manager: ${features.contentToString()}")
        
        val intent = Intent("com.example.odmas.TOUCH_DATA")
        intent.putExtra("features", features)
        sendBroadcast(intent)
    }
    
    private fun sendTypingDataToSecurityManager(dwellTime: Long, flightTime: Long) {
        // Send typing pattern data to the security manager
        Log.d(TAG, "Sending typing data: dwell=${dwellTime}ms, flight=${flightTime}ms")
        
        val intent = Intent("com.example.odmas.TYPING_DATA")
        intent.putExtra("dwellTime", dwellTime)
        intent.putExtra("flightTime", flightTime)
        sendBroadcast(intent)
    }
    
    /**
     * Generate touch event when system interactions are detected
     */
    private fun generateSystemTouchEvent() {
        val currentTime = System.currentTimeMillis()
        
        // Create synthetic touch features for system interactions
        val systemTouchFeatures = doubleArrayOf(
            Math.random(), // Normalized x coordinate
            Math.random(), // Normalized y coordinate
            0.5, // Average pressure
            0.8, // Average size
            (currentTime % 1000).toDouble() / 1000.0, // Time-based dwell
            Math.random() * 2.0, // Velocity
            0.1, // Low curvature for system taps
            0.1, // Low pressure variance
            0.1, // Low size variance
            1.0  // Distance (normalized)
        )
        
        Log.d(TAG, "Generated system touch event")
        sendTouchDataToSecurityManager(systemTouchFeatures)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Touch accessibility service destroyed")
    }
}
