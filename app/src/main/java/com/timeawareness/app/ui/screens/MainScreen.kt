package com.timeawareness.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.ui.components.AppTimerRow
import com.timeawareness.app.ui.components.PermissionBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appStates: List<AppTimerState>,
    hasUsageStatsPermission: Boolean,
    hasOverlayPermission: Boolean,
    onRequestUsageStats: () -> Unit,
    onRequestOverlay: () -> Unit,
    onToggleMonitored: (String, Boolean) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Time Awareness") }) }
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

            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(
                    items = appStates,
                    key = { it.packageName }
                ) { state ->
                    AppTimerRow(
                        state = state,
                        onToggle = { enabled -> onToggleMonitored(state.packageName, enabled) }
                    )
                }
            }
        }
    }
}
