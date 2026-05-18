package com.timeawareness.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.timeawareness.app.data.OverlayContent
import com.timeawareness.app.data.OverlayStyle
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_ALERT_MAX
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_MINUTES_MIN
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_WARN_MAX
import com.timeawareness.app.util.ColorUtil

@Composable
fun ProSettingsSection(
    style: OverlayStyle,
    onStyleChange: (OverlayStyle) -> Unit,
    globalThresholds: Pair<Int, Int>,
    onGlobalThresholdsChange: (warnMin: Int, alertMin: Int) -> Unit,
    dailySummaryEnabled: Boolean,
    onDailySummaryToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val cornerRadius = 12.dp
    val expandedShape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
    val collapsedShape = RoundedCornerShape(cornerRadius)

    Column(modifier = modifier) {
        Surface(
            onClick = { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = if (expanded) expandedShape else collapsedShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Pro Settings",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    StylePresetsSection(style, onStyleChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    OverlayContentSection(style, onStyleChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    OverlayColorSection(style, onStyleChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    GlobalThresholdsSection(globalThresholds, onGlobalThresholdsChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    BlinkSection(style, onStyleChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    RandomMoveSection(style, onStyleChange)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    DailySummarySection(dailySummaryEnabled, onDailySummaryToggle)
                }
            }
        }
    }
}

// ── Style presets ─────────────────────────────────────────────────────────────

private data class StylePreset(
    val name: String,
    val hue: Float, val saturation: Float, val lightness: Float, val alpha: Int,
)

private val STYLE_PRESETS = listOf(
    StylePreset("Default", 0f,   0f,   0f,    204),
    StylePreset("Amber",   38f,  0.9f, 0.35f, 230),
    StylePreset("Cyan",    195f, 0.8f, 0.35f, 230),
    StylePreset("Green",   145f, 0.7f, 0.3f,  230),
    StylePreset("Night",   0f,   0f,   0.05f, 160),
)

@Composable
private fun StylePresetsSection(style: OverlayStyle, onStyleChange: (OverlayStyle) -> Unit) {
    Text("Quick Presets", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        STYLE_PRESETS.forEach { preset ->
            val isSelected = style.hue == preset.hue &&
                             style.saturation == preset.saturation &&
                             style.lightness == preset.lightness &&
                             style.alpha == preset.alpha
            FilterChip(
                selected = isSelected,
                onClick  = {
                    onStyleChange(style.copy(
                        hue        = preset.hue,
                        saturation = preset.saturation,
                        lightness  = preset.lightness,
                        alpha      = preset.alpha,
                    ))
                },
                label = { Text(preset.name) },
            )
        }
    }
}

// ── Overlay content ───────────────────────────────────────────────────────────

@Composable
private fun OverlayContentSection(style: OverlayStyle, onStyleChange: (OverlayStyle) -> Unit) {
    Text("Display", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        listOf(
            OverlayContent.ELAPSED_TIME  to "Time",
            OverlayContent.CLOCK         to "Clock",
            OverlayContent.SESSION_COUNT to "Sessions",
            OverlayContent.CUSTOM_LABEL  to "Label",
        ).forEach { (content, label) ->
            FilterChip(
                selected = style.overlayContent == content,
                onClick  = { onStyleChange(style.copy(overlayContent = content)) },
                label    = { Text(label) },
            )
        }
    }
    AnimatedVisibility(visible = style.overlayContent == OverlayContent.CUSTOM_LABEL) {
        Column {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value         = style.customLabel,
                onValueChange = { onStyleChange(style.copy(customLabel = it)) },
                label         = { Text("Custom text") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Global thresholds ─────────────────────────────────────────────────────────

@Composable
private fun GlobalThresholdsSection(
    thresholds: Pair<Int, Int>,
    onChange: (warnMin: Int, alertMin: Int) -> Unit,
) {
    val (warnMin, alertMin) = thresholds
    Text("Default Thresholds", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        "Overlay turns amber / red after these durations (applies to all apps unless overridden).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    SliderRow(label = "Warn after  ${warnMin}m") {
        Slider(
            value = warnMin.toFloat(),
            onValueChange = { onChange(it.toInt(), alertMin.coerceAtLeast(it.toInt() + 1)) },
            valueRange = THRESHOLD_MINUTES_MIN.toFloat()..THRESHOLD_WARN_MAX.toFloat(),
        )
    }
    SliderRow(label = "Alert after  ${alertMin}m") {
        Slider(
            value = alertMin.toFloat(),
            onValueChange = { onChange(warnMin, it.toInt()) },
            valueRange = (warnMin + 1).toFloat()..THRESHOLD_ALERT_MAX.toFloat(),
        )
    }
}

// ── Overlay color ─────────────────────────────────────────────────────────────

@Composable
private fun OverlayColorSection(style: OverlayStyle, onStyleChange: (OverlayStyle) -> Unit) {
    val alphaFraction = style.alpha / 255f

    // Precompute the current fully-opaque color for gradient endpoints.
    val fullColor = ColorUtil.hslToComposeColor(style.hue, style.saturation, style.lightness)

    Text("Overlay Color", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))

    // Hue
    SliderRow(label = "Hue") {
        GradientSlider(
            value = style.hue,
            onValueChange = { onStyleChange(style.copy(hue = it)) },
            valueRange = 0f..360f,
            gradientColors = listOf(
                Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
            ),
        )
    }

    // Saturation
    SliderRow(label = "Saturation") {
        GradientSlider(
            value = style.saturation,
            onValueChange = { onStyleChange(style.copy(saturation = it)) },
            valueRange = 0f..1f,
            gradientColors = listOf(
                ColorUtil.hslToComposeColor(style.hue, 0f, style.lightness.coerceAtLeast(0.15f)),
                fullColor,
            ),
        )
    }

    // Lightness
    SliderRow(label = "Lightness") {
        GradientSlider(
            value = style.lightness,
            onValueChange = { onStyleChange(style.copy(lightness = it)) },
            valueRange = 0f..1f,
            gradientColors = listOf(
                Color.Black,
                fullColor,
                Color.White,
            ),
        )
    }

    // Opacity
    SliderRow(label = "Opacity  ${(alphaFraction * 100).toInt()}%") {
        AlphaSlider(
            alpha = alphaFraction,
            opaqueColor = fullColor,
            onAlphaChange = { onStyleChange(style.copy(alpha = (it * 255f).toInt())) },
        )
    }

    // Live preview swatch
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Preview",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .background(ColorUtil.hslToComposeColor(style.hue, style.saturation, style.lightness, alphaFraction)),
        )
    }
}

// ── Blink ─────────────────────────────────────────────────────────────────────

@Composable
private fun BlinkSection(style: OverlayStyle, onStyleChange: (OverlayStyle) -> Unit) {
    ToggleRow(
        label = "Blink",
        checked = style.blinkEnabled,
        onCheckedChange = { onStyleChange(style.copy(blinkEnabled = it)) },
    )
    AnimatedVisibility(visible = style.blinkEnabled) {
        Column {
            Spacer(Modifier.height(4.dp))
            SliderRow(label = "Every ${style.blinkIntervalSeconds}s") {
                Slider(
                    value = style.blinkIntervalSeconds.toFloat(),
                    onValueChange = { onStyleChange(style.copy(blinkIntervalSeconds = it.toInt())) },
                    valueRange = TimerDataStore.BLINK_INTERVAL_MIN.toFloat()..
                                 TimerDataStore.BLINK_INTERVAL_MAX.toFloat(),
                )
            }
        }
    }
}

// ── Random movement ───────────────────────────────────────────────────────────

@Composable
private fun RandomMoveSection(style: OverlayStyle, onStyleChange: (OverlayStyle) -> Unit) {
    ToggleRow(
        label = "Random Movement",
        checked = style.randomMoveEnabled,
        onCheckedChange = { onStyleChange(style.copy(randomMoveEnabled = it)) },
    )
    AnimatedVisibility(visible = style.randomMoveEnabled) {
        Column {
            Spacer(Modifier.height(4.dp))
            SliderRow(label = "Every ${style.randomMoveIntervalSeconds}s") {
                Slider(
                    value = style.randomMoveIntervalSeconds.toFloat(),
                    onValueChange = { onStyleChange(style.copy(randomMoveIntervalSeconds = it.toInt())) },
                    valueRange = TimerDataStore.RANDOM_MOVE_INTERVAL_MIN.toFloat()..
                                 TimerDataStore.RANDOM_MOVE_INTERVAL_MAX.toFloat(),
                )
            }
        }
    }
}

// ── Daily summary ─────────────────────────────────────────────────────────────

@Composable
private fun DailySummarySection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ToggleRow(label = "Daily Summary Notification", checked = enabled, onCheckedChange = onToggle)
    AnimatedVisibility(visible = enabled) {
        Text(
            "Sends a notification with yesterday's usage totals at midnight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── Reusable layout primitives ────────────────────────────────────────────────

@Composable
private fun SliderRow(label: String, content: @Composable () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    content()
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Gradient sliders ──────────────────────────────────────────────────────────

/**
 * A Slider with a custom gradient track.
 * The gradient fills the full track width; the thumb shows the current position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        track = { _ ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(gradientColors))
            )
        },
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Alpha slider with a checkerboard backdrop so transparency is clearly visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlphaSlider(
    alpha: Float,
    opaqueColor: Color,
    onAlphaChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = alpha,
        onValueChange = onAlphaChange,
        valueRange = 0f..1f,
        track = { _ ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            ) {
                // Checkerboard to show transparency
                Canvas(Modifier.matchParentSize()) {
                    val sq = 6.dp.toPx()
                    val cols = (size.width  / sq).toInt() + 1
                    val rows = (size.height / sq).toInt() + 1
                    for (row in 0..rows) {
                        for (col in 0..cols) {
                            drawRect(
                                color = if ((row + col) % 2 == 0) Color.White
                                        else Color(0xFFCCCCCC),
                                topLeft = Offset(col * sq, row * sq),
                                size    = Size(sq, sq),
                            )
                        }
                    }
                }
                // Gradient from transparent → opaque current color
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(opaqueColor.copy(alpha = 0f), opaqueColor)
                            )
                        )
                )
            }
        },
        colors = SliderDefaults.colors(thumbColor = Color.White),
        modifier = modifier.fillMaxWidth(),
    )
}
