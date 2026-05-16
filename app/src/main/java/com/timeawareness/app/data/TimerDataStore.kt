package com.timeawareness.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "timer_data")

class TimerDataStore(private val context: Context) {

    private fun elapsedKey(pkg: String) = longPreferencesKey("elapsed_$pkg")
    private fun dateKey(pkg: String) = stringPreferencesKey("date_$pkg")
    private val monitoredAppsKey = stringSetPreferencesKey("monitored_apps")

    // Returns today's elapsed seconds for a package, or 0 if the stored date is not today.
    fun elapsedSecondsFlow(pkg: String): Flow<Long> =
        context.dataStore.data.map { prefs ->
            val storedDate = prefs[dateKey(pkg)] ?: ""
            val today = LocalDate.now().toString()
            if (storedDate == today) prefs[elapsedKey(pkg)] ?: 0L else 0L
        }

    fun monitoredAppsFlow(): Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[monitoredAppsKey] ?: emptySet() }

    suspend fun saveElapsedSeconds(pkg: String, seconds: Long) {
        context.dataStore.edit { prefs ->
            prefs[elapsedKey(pkg)] = seconds
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

    // Returns a snapshot map of packageName -> elapsedSeconds (today only).
    suspend fun readAllElapsedToday(): Map<String, Long> {
        val today = LocalDate.now().toString()
        val prefs = context.dataStore.data.first()
        val monitored = prefs[monitoredAppsKey] ?: emptySet()
        return monitored.associateWith { pkg ->
            if ((prefs[dateKey(pkg)] ?: "") == today) prefs[elapsedKey(pkg)] ?: 0L else 0L
        }
    }
}
