package com.mindfulfocus.ui.bell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulfocus.core.IntervalBellSettings
import com.mindfulfocus.core.MeditationSettings
import com.mindfulfocus.core.RandomMindfulSettings
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionController
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.service.TimerForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.app.Application
import androidx.lifecycle.AndroidViewModel

class BellViewModel(
    app: Application,
    private val controller: SessionController
) : AndroidViewModel(app) {

    private val context = app.applicationContext

    val intervalSettings: StateFlow<IntervalBellSettings> =
        controller.intervalBellSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), IntervalBellSettings())

    val meditationSettings: StateFlow<MeditationSettings> =
        controller.meditationSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), MeditationSettings())

    val randomSettings: StateFlow<RandomMindfulSettings> =
        controller.randomMindfulSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), RandomMindfulSettings())

    val intervalSession: StateFlow<SessionSnapshot> =
        controller.observeSession(SessionType.INTERVAL_BELL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), SessionSnapshot(SessionType.INTERVAL_BELL))

    val meditationSession: StateFlow<SessionSnapshot> =
        controller.observeSession(SessionType.MEDITATION)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), SessionSnapshot(SessionType.MEDITATION))

    val randomSession: StateFlow<SessionSnapshot> =
        controller.observeSession(SessionType.RANDOM_MINDFUL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), SessionSnapshot(SessionType.RANDOM_MINDFUL))

    // ----- settings edits -----
    fun updateInterval(block: (IntervalBellSettings) -> IntervalBellSettings) = viewModelScope.launch {
        controller.saveIntervalBell(block(intervalSettings.value))
    }

    fun updateMeditation(block: (MeditationSettings) -> MeditationSettings) = viewModelScope.launch {
        controller.saveMeditation(block(meditationSettings.value))
    }

    fun updateRandom(block: (RandomMindfulSettings) -> RandomMindfulSettings) = viewModelScope.launch {
        controller.saveRandomMindful(block(randomSettings.value))
    }

    // ----- controls (each targets its own session -> independent state) -----
    fun startInterval() = run {
        viewModelScope.launch { controller.startIntervalBell() }
        ensureService()
    }

    fun startMeditation() = run {
        viewModelScope.launch { controller.startMeditation() }
        ensureService()
    }

    fun startRandom() = run {
        viewModelScope.launch { controller.startRandomMindful() }
        ensureService()
    }

    fun pause(type: SessionType) = viewModelScope.launch {
        controller.pause(type)
    }

    fun resume(type: SessionType) = run {
        viewModelScope.launch { controller.resume(type) }
        ensureService()
    }

    fun stop(type: SessionType) = viewModelScope.launch {
        controller.stop(type)
    }

    /** Preview one of the synthesised blind-box sounds (点选即试听). */
    fun previewBlindSound(sound: com.mindfulfocus.core.BlindBoxSound) {
        controller.previewBlindSound(sound, randomSettings.value.volumePercent)
    }

    private fun ensureService() {
        TimerForegroundService.start(context)
    }

    /** True when no bell session is currently active (used to enable Start buttons). */
    fun isIdle(snapshot: SessionSnapshot) = snapshot.status == RunStatus.IDLE
}
