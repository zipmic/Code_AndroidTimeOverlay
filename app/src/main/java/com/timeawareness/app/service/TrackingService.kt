package com.timeawareness.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.timeawareness.app.R
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.ui.MainActivity
import com.timeawareness.app.util.FormatUtil
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Foreground service that:
 *   • polls UsageStatsManager every second for foreground app transitions,
 *   • increments per-app daily counters while a monitored app is in focus,
 *   • shows/hides a draggable overlay timer,
 *   • persists state to DataStore in batched flushes (every PERSIST_INTERVAL_SECONDS),
 *   • resets counters on a date change.
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "tracking_service"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 2_000L
        private const val PERSIST_INTERVAL_TICKS = 3   // every 6s with 2s tick
        private const val PERMISSION_RECHECK_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var dataStore: TimerDataStore
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayTimerText: TextView? = null

    private val elapsedSeconds = mutableMapOf<String, Long>()
    private val dirtyPackages = mutableSetOf<String>()
    private var ticksSincePersist = 0

    private var monitoredApps = setOf<String>()
    private var currentForegroundPkg: String? = null
    private var currentDate: LocalDate = LocalDate.now()
    private var lastPollTime = System.currentTimeMillis()
    private var tickJob: Job? = null

    // Cached permission state — re-checked at most every PERMISSION_RECHECK_MS.
    private var cachedHasUsagePermission = false
    private var lastPermissionCheckMs = 0L

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        // Flush any unpersisted seconds before exiting.
        lifecycleScope.launch { flushDirty() }
        removeOverlay()
        tickJob?.cancel()
        super.onDestroy()
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
                // Seed in-memory counters for newly-added packages using a single
                // DataStore snapshot read (the prior per-package .first() was N reads).
                val newPackages = apps.filter { it !in elapsedSeconds }
                if (newPackages.isNotEmpty()) {
                    val snapshot = dataStore.readAllElapsedToday()
                    newPackages.forEach { pkg -> elapsedSeconds[pkg] = snapshot[pkg] ?: 0L }
                }
                // If the user removed an app while its overlay was shown, hide it.
                val fg = currentForegroundPkg
                if (overlayView != null && (fg == null || fg !in apps)) {
                    removeOverlay()
                }
                if (apps.isEmpty()) stopSelf()
            }
        }
        lifecycleScope.launch {
            dataStore.masterEnabledFlow().collect { enabled ->
                if (!enabled) stopSelf()
            }
        }
    }

    // ── Main tick loop ────────────────────────────────────────────────────────

    private fun startTickLoop() {
        tickJob = lifecycleScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private suspend fun tick() {
        if (!hasUsagePermissionCached()) return
        if (monitoredApps.isEmpty()) return

        handleMidnightRollover()

        val foreground = UsageStatsHelper.getForegroundPackage(
            context = this,
            previous = currentForegroundPkg,
            sinceMillis = lastPollTime - 1_000,
        )
        lastPollTime = System.currentTimeMillis()

        if (foreground != currentForegroundPkg) {
            if (currentForegroundPkg in monitoredApps) removeOverlay()
            currentForegroundPkg = foreground
            if (foreground in monitoredApps) {
                showOverlay(elapsedSeconds[foreground] ?: 0L)
            }
        }

        val pkg = currentForegroundPkg ?: return
        if (pkg !in monitoredApps) return

        // Add seconds equal to the tick interval (not 1s) since we now tick every 2s.
        val tickSeconds = TICK_INTERVAL_MS / 1000L
        val updated = (elapsedSeconds[pkg] ?: 0L) + tickSeconds
        elapsedSeconds[pkg] = updated
        dirtyPackages += pkg
        updateOverlayText(updated)

        ticksSincePersist++
        if (ticksSincePersist >= PERSIST_INTERVAL_TICKS) {
            flushDirty()
            ticksSincePersist = 0
        }
    }

    private fun hasUsagePermissionCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPermissionCheckMs > PERMISSION_RECHECK_MS) {
            cachedHasUsagePermission = UsageStatsHelper.hasUsageStatsPermission(this)
            lastPermissionCheckMs = now
        }
        return cachedHasUsagePermission
    }

    private suspend fun flushDirty() {
        if (dirtyPackages.isEmpty()) return
        val snapshot = dirtyPackages.toList()
        dirtyPackages.clear()
        snapshot.forEach { pkg ->
            dataStore.saveElapsedSeconds(pkg, elapsedSeconds[pkg] ?: 0L)
        }
    }

    private fun handleMidnightRollover() {
        val today = LocalDate.now()
        if (today != currentDate) {
            currentDate = today
            elapsedSeconds.clear()
            dirtyPackages.clear()
            ticksSincePersist = 0
            updateOverlayText(0L)
        }
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
            MotionEvent.ACTION_UP -> {
                view.performClick()
                true
            }
            else -> false
        }
    }
}
