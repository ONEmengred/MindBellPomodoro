package com.mindfulfocus.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One finished (or early-ended) meditation sitting. */
@Entity(tableName = "meditation_records")
data class MeditationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute wall-clock epoch (ms) when the sitting started. */
    val startedAtEpochMs: Long,
    /** Actual practiced duration in ms (excludes prep time). */
    val practicedMs: Long,
    val plannedMs: Long,
    val completed: Boolean
)

/** One finished (or early-ended) pomodoro focus/break phase. */
@Entity(tableName = "pomodoro_records")
data class PomodoroRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    /** FOCUS / SHORT_BREAK / LONG_BREAK name. */
    val phase: String,
    val focusedMs: Long,
    val completed: Boolean,
    val taskName: String = ""
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertMeditation(record: MeditationRecord)

    @Insert
    suspend fun insertPomodoro(record: PomodoroRecord)

    @Query("SELECT * FROM meditation_records ORDER BY startedAtEpochMs DESC")
    fun observeMeditations(): Flow<List<MeditationRecord>>

    @Query("SELECT * FROM pomodoro_records WHERE phase = 'FOCUS' ORDER BY startedAtEpochMs DESC")
    fun observePomodoros(): Flow<List<PomodoroRecord>>

    @Query("SELECT COALESCE(SUM(practicedMs), 0) FROM meditation_records")
    fun observeTotalMeditationMs(): Flow<Long>

    @Query("SELECT COUNT(*) FROM pomodoro_records WHERE phase = 'FOCUS' AND completed = 1")
    fun observeCompletedPomodoroCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(focusedMs), 0) FROM pomodoro_records WHERE phase = 'FOCUS'")
    fun observeTotalFocusMs(): Flow<Long>

    @Query("DELETE FROM meditation_records")
    suspend fun clearMeditations()

    @Query("DELETE FROM pomodoro_records")
    suspend fun clearPomodoros()
}

@Database(
    entities = [MeditationRecord::class, PomodoroRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mind_focus.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
