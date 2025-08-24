# Checkobi SDK Integration Guide

## Overview

This document provides instructions for integrating the actual Checkobi SDK into the OD-MAS application. The current implementation includes a comprehensive placeholder that simulates Checkobi SDK functionality.

## Current Implementation

### Files Created
- `app/src/main/java/com/example/odmas/core/checkobi/CheckobiSDKManager.kt` - Main SDK integration manager
- Updated `SecurityManager.kt` - Integrated Checkobi analysis
- Updated `SensorMonitoringViewModel.kt` - Real-time Checkobi monitoring

### Features Implemented
- ✅ SDK initialization and lifecycle management
- ✅ Behavioral monitoring start/stop
- ✅ Real-time behavioral analysis
- ✅ Baseline management
- ✅ Risk scoring and confidence assessment
- ✅ Anomaly detection
- ✅ Integration with existing multi-agent system

## Integration Steps

### 1. Add Checkobi Dependencies

In `app/build.gradle.kts`, uncomment and update the Checkobi dependencies:

```kotlin
// Checkobi SDK for behavioral biometrics
implementation("com.checkobi:checkobi-sdk:2.1.0") // Replace with actual version
implementation("com.checkobi:checkobi-core:2.1.0") // Replace with actual version
implementation("com.checkobi:checkobi-behavioral:2.1.0") // Replace with actual version
```

### 2. Add Repository (if needed)

If Checkobi SDK is hosted in a private repository, add it to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        // ... existing repositories
        maven { url = uri("https://checkobi.com/maven") } // Replace with actual URL
    }
}
```

### 3. Update CheckobiSDKManager.kt

Replace the TODO sections in `CheckobiSDKManager.kt` with actual SDK calls:

#### Example Initialization:
```kotlin
// Replace this:
// TODO: Replace with actual Checkobi SDK initialization

// With this:
checkobiSDK = CheckobiSDK.Builder(context)
    .setApiKey(apiKey)
    .setBehavioralModelEnabled(true)
    .setTouchDynamicsEnabled(true)
    .setMotionAnalysisEnabled(true)
    .setTypingPatternsEnabled(true)
    .setOfflineMode(true) // For privacy
    .build()

checkobiSDK.initialize()
```

#### Example Analysis:
```kotlin
// Replace this:
// TODO: Replace with actual Checkobi SDK analysis

// With this:
val analysis = checkobiSDK.analyzeCurrentBehavior()
val riskScore = analysis.getRiskScore()
val confidence = analysis.getConfidence()
val anomalies = analysis.getDetectedAnomalies()
```

### 4. Update Permissions (if needed)

Add any additional permissions required by Checkobi SDK to `AndroidManifest.xml`:

```xml
<!-- Example permissions that might be needed -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

### 5. Configure SDK Settings

Update the SDK configuration in `CheckobiSDKManager.initialize()`:

```kotlin
suspend fun initialize(apiKey: String? = null): Boolean {
    return try {
        Log.d(TAG, "Initializing Checkobi SDK...")
        
        // Actual Checkobi SDK initialization
        checkobiSDK = CheckobiSDK.Builder(context)
            .setApiKey(apiKey)
            .setBehavioralModelEnabled(true)
            .setTouchDynamicsEnabled(true)
            .setMotionAnalysisEnabled(true)
            .setTypingPatternsEnabled(true)
            .setOfflineMode(true) // For privacy
            .setAnalysisInterval(3000L) // 3 seconds
            .setConfidenceThreshold(0.8f) // 80% confidence
            .build()
        
        checkobiSDK.initialize()
        
        _isInitialized.value = true
        Log.d(TAG, "Checkobi SDK initialized successfully")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Checkobi SDK: ${e.message}")
        _isInitialized.value = false
        false
    }
}
```

## API Reference

### CheckobiSDKManager Methods

| Method | Description | Parameters | Returns |
|--------|-------------|------------|---------|
| `initialize()` | Initialize the SDK | `apiKey: String?` | `Boolean` |
| `startMonitoring()` | Start behavioral monitoring | None | `Unit` |
| `stopMonitoring()` | Stop behavioral monitoring | None | `Unit` |
| `analyzeBehavior()` | Analyze current behavior | None | `CheckobiAnalysisResult` |
| `getBaseline()` | Get behavioral baseline | None | `CheckobiBaseline` |
| `updateBaseline()` | Update behavioral baseline | `duration: Long` | `Boolean` |
| `getBehavioralInsights()` | Get detailed insights | None | `CheckobiInsights` |
| `cleanup()` | Clean up resources | None | `Unit` |

### Data Classes

#### CheckobiAnalysisResult
```kotlin
data class CheckobiAnalysisResult(
    val riskScore: Float, // 0-100%
    val confidence: Float, // 0-100%
    val isAnomalous: Boolean,
    val anomalies: List<String>,
    val touchDynamicsScore: Float,
    val motionAnalysisScore: Float,
    val typingPatternScore: Float,
    val timestamp: Long
)
```

#### CheckobiBaseline
```kotlin
data class CheckobiBaseline(
    val touchDynamicsBaseline: Map<String, Float>,
    val motionBaseline: Map<String, Float>,
    val typingBaseline: Map<String, Float>,
    val timestamp: Long
)
```

#### CheckobiInsights
```kotlin
data class CheckobiInsights(
    val overallBehavioralScore: Float,
    val touchDynamicsInsights: List<String>,
    val motionInsights: List<String>,
    val typingInsights: List<String>,
    val recommendations: List<String>
)
```

## Integration with Multi-Agent System

The Checkobi SDK is integrated with the existing multi-agent system:

### Fusion Strategy
- **High Confidence (>80%)**: Checkobi results weighted 70%, Tier-0 results 30%
- **Low Confidence (≤80%)**: Fall back to existing fusion algorithm
- **No Checkobi**: Use existing Tier-0 + Tier-1 fusion

### Risk Calculation
```kotlin
val sessionRisk = if (checkobiResult.confidence > 80f) {
    // Use Checkobi as primary, others as backup
    (checkobiResult.riskScore * 0.7 + (tier0Risk * 0.3)).toDouble()
} else {
    fusionAgent.fuseRisks(tier0Risk, tier1Risk)
}
```

## Testing

### Unit Tests
Create tests for Checkobi integration:

```kotlin
@Test
fun testCheckobiInitialization() {
    val manager = CheckobiSDKManager.getInstance(context)
    val initialized = runBlocking { manager.initialize() }
    assertTrue(initialized)
}

@Test
fun testCheckobiAnalysis() {
    val manager = CheckobiSDKManager.getInstance(context)
    runBlocking { manager.initialize() }
    
    val result = runBlocking { manager.analyzeBehavior() }
    assertNotNull(result)
    assertTrue(result.riskScore in 0f..100f)
    assertTrue(result.confidence in 0f..100f)
}
```

### Integration Tests
Test the full integration with the security system:

```kotlin
@Test
fun testCheckobiWithSecurityManager() {
    val securityManager = SecurityManager(context)
    runBlocking { securityManager.initialize() }
    
    // Simulate sensor data
    val features = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
    securityManager.processSensorData(features)
    
    // Verify Checkobi integration
    val state = securityManager.securityState.value
    assertTrue(state.sessionRisk > 0)
}
```

## Troubleshooting

### Common Issues

1. **SDK Not Found**
   - Verify dependencies are correctly added
   - Check repository URLs
   - Ensure correct version numbers

2. **Initialization Failed**
   - Check API key validity
   - Verify permissions
   - Check device compatibility

3. **Analysis Not Working**
   - Ensure monitoring is started
   - Check sensor permissions
   - Verify baseline is established

### Debug Logging

Enable debug logging to troubleshoot issues:

```kotlin
// In CheckobiSDKManager
Log.d(TAG, "Checkobi analysis: Risk=${result.riskScore}%, Confidence=${result.confidence}%")
Log.d(TAG, "Checkobi anomalies: ${result.anomalies}")
```

## Privacy Considerations

The Checkobi SDK is configured for privacy:

- **Offline Mode**: All analysis happens on-device
- **No Data Transmission**: No behavioral data leaves the device
- **Local Storage**: All data stored locally
- **User Control**: Users can delete all data

## Performance Optimization

### Recommended Settings
- **Analysis Interval**: 3 seconds (matches existing system)
- **Confidence Threshold**: 80% (reduces false positives)
- **Baseline Duration**: 2 minutes (sufficient for learning)

### Memory Management
- Clean up resources when app is backgrounded
- Monitor memory usage during analysis
- Implement proper lifecycle management

## Next Steps

1. **Get Checkobi SDK Access**: Contact Checkobi for SDK access
2. **Update Dependencies**: Replace placeholder dependencies
3. **Test Integration**: Verify all functionality works
4. **Optimize Performance**: Fine-tune settings for your use case
5. **Deploy**: Release with Checkobi integration

## Support

For Checkobi SDK support:
- Checkobi Documentation: [URL to be provided]
- Checkobi Support: [Contact information to be provided]
- Integration Issues: Check this guide and logs first
