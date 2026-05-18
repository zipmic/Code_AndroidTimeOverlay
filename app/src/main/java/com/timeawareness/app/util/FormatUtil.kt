package com.timeawareness.app.util

import java.util.Locale

object FormatUtil {
    fun formatSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun formatClock(): String {
        val t = java.time.LocalTime.now()
        return String.format(Locale.ROOT, "%d:%02d", t.hour, t.minute)
    }

    /** Compact "2h 15m" / "15m" / "45s" representation for headers and notifications. */
    fun formatHumanShort(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%dh %dm", h, m)
            m > 0 -> String.format(Locale.ROOT, "%dm", m)
            else -> String.format(Locale.ROOT, "%ds", s)
        }
    }
}
