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
import android.util.DisplayMetrics
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
import com.timeawareness.app.data.ActiveSchedule
import com.timeawareness.app.data.OverlayContent
import com.timeawareness.app.data.OverlayStyle
import java.time.LocalTime
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.ui.MainActivity
import com.timeawareness.app.util.ColorUtil
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
        private const val CHANNEL_ID         = "tracking_service"
        private const val SUMMARY_CHANNEL_ID  = "daily_summary"
        private const val NOTIFICATION_ID     = 1
        private const val SUMMARY_NOTIFICATION_ID = 2
        private const val TICK_INTERVAL_MS = 2_000L
        private const val PERSIST_INTERVAL_TICKS = 3   // every 6 s with 2 s tick
        private const val PERMISSION_RECHECK_MS = 30_000L
        private const val INITIAL_LOOKBACK_MS = 60 * 60 * 1000L  // 1 hour

        // Fixed colours for time-based warn/alert thresholds (not user-customisable).
        private const val COLOR_WARN  = 0xCCB45309.toInt()   // amber
        private const val COLOR_ALERT = 0xCCB91C1C.toInt()   // red

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

    private val elapsedSeconds  = mutableMapOf<String, Long>()
    private val sessionCounts   = mutableMapOf<String, Int>()
    private val dirtyPackages   = mutableSetOf<String>()
    private val dirtySessionPkgs = mutableSetOf<String>()
    private var ticksSincePersist = 0

    private var monitoredApps        = setOf<String>()
    private var currentForegroundPkg: String? = null
    private var currentDate: LocalDate = LocalDate.now()
    private var lastPollTime          = System.currentTimeMillis()
    private var tickJob: Job?         = null

    private var cachedHasUsagePermission = false
    private var lastPermissionCheckMs    = 0L

    private var cachedOverlayTextSp        = TimerDataStore.OVERLAY_SIZE_DEFAULT
    private var cachedOverlayStyle         = OverlayStyle()
    private var cachedDailySummaryEnabled  = false
    private var cachedActiveSchedule       = ActiveSchedule()

    // Per-app thresholds: updated reactively when the foreground app changes.
    private var currentWarnSeconds  = TimerDataStore.WARN_MINUTES_DEFAULT  * 60L
    private var currentAlertSeconds = TimerDataStore.ALERT_MINUTES_DEFAULT * 60L
    private var currentAppThresholdsJob: Job? = null

    // Display ticker — smooth 1 s visual refresh independent of the 2 s tracking tick.
    private var displayJob: Job?     = null
    private var displayBaseSeconds   = 0L
    private var displayBaseTimeMs    = 0L
    private var lastBlinkMs          = 0L

    // Random-movement job — separate from display ticker so each has its own interval.
    private var moveJob: Job?        = null

    // Set true during a user drag to prevent random movement fighting the gesture.
    private var isDragging = false

    private var overlayParams: WindowManager.LayoutParams? = null
    private var cachedOverlayPosition: Pair<Int, Int>? = null
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX   = 0f
    private var dragTouchY   = 0f

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        dataStore = TimerDataStore(this)
        windowManager     = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        openAppPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        startForegroundWithNotification()
        observeMonitoredApps()
        lifecycleScope.launch { cachedOverlayPosition = dataStore.readOverlayPosition() }
        observeOverlaySize()
        observeOverlayStyle()
        observeDailySummaryEnabled()
        observeActiveSchedule()
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
        moveJob?.cancel()
        currentAppThresholdsJob?.cancel()
        tickJob?.cancel()
        runBlocking { flushDirty() }
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        notificationManager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ID, "App Timer", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Tracks time spent in selected apps" },
            NotificationChannel(SUMMARY_CHANNEL_ID, "Daily Summary", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "End-of-day usage summary" },
        ))

        val notification = buildNotification("Monitoring active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
    } catch (e: Exception) { pkg }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeMonitoredApps() {
        lifecycleScope.launch {
            dataStore.monitoredAppsFlow().collect { apps ->
                monitoredApps = apps
                val newPackages = apps.filter { it !in elapsedSeconds }
                if (newPackages.isNotEmpty()) {
                    val snapshot        = dataStore.readAllElapsedToday()
                    val sessionSnapshot = dataStore.readAllSessionsToday()
                    newPackages.forEach { pkg ->
                        elapsedSeconds[pkg] = snapshot[pkg] ?: 0L
                        sessionCounts[pkg]  = sessionSnapshot[pkg] ?: 0
                    }
                }
                val fg = currentForegroundPkg
                if (overlayView != null && (fg == null || fg !in apps)) removeOverlay()
                if (apps.isEmpty()) stopSelf()
            }
        }
        lifecycleScope.launch {
            dataStore.masterEnabledFlow().collect { enabled ->
                if (!enabled) stopSelf()
            }
        }
    }

    private fun startThresholdObserver(pkg: String) {
        currentAppThresholdsJob?.cancel()
        currentAppThresholdsJob = lifecycleScope.launch {
            dataStore.resolvedThresholdsFlow(pkg).collect { (warnMin, alertMin) ->
                currentWarnSeconds  = warnMin  * 60L
                currentAlertSeconds = alertMin * 60L
            }
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

    private fun observeOverlayStyle() {
        lifecycleScope.launch {
            dataStore.overlayStyleFlow().collect { style ->
                val prev = cachedOverlayStyle
                cachedOverlayStyle = style
                // Restart the move job only when movement settings actually changed.
                if (overlayView != null && (
                    style.randomMoveEnabled      != prev.randomMoveEnabled ||
                    style.randomMoveIntervalSeconds != prev.randomMoveIntervalSeconds
                )) {
                    restartMoveJobIfNeeded()
                }
            }
        }
    }

    private fun observeDailySummaryEnabled() {
        lifecycleScope.launch {
            dataStore.dailySummaryEnabledFlow().collect { enabled ->
                cachedDailySummaryEnabled = enabled
            }
        }
    }

    private fun observeActiveSchedule() {
        lifecycleScope.launch {
            dataStore.activeScheduleFlow().collect { schedule ->
                cachedActiveSchedule = schedule
                // Recheck overlay visibility immediately if a monitored app is in the foreground.
                ensureOverlayState()
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
            if (foreground != null && foreground in monitoredApps) {
                val newCount = (sessionCounts[foreground] ?: 0) + 1
                sessionCounts[foreground] = newCount
                dirtySessionPkgs += foreground
            }
            updateNotification()
        }

        // Reconcile overlay with current schedule — runs every tick to catch hour/day transitions.
        ensureOverlayState()

        val pkg = currentForegroundPkg ?: return
        if (pkg !in monitoredApps) return

        val updated = (elapsedSeconds[pkg] ?: 0L) + TICK_INTERVAL_MS / 1000L
        elapsedSeconds[pkg] = updated
        dirtyPackages += pkg
        // Stamp the authoritative value; display ticker interpolates from here.
        displayBaseSeconds = updated
        displayBaseTimeMs  = System.currentTimeMillis()

        ticksSincePersist++
        if (ticksSincePersist >= PERSIST_INTERVAL_TICKS) {
            flushDirty()
            ticksSincePersist = 0
            updateNotification()
        }
    }

    private fun isActiveNow(): Boolean {
        val s = cachedActiveSchedule
        val dayBit = LocalDate.now().dayOfWeek.value - 1  // 0=Mon … 6=Sun
        if ((s.activeDays shr dayBit) and 1 == 0) return false
        if (!s.activeHoursEnabled) return true
        val nowHour = LocalTime.now().hour
        return nowHour in s.activeHoursStartHour..s.activeHoursEndHour
    }

    private fun ensureOverlayState() {
        val pkg = currentForegroundPkg
        if (pkg == null || pkg !in monitoredApps) {
            if (overlayView != null) removeOverlay()
            return
        }
        val shouldShow = isActiveNow()
        if (shouldShow && overlayView == null) {
            showOverlay(elapsedSeconds[pkg] ?: 0L)
            startThresholdObserver(pkg)
        } else if (!shouldShow && overlayView != null) {
            removeOverlay()
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
        if (dirtyPackages.isEmpty() && dirtySessionPkgs.isEmpty()) return
        val today = LocalDate.now()
        if (dirtyPackages.isNotEmpty()) {
            val snapshot = dirtyPackages.toList()
            dirtyPackages.clear()
            snapshot.forEach { pkg ->
                val seconds = elapsedSeconds[pkg] ?: 0L
                dataStore.saveElapsedSeconds(pkg, seconds)
                // Keep today's history entry current so the chart reflects live progress.
                dataStore.saveHistoryEntry(pkg, today, seconds)
            }
        }
        if (dirtySessionPkgs.isNotEmpty()) {
            val sessionSnapshot = dirtySessionPkgs.toList()
            dirtySessionPkgs.clear()
            sessionSnapshot.forEach { pkg ->
                dataStore.saveSessionCount(pkg, today, sessionCounts[pkg] ?: 0)
            }
        }
    }

    private suspend fun handleMidnightRollover() {
        val today = LocalDate.now()
        if (today != currentDate) {
            val yesterday         = currentDate
            val yesterdayElapsed  = elapsedSeconds.toMap()
            // Persist yesterday's final totals before clearing the in-memory map.
            yesterdayElapsed.forEach { (pkg, seconds) ->
                if (seconds > 0) dataStore.saveHistoryEntry(pkg, yesterday, seconds)
            }
            sendDailySummary(yesterdayElapsed)
            currentDate = today
            elapsedSeconds.clear()
            sessionCounts.clear()
            dirtyPackages.clear()
            dirtySessionPkgs.clear()
            ticksSincePersist  = 0
            displayBaseSeconds = 0L
            displayBaseTimeMs  = System.currentTimeMillis()
            updateNotification()
        }
    }

    private fun sendDailySummary(elapsed: Map<String, Long>) {
        if (!cachedDailySummaryEnabled) return
        val sorted = elapsed.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        if (sorted.isEmpty()) return
        val total = sorted.sumOf { it.value }
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle("Yesterday's Summary")
        sorted.take(5).forEach { (pkg, seconds) ->
            inboxStyle.addLine("${labelFor(pkg)} — ${FormatUtil.formatHumanShort(seconds)}")
        }
        if (sorted.size > 5) inboxStyle.setSummaryText("+${sorted.size - 5} more")
        val notification = NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
            .setContentTitle("Yesterday: ${FormatUtil.formatHumanShort(total)}")
            .setContentText("Across ${sorted.size} app${if (sorted.size == 1) "" else "s"}")
            .setSmallIcon(R.drawable.ic_timer)
            .setStyle(inboxStyle)
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    // ── Overlay color ─────────────────────────────────────────────────────────

    private fun applyOverlayTextSize() {
        overlayTimerText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, cachedOverlayTextSp.toFloat())
    }

    private fun colorForElapsed(seconds: Long): Int {
        val s = cachedOverlayStyle
        return when {
            seconds < currentWarnSeconds  -> ColorUtil.hslToArgb(s.hue, s.saturation, s.lightness, s.alpha)
            seconds < currentAlertSeconds -> COLOR_WARN
            else                          -> COLOR_ALERT
        }
    }

    // ── Display ticker ────────────────────────────────────────────────────────

    private fun startDisplayTicker(initialSeconds: Long) {
        displayBaseSeconds = initialSeconds
        displayBaseTimeMs  = System.currentTimeMillis()
        lastBlinkMs        = System.currentTimeMillis()
        displayJob?.cancel()
        displayJob = lifecycleScope.launch {
            while (true) {
                val now   = System.currentTimeMillis()
                val shown = displayBaseSeconds + (now - displayBaseTimeMs) / 1_000L
                updateOverlay(shown)

                val style = cachedOverlayStyle
                if (style.blinkEnabled && (now - lastBlinkMs) >= style.blinkIntervalSeconds * 1_000L) {
                    lastBlinkMs = now
                    // Fire-and-forget: brief alpha flash, does not block the display tick.
                    launch {
                        overlayView?.alpha = 0f
                        delay(200L)
                        overlayView?.alpha = 1f
                    }
                }

                delay(1_000L)
            }
        }
    }

    private fun stopDisplayTicker() {
        displayJob?.cancel()
        displayJob = null
    }

    // ── Random movement ───────────────────────────────────────────────────────

    private fun restartMoveJobIfNeeded() {
        moveJob?.cancel()
        moveJob = null
        if (!cachedOverlayStyle.randomMoveEnabled || overlayView == null) return
        moveJob = lifecycleScope.launch {
            while (true) {
                delay(cachedOverlayStyle.randomMoveIntervalSeconds * 1_000L)
                if (!isDragging) moveOverlayRandomly()
            }
        }
    }

    private fun moveOverlayRandomly() {
        val p    = overlayParams ?: return
        val view = overlayView   ?: return

        val (screenW, screenH) = getScreenSize()
        val vw = view.width.takeIf  { it > 0 } ?: 200
        val vh = view.height.takeIf { it > 0 } ?: 80

        val maxX = (screenW - vw).coerceAtLeast(0)
        // Leave generous top/bottom margin for status bar and nav bar.
        val minY = 80
        val maxY = (screenH - vh - 200).coerceAtLeast(minY)

        p.x = (0..maxX).random()
        p.y = (minY..maxY).random()

        try {
            windowManager.updateViewLayout(view, p)
            cachedOverlayPosition = p.x to p.y
        } catch (e: Exception) {
            Log.w(TAG, "moveOverlayRandomly: updateViewLayout failed", e)
        }
    }

    private fun getScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(m)
            m.widthPixels to m.heightPixels
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
            x = saved?.first  ?: 24
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
            restartMoveJobIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add overlay view", e)
            overlayView      = null
            overlayTimerText = null
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        stopDisplayTicker()
        moveJob?.cancel()
        moveJob = null
        currentAppThresholdsJob?.cancel()
        currentAppThresholdsJob = null
        currentWarnSeconds  = TimerDataStore.WARN_MINUTES_DEFAULT  * 60L
        currentAlertSeconds = TimerDataStore.ALERT_MINUTES_DEFAULT * 60L
        overlayView      = null
        overlayTimerText = null
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "removeView no-op: ${e.message}")
        }
    }

    private fun updateOverlay(seconds: Long) {
        val text = when (cachedOverlayStyle.overlayContent) {
            OverlayContent.ELAPSED_TIME  -> FormatUtil.formatSeconds(seconds)
            OverlayContent.CLOCK         -> FormatUtil.formatClock()
            OverlayContent.SESSION_COUNT -> "×${sessionCounts[currentForegroundPkg] ?: 0}"
            OverlayContent.CUSTOM_LABEL  -> cachedOverlayStyle.customLabel.ifBlank { "▶" }
        }
        overlayTimerText?.text = text
        val bg = overlayView?.background
        if (bg is GradientDrawable) bg.setColor(colorForElapsed(seconds))
    }

    // ── Drag listener ─────────────────────────────────────────────────────────

    private val overlayDragListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging   = true
                dragInitialX = overlayParams?.x ?: 0
                dragInitialY = overlayParams?.y ?: 0
                dragTouchX   = event.rawX
                dragTouchY   = event.rawY
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
                isDragging = false
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
