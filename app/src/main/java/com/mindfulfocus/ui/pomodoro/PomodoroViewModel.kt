package com.mindfulfocus.ui.pomodoro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulfocus.core.PomodoroPhase
import com.mindfulfocus.core.PomodoroSettings
import com.mindfulfocus.core.RunStatus
import com.mindfulfocus.core.SessionController
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import com.mindfulfocus.service.TimerForegroundService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PomodoroViewModel(
    app: Application,
    private val controller: SessionController
) : AndroidViewModel(app) {

    private val context = app.applicationContext

    private val _taskName = kotlinx.coroutines.flow.MutableStateFlow("")
    val taskName: StateFlow<String> = _taskName

    val settings: StateFlow<PomodoroSettings> =
        controller.pomodoroSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), PomodoroSettings())

    val session: StateFlow<SessionSnapshot> =
        controller.observeSession(SessionType.POMODORO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), SessionSnapshot(SessionType.POMODORO))

    fun setTaskName(name: String) { _taskName.value = name }

    fun updateSettings(block: (PomodoroSettings) -> PomodoroSettings) = viewModelScope.launch {
        controller.savePomodoro(block(settings.value))
    }

    fun start() = run {
        viewModelScope.launch { controller.startPomodoro() }
        TimerForegroundService.start(context)
    }

    fun startNextPhase() = run {
        viewModelScope.launch { controller.startNextPomodoroPhase() }
        TimerForegroundService.start(context)
    }

    fun pause() = viewModelScope.launch { controller.pause(SessionType.POMODORO) }

    fun resume() = run {
        viewModelScope.launch { controller.resume(SessionType.POMODORO) }
        TimerForegroundService.start(context)
    }

    fun stop() = viewModelScope.launch { controller.stop(SessionType.POMODORO) }

    /** Pomodoro is at a phase boundary waiting for the user (no running countdown). */
    fun isAtBoundary(s: SessionSnapshot): Boolean =
        s.status == RunStatus.RUNNING && s.phaseEndEpochMs <= 0L
}
