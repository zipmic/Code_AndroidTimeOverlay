package com.timeawareness.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
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
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Foreground service that:
 *   • polls UsageStatsManager every TICK_INTERVAL_MS for foreground transitions,
 *   • increments per-app daily counters while a monitored app is in focus,
 *   • shows/hides a draggable colour-coded overlay timer,
 *   • persists state to DataStore in batched flushes,
 *   • resets counters on a date change,
 *   • updates its persistent notification with the active app + elapsed time.
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking_service"
        private const val NOTIFICATION_ID = 1
        private const val TICK_INTERVAL_MS = 2_000L
        private const val PERSIST_INTERVAL_TICKS = 3   // every 6s with 2s tick
        private const val PERMISSION_RECHECK_MS = 30_000L
        private const val INITIAL_LOOKBACK_MS = 60 * 60 * 1000L  // 1 hour

        // Overlay background colours by elapsed bucket.
        private const val COLOR_NEUTRAL = 0xCC000000.toInt()
        private const val COLOR_WARN = 0xCCB45309.toInt()    // amber
        private const val COLOR_ALERT = 0xCCB91C1C.toInt()   // red
        private const val WARN_AFTER_SECONDS = 30L * 60      // 30 min
        private const val ALERT_AFTER_SECONDS = 60L * 60     // 60 min

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var dataStore: TimerDataStore
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var openAppPendingIntent: PendingIntent

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

    private var cachedHasUsagePermission = false
    private var lastPermissionCheckMs = 0L

    private var cachedOverlayTextSp = TimerDataStore.OVERLAY_SIZE_DEFAULT

    // Display ticker — runs every 1s independently of the 2s tracking tick.
    // displayBaseSeconds + wall-clock elapsed since displayBaseTimeMs gives the
    // interpolated value shown on the overlay, keeping it visually smooth.
    private var displayJob: Job? = null
    private var displayBaseSeconds = 0L
    private var displayBaseTimeMs = 0L

    private var overlayParams: WindowManager.LayoutParams? = null
    private var cachedOverlayPosition: Pair<Int, Int>? = null
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        dataStore = TimerDataStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        openAppPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        startForegroundWithNotification()
        observeMonitoredApps()
        // Warm the overlay position cache so showOverlay doesn't need to block on disk.
        lifecycleScope.launch { cachedOverlayPosition = dataStore.readOverlayPosition() }
        observeOverlaySize()
        startTickLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        removeOverlay()
        displayJob?.cancel()
        tickJob?.cancel()
        runBlocking { flushDirty() }
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "App Timer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Tracks time spent in selected apps" }
        notificationManager.createNotificationChannel(channel)

        val notification = buildNotification("Monitoring active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Time Awareness")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification() {
        val pkg = currentForegroundPkg
        val text = if (pkg != null && pkg in monitoredApps) {
            val label = labelFor(pkg)
            val seconds = elapsedSeconds[pkg] ?: 0L
            "$label — ${FormatUtil.formatSeconds(seconds)}"
        } else {
            "Monitoring active"
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    // ── Monitored app list ────────────────────────────────────────────────────

    private fun observeMonitoredApps() {
        lifecycleScope.launch {
            dataStore.monitoredAppsFlow().collect { apps ->
                monitoredApps = apps
                val newPackages = apps.filter { it !in elapsedSeconds }
                if (newPackages.isNotEmpty()) {
                    val snapshot = dataStore.readAllElapsedToday()
                    newPackages.forEach { pkg -> elapsedSeconds[pkg] = snapshot[pkg] ?: 0L }
                }
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
        lastPollTime = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        tickJob = lifecycleScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private suspend fun tick() {
        if (!hasUsagePermissionCached()) {
            // Permission revoked from Settings — no point continuing to run.
            stopSelf()
            return
        }
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
            updateNotification()
        }

        val pkg = currentForegroundPkg ?: return
        if (pkg !in monitoredApps) return

        val tickSeconds = TICK_INTERVAL_MS / 1000L
        val updated = (elapsedSeconds[pkg] ?: 0L) + tickSeconds
        elapsedSeconds[pkg] = updated
        dirtyPackages += pkg
        // Stamp the authoritative value; the 1s display ticker interpolates from here.
        displayBaseSeconds = updated
        displayBaseTimeMs = System.currentTimeMillis()

        ticksSincePersist++
        if (ticksSincePersist >= PERSIST_INTERVAL_TICKS) {
            flushDirty()
            ticksSincePersist = 0
            updateNotification()
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
            displayBaseSeconds = 0L
            displayBaseTimeMs = System.currentTimeMillis()
            updateNotification()
        }
    }

    private fun observeOverlaySize() {
        lifecycleScope.launch {
            dataStore.overlaySizeFlow().collect { sp ->
                cachedOverlayTextSp = sp
                applyOverlayTextSize()
            }
        }
    }

    private fun applyOverlayTextSize() {
        overlayTimerText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedOverlayTextSp.toFloat())
    }

    // ── Display ticker ────────────────────────────────────────────────────────

    private fun startDisplayTicker(initialSeconds: Long) {
        displayBaseSeconds = initialSeconds
        displayBaseTimeMs = System.currentTimeMillis()
        displayJob?.cancel()
        displayJob = lifecycleScope.launch {
            while (true) {
                val shown = displayBaseSeconds +
                    (System.currentTimeMillis() - displayBaseTimeMs) / 1_000L
                updateOverlay(shown)
                delay(1_000L)
            }
        }
    }

    private fun stopDisplayTicker() {
        displayJob?.cancel()
        displayJob = null
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(initialSeconds: Long) {
        if (!UsageStatsHelper.hasOverlayPermission(this)) return
        if (overlayView != null) return

        val saved = cachedOverlayPosition
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = saved?.first ?: 24
            y = saved?.second ?: 120
        }
        overlayParams = params

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_timer, null)
        overlayTimerText = view.findViewById(R.id.tv_timer)
        view.setOnTouchListener(overlayDragListener)

        try {
            windowManager.addView(view, params)
            overlayView = view
            applyOverlayTextSize()
            startDisplayTicker(initialSeconds)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add overlay view", e)
            overlayView = null
            overlayTimerText = null
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        stopDisplayTicker()
        overlayView = null
        overlayTimerText = null
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // View was already detached by the system — safe to ignore.
            Log.d(TAG, "removeView no-op: ${e.message}")
        }
    }

    private fun updateOverlay(seconds: Long) {
        overlayTimerText?.text = FormatUtil.formatSeconds(seconds)
        val bg = overlayView?.background
        if (bg is GradientDrawable) {
            bg.setColor(colorForElapsed(seconds))
        }
    }

    private fun colorForElapsed(seconds: Long): Int = when {
        seconds < WARN_AFTER_SECONDS -> COLOR_NEUTRAL
        seconds < ALERT_AFTER_SECONDS -> COLOR_WARN
        else -> COLOR_ALERT
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
                    try {
                        windowManager.updateViewLayout(view, p)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout failed during drag", e)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.performClick()
                overlayParams?.let { p ->
                    cachedOverlayPosition = p.x to p.y
                    lifecycleScope.launch { dataStore.saveOverlayPosition(p.x, p.y) }
                }
                true
            }
            else -> false
        }
    }
}
