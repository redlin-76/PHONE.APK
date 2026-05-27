package com.example.database

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    val allTasks: Flow<List<Task>> = taskDao.getAllTasks()

    suspend fun insertTask(task: Task): Long {
        return taskDao.insertTask(task)
    }

    suspend fun updateTask(task: Task) {
        taskDao.updateTask(task)
    }

    suspend fun deleteTask(id: Int) {
        taskDao.deleteTaskById(id)
    }
}

class EmailAlertRepository(private val emailAlertDao: EmailAlertDao) {
    val allAlerts: Flow<List<EmailAlert>> = emailAlertDao.getAllAlerts()

    suspend fun insertAlert(alert: EmailAlert): Long {
        return emailAlertDao.insertAlert(alert)
    }

    suspend fun markAsRead(id: Int) {
        emailAlertDao.markAsRead(id)
    }

    suspend fun deleteAlert(id: Int) {
        emailAlertDao.deleteAlertById(id)
    }
}

class CallRecordRepository(private val callRecordDao: CallRecordDao) {
    val allCallRecords: Flow<List<CallRecord>> = callRecordDao.getAllCallRecords()

    suspend fun insertCallRecord(record: CallRecord): Long {
        return callRecordDao.insertCallRecord(record)
    }

    suspend fun deleteCallRecord(id: Int) {
        callRecordDao.deleteCallRecordById(id)
    }
}
