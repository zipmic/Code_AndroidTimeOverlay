package com.timeawareness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timeawareness.app.data.ActiveSchedule
import com.timeawareness.app.data.OverlayStyle
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.ui.components.AppTimerRow
import com.timeawareness.app.ui.components.HistoryDialog
import com.timeawareness.app.ui.components.PermissionBanner
import com.timeawareness.app.ui.components.ProSettingsSection
import com.timeawareness.app.ui.components.WeeklyReportDialog
import com.timeawareness.app.util.FormatUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appStates: List<AppTimerState>,
    masterEnabled: Boolean,
    hasUsageStatsPermission: Boolean,
    hasOverlayPermission: Boolean,
    isBatteryUnrestricted: Boolean,
    overlaySize: Int,
    overlayStyle: OverlayStyle,
    onMasterToggle: (Boolean) -> Unit,
    onOverlaySizeChange: (Int) -> Unit,
    onOverlayStyleChange: (OverlayStyle) -> Unit,
    onRequestUsageStats: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onToggleMonitored: (String, Boolean) -> Unit,
    onResetTimer: (String) -> Unit,
    onGetHistory: (String) -> Flow<Map<LocalDate, Long>>,
    globalThresholds: Pair<Int, Int>,
    onGlobalThresholdsChange: (warnMin: Int, alertMin: Int) -> Unit,
    onGetAppThresholds: (String) -> Flow<Pair<Int, Int>?>,
    onSaveAppThreshold: (pkg: String, warnMin: Int, alertMin: Int) -> Unit,
    onClearAppThreshold: (pkg: String) -> Unit,
    dailySummaryEnabled: Boolean,
    onDailySummaryToggle: (Boolean) -> Unit,
    activeSchedule: ActiveSchedule,
    onActiveScheduleChange: (ActiveSchedule) -> Unit,
    onExportHistory: suspend () -> android.net.Uri?,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var resetTargetPkg by remember { mutableStateOf<String?>(null) }
    var resetTargetLabel by remember { mutableStateOf("") }
    var historyTarget by remember { mutableStateOf<AppTimerState?>(null) }
    var showWeeklyReport by remember { mutableStateOf(false) }
    var exportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    exportUri?.let { uri ->
        LaunchedEffect(uri) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export history"))
            exportUri = null
        }
    }

    val monitoredApps = appStates.filter { it.isMonitored }
    val totalSeconds = monitoredApps.sumOf { it.elapsedSeconds }
    val allPermissionsGranted = hasUsageStatsPermission && hasOverlayPermission

    val filteredApps = if (query.isBlank()) appStates
        else appStates.filter { it.appLabel.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Awareness") },
                actions = {
                    if (monitoredApps.isNotEmpty()) {
                        IconButton(onClick = { showWeeklyReport = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Weekly report")
                        }
                        IconButton(onClick = {
                            scope.launch { exportUri = onExportHistory() }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Export CSV")
                        }
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = onMasterToggle,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!hasUsageStatsPermission) {
                PermissionBanner(
                    message = "Usage access is required to detect which app is open.",
                    buttonLabel = "Grant access",
                    onClick = onRequestUsageStats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (!hasOverlayPermission) {
                PermissionBanner(
                    message = "Draw over other apps is required to show the timer overlay.",
                    buttonLabel = "Grant access",
                    onClick = onRequestOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (allPermissionsGranted && !isBatteryUnrestricted) {
                PermissionBanner(
                    message = "Allow Time Awareness to run in the background so the timer keeps working when the screen is off.",
                    buttonLabel = "Improve reliability",
                    onClick = onRequestBatteryExemption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // First-run hint: permissions granted but master still off → tell the user.
            if (allPermissionsGranted && !masterEnabled) {
                Text(
                    text = if (monitoredApps.isEmpty()) {
                        "You're all set. Pick an app below, then flip the switch above to start tracking."
                    } else {
                        "Tracking is paused. Turn on the switch above to resume."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Daily total summary.
            if (monitoredApps.isNotEmpty()) {
                Text(
                    text = "Today: ${FormatUtil.formatHumanShort(totalSeconds)} across " +
                        "${monitoredApps.size} app${if (monitoredApps.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Overlay size",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${overlaySize}sp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = overlaySize.toFloat(),
                onValueChange = { onOverlaySizeChange(it.toInt()) },
                valueRange = TimerDataStore.OVERLAY_SIZE_MIN.toFloat()..TimerDataStore.OVERLAY_SIZE_MAX.toFloat(),
                steps = TimerDataStore.OVERLAY_SIZE_MAX - TimerDataStore.OVERLAY_SIZE_MIN - 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            ProSettingsSection(
                style = overlayStyle,
                onStyleChange = onOverlayStyleChange,
                globalThresholds = globalThresholds,
                onGlobalThresholdsChange = onGlobalThresholdsChange,
                dailySummaryEnabled    = dailySummaryEnabled,
                onDailySummaryToggle  = onDailySummaryToggle,
                activeSchedule        = activeSchedule,
                onActiveScheduleChange = onActiveScheduleChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            when {
                appStates.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                filteredApps.isEmpty() -> {
                    Text(
                        text = "No apps match \"$query\"",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.alpha(if (masterEnabled) 1f else 0.5f),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(items = filteredApps, key = { it.packageName }) { state ->
                            AppTimerRow(
                                state = state,
                                onToggle = { enabled -> onToggleMonitored(state.packageName, enabled) },
                                onReset = {
                                    resetTargetPkg = state.packageName
                                    resetTargetLabel = state.appLabel
                                },
                                onShowHistory = { historyTarget = state },
                            )
                        }
                    }
                }
            }
        }
    }

    resetTargetPkg?.let { pkg ->
        AlertDialog(
            onDismissRequest = { resetTargetPkg = null },
            title = { Text("Reset $resetTargetLabel?") },
            text = { Text("Today's elapsed time for this app will be set back to 0:00.") },
            confirmButton = {
                TextButton(onClick = {
                    onResetTimer(pkg)
                    resetTargetPkg = null
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { resetTargetPkg = null }) { Text("Cancel") }
            }
        )
    }

    if (showWeeklyReport) {
        WeeklyReportDialog(
            monitoredApps = monitoredApps,
            onGetHistory  = onGetHistory,
            onDismiss     = { showWeeklyReport = false },
        )
    }

    historyTarget?.let { state ->
        HistoryDialog(
            appLabel = state.appLabel,
            historyFlow = onGetHistory(state.packageName),
            globalThresholds = globalThresholds,
            appThresholdsFlow = onGetAppThresholds(state.packageName),
            onSaveThreshold = { w, a -> onSaveAppThreshold(state.packageName, w, a) },
            onClearThreshold = { onClearAppThreshold(state.packageName) },
            onDismiss = { historyTarget = null },
        )
    }
}
