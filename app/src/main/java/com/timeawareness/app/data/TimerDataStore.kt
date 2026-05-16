package com.timeawareness.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
)

class TimerDataStore(private val context: Context) {

    private fun elapsedKey(pkg: String) = longPreferencesKey("elapsed_$pkg")
    private fun dateKey(pkg: String) = stringPreferencesKey("date_$pkg")
    private val monitoredAppsKey = stringSetPreferencesKey("monitored_apps")
    private val masterEnabledKey = booleanPreferencesKey("master_enabled")
    private val overlayXKey = intPreferencesKey("overlay_x")
    private val overlayYKey = intPreferencesKey("overlay_y")
    private val overlaySizeKey = intPreferencesKey("overlay_size")

    companion object {
        const val OVERLAY_SIZE_DEFAULT = 14
        const val OVERLAY_SIZE_MIN = 10
        const val OVERLAY_SIZE_MAX = 32
    }

    fun monitoredAppsFlow(): Flow<Set<String>> =
        context.dataStore.data
            .map { prefs -> prefs[monitoredAppsKey] ?: emptySet() }
            .distinctUntilChanged()

    fun masterEnabledFlow(): Flow<Boolean> =
        context.dataStore.data
            .map { prefs -> prefs[masterEnabledKey] ?: false }
            .distinctUntilChanged()

    /**
     * Combined snapshot flow — emits whenever monitored apps or any elapsed value changes.
     * Replaces the two separate flows previously consumed via `combine` in the ViewModel
     * (and dedupes identical emissions automatically).
     */
    fun snapshotFlow(): Flow<TimerSnapshot> =
        context.dataStore.data
            .map { prefs ->
                val today = LocalDate.now().toString()
                val monitored = prefs[monitoredAppsKey] ?: emptySet()
                val elapsed = monitored.associateWith { pkg ->
                    if ((prefs[dateKey(pkg)] ?: "") == today) prefs[elapsedKey(pkg)] ?: 0L else 0L
                }
                TimerSnapshot(monitored, elapsed)
            }
            .distinctUntilChanged()

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

    // ── Overlay size ──────────────────────────────────────────────────────────

    fun overlaySizeFlow(): Flow<Int> =
        context.dataStore.data
            .map { prefs -> prefs[overlaySizeKey] ?: OVERLAY_SIZE_DEFAULT }
            .distinctUntilChanged()

    suspend fun saveOverlaySize(sp: Int) {
        context.dataStore.edit { prefs -> prefs[overlaySizeKey] = sp }
    }

    // ── Overlay position ─────────────────────────────────────────────────────

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
}
