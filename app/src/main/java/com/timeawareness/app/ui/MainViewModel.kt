package com.timeawareness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timeawareness.app.data.TimerDataStore
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

    val appStates: StateFlow<List<AppTimerState>> = combine(
        dataStore.monitoredAppsFlow(),
        _installedApps,
        dataStore.allElapsedTodayFlow()
    ) { monitored, installed, elapsed ->
        installed.map { (pkg, label) ->
            AppTimerState(
                packageName = pkg,
                appLabel = label,
                iconKey = pkg,
                isMonitored = pkg in monitored,
                elapsedSeconds = elapsed[pkg] ?: 0L,
                isActiveNow = false
            )
        }
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

    // Kept for the activity's permission-grant callbacks to nudge an icon-list refresh
    // if the user has just sideloaded an app and returns to us.
    fun refreshInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                InstalledAppsCache.refresh(getApplication())
            }
        }
    }
}
