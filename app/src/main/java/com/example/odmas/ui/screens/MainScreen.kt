package com.example.odmas.ui.screens

import android.util.Log
import androidx.compose.foundation.background
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
                    // Send touch event to sensor monitoring
                    Log.d("MainScreen", "Touch detected at: $offset")
                    sensorMonitoringViewModel?.onTouchEvent(
                        dwellTime = 150L, // Simulate tap dwell time
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
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Risk dial with explanation
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
