package com.timeawareness.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.util.FormatUtil
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Composable
fun WeeklyReportDialog(
    monitoredApps: List<AppTimerState>,
    onGetHistory: (String) -> Flow<Map<LocalDate, Long>>,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {

                Text("Weekly Report", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Last 7 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (monitoredApps.isEmpty()) {
                    Text(
                        "No apps monitored yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.heightIn(max = 440.dp),
                    ) {
                        items(monitoredApps, key = { it.packageName }) { state ->
                            AppWeekRow(
                                appLabel    = state.appLabel,
                                days        = days,
                                historyFlow = onGetHistory(state.packageName),
                                barColor    = MaterialTheme.colorScheme.primaryContainer,
                                todayColor  = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun AppWeekRow(
    appLabel: String,
    days: List<LocalDate>,
    historyFlow: Flow<Map<LocalDate, Long>>,
    barColor: Color,
    todayColor: Color,
) {
    val history by historyFlow.collectAsState(initial = emptyMap())
    val today   = LocalDate.now()
    val values  = days.map { history[it] ?: 0L }
    val weekTotal = values.sum()

    if (weekTotal == 0L) return

    val maxVal = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = appLabel,
                style    = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text  = FormatUtil.formatHumanShort(weekTotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val spacing  = 3.dp.toPx()
            val barWidth = (size.width - spacing * (days.size - 1)) / days.size
            days.forEachIndexed { i, date ->
                val value = values[i]
                if (value > 0L) {
                    val barHeight = (value.toFloat() / maxVal.toFloat()) * size.height
                    val x         = i * (barWidth + spacing)
                    drawRoundRect(
                        color        = if (date == today) todayColor else barColor,
                        topLeft      = Offset(x, size.height - barHeight),
                        size         = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
        }
    }
}
