package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val zipFileUri: String? = null,
    val zipFileName: String? = null,
    val repoJsonPath: String? = null,
    val isDafYomiEnabled: Boolean = false,
    val lastDafYomiTriggerDate: String? = null
)

@Entity(tableName = "daily_tracks")
data class DailyTrack(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val rootFolder: String, // e.g. "מאגר השאלות/שס/הרב הורביץ - מקיף/ברכות"
    val startFile: String, // e.g. "מאגר השאלות/שס/הרב הורביץ - מקיף/ברכות/ברכות_ב.txt"
    val currentFileIndex: Int = 0,
    val advanceBy: Int = 1,
    val isPartial: Boolean = false,
    val isEnabled: Boolean = true,
    val triggerMode: String = "daily", // "daily" or "on_open"
    val lastTriggeredDate: String? = null
)

@Entity(tableName = "notification_schedules")
data class NotificationSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean = true
)

@Entity(tableName = "imported_questions")
data class ImportedQuestion(
    @PrimaryKey val questionKey: String, // hash representing the question text or a unique ID
    val importTimestamp: Long = System.currentTimeMillis()
)
