package com.timeawareness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timeawareness.app.data.TimerDataStore
import com.timeawareness.app.model.AppTimerState
import com.timeawareness.app.util.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = TimerDataStore(application)

    private val _installedApps = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val _elapsedSnapshot = MutableStateFlow<Map<String, Long>>(emptyMap())

    val appStates: StateFlow<List<AppTimerState>> = combine(
        dataStore.monitoredAppsFlow(),
        _installedApps,
        _elapsedSnapshot
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
                UsageStatsHelper.getInstalledApps(getApplication())
            }
        }
        refresh()
        startElapsedPoll()
    }

    fun setMonitored(pkg: String, monitored: Boolean) {
        viewModelScope.launch { dataStore.setMonitored(pkg, monitored) }
    }

    fun refresh() {
        viewModelScope.launch {
            _elapsedSnapshot.value = dataStore.readAllElapsedToday()
        }
    }

    /**
     * Keep the on-screen elapsed numbers fresh while the app is visible. The service writes
     * to DataStore on a 5s cadence; polling at 2s feels responsive without spamming reads.
     */
    private fun startElapsedPoll() {
        viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                _elapsedSnapshot.value = dataStore.readAllElapsedToday()
            }
        }
    }
}
