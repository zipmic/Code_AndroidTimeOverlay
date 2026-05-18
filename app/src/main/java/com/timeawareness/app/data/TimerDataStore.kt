package com.timeawareness.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "timer_data")

/** Snapshot of the prefs needed by the UI / service. Decoupled from Preferences keys. */
data class TimerSnapshot(
    val monitoredApps: Set<String>,
    val elapsedToday: Map<String, Long>,
    val sessionsToday: Map<String, Int>,
)

enum class OverlayContent { ELAPSED_TIME, CLOCK, SESSION_COUNT, CUSTOM_LABEL }

/**
 * All pro overlay settings in one place.
 * Defaults match the existing visual appearance so existing installs look unchanged.
 */
data class OverlayStyle(
    // Color: h 0..360, s 0..1, l 0..1 — default is opaque near-black (same as original #CC000000)
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val lightness: Float = 0f,
    // Alpha 0..255; 204 = 0xCC ≈ 80 % (original value)
    val alpha: Int = 204,
    val blinkEnabled: Boolean = false,
    val blinkIntervalSeconds: Int = 30,
    val randomMoveEnabled: Boolean = false,
    val randomMoveIntervalSeconds: Int = 30,
    val overlayContent: OverlayContent = OverlayContent.ELAPSED_TIME,
    val customLabel: String = "",
)

class TimerDataStore(private val context: Context) {

    // ── Keys ─────────────────────────────────────────────────────────────────

    private fun elapsedKey(pkg: String) = longPreferencesKey("elapsed_$pkg")
    private fun dateKey(pkg: String) = stringPreferencesKey("date_$pkg")
    private val monitoredAppsKey       = stringSetPreferencesKey("monitored_apps")
    private val masterEnabledKey       = booleanPreferencesKey("master_enabled")
    private val overlayXKey            = intPreferencesKey("overlay_x")
    private val overlayYKey            = intPreferencesKey("overlay_y")
    private val overlaySizeKey         = intPreferencesKey("overlay_size")
    private val globalWarnKey          = intPreferencesKey("global_warn_minutes")
    private val globalAlertKey         = intPreferencesKey("global_alert_minutes")
    private fun appThresholdKey(pkg: String) = stringPreferencesKey("threshold_$pkg")

    private val overlayHueKey          = floatPreferencesKey("overlay_hue")
    private val overlaySaturationKey   = floatPreferencesKey("overlay_saturation")
    private val overlayLightnessKey    = floatPreferencesKey("overlay_lightness")
    private val overlayAlphaKey        = intPreferencesKey("overlay_alpha")
    private val blinkEnabledKey        = booleanPreferencesKey("blink_enabled")
    private val blinkIntervalKey       = intPreferencesKey("blink_interval_seconds")
    private val randomMoveEnabledKey   = booleanPreferencesKey("random_move_enabled")
    private val randomMoveIntervalKey  = intPreferencesKey("random_move_interval_seconds")
    private val overlayContentKey      = stringPreferencesKey("overlay_content")
    private val customLabelKey         = stringPreferencesKey("overlay_custom_label")
    private fun sessionKey(pkg: String) = stringPreferencesKey("session_$pkg")

    companion object {
        const val OVERLAY_SIZE_DEFAULT = 14
        const val OVERLAY_SIZE_MIN     = 10
        const val OVERLAY_SIZE_MAX     = 60

        const val OVERLAY_ALPHA_DEFAULT       = 204   // 0xCC ≈ 80 %
        const val BLINK_INTERVAL_MIN          = 5
        const val BLINK_INTERVAL_MAX          = 120
        const val BLINK_INTERVAL_DEFAULT      = 30
        const val RANDOM_MOVE_INTERVAL_MIN    = 5
        const val RANDOM_MOVE_INTERVAL_MAX    = 300
        const val RANDOM_MOVE_INTERVAL_DEFAULT = 30

        const val WARN_MINUTES_DEFAULT  = 30
        const val ALERT_MINUTES_DEFAULT = 60
        const val THRESHOLD_MINUTES_MIN = 5
        const val THRESHOLD_WARN_MAX    = 120
        const val THRESHOLD_ALERT_MAX   = 300
    }

    // ── Core flows ────────────────────────────────────────────────────────────

    fun monitoredAppsFlow(): Flow<Set<String>> =
        context.dataStore.data
            .map { prefs -> prefs[monitoredAppsKey] ?: emptySet() }
            .distinctUntilChanged()

    fun masterEnabledFlow(): Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[masterEnabledKey] ?: false }
            .distinctUntilChanged()

    /**
     * Combined snapshot — emits whenever monitored apps or any elapsed value changes.
     */
    fun snapshotFlow(): Flow<TimerSnapshot> =
        context.dataStore.data
            .map { prefs ->
                val today = LocalDate.now().toString()
                val monitored = prefs[monitoredAppsKey] ?: emptySet()
                val elapsed = monitored.associateWith { pkg ->
                    if ((prefs[dateKey(pkg)] ?: "") == today) prefs[elapsedKey(pkg)] ?: 0L else 0L
                }
                val sessions = monitored.associateWith { pkg ->
                    parseSessionCount(prefs[sessionKey(pkg)], today)
                }
                TimerSnapshot(monitored, elapsed, sessions)
            }
            .distinctUntilChanged()

    // ── Overlay size ──────────────────────────────────────────────────────────

    fun overlaySizeFlow(): Flow<Int> =
        context.dataStore.data
            .map { prefs -> prefs[overlaySizeKey] ?: OVERLAY_SIZE_DEFAULT }
            .distinctUntilChanged()

    suspend fun saveOverlaySize(sp: Int) {
        context.dataStore.edit { prefs -> prefs[overlaySizeKey] = sp }
    }

    // ── Overlay style (pro) ───────────────────────────────────────────────────

    fun overlayStyleFlow(): Flow<OverlayStyle> =
        context.dataStore.data
            .map { prefs ->
                OverlayStyle(
                    hue                    = prefs[overlayHueKey]        ?: 0f,
                    saturation             = prefs[overlaySaturationKey] ?: 0f,
                    lightness              = prefs[overlayLightnessKey]  ?: 0f,
                    alpha                  = prefs[overlayAlphaKey]      ?: OVERLAY_ALPHA_DEFAULT,
                    blinkEnabled           = prefs[blinkEnabledKey]      ?: false,
                    blinkIntervalSeconds   = prefs[blinkIntervalKey]     ?: BLINK_INTERVAL_DEFAULT,
                    randomMoveEnabled      = prefs[randomMoveEnabledKey] ?: false,
                    randomMoveIntervalSeconds = prefs[randomMoveIntervalKey] ?: RANDOM_MOVE_INTERVAL_DEFAULT,
                    overlayContent         = prefs[overlayContentKey]?.let {
                        runCatching { OverlayContent.valueOf(it) }.getOrDefault(OverlayContent.ELAPSED_TIME)
                    } ?: OverlayContent.ELAPSED_TIME,
                    customLabel            = prefs[customLabelKey]       ?: "",
                )
            }
            .distinctUntilChanged()

    suspend fun saveOverlayStyle(style: OverlayStyle) {
        context.dataStore.edit { prefs ->
            prefs[overlayHueKey]        = style.hue
            prefs[overlaySaturationKey] = style.saturation
            prefs[overlayLightnessKey]  = style.lightness
            prefs[overlayAlphaKey]      = style.alpha
            prefs[blinkEnabledKey]      = style.blinkEnabled
            prefs[blinkIntervalKey]     = style.blinkIntervalSeconds
            prefs[randomMoveEnabledKey] = style.randomMoveEnabled
            prefs[randomMoveIntervalKey] = style.randomMoveIntervalSeconds
            prefs[overlayContentKey]    = style.overlayContent.name
            prefs[customLabelKey]       = style.customLabel
        }
    }

    // ── Overlay position ──────────────────────────────────────────────────────

    suspend fun readOverlayPosition(): Pair<Int, Int>? {
        val prefs = context.dataStore.data.first()
        val x = prefs[overlayXKey] ?: return null
        val y = prefs[overlayYKey] ?: return null
        return x to y
    }

    suspend fun saveOverlayPosition(x: Int, y: Int) {
        context.dataStore.edit { prefs ->
            prefs[overlayXKey] = x
            prefs[overlayYKey] = y
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[masterEnabledKey] = enabled }
    }

    suspend fun isMasterEnabled(): Boolean =
        context.dataStore.data.first()[masterEnabledKey] ?: false

    suspend fun saveElapsedSeconds(pkg: String, seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[elapsedKey(pkg)] = seconds
            prefs[dateKey(pkg)] = LocalDate.now().toString()
        }
    }

    suspend fun resetElapsedSeconds(pkg: String) {
        context.dataStore.edit { prefs ->
            prefs[elapsedKey(pkg)] = 0L
            prefs[dateKey(pkg)] = LocalDate.now().toString()
        }
    }

    suspend fun setMonitored(pkg: String, monitored: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[monitoredAppsKey]?.toMutableSet() ?: mutableSetOf()
            if (monitored) current.add(pkg) else current.remove(pkg)
            prefs[monitoredAppsKey] = current
        }
    }

    suspend fun readMonitoredApps(): Set<String> =
        context.dataStore.data.first()[monitoredAppsKey] ?: emptySet()

    suspend fun readAllElapsedToday(): Map<String, Long> = snapshotFlow().first().elapsedToday

    // ── Thresholds ────────────────────────────────────────────────────────────

    fun globalThresholdsFlow(): Flow<Pair<Int, Int>> =
        context.dataStore.data
            .map { prefs ->
                (prefs[globalWarnKey] ?: WARN_MINUTES_DEFAULT) to
                (prefs[globalAlertKey] ?: ALERT_MINUTES_DEFAULT)
            }
            .distinctUntilChanged()

    /** Returns null when the app has no override (uses global). */
    fun appThresholdsFlow(pkg: String): Flow<Pair<Int, Int>?> =
        context.dataStore.data
            .map { prefs -> parseThreshold(prefs[appThresholdKey(pkg)]) }
            .distinctUntilChanged()

    /** Returns the effective thresholds: per-app override if set, global otherwise. */
    fun resolvedThresholdsFlow(pkg: String): Flow<Pair<Int, Int>> =
        context.dataStore.data
            .map { prefs ->
                parseThreshold(prefs[appThresholdKey(pkg)])
                    ?: ((prefs[globalWarnKey] ?: WARN_MINUTES_DEFAULT) to
                        (prefs[globalAlertKey] ?: ALERT_MINUTES_DEFAULT))
            }
            .distinctUntilChanged()

    suspend fun saveGlobalThresholds(warnMin: Int, alertMin: Int) {
        context.dataStore.edit { prefs ->
            prefs[globalWarnKey]  = warnMin
            prefs[globalAlertKey] = alertMin
        }
    }

    suspend fun saveAppThreshold(pkg: String, warnMin: Int, alertMin: Int) {
        context.dataStore.edit { prefs -> prefs[appThresholdKey(pkg)] = "$warnMin,$alertMin" }
    }

    suspend fun clearAppThreshold(pkg: String) {
        context.dataStore.edit { prefs -> prefs.remove(appThresholdKey(pkg)) }
    }

    private fun parseThreshold(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        return runCatching { parts[0].toInt() to parts[1].toInt() }.getOrNull()
    }

    // ── Usage history ─────────────────────────────────────────────────────────

    private fun historyKey(pkg: String) = stringPreferencesKey("history_$pkg")

    private fun parseHistory(raw: String): Map<LocalDate, Long> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size != 2) return@mapNotNull null
            runCatching { LocalDate.parse(parts[0]) to parts[1].toLong() }.getOrNull()
        }.toMap()
    }

    private fun serializeHistory(history: Map<LocalDate, Long>): String =
        history.entries.joinToString("|") { "${it.key}=${it.value}" }

    fun historyFlow(pkg: String): Flow<Map<LocalDate, Long>> =
        context.dataStore.data
            .map { prefs -> parseHistory(prefs[historyKey(pkg)] ?: "") }
            .distinctUntilChanged()

    // ── Session counts ────────────────────────────────────────────────────────

    private fun parseSessionCount(raw: String?, today: String): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.split("=")
        if (parts.size != 2 || parts[0] != today) return 0
        return parts[1].toIntOrNull() ?: 0
    }

    suspend fun saveSessionCount(pkg: String, date: LocalDate, count: Int) {
        context.dataStore.edit { prefs -> prefs[sessionKey(pkg)] = "$date=$count" }
    }

    suspend fun readAllSessionsToday(): Map<String, Int> {
        val prefs   = context.dataStore.data.first()
        val today   = LocalDate.now().toString()
        val monitored = prefs[monitoredAppsKey] ?: emptySet()
        return monitored.associateWith { pkg -> parseSessionCount(prefs[sessionKey(pkg)], today) }
    }

    suspend fun saveHistoryEntry(pkg: String, date: LocalDate, seconds: Long) {
        context.dataStore.edit { prefs ->
            val key = historyKey(pkg)
            val updated = (parseHistory(prefs[key] ?: "") + (date to seconds))
                .entries
                .sortedByDescending { it.key }
                .take(30)
                .associate { it.key to it.value }
            prefs[key] = serializeHistory(updated)
        }
    }
}
