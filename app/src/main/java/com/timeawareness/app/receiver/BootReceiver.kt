package com.timeawareness.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.timeawareness.app.service.TrackingService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only restart if the user had previously started the service.
            // The service itself checks permissions before doing anything.
            TrackingService.start(context)
        }
    }
}
