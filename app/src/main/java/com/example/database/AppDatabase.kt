package com.example.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- entities ---

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "email_alerts")
data class EmailAlert(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String,
    val subject: String,
    val content: String,
    val category: String, // "EMAIL" or "ALERT"
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val contactName: String,
    val phoneNumber: String,
    val duration: Long, // in seconds
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String, // "FULL_AI", "AUTO", "OFF"
    val transcript: String,
    val summary: String
)

// --- daos ---

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Int)
}

@Dao
interface EmailAlertDao {
    @Query("SELECT * FROM email_alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<EmailAlert>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: EmailAlert): Long

    @Query("UPDATE email_alerts SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Int)

    @Query("DELETE FROM email_alerts WHERE id = :id")
    suspend fun deleteAlertById(id: Int)
}

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllCallRecords(): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecord): Long

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteCallRecordById(id: Int)
}

// --- database ---

@Database(
    entities = [Task::class, EmailAlert::class, CallRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun emailAlertDao(): EmailAlertDao
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hermes_phone_assistant_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
