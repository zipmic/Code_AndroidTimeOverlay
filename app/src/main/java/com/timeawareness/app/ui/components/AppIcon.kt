package com.timeawareness.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(packageName) {
        icon = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(packageName).mutate() }
            catch (e: Exception) { null }
        }
    }
    Canvas(modifier = modifier) {
        val current = icon
        if (current == null) {
            // Neutral placeholder while the drawable loads asynchronously.
            drawRoundRect(
                color = Color.LightGray.copy(alpha = 0.4f),
                cornerRadius = CornerRadius(size.minDimension * 0.2f)
            )
        } else {
            drawIntoCanvas { canvas ->
                current.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                current.draw(canvas.nativeCanvas)
            }
        }
    }
}
