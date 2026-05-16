package com.timeawareness.app.util

import android.content.Context

/**
 * Process-scope cache for the launchable-apps list. Enumerating PackageManager and
 * calling loadLabel() on every entry can take hundreds of ms — we only want to pay
 * that cost once per process lifetime.
 */
object InstalledAppsCache {

    @Volatile
    private var cached: List<Pair<String, String>>? = null

    fun get(context: Context): List<Pair<String, String>> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: UsageStatsHelper.getInstalledApps(context).also { cached = it }
        }
    }

    fun refresh(context: Context): List<Pair<String, String>> {
        val fresh = UsageStatsHelper.getInstalledApps(context)
        cached = fresh
        return fresh
    }
}
