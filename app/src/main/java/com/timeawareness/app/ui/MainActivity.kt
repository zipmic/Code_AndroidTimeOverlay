package com.timeawareness.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.timeawareness.app.service.TrackingService
import com.timeawareness.app.ui.screens.MainScreen
import com.timeawareness.app.ui.theme.TimeAwarenessTheme
import com.timeawareness.app.util.UsageStatsHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        maybeStartService()
    }

    private val usageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        maybeStartService()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Result not needed — service runs either way; user can re-enable in Settings. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            TimeAwarenessTheme {
                val appStates by viewModel.appStates.collectAsState()
                val masterEnabled by viewModel.masterEnabled.collectAsState()

                var hasUsageStats by remember { mutableStateOf(UsageStatsHelper.hasUsageStatsPermission(this)) }
                var hasOverlay by remember { mutableStateOf(UsageStatsHelper.hasOverlayPermission(this)) }

                // Re-check permissions every time the activity returns to the foreground.
                val lifecycleOwner = LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasUsageStats = UsageStatsHelper.hasUsageStatsPermission(this@MainActivity)
                            hasOverlay = UsageStatsHelper.hasOverlayPermission(this@MainActivity)
                            maybeStartService()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                MainScreen(
                    appStates = appStates,
                    masterEnabled = masterEnabled,
                    hasUsageStatsPermission = hasUsageStats,
                    hasOverlayPermission = hasOverlay,
                    onMasterToggle = { enabled ->
                        viewModel.setMasterEnabled(enabled)
                        if (enabled) maybeStartService()
                        // When disabled, the service observes the flow and stops itself.
                    },
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

    private fun maybeStartService() {
        if (!UsageStatsHelper.hasUsageStatsPermission(this)) return
        if (viewModel.masterEnabled.value) TrackingService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
