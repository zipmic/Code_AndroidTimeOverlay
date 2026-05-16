package com.timeawareness.app.model

data class AppTimerState(
    val packageName: String,
    val appLabel: String,
    val iconKey: String,          // same as packageName, used for icon loading
    val isMonitored: Boolean,
    val elapsedSeconds: Long,     // today's accumulated seconds
    val isActiveNow: Boolean,
)
