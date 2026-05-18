package com.timeawareness.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
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
    ) { maybeStartService() }

    private val usageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { maybeStartService() }

    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result re-evaluated on next ON_RESUME via the state observer */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result not strictly needed — service runs either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            TimeAwarenessTheme {
                val appStates by viewModel.appStates.collectAsState()
                val masterEnabled by viewModel.masterEnabled.collectAsState()
                val overlaySize by viewModel.overlaySize.collectAsState()
                val overlayStyle by viewModel.overlayStyle.collectAsState()
                val globalThresholds by viewModel.globalThresholds.collectAsState()
                val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsState()
                val activeSchedule by viewModel.activeSchedule.collectAsState()

                var hasUsageStats by remember {
                    mutableStateOf(UsageStatsHelper.hasUsageStatsPermission(this))
                }
                var hasOverlay by remember {
                    mutableStateOf(UsageStatsHelper.hasOverlayPermission(this))
                }
                var isBatteryUnrestricted by remember {
                    mutableStateOf(isIgnoringBatteryOptimizations())
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasUsageStats = UsageStatsHelper.hasUsageStatsPermission(this@MainActivity)
                            hasOverlay = UsageStatsHelper.hasOverlayPermission(this@MainActivity)
                            isBatteryUnrestricted = isIgnoringBatteryOptimizations()
                            maybeStartService()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                MainScreen(
                    appStates = appStates,
                    masterEnabled = masterEnabled,
                    overlaySize = overlaySize,
                    overlayStyle = overlayStyle,
                    hasUsageStatsPermission = hasUsageStats,
                    hasOverlayPermission = hasOverlay,
                    isBatteryUnrestricted = isBatteryUnrestricted,
                    onMasterToggle = { enabled ->
                        viewModel.setMasterEnabled(enabled)
                        // Use the freshly-toggled value rather than reading the StateFlow,
                        // which won't have observed the DataStore write yet.
                        if (enabled && UsageStatsHelper.hasUsageStatsPermission(this)) {
                            TrackingService.start(this)
                        }
                    },
                    onOverlaySizeChange = { viewModel.setOverlaySize(it) },
                    onOverlayStyleChange = { viewModel.setOverlayStyle(it) },
                    onRequestUsageStats = { requestUsageStatsPermission() },
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestBatteryExemption = { requestBatteryExemption() },
                    onToggleMonitored = { pkg, enabled ->
                        viewModel.setMonitored(pkg, enabled)
                        maybeStartService()
                    },
                    onResetTimer = { pkg -> viewModel.resetTimer(pkg) },
                    onGetHistory = { pkg -> viewModel.historyFor(pkg) },
                    globalThresholds = globalThresholds,
                    onGlobalThresholdsChange = { w, a -> viewModel.setGlobalThresholds(w, a) },
                    onGetAppThresholds = { pkg -> viewModel.appThresholdsFor(pkg) },
                    onSaveAppThreshold = { pkg, w, a -> viewModel.setAppThreshold(pkg, w, a) },
                    onClearAppThreshold  = { pkg -> viewModel.clearAppThreshold(pkg) },
                    dailySummaryEnabled    = dailySummaryEnabled,
                    onDailySummaryToggle  = { viewModel.setDailySummaryEnabled(it) },
                    activeSchedule         = activeSchedule,
                    onActiveScheduleChange = { viewModel.setActiveSchedule(it) },
                    onExportHistory        = { viewModel.exportHistory() },
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
        if (granted) return
        // shouldShowRequestPermissionRationale returns false on the *first* request OR after
        // permanent denial. We can't easily distinguish, so we only auto-request once per
        // process; if denied, the in-app notification banner / channel can prompt later.
        if (!hasAskedForNotificationsThisSession) {
            hasAskedForNotificationsThisSession = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        batteryExemptionLauncher.launch(intent)
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

    companion object {
        // Process-scoped flag so we don't re-prompt on every activity recreate
        // (config change, etc.) within the same process.
        private var hasAskedForNotificationsThisSession = false
    }
}
