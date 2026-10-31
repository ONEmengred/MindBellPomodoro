package com.mindfulfocus.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mindfulfocus.data.HistoryDao
import com.mindfulfocus.data.MeditationRecord
import com.mindfulfocus.data.PomodoroRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val dao: HistoryDao) : ViewModel() {

    val meditations: StateFlow<List<MeditationRecord>> =
        dao.observeMeditations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), emptyList())

    val pomodoros: StateFlow<List<PomodoroRecord>> =
        dao.observePomodoros().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), emptyList())

    val totalMeditationMs: StateFlow<Long> =
        dao.observeTotalMeditationMs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), 0L)

    val completedPomodoroCount: StateFlow<Int> =
        dao.observeCompletedPomodoroCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), 0)

    val totalFocusMs: StateFlow<Long> =
        dao.observeTotalFocusMs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), 0L)

    fun clearAll() = viewModelScope.launch {
        dao.clearMeditations()
        dao.clearPomodoros()
    }
}
