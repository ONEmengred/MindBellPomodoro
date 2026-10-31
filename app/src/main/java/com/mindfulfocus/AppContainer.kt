package com.mindfulfocus

import android.content.Context
import com.mindfulfocus.audio.BellPlayer
import com.mindfulfocus.audio.ToneSynth
import com.mindfulfocus.audio.VibrationManager
import com.mindfulfocus.core.SessionController
import com.mindfulfocus.data.AppDataStore
import com.mindfulfocus.data.AppDatabase

/**
 * Tiny manual dependency container (no Hilt needed for a project this size).
 * Held by the Application so the foreground service and every ViewModel share
 * the exact same controller / datastore / database instances.
 */
class AppContainer(context: Context) {

    val dataStore = AppDataStore(context.applicationContext)
    val database = AppDatabase.get(context)
    private val synth = ToneSynth()
    private val bellPlayer = BellPlayer(context.applicationContext, synth)
    private val vibration = VibrationManager(context.applicationContext)

    val controller = SessionController(
        store = dataStore,
        history = database.historyDao(),
        bellPlayer = bellPlayer,
        vibration = vibration,
        synth = synth
    )

    val historyDao get() = database.historyDao()
}
