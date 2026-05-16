package com.timeawareness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = TimerDataStore(application)

    private val installedApps: List<Pair<String, String>> by lazy {
        UsageStatsHelper.getInstalledApps(application)
    }

    // Snapshot of today's elapsed seconds, updated on each refresh.
    private val _elapsedSnapshot = MutableStateFlow<Map<String, Long>>(emptyMap())

    val appStates: StateFlow<List<AppTimerState>> = combine(
        dataStore.monitoredAppsFlow(),
        _elapsedSnapshot
    ) { monitored, elapsed ->
        installedApps.map { (pkg, label) ->
            AppTimerState(
                packageName = pkg,
                appLabel = label,
                iconKey = pkg,
                isMonitored = monitored.contains(pkg),
                elapsedSeconds = elapsed[pkg] ?: 0L,
                isActiveNow = false
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun setMonitored(pkg: String, monitored: Boolean) {
        viewModelScope.launch {
            dataStore.setMonitored(pkg, monitored)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _elapsedSnapshot.value = dataStore.readAllElapsedToday()
        }
    }
}
