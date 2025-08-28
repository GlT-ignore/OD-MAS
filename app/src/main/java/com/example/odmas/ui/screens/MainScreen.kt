package com.example.odmas.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.odmas.core.agents.RiskLevel
import com.example.odmas.ui.components.BiometricPromptSheet
import com.example.odmas.ui.components.RiskDial
import com.example.odmas.ui.components.StatusChip
import com.example.odmas.viewmodels.SecurityViewModel
import com.example.odmas.viewmodels.SecurityUIState
import com.example.odmas.viewmodels.SensorMonitoringViewModel
import com.example.odmas.utils.PermissionHelper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.style.TextAlign

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = viewModel(),
    onNavigateToSensors: () -> Unit = {},
    sensorMonitoringViewModel: SensorMonitoringViewModel? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val biometricState by viewModel.biometricPromptState.collectAsState()
    var showCalibrationPromptSheet by remember { mutableStateOf(false) }
    
    // Make screen scrollable to ensure all controls are reachable on small screens
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val scrollState = rememberScrollState()
        Column(modifier = Modifier.verticalScroll(scrollState)) {
        // Top bar
        TopBar()
        
        // Heads-up permission banner (visible until all granted)
        PermissionBanner()

        Spacer(modifier = Modifier.height(32.dp))
        
        // Risk dial with explanation
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                RiskDial(
                    risk = uiState.securityState.sessionRisk,
                    riskLevel = uiState.securityState.riskLevel,
                    modifier = Modifier.size(200.dp)
                )
            }
            
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

            // Spacer left intentionally; controls appear in a row below
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
        
        // Calibration progress (motion temporarily disabled)
        CalibrationCard(
            isLearning = uiState.securityState.isLearning,
            progressPercentRemaining = uiState.securityState.baselineProgressSec,
            stage = uiState.securityState.calibrationStage,
            motion = 0 to 0,
            touch = uiState.securityState.touchCount to uiState.securityState.touchTarget,
            typing = uiState.securityState.typingCount to uiState.securityState.typingTarget,
            onOpenPrompts = { showCalibrationPromptSheet = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

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
        
        Spacer(modifier = Modifier.height(16.dp))
        
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
                onReset = { viewModel.resetSecurity() },
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

    // Guided calibration sheet
    val ctx = LocalContext.current
    CalibrationGuidedSheet(
        isVisible = showCalibrationPromptSheet && uiState.securityState.isLearning,
        stage = uiState.securityState.calibrationStage,
        motion = 0 to 0,
        touch = uiState.securityState.touchCount to uiState.securityState.touchTarget,
        typing = uiState.securityState.typingCount to uiState.securityState.typingTarget,
        onDismiss = { showCalibrationPromptSheet = false },
        onOpenA11y = { PermissionHelper.requestAccessibilityServicePermission(ctx as android.app.Activity) },
        onOpenUsageStats = { PermissionHelper.requestUsageStatsPermission(ctx as android.app.Activity) },
        onOpenTextField = { onNavigateToSensors() }
    )
}
@Composable
private fun CalibrationCard(
    isLearning: Boolean,
    progressPercentRemaining: Int,
    stage: String,
    motion: Pair<Int, Int>,
    touch: Pair<Int, Int>,
    typing: Pair<Int, Int>,
    onOpenPrompts: () -> Unit
) {
    if (!isLearning) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            var elapsedSec by remember { mutableStateOf(0) }
            LaunchedEffect(isLearning) {
                elapsedSec = 0
                while (isLearning) {
                    kotlinx.coroutines.delay(1000)
                    elapsedSec += 1
                }
            }
            Text(
                text = "Calibration in progress",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = (100 - progressPercentRemaining).coerceIn(0, 100) / 100f,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val donePercent = (100 - progressPercentRemaining).coerceIn(0, 100)
                Text(
                    text = "Progress: $donePercent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val mm = (elapsedSec / 60)
                val ss = (elapsedSec % 60)
                val timeStr = "%02d:%02d".format(mm, ss)
                Text(
                    text = "Elapsed: $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Stage: $stage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Motion", style = MaterialTheme.typography.labelSmall)
                    Text("${motion.first}/${motion.second}")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Touch", style = MaterialTheme.typography.labelSmall)
                    Text("${touch.first}/${touch.second}")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Typing", style = MaterialTheme.typography.labelSmall)
                    Text("${typing.first}/${typing.second}")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenPrompts,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Calibration Guide") }
        }
    }
}

// Guided bottom sheet mirroring the modified app’s staged prompts
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationGuidedSheet(
    isVisible: Boolean,
    stage: String,
    motion: Pair<Int, Int>,
    touch: Pair<Int, Int>,
    typing: Pair<Int, Int>,
    onDismiss: () -> Unit,
    onOpenA11y: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenTextField: () -> Unit
) {
    if (!isVisible) return
    val context = LocalContext.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { false } // prevent dismiss by outside taps while calibrating
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Calibration Guide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            when (stage) {
                "TOUCH" -> {
                    Text("Tap anywhere on the screen ~30 times at your normal pace.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Progress: ${touch.first}/${touch.second}")
                    Spacer(modifier = Modifier.height(8.dp))
                    // Built-in tap area so taps count without leaving this sheet
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    // Send a calibrated touch vector via the same broadcast as a11y
                                    val dwellMs = 150L
                                    val now = System.currentTimeMillis()
                                    val pSeed = (now % 17).toInt()
                                    val sSeed = ((now / 3) % 19).toInt()
                                    val pressure = (0.55 + 0.20 * ((pSeed % 11) / 10.0 - 0.5)).coerceIn(0.3, 0.9)
                                    val size = (0.65 + 0.20 * ((sSeed % 9) / 8.0 - 0.5)).coerceIn(0.4, 0.95)
                                    val dwellSec = (dwellMs.toDouble() / 1000.0).coerceIn(0.0, 2.0)
                                    val velocity = (0.2 + (120.0 / dwellMs.coerceAtLeast(60).toDouble())).coerceIn(0.1, 1.0)
                                    val curvature = 0.1
                                    val pressureVar = (0.01 + 0.02 * ((pSeed % 7) / 6.0)).coerceIn(0.0, 0.1)
                                    val sizeVar = (0.01 + 0.02 * ((sSeed % 5) / 4.0)).coerceIn(0.0, 0.1)
                                    val distance = (velocity * dwellSec).coerceIn(0.0, 1.0)
                                    val features = doubleArrayOf(
                                        0.5, 0.5, pressure, size, dwellSec, velocity, curvature, pressureVar, sizeVar, distance
                                    )
                                    try {
                                        val intent = android.content.Intent("com.example.odmas.TOUCH_DATA").setPackage(context.packageName)
                                        intent.putExtra("features", features)
                                        context.sendBroadcast(intent)
                                    } catch (_: Exception) {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tap here to register touches", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                "TYPING" -> {
                    Text("Type a sentence in any text field (e.g. Sensors screen). Space keys count words.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Progress: ${typing.first}/${typing.second}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenTextField) { Text("Open Text Field") }
                    }
                }
                else -> {
                    // Treat any other stage as touch first when motion is disabled
                    Text("Tap anywhere on the screen ~30 times at your normal pace.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Progress: ${touch.first}/${touch.second}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    val dwellMs = 150L
                                    val now = System.currentTimeMillis()
                                    val pSeed = (now % 17).toInt()
                                    val sSeed = ((now / 3) % 19).toInt()
                                    val pressure = (0.55 + 0.20 * ((pSeed % 11) / 10.0 - 0.5)).coerceIn(0.3, 0.9)
                                    val size = (0.65 + 0.20 * ((sSeed % 9) / 8.0 - 0.5)).coerceIn(0.4, 0.95)
                                    val dwellSec = (dwellMs.toDouble() / 1000.0).coerceIn(0.0, 2.0)
                                    val velocity = (0.2 + (120.0 / dwellMs.coerceAtLeast(60).toDouble())).coerceIn(0.1, 1.0)
                                    val curvature = 0.1
                                    val pressureVar = (0.01 + 0.02 * ((pSeed % 7) / 6.0)).coerceIn(0.0, 0.1)
                                    val sizeVar = (0.01 + 0.02 * ((sSeed % 5) / 4.0)).coerceIn(0.0, 0.1)
                                    val distance = (velocity * dwellSec).coerceIn(0.0, 1.0)
                                    val features = doubleArrayOf(
                                        0.5, 0.5, pressure, size, dwellSec, velocity, curvature, pressureVar, sizeVar, distance
                                    )
                                    try {
                                        val intent = android.content.Intent("com.example.odmas.TOUCH_DATA").setPackage(context.packageName)
                                        intent.putExtra("features", features)
                                        context.sendBroadcast(intent)
                                    } catch (_: Exception) {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Tap here to register touches", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("If nothing updates, ensure permissions are granted:", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenA11y) { Text("Enable Accessibility") }
                OutlinedButton(onClick = onOpenUsageStats) { Text("Enable Usage Stats") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
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
private fun PermissionBanner() {
    val context = LocalContext.current
    val missing = remember { PermissionHelper.getMissingPermissions(context) }
    if (missing.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Permissions needed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = missing.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Start
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { PermissionHelper.requestAccessibilityServicePermission(context as android.app.Activity) } catch (_: Exception) {}
                }) { Text("Enable Accessibility") }
                OutlinedButton(onClick = {
                    try { PermissionHelper.requestUsageStatsPermission(context as android.app.Activity) } catch (_: Exception) {}
                }) { Text("Enable Usage Stats") }
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
                    isReady = tier0Ready,
                    isCompact = true
                )
                
                AgentStatusItem(
                    name = "T1", 
                    description = "Neural",
                    isReady = tier1Ready,
                    isCompact = true
                )
                
                AgentStatusItem(
                    name = "Fusion",
                    description = "Combine",
                    isReady = fusionReady,
                    isCompact = true
                )
                
                AgentStatusItem(
                    name = "Policy", 
                    description = "Control",
                    isReady = policyReady,
                    isCompact = true
                )
            }
        }
    }
}

@Composable
private fun AgentStatusItem(
    name: String,
    description: String,
    isReady: Boolean,
    isCompact: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(if (isCompact) 28.dp else 40.dp)
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
                modifier = Modifier.size(if (isCompact) 14.dp else 20.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(if (isCompact) 2.dp else 4.dp))
        
        Text(
            text = name,
            style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
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
    onReset: () -> Unit,
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
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
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
