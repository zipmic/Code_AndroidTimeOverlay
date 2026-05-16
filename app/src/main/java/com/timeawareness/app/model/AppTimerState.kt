package com.timeawareness.app.model

data class AppTimerState(
    val packageName: String,
    val appLabel: String,
    val isMonitored: Boolean,
    val elapsedSeconds: Long,
)
