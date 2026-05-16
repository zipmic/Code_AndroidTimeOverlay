package com.timeawareness.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon: Drawable? = remember(packageName) {
        try { context.packageManager.getApplicationIcon(packageName) }
        catch (e: Exception) { null }
    }
    Canvas(modifier = modifier) {
        drawIntoCanvas { canvas ->
            icon?.let {
                it.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                it.draw(canvas.nativeCanvas)
            }
        }
    }
}
