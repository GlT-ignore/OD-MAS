package com.example.odmas.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.odmas.core.agents.RiskLevel
import com.example.odmas.ui.components.BiometricPromptSheet
import com.example.odmas.ui.components.RiskDial
import com.example.odmas.ui.components.StatusChip
import com.example.odmas.viewmodels.SecurityViewModel
import com.example.odmas.viewmodels.SensorMonitoringViewModel
import com.example.odmas.utils.PermissionHelper

@Composable
private fun FeatureBoundsTable(
    bounds: List<com.example.odmas.viewmodels.FeatureBound>
) {
    // Expecting up to 30 entries: Touch[0..9], Motion[10..19], Typing[20..29]
    @Composable
    fun Section(title: String, startIndex: Int) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 10) {
                val item = bounds.getOrNull(startIndex + i)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "F$i",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val text = if (item != null)
                        String.format(java.util.Locale.US, "%.3f ± %.3f", item.mean, item.std)
                    else "—"
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Touch", 0)
        Section("Motion", 10)
        Section("Typing", 20)
    }
}
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = viewModel(),
    onNavigateToSensors: () -> Unit = {},
    sensorMonitoringViewModel: SensorMonitoringViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val biometricState by viewModel.biometricPromptState.collectAsState()


    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Send touch event to sensor monitoring (for UI visualizations)
                    Log.d("MainScreen", "Touch detected at: $offset")
                    sensorMonitoringViewModel?.onTouchEvent(
                        dwellTime = 150L, // Simulate tap dwell time
                        pressure = 0.8f,
                        size = 20f,
                        velocity = 300f,
                        curvature = 0.1f
                    )
                    // Also send normalized synthetic features to security service for baseline learning
                    viewModel.sendSyntheticTouchFeatures(
                        dwellTimeMs = 150L,
                        pressure = 0.8f,
                        size = 20f,
                        velocity = 300f,
                        curvature = 0.1f
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar
        TopBar()
        
        Spacer(modifier = Modifier.height(16.dp))

        // Always show baseline card, change content based on learning state
        BaselineCard(
            isLearning = uiState.isLearning,
            progressSec = uiState.baselineProgressSec,
            bounds = uiState.baselineBounds,
            calibrationStage = uiState.calibrationStage,
            motionCount = uiState.motionCount,
            motionTarget = uiState.motionTarget,
            touchCount = uiState.touchCount,
            touchTarget = uiState.touchTarget,
            typingCount = uiState.typingCount,
            typingTarget = uiState.typingTarget,
            sendTyping = { dwell, flight, isSpace -> viewModel.sendSyntheticTypingEvent(dwell, flight, isSpace) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        // Risk dial: hidden during learning (shown after 120s)
        if (!uiState.isLearning) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RiskDial(
                    risk = uiState.securityState.sessionRisk,
                    riskLevel = uiState.securityState.riskLevel,
                    modifier = Modifier.size(200.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Risk explanation
                Text(
                    text = "Behavioral Risk Score (0-100)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                
                Text(
                    text = when {
                        uiState.securityState.sessionRisk < 30 -> "Normal behavior pattern"
                        uiState.securityState.sessionRisk < 75 -> "Slight deviation detected"
                        else -> "Unusual pattern - verify identity"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Status chip + Reset baseline side-by-side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                riskLevel = uiState.securityState.riskLevel,
                isEscalated = uiState.securityState.isEscalated
            )
            OutlinedButton(onClick = { viewModel.resetSecurity() }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset Baseline")
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Trust credits
        TrustCreditsCard(
            trustCredits = uiState.securityState.trustCredits,
            consecutiveHighRisk = uiState.securityState.consecutiveHighRisk,
            consecutiveLowRisk = uiState.securityState.consecutiveLowRisk
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Agent status
        AgentStatusCard(
            tier0Ready = uiState.securityState.tier0Ready,
            tier1Ready = uiState.securityState.tier1Ready,
            fusionReady = uiState.isInitialized && uiState.securityState.tier0Ready,
            policyReady = uiState.isInitialized
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Debug info
        DebugInfoCard(
            isInitialized = uiState.isInitialized,
            riskLevel = uiState.securityState.riskLevel,
            sessionRisk = uiState.securityState.sessionRisk,
            trustCredits = uiState.securityState.trustCredits
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Demo controls
        if (uiState.isDemoMode) {
            DemoControls(
                onToggleDemo = { viewModel.toggleDemoMode() }
            )
        } else {
            DemoModeToggle(
                onToggle = { viewModel.toggleDemoMode() }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Navigation to sensor monitoring
        NavigationButton(
            onNavigateToSensors = onNavigateToSensors
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Permissions button
        PermissionsButton()
    }
    
    
    // Biometric prompt
    biometricState?.let { state ->
        BiometricPromptSheet(
            isVisible = state.isVisible,
            reason = state.reason,
            onSuccess = { viewModel.onBiometricSuccess() },
            onFailure = { viewModel.onBiometricFailure() },
            onCancel = { viewModel.onBiometricCancelled() }
        )
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "OD-MAS",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "On-Device Multi-Agent Security",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Privacy",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "On-device · No Cloud",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BaselineCard(
    isLearning: Boolean,
    progressSec: Int,
    bounds: List<com.example.odmas.viewmodels.FeatureBound>,
    calibrationStage: String,
    motionCount: Int,
    motionTarget: Int,
    touchCount: Int,
    touchTarget: Int,
    typingCount: Int,
    typingTarget: Int,
    sendTyping: (Long, Long, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLearning)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLearning) "Learning Baseline" else "Baseline Established",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                if (isLearning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLearning) {
                val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                var typedText by remember { mutableStateOf("") }

                // Header by stage
                val (title, instruction, counter) = when (calibrationStage) {
                    "MOTION" -> Triple(
                        "Step 1/3 • Motion",
                        "Hold the device naturally and keep steady to capture your stationary profile.",
                        "Motion: $motionCount / $motionTarget windows"
                    )
                    "TOUCH" -> Triple(
                        "Step 2/3 • Touch",
                        "Tap randomly anywhere on the screen about $touchTarget times.",
                        "Touch: $touchCount / $touchTarget taps"
                    )
                    "TYPING" -> Triple(
                        "Step 3/3 • Typing",
                        "Type in the field below using your keyboard. Include a few spaces.",
                        "Typing: $typingCount / $typingTarget keys"
                    )
                    else -> Triple(
                        "Calibration",
                        "Establishing normal behavior patterns...",
                        ""
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = instruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (counter.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Calibration coverage progress (remaining percent)
                LinearProgressIndicator(
                    progress = { 1f - (progressSec.coerceAtLeast(0) / 100f) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Calibration remaining: ${progressSec.coerceAtLeast(0)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Typing stage: force focus and show keyboard
                if (calibrationStage == "TYPING") {
                    Spacer(Modifier.height(12.dp))
                    var lastChangeTs by remember { mutableStateOf(0L) }
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { newVal ->
                            val now = System.currentTimeMillis()
                            val prevLen = typedText.length
                            typedText = newVal
                            val added = (newVal.length - prevLen).coerceAtLeast(0)
                            val flight = if (lastChangeTs > 0L) now - lastChangeTs else 0L
                            if (added > 0) {
                                val isSpace = newVal.lastOrNull() == ' '
                                repeat(added) {
                                    sendTyping(80L, flight, isSpace)
                                }
                            }
                            lastChangeTs = now
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .focusRequester(focusRequester),
                        label = { Text("Type here to complete typing calibration") },
                        singleLine = false
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }

                if (bounds.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Running feature bounds (mean ± σ)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Reuse the labeled list to show human-readable feature names
                    FeatureBoundsTable(bounds = bounds)
                }
            } else {
                if (bounds.isNotEmpty()) {
                    Text(
                        text = "Running feature bounds (mean ± σ)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Show top 6 features with human-readable labels
                    FeatureBoundsTable(bounds = bounds)

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Normal behavior learned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = "Baseline not available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrustCreditsCard(
    trustCredits: Int,
    consecutiveHighRisk: Int,
    consecutiveLowRisk: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Trust Credits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$trustCredits/3",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (trustCredits > 0) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.error
                    )
                }
                
                Column {
                    Text(
                        text = "High Risk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = consecutiveHighRisk.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (consecutiveHighRisk > 0) MaterialTheme.colorScheme.error 
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Column {
                    Text(
                        text = "Low Risk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = consecutiveLowRisk.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (consecutiveLowRisk > 0) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentStatusCard(
    tier0Ready: Boolean,
    tier1Ready: Boolean,
    fusionReady: Boolean,
    policyReady: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Agent Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // All agents in single row to save screen space
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AgentStatusItem(
                    name = "T0",
                    description = "Stats",
                    isReady = tier0Ready
                )

                AgentStatusItem(
                    name = "T1",
                    description = "Neural",
                    isReady = tier1Ready
                )

                AgentStatusItem(
                    name = "Fusion",
                    description = "Combine",
                    isReady = fusionReady
                )

                AgentStatusItem(
                    name = "Policy",
                    description = "Control",
                    isReady = policyReady
                )
            }
        }
    }
}

@Composable
private fun AgentStatusItem(
    name: String,
    description: String,
    isReady: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (isReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isReady) Icons.Default.Check else Icons.Default.Info,
                contentDescription = if (isReady) "Ready" else "Initializing",
                tint = if (isReady) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun DemoModeToggle(
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Enable Demo Mode")
    }
}

@Composable
private fun DemoControls(
    onToggleDemo: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Removed Reset Baseline here to avoid duplicate control.
        OutlinedButton(
            onClick = onToggleDemo,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Disable Demo Mode")
        }
    }
}

@Composable
private fun DebugInfoCard(
    isInitialized: Boolean,
    riskLevel: RiskLevel,
    sessionRisk: Double,
    trustCredits: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "🔧 Debug Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Initialized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (isInitialized) "✅ Yes" else "❌ No",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                
                Column {
                    Text(
                        text = "Risk Level",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = riskLevel.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                
                Column {
                    Text(
                        text = "Session Risk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "%.1f".format(sessionRisk),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                
                Column {
                    Text(
                        text = "Credits",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "$trustCredits/3",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationButton(
    onNavigateToSensors: () -> Unit
) {
    OutlinedButton(
        onClick = onNavigateToSensors,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Icon(
            imageVector = Icons.Default.MonitorHeart,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Open Sensor Monitoring")
    }
}

@Composable
private fun PermissionsButton() {
    val context = LocalContext.current
    val missingPermissions = PermissionHelper.getMissingPermissions(context)
    
    Log.d("PermissionsButton", "Missing permissions: $missingPermissions")
    
    if (missingPermissions.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                Log.d("PermissionsButton", "Button clicked, missing: $missingPermissions")
                try {
                    if (missingPermissions.contains("Usage Stats Access")) {
                        Log.d("PermissionsButton", "Opening Usage Stats settings...")
                        PermissionHelper.requestUsageStatsPermission(context as android.app.Activity)
                    } else if (missingPermissions.contains("Display over other apps")) {
                        Log.d("PermissionsButton", "Opening System Alert Window settings...")
                        PermissionHelper.requestSystemAlertWindowPermission(context as android.app.Activity)
                    } else if (missingPermissions.contains("Accessibility Service")) {
                        Log.d("PermissionsButton", "Opening Accessibility settings...")
                        PermissionHelper.requestAccessibilityServicePermission(context as android.app.Activity)
                    }
                } catch (e: Exception) {
                    Log.e("PermissionsButton", "Error opening settings: ${e.message}")
                    // Fallback: open general app settings
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = "package:${context.packageName}".toUri()
                    context.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Special Permissions (${missingPermissions.size})")
        }
    }
}
