package com.mindfulfocus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mindfulfocus.core.IntervalBellSettings
import com.mindfulfocus.core.MeditationSettings
import com.mindfulfocus.core.PomodoroSettings
import com.mindfulfocus.core.RandomMindfulSettings
import com.mindfulfocus.core.SessionSnapshot
import com.mindfulfocus.core.SessionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mind_focus_store")

/**
 * Single place that persists every session snapshot and every settings block.
 *
 * Each of the four [SessionType]s has its own dedicated key, which is exactly what
 * guarantees "四种计时场景拥有独立的运行状态，不能相互覆盖": writing the pomodoro
 * snapshot can never overwrite the meditation snapshot (or vice versa).
 */
class AppDataStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---- Session snapshot keys (one per session type) ----
    private fun sessionKey(type: SessionType) =
        stringPreferencesKey("session_snapshot_${type.name}")

    private val intervalBellSettingsKey = stringPreferencesKey("settings_interval_bell")
    private val meditationSettingsKey = stringPreferencesKey("settings_meditation")
    private val randomMindfulSettingsKey = stringPreferencesKey("settings_random_mindful")
    private val pomodoroSettingsKey = stringPreferencesKey("settings_pomodoro")

    // ---------- Session snapshots ----------

    fun observeSession(type: SessionType): Flow<SessionSnapshot> =
        context.dataStore.data.map { prefs ->
            prefs[sessionKey(type)]?.let { decodeSnapshot(it) }
                ?: SessionSnapshot(type = type)
        }

    suspend fun session(type: SessionType): SessionSnapshot =
        observeSession(type).first()

    suspend fun saveSession(snapshot: SessionSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[sessionKey(snapshot.type)] = json.encodeToString(
                SessionSnapshot.serializer(), snapshot
            )
        }
    }

    fun observeAllSessions(): Flow<Map<SessionType, SessionSnapshot>> =
        context.dataStore.data.map { prefs ->
            SessionType.entries.associateWith { type ->
                prefs[sessionKey(type)]?.let { decodeSnapshot(it) } ?: SessionSnapshot(type = type)
            }
        }

    private fun decodeSnapshot(raw: String): SessionSnapshot? =
        runCatching { json.decodeFromString(SessionSnapshot.serializer(), raw) }.getOrNull()

    // ---------- Settings ----------

    fun observeIntervalBellSettings(): Flow<IntervalBellSettings> =
        context.dataStore.data.map { prefs ->
            prefs[intervalBellSettingsKey]?.let {
                runCatching { json.decodeFromString(IntervalBellSettings.serializer(), it) }.getOrNull()
            } ?: IntervalBellSettings()
        }

    suspend fun saveIntervalBellSettings(settings: IntervalBellSettings) {
        context.dataStore.edit { it[intervalBellSettingsKey] = json.encodeToString(IntervalBellSettings.serializer(), settings) }
    }

    fun observeMeditationSettings(): Flow<MeditationSettings> =
        context.dataStore.data.map { prefs ->
            prefs[meditationSettingsKey]?.let {
                runCatching { json.decodeFromString(MeditationSettings.serializer(), it) }.getOrNull()
            } ?: MeditationSettings()
        }

    suspend fun saveMeditationSettings(settings: MeditationSettings) {
        context.dataStore.edit { it[meditationSettingsKey] = json.encodeToString(MeditationSettings.serializer(), settings) }
    }

    fun observeRandomMindfulSettings(): Flow<RandomMindfulSettings> =
        context.dataStore.data.map { prefs ->
            prefs[randomMindfulSettingsKey]?.let {
                runCatching { json.decodeFromString(RandomMindfulSettings.serializer(), it) }.getOrNull()
            } ?: RandomMindfulSettings()
        }

    suspend fun saveRandomMindfulSettings(settings: RandomMindfulSettings) {
        context.dataStore.edit { it[randomMindfulSettingsKey] = json.encodeToString(RandomMindfulSettings.serializer(), settings) }
    }

    fun observePomodoroSettings(): Flow<PomodoroSettings> =
        context.dataStore.data.map { prefs ->
            prefs[pomodoroSettingsKey]?.let {
                runCatching { json.decodeFromString(PomodoroSettings.serializer(), it) }.getOrNull()
            } ?: PomodoroSettings()
        }

    suspend fun savePomodoroSettings(settings: PomodoroSettings) {
        context.dataStore.edit { it[pomodoroSettingsKey] = json.encodeToString(PomodoroSettings.serializer(), settings) }
    }
}
