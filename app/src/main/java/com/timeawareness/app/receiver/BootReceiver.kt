package com.timeawareness.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.service.TrackingService
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {

    private companion object {
        // BroadcastReceiver.goAsync() has a ~10s budget; keep our IO well inside that.
        const val IO_BUDGET_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!UsageStatsHelper.hasUsageStatsPermission(context)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                withTimeoutOrNull(IO_BUDGET_MS) {
                    val store = TimerDataStore(context)
                    if (!store.isMasterEnabled()) return@withTimeoutOrNull
                    if (store.readMonitoredApps().isNotEmpty()) {
                        TrackingService.start(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
