package com.timeawareness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timeawareness.app.data.TimerDataStore
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

    /**
     * Apps shown in the list, sorted with monitored apps first and the rest
     * alphabetically below. Built from a single combined snapshot flow that
     * only emits when DataStore actually changes.
     */
    val appStates: StateFlow<List<AppTimerState>> = combine(
        _installedApps,
        dataStore.snapshotFlow()
    ) { installed, snapshot ->
        installed
            .map { (pkg, label) ->
                AppTimerState(
                    packageName = pkg,
                    appLabel = label,
                    isMonitored = pkg in snapshot.monitoredApps,
                    elapsedSeconds = snapshot.elapsedToday[pkg] ?: 0L,
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
        val clamped = sp.coerceIn(OVERLAY_SIZE_MIN, OVERLAY_SIZE_MAX)
        viewModelScope.launch { dataStore.saveOverlaySize(clamped) }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                InstalledAppsCache.refresh(getApplication())
            }
        }
    }
}
