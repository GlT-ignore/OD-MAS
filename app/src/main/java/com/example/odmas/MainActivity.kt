package com.example.odmas

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.example.odmas.utils.LogFileLogger
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.odmas.ui.theme.ODMASTheme
import com.example.odmas.ui.screens.MainScreen
import com.example.odmas.ui.screens.PermissionGateScreen
import com.example.odmas.ui.screens.SensorMonitoringScreen
import com.example.odmas.utils.PermissionHelper
import com.example.odmas.viewmodels.SecurityViewModel
import com.example.odmas.viewmodels.SensorMonitoringViewModel

class MainActivity : FragmentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All runtime permissions granted")
        } else {
            Log.w(TAG, "Some permissions denied: ${permissions.filter { !it.value }.keys}")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize file logger and global crash handler for post-mortem logs
        LogFileLogger.init(applicationContext)
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                LogFileLogger.log("Uncaught", "Thread ${thread.name}: ${ex.message}", ex)
            } catch (_: Throwable) { }
        }

        enableEdgeToEdge()
        
        setContent {
            ODMASTheme {
                var allPermissionsGranted by remember { mutableStateOf(false) }
                
                // Check initial permissions state
                LaunchedEffect(Unit) {
                    val missingPermissions = PermissionHelper.getMissingPermissions(this@MainActivity)
                    allPermissionsGranted = missingPermissions.isEmpty()
                    Log.d(TAG, "Initial permission check - missing: $missingPermissions")
                    
                    // Request runtime permissions first
                    if (!PermissionHelper.hasAllPermissions(this@MainActivity)) {
                        PermissionHelper.requestPermissions(this@MainActivity)
                    }
                }
                
                if (allPermissionsGranted) {
                    // Main app with all permissions granted
                    val navController = rememberNavController()
                    val sensorMonitoringViewModel: SensorMonitoringViewModel = viewModel()
                    
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                MainScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onNavigateToSensors = {
                                        navController.navigate("sensors")
                                    },
                                    sensorMonitoringViewModel = sensorMonitoringViewModel
                                )
                            }
                        }
                        composable("sensors") {
                            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                SensorMonitoringScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    viewModel = sensorMonitoringViewModel
                                )
                            }
                        }
                    }
                } else {
                    // Permission gate screen
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        PermissionGateScreen(
                            modifier = Modifier.padding(innerPadding),
                            onAllPermissionsGranted = {
                                allPermissionsGranted = true
                                Log.d(TAG, "All permissions granted - switching to main app")
                            }
                        )
                    }
                }
            }
        }
    }

}