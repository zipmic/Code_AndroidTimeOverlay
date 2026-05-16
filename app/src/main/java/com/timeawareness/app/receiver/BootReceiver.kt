package com.timeawareness.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.service.TrackingService
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!UsageStatsHelper.hasUsageStatsPermission(context)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = TimerDataStore(context)
                if (!store.isMasterEnabled()) return@launch
                val monitored = store.readAllElapsedToday().keys
                if (monitored.isNotEmpty()) {
                    TrackingService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
