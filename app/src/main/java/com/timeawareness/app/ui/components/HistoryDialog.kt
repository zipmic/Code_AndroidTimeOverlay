package com.timeawareness.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_ALERT_MAX
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_MINUTES_MIN
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_WARN_MAX
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.timeawareness.app.data.EarningsSettings
import com.timeawareness.app.util.FormatUtil
import com.timeawareness.app.util.FormatUtil.formatCost
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HistoryDialog(
    appLabel: String,
    historyFlow: Flow<Map<LocalDate, Long>>,
    globalThresholds: Pair<Int, Int>,
    appThresholdsFlow: Flow<Pair<Int, Int>?>,
    onSaveThreshold: (warnMin: Int, alertMin: Int) -> Unit,
    onClearThreshold: () -> Unit,
    earningsSettings: EarningsSettings,
    onDismiss: () -> Unit,
) {
    val history by historyFlow.collectAsState(initial = emptyMap())
    val appThresholds by appThresholdsFlow.collectAsState(initial = null)
    var selectedDays by remember { mutableIntStateOf(7) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {

                Text(appLabel, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))

                // 7 / 30 day toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 30).forEach { days ->
                        FilterChip(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days },
                            label = { Text("$days days") },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                val today = LocalDate.now()
                val rangeStart = today.minusDays(selectedDays - 1L)
                val daysInRange = (0 until selectedDays).map { rangeStart.plusDays(it.toLong()) }
                val values = daysInRange.map { date -> history[date] ?: 0L }
                val totalSeconds = values.sum()
                val maxVal = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

                if (totalSeconds == 0L) {
                    Text(
                        "No data for this period yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                    )
                } else {
                    BarChart(
                        days = daysInRange,
                        values = values,
                        maxVal = maxVal,
                        showLabels = selectedDays == 7,
                        primaryColor = MaterialTheme.colorScheme.primary,
                        barColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (selectedDays == 7) 160.dp else 120.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                AppThresholdSection(
                    appThresholds = appThresholds,
                    globalThresholds = globalThresholds,
                    onSave = onSaveThreshold,
                    onClear = onClearThreshold,
                )

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Total: ${FormatUtil.formatHumanShort(totalSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (earningsSettings.enabled && earningsSettings.hourlyWage > 0f && totalSeconds > 0L) {
                            Text(
                                text = "Cost: ${formatCost(totalSeconds, earningsSettings.hourlyWage, earningsSettings.currencyCode)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun BarChart(
    days: List<LocalDate>,
    values: List<Long>,
    maxVal: Long,
    showLabels: Boolean,
    primaryColor: Color,
    barColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val labelColorArgb = labelColor.toArgb()
    val labelSizePx = 10.sp

    Canvas(modifier = modifier) {
        val labelHeight = if (showLabels) 28f else 0f
        val chartHeight = size.height - labelHeight
        val spacing = 4.dp.toPx()
        val barWidth = (size.width - spacing * (days.size - 1)) / days.size

        days.forEachIndexed { i, date ->
            val value = values[i]
            val barHeight = (value.toFloat() / maxVal.toFloat()) * (chartHeight - 8.dp.toPx())
            val x = i * (barWidth + spacing)
            val isToday = date == today
            val color = if (isToday) primaryColor else barColor

            if (barHeight > 0f) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, chartHeight - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }

            if (showLabels) {
                drawDayLabel(
                    label = date.dayOfWeek
                        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        .take(2),
                    x = x + barWidth / 2f,
                    y = size.height,
                    colorArgb = if (isToday) primaryColor.toArgb() else labelColorArgb,
                    sizeSp = labelSizePx,
                )
            }
        }
    }
}

@Composable
private fun AppThresholdSection(
    appThresholds: Pair<Int, Int>?,
    globalThresholds: Pair<Int, Int>,
    onSave: (warnMin: Int, alertMin: Int) -> Unit,
    onClear: () -> Unit,
) {
    val (gWarn, gAlert) = globalThresholds
    var customEnabled by remember { mutableStateOf(appThresholds != null) }
    var warnMin by remember { mutableIntStateOf(appThresholds?.first ?: gWarn) }
    var alertMin by remember { mutableIntStateOf(appThresholds?.second ?: gAlert) }

    Text("App Thresholds", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (customEnabled) "Custom thresholds for this app"
                   else "Using global (${gWarn}m / ${gAlert}m)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = customEnabled,
            onCheckedChange = { enabled ->
                customEnabled = enabled
                if (enabled) onSave(warnMin, alertMin) else onClear()
            },
        )
    }

    AnimatedVisibility(visible = customEnabled) {
        Column {
            Spacer(Modifier.height(4.dp))
            Text(
                "Warn after  ${warnMin}m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = warnMin.toFloat(),
                onValueChange = {
                    warnMin = it.toInt()
                    alertMin = alertMin.coerceAtLeast(warnMin + 1)
                    onSave(warnMin, alertMin)
                },
                valueRange = THRESHOLD_MINUTES_MIN.toFloat()..THRESHOLD_WARN_MAX.toFloat(),
            )
            Text(
                "Alert after  ${alertMin}m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = alertMin.toFloat(),
                onValueChange = {
                    alertMin = it.toInt()
                    onSave(warnMin, alertMin)
                },
                valueRange = (warnMin + 1).toFloat()..THRESHOLD_ALERT_MAX.toFloat(),
            )
        }
    }
}

private fun DrawScope.drawDayLabel(
    label: String,
    x: Float,
    y: Float,
    colorArgb: Int,
    sizeSp: androidx.compose.ui.unit.TextUnit,
) {
    drawContext.canvas.nativeCanvas.drawText(
        label,
        x,
        y,
        android.graphics.Paint().apply {
            color = colorArgb
            textSize = sizeSp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        },
    )
}
