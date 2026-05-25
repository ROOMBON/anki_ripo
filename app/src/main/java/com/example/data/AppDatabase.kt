package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // AppSettings
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettings)

    // DailyTracks
    @Query("SELECT * FROM daily_tracks ORDER BY id ASC")
    fun getAllTracksFlow(): Flow<List<DailyTrack>>

    @Query("SELECT * FROM daily_tracks ORDER BY id ASC")
    suspend fun getAllTracks(): List<DailyTrack>

    @Query("SELECT * FROM daily_tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: Int): DailyTrack?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: DailyTrack): Long

    @Update
    suspend fun updateTrack(track: DailyTrack)

    @Query("DELETE FROM daily_tracks WHERE id = :id")
    suspend fun deleteTrack(id: Int)

    // Notification Schedules
    @Query("SELECT * FROM notification_schedules ORDER BY hour ASC, minute ASC")
    fun getAllSchedulesFlow(): Flow<List<NotificationSchedule>>

    @Query("SELECT * FROM notification_schedules ORDER BY hour ASC, minute ASC")
    suspend fun getAllSchedules(): List<NotificationSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: NotificationSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: NotificationSchedule)

    @Query("DELETE FROM notification_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Int)

    // Imported Questions
    @Query("SELECT questionKey FROM imported_questions")
    fun getAllImportedKeysFlow(): Flow<List<String>>

    @Query("SELECT questionKey FROM imported_questions")
    suspend fun getAllImportedKeys(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM imported_questions WHERE questionKey = :key)")
    suspend fun isQuestionImported(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markQuestionsImported(questions: List<ImportedQuestion>)

    @Query("DELETE FROM imported_questions")
    suspend fun clearImportedHistory()
}

@Database(
    entities = [
        AppSettings::class,
        DailyTrack::class,
        NotificationSchedule::class,
        ImportedQuestion::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
}
