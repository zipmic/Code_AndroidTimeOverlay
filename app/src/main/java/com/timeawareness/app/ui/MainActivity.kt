package com.timeawareness.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.timeawareness.app.service.TrackingService
import com.timeawareness.app.ui.screens.MainScreen
import com.timeawareness.app.ui.theme.TimeAwarenessTheme
import com.timeawareness.app.util.UsageStatsHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Launcher for the overlay permission settings screen.
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refresh()
        maybeStartService()
    }

    // Launcher for the usage stats permission settings screen.
    private val usageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refresh()
        maybeStartService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimeAwarenessTheme {
                val appStates by viewModel.appStates.collectAsState()
                MainScreen(
                    appStates = appStates,
                    hasUsageStatsPermission = UsageStatsHelper.hasUsageStatsPermission(this),
                    hasOverlayPermission = UsageStatsHelper.hasOverlayPermission(this),
                    onRequestUsageStats = { requestUsageStatsPermission() },
                    onRequestOverlay = { requestOverlayPermission() },
                    onToggleMonitored = { pkg, enabled ->
                        viewModel.setMonitored(pkg, enabled)
                        maybeStartService()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun maybeStartService() {
        if (UsageStatsHelper.hasUsageStatsPermission(this)) {
            TrackingService.start(this)
        }
    }

    private fun requestUsageStatsPermission() {
        usageStatsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }
}
