package com.timeawareness.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.timeawareness.app.R
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.ui.MainActivity
import com.timeawareness.app.util.FormatUtil
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TrackingService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "tracking_service"
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var dataStore: TimerDataStore
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayTimerText: TextView? = null

    // In-memory counters. Persisted to DataStore every tick.
    private val elapsedSeconds = mutableMapOf<String, Long>()
    private var monitoredApps = setOf<String>()
    private var currentForegroundPkg: String? = null
    private var tickJob: Job? = null

    // Overlay drag state
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        dataStore = TimerDataStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        observeMonitoredApps()
        startTickLoop()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        tickJob?.cancel()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "App Timer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Tracks time spent in selected apps" }
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Time Awareness")
            .setContentText("Monitoring active")
            .setSmallIcon(R.drawable.ic_timer)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // ── Monitored app list ────────────────────────────────────────────────────

    private fun observeMonitoredApps() {
        lifecycleScope.launch {
            dataStore.monitoredAppsFlow().collect { apps ->
                monitoredApps = apps
                // Seed in-memory counters for newly added apps.
                apps.forEach { pkg ->
                    if (!elapsedSeconds.containsKey(pkg)) {
                        elapsedSeconds[pkg] = dataStore.elapsedSecondsFlow(pkg).first()
                    }
                }
            }
        }
    }

    // ── Main tick loop ────────────────────────────────────────────────────────

    private fun startTickLoop() {
        tickJob = lifecycleScope.launch {
            while (true) {
                delay(1_000)
                tick()
            }
        }
    }

    private suspend fun tick() {
        if (!UsageStatsHelper.hasUsageStatsPermission(this)) return

        val foreground = UsageStatsHelper.getForegroundPackage(this)

        if (foreground != currentForegroundPkg) {
            // App changed — hide overlay for the old app.
            if (currentForegroundPkg != null && monitoredApps.contains(currentForegroundPkg)) {
                removeOverlay()
            }
            currentForegroundPkg = foreground

            // Show overlay for the new app if it is monitored.
            if (foreground != null && monitoredApps.contains(foreground)) {
                val seconds = elapsedSeconds[foreground] ?: 0L
                showOverlay(seconds)
            }
        }

        // Increment and persist if a monitored app is active.
        val pkg = currentForegroundPkg ?: return
        if (!monitoredApps.contains(pkg)) return

        val updated = (elapsedSeconds[pkg] ?: 0L) + 1L
        elapsedSeconds[pkg] = updated
        dataStore.saveElapsedSeconds(pkg, updated)
        updateOverlayText(updated)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(initialSeconds: Long) {
        if (!UsageStatsHelper.hasOverlayPermission(this)) return
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }
        overlayParams = params

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_timer, null)
        overlayTimerText = view.findViewById(R.id.tv_timer)
        overlayTimerText?.text = FormatUtil.formatSeconds(initialSeconds)

        view.setOnTouchListener(overlayDragListener)

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            overlayTimerText = null
        }
    }

    private fun updateOverlayText(seconds: Long) {
        overlayTimerText?.text = FormatUtil.formatSeconds(seconds)
    }

    private val overlayDragListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragInitialX = overlayParams?.x ?: 0
                dragInitialY = overlayParams?.y ?: 0
                dragTouchX = event.rawX
                dragTouchY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                overlayParams?.let { p ->
                    p.x = dragInitialX + (dragTouchX - event.rawX).toInt()
                    p.y = dragInitialY + (event.rawY - dragTouchY).toInt()
                    windowManager.updateViewLayout(view, p)
                }
                true
            }
            else -> false
        }
    }
}
