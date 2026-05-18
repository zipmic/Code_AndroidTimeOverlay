package com.timeawareness.app.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timeawareness.app.data.ActiveSchedule
import com.timeawareness.app.data.OverlayStyle
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.util.CsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_MINUTES_MIN
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_WARN_MAX
import com.timeawareness.app.data.TimerDataStore.Companion.THRESHOLD_ALERT_MAX
import com.timeawareness.app.data.TimerDataStore.Companion.OVERLAY_SIZE_MAX
import com.timeawareness.app.data.TimerDataStore.Companion.OVERLAY_SIZE_MIN
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.util.InstalledAppsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = TimerDataStore(application)

    private val _installedApps = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    val masterEnabled: StateFlow<Boolean> = dataStore.masterEnabledFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val overlaySize: StateFlow<Int> = dataStore.overlaySizeFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerDataStore.OVERLAY_SIZE_DEFAULT)

    val overlayStyle: StateFlow<OverlayStyle> = dataStore.overlayStyleFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, OverlayStyle())

    /**
     * Apps shown in the list, sorted monitored-first then alphabetically.
     * Built from a single combined snapshot flow that only emits on real changes.
     */
    val appStates: StateFlow<List<AppTimerState>> = combine(
        _installedApps,
        dataStore.snapshotFlow()
    ) { installed, snapshot ->
        installed
            .map { (pkg, label) ->
                AppTimerState(
                    packageName    = pkg,
                    appLabel       = label,
                    isMonitored    = pkg in snapshot.monitoredApps,
                    elapsedSeconds = snapshot.elapsedToday[pkg] ?: 0L,
                    sessionCount   = snapshot.sessionsToday[pkg] ?: 0,
                )
            }
            .sortedWith(
                compareByDescending<AppTimerState> { it.isMonitored }
                    .thenBy { it.appLabel.lowercase() }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                InstalledAppsCache.get(getApplication())
            }
        }
    }

    fun setMonitored(pkg: String, monitored: Boolean) {
        viewModelScope.launch { dataStore.setMonitored(pkg, monitored) }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setMasterEnabled(enabled) }
    }

    fun resetTimer(pkg: String) {
        viewModelScope.launch { dataStore.resetElapsedSeconds(pkg) }
    }

    fun setOverlaySize(sp: Int) {
        viewModelScope.launch { dataStore.saveOverlaySize(sp.coerceIn(OVERLAY_SIZE_MIN, OVERLAY_SIZE_MAX)) }
    }

    fun setOverlayStyle(style: OverlayStyle) {
        viewModelScope.launch { dataStore.saveOverlayStyle(style) }
    }

    fun historyFor(pkg: String): Flow<Map<LocalDate, Long>> = dataStore.historyFlow(pkg)

    val globalThresholds: StateFlow<Pair<Int, Int>> = dataStore.globalThresholdsFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly,
            TimerDataStore.WARN_MINUTES_DEFAULT to TimerDataStore.ALERT_MINUTES_DEFAULT)

    fun setGlobalThresholds(warnMin: Int, alertMin: Int) {
        val w = warnMin.coerceIn(THRESHOLD_MINUTES_MIN, THRESHOLD_WARN_MAX)
        val a = alertMin.coerceIn(w + 1, THRESHOLD_ALERT_MAX)
        viewModelScope.launch { dataStore.saveGlobalThresholds(w, a) }
    }

    fun appThresholdsFor(pkg: String): Flow<Pair<Int, Int>?> = dataStore.appThresholdsFlow(pkg)

    fun setAppThreshold(pkg: String, warnMin: Int, alertMin: Int) {
        val w = warnMin.coerceIn(THRESHOLD_MINUTES_MIN, THRESHOLD_WARN_MAX)
        val a = alertMin.coerceIn(w + 1, THRESHOLD_ALERT_MAX)
        viewModelScope.launch { dataStore.saveAppThreshold(pkg, w, a) }
    }

    fun clearAppThreshold(pkg: String) {
        viewModelScope.launch { dataStore.clearAppThreshold(pkg) }
    }

    val dailySummaryEnabled: StateFlow<Boolean> = dataStore.dailySummaryEnabledFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setDailySummaryEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setDailySummaryEnabled(enabled) }
    }

    val activeSchedule: StateFlow<ActiveSchedule> = dataStore.activeScheduleFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveSchedule())

    fun setActiveSchedule(schedule: ActiveSchedule) {
        viewModelScope.launch { dataStore.saveActiveSchedule(schedule) }
    }

    suspend fun exportHistory(): Uri? = withContext(Dispatchers.IO) {
        val app      = getApplication<Application>()
        val monitored = dataStore.readMonitoredApps()
        if (monitored.isEmpty()) return@withContext null
        val history  = dataStore.readAllHistory(monitored)
        val csv      = CsvExporter.buildCsv(history) { pkg ->
            try {
                app.packageManager
                    .getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0))
                    .toString()
            } catch (e: Exception) { pkg }
        }
        val file = CsvExporter.writeToCache(app, csv)
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                InstalledAppsCache.refresh(getApplication())
            }
        }
    }
}
