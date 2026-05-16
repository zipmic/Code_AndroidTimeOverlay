package com.timeawareness.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

object UsageStatsHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /**
     * Stateful foreground-app detector. Pass the previously returned package on each call;
     * returns the currently-foreground package (or null if no monitored app is open).
     *
     * Uses [UsageStatsManager.queryEvents] which is the only reliable source for
     * foreground/background transitions — aggregated stats lag and produce stale results.
     */
    fun getForegroundPackage(
        context: Context,
        previous: String?,
        sinceMillis: Long,
    ): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(sinceMillis, now)
        var current = previous
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (current == event.packageName) current = null
                }
            }
        }
        if (current == null) return null
        if (current == context.packageName) return null
        if (current in getHomePackages(context)) return null
        return current
    }

    private var cachedHomePackages: Set<String>? = null

    /** Packages registered as launcher/home screens. Cached once per process. */
    private fun getHomePackages(context: Context): Set<String> {
        cachedHomePackages?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkgs = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        cachedHomePackages = pkgs
        return pkgs
    }

    /** Returns all installed launchable user-facing apps sorted by label. */
    fun getInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString()
                pkg to label
            }
            .distinctBy { it.first }
            .filter { (pkg, _) -> pkg != context.packageName }
            .sortedBy { (_, label) -> label.lowercase() }
    }
}
