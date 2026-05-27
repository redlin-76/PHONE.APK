package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiMode
import com.example.database.*
import com.example.telecom.TelecomCallManager
import com.example.websocket.HermesWebSocketClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    val telecomCallManager: TelecomCallManager,
    val webSocketClient: HermesWebSocketClient,
    private val taskRepo: TaskRepository,
    private val alertRepo: EmailAlertRepository,
    private val callRecordRepo: CallRecordRepository
) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

    // App Navigation state
    private val _currentTab = MutableStateFlow(Tab.DASHBOARD)
    val currentTab: StateFlow<Tab> = _currentTab

    // Server Configurations
    val serverAddress = MutableStateFlow("hermes-ai.ecs.alibaba.net:8880")
    val apiKey = MutableStateFlow("hermes_sec_auth_t9kx3q")

    // Reactive DB feeds
    val tasks: StateFlow<List<Task>> = taskRepo.allTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alerts: StateFlow<List<EmailAlert>> = alertRepo.allAlerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val callRecords: StateFlow<List<CallRecord>> = callRecordRepo.allCallRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Simulated contacts for our cyberpunk CRM
    val contacts = listOf(
        Contact("王小明", "+886 912 345 678", "VIP Customer", "台灣電力"),
        Contact("林春嬌", "+886 923 456 789", "Partner Coordinator", "阿爾巴半導體"),
        Contact("陳大同", "+886 934 567 890", "System Engineer", "雲端科技"),
        Contact("Hermes Central Gate", "10086", "AI Service Gateway", "Alibaba ECS")
    )

    enum class Tab {
        DASHBOARD,
        ASSISTANT,
        CALL_LOG,
        ALERTS,
        TASKS,
        SETTINGS
    }

    data class Contact(
        val name: String,
        val phoneNumber: String,
        val role: String,
        val company: String
    )

    init {
        // Observe tool calls from Hermes web-socket server and translate them to Room data actions
        viewModelScope.launch {
            webSocketClient.toolCallEvent.collect { toolCall ->
                handleToolCall(toolCall)
            }
        }

        // Add pre-seeded data on first run
        preseedDefaultData()
    }

    fun selectTab(tab: Tab) {
        _currentTab.value = tab
    }

    // --- Database actions (room) ---

    fun addTask(title: String, description: String) {
        viewModelScope.launch {
            taskRepo.insertTask(Task(title = title, description = description))
        }
    }

    fun toggleTask(task: Task) {
        viewModelScope.launch {
            taskRepo.updateTask(task.copy(isCompleted = !task.isCompleted))
        }
    }

    fun deleteTask(id: Int) {
        viewModelScope.launch {
            taskRepo.deleteTask(id)
        }
    }

    fun insertAlert(sender: String, subject: String, content: String, category: String) {
        viewModelScope.launch {
            alertRepo.insertAlert(EmailAlert(sender = sender, subject = subject, content = content, category = category))
        }
    }

    fun markAlertAsRead(id: Int) {
        viewModelScope.launch {
            alertRepo.markAsRead(id)
        }
    }

    fun deleteAlert(id: Int) {
        viewModelScope.launch {
            alertRepo.deleteAlert(id)
        }
    }

    fun saveCallLog(name: String, number: String, duration: Long, mode: AiMode, transcript: String, summary: String) {
        viewModelScope.launch {
            callRecordRepo.insertCallRecord(
                CallRecord(
                    contactName = name,
                    phoneNumber = number,
                    duration = duration,
                    mode = mode.name,
                    transcript = transcript,
                    summary = summary
                )
            )
        }
    }

    fun deleteCallRecord(id: Int) {
        viewModelScope.launch {
            callRecordRepo.deleteCallRecord(id)
        }
    }

    // --- Server Connections ---

    fun connectToHermes() {
        webSocketClient.connect(serverAddress.value, apiKey.value)
    }

    fun disconnectFromHermes() {
        webSocketClient.disconnect()
    }

    // --- Assistant Voice / Text input ---

    fun sendAssistantMessage(text: String) {
        webSocketClient.sendTextMessage(text)
    }

    // --- Tool call event integration ---

    private fun handleToolCall(toolCall: HermesWebSocketClient.ToolCall) {
        Log.i(TAG, "Executing tool call: ${toolCall.functionName} with arguments: ${toolCall.arguments}")
        try {
            val args = org.json.JSONObject(toolCall.arguments)
            when (toolCall.functionName) {
                "create_task" -> {
                    val title = args.optString("title", "Hermes Task")
                    val desc = args.optString("description", "")
                    addTask(title, desc)
                    insertAlert("SYSTEM", "New Task Created by AI", "Task \"$title\" created via call tool_calling function.", "ALERT")
                }
                "send_email" -> {
                    val recipient = args.optString("to", "user@gamania.com")
                    val subject = args.optString("subject", "Hermes Action Needed")
                    val body = args.optString("body", "")
                    insertAlert(recipient, "Email Outbox: $subject", body, "EMAIL")
                }
                "summarize_alert" -> {
                    val alertName = args.optString("alert", "Overload Warning")
                    val severity = args.optString("severity", "CRITICAL")
                    val content = args.optString("content", "Server CPU load is 98% on Alibaba ECS instance.")
                    insertAlert("Hermes Engine", "[$severity] Server Alert", content, "ALERT")
                }
                "query_crm" -> {
                    // Simulates lookup
                    Log.d(TAG, "CRM Lookup requested")
                }
                "call_contact" -> {
                    val targetNum = args.optString("phoneNumber", "")
                    val targetName = args.optString("name", "CRM Contact")
                    telecomCallManager.initiateSimulatedOutgoingCall(targetName, targetNum)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling tool call: ${e.message}", e)
        }
    }

    private fun preseedDefaultData() {
        viewModelScope.launch {
            tasks.first().let { currentList ->
                if (currentList.isEmpty()) {
                    taskRepo.insertTask(Task(title = "Review ERP Dashboard Alerts", description = "Monitor anomalous traffic reports from Nginx proxy backend.", isCompleted = false))
                    taskRepo.insertTask(Task(title = "Email back Semiconductor Vendor", description = "Schedule an AI-moderated virtual call to details specs.", isCompleted = true))
                    taskRepo.insertTask(Task(title = "Deploy systemd config on ECS", description = "Ensure automatic restarts for WebSocket gateways.", isCompleted = false))
                }
            }

            alerts.first().let { currentList ->
                if (currentList.isEmpty()) {
                    alertRepo.insertAlert(EmailAlert(sender = "aws-alerts@amazon.com", subject = "Instance CPU Exceeded 90%", content = "Elastic Compute node running GPT-4o proxy triggered high usage threshold.", category = "ALERT"))
                    alertRepo.insertAlert(EmailAlert(sender = "manager@gamania.com", subject = "Urgent: Project Schedule Review", content = "Need the summary of the latest client audio transcript before tomorrow's standup.", category = "EMAIL"))
                    alertRepo.insertAlert(EmailAlert(sender = "noreply@github.com", subject = "Deployment Success on Alibaba ECS", content = "Nginx gateway system services compiled and restarted successfully.", category = "ALERT"))
                }
            }

            callRecords.first().let { currentList ->
                if (currentList.isEmpty()) {
                    callRecordRepo.insertCallRecord(
                        CallRecord(
                            contactName = "王小明",
                            phoneNumber = "+886 912 345 678",
                            duration = 142,
                            mode = "FULL_AI",
                            transcript = "AI: 您好！我是陳先生的 AI 電話助理。請問您找他有什麼事呢？\n王小明: 您好，我想跟陳先生約明天早上十一點開會 discuss 專案進度。\nAI: 好的，我已經幫您將「約明天早上十一點開會」加入到陳先生的工作清單中了，並通知他。謝謝您的來電！",
                            summary = "王小明來電，預約明天早上11:00開會探討專案進度。AI 助理已接起並自動建立了行事例工作提醒。"
                        )
                    )
                }
            }
        }
    }
}
