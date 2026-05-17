package com.timeawareness.app.util

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

object ColorUtil {

    /** HSL → packed ARGB int. h: 0..360, s: 0..1, l: 0..1, alpha: 0..255. */
    fun hslToArgb(h: Float, s: Float, l: Float, alpha: Int): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else    -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (alpha.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Convenience wrapper returning a Compose Color with full opacity. */
    fun hslToComposeColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Color =
        Color(hslToArgb(h, s, l, (alpha * 255f).toInt()))
}
