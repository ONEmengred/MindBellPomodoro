package com.mindfulfocus.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mindfulfocus.MindFocusApp
import com.mindfulfocus.ui.bell.BellViewModel
import com.mindfulfocus.ui.history.HistoryViewModel
import com.mindfulfocus.ui.pomodoro.PomodoroViewModel

/**
 * Concrete ViewModel factories built from the app's container. No DI framework
 * needed — each factory grabs the [MindFocusApp] and reads what it requires.
 */
object AppViewModelProviders {

    private fun CreationExtras.app(): MindFocusApp =
        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MindFocusApp

    fun pomodoroFactory() = viewModelFactory {
        initializer {
            val app = app()
            PomodoroViewModel(app, app.container.controller)
        }
    }

    fun bellFactory() = viewModelFactory {
        initializer {
            val app = app()
            BellViewModel(app, app.container.controller)
        }
    }

    fun historyFactory() = viewModelFactory {
        initializer {
            val app = app()
            HistoryViewModel(app.container.historyDao)
        }
    }
}
