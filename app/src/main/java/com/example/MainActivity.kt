package com.example

import android.Manifest
import android.app.Application
import kotlinx.coroutines.delay
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.AiMode
import com.example.audio.AudioEngineManager
import com.example.database.*
import com.example.telecom.TelecomCallManager
import com.example.ui.*
import com.example.ui.theme.*
import com.example.websocket.HermesWebSocketClient

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private lateinit var db: AppDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var alertRepository: EmailAlertRepository
    private lateinit var callRecordRepository: CallRecordRepository

    private lateinit var webSocketClient: HermesWebSocketClient
    private lateinit var audioEngine: AudioEngineManager
    private lateinit var telecomCallManager: TelecomCallManager

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize local SQLite architecture
        db = AppDatabase.getDatabase(applicationContext)
        taskRepository = TaskRepository(db.taskDao())
        alertRepository = EmailAlertRepository(db.emailAlertDao())
        callRecordRepository = CallRecordRepository(db.callRecordDao())

        // Initialize low-latency voice, networking and call state managers
        webSocketClient = HermesWebSocketClient()
        audioEngine = AudioEngineManager()
        telecomCallManager = TelecomCallManager(applicationContext, audioEngine, webSocketClient)

        // MVVM standard custom factory constructor injection
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(
                    application = application,
                    telecomCallManager = telecomCallManager,
                    webSocketClient = webSocketClient,
                    taskRepo = taskRepository,
                    alertRepo = alertRepository,
                    callRecordRepo = callRecordRepository
                ) as T
            }
        }

        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]

        setContent {
            MyApplicationTheme {
                MainContainer(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainContainer(viewModel: MainViewModel) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val callState by viewModel.telecomCallManager.currentCallState.collectAsStateWithLifecycle()
    val webSocketState by viewModel.webSocketClient.connectionState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Request permissions dynamically
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val recordOk = results[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordOk) {
            Toast.makeText(context, "Voice permissions granted! Ready for AI call.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Microphone access is required for real-time translation.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE
            )
        }
        recordPermissionLauncher.launch(perms)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.navigationBars, // Respect device navigation pill
        bottomBar = {
            CyberBottomNavigation(
                currentTab = currentTab,
                onTabSelected = { viewModel.selectTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(CyberBg, Color(0xFF030712))
                    )
                )
                .padding(innerPadding)
        ) {
            // Check if there is an active call occurring. If so, display a persistent glowing Call overlay
            if (callState != TelecomCallManager.CallState.IDLE) {
                ActiveCallOverlayScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Futuristic Header
                    CyberHeader(webSocketState = webSocketState)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "screen_navigation"
                        ) { tab ->
                            when (tab) {
                                MainViewModel.Tab.DASHBOARD -> DashboardTab(viewModel = viewModel)
                                MainViewModel.Tab.ASSISTANT -> AssistantTab(viewModel = viewModel)
                                MainViewModel.Tab.CALL_LOG -> CallLogTab(viewModel = viewModel)
                                MainViewModel.Tab.ALERTS -> AlertsTab(viewModel = viewModel)
                                MainViewModel.Tab.TASKS -> TasksTab(viewModel = viewModel)
                                MainViewModel.Tab.SETTINGS -> SettingsTab(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Header ---

@Composable
fun CyberHeader(webSocketState: HermesWebSocketClient.ConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "HERMES GATEWAY",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "TELECOM / AI TAKEOVER CO-PROCESSOR",
                color = SilentGray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        val (badgeLabel, badgeColor) = when (webSocketState) {
            HermesWebSocketClient.ConnectionState.CONNECTED -> "SECURE ON" to NeonTeal
            HermesWebSocketClient.ConnectionState.CONNECTING -> "TUNNELING" to NeonMagenta
            HermesWebSocketClient.ConnectionState.FAILED -> "TUNNEL FAIL" to AlertCoral
            HermesWebSocketClient.ConnectionState.DISCONNECTED -> "OFFLINE" to SilentGray
        }

        GlowBadge(text = badgeLabel, color = badgeColor)
    }
}

// --- Bottom Navigation ---

@Composable
fun CyberBottomNavigation(
    currentTab: MainViewModel.Tab,
    onTabSelected: (MainViewModel.Tab) -> Unit
) {
    NavigationBar(
        containerColor = CyberCard,
        tonalElevation = 8.dp,
        modifier = Modifier.border(width = (0.5).dp, color = GridLine, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        val items = listOf(
            Triple(MainViewModel.Tab.DASHBOARD, Icons.Default.Dashboard, "總覽"),
            Triple(MainViewModel.Tab.ASSISTANT, Icons.Default.Chat, "助理"),
            Triple(MainViewModel.Tab.CALL_LOG, Icons.Default.History, "歷史"),
            Triple(MainViewModel.Tab.ALERTS, Icons.Default.Notifications, "告警"),
            Triple(MainViewModel.Tab.TASKS, Icons.Default.CheckCircle, "任務"),
            Triple(MainViewModel.Tab.SETTINGS, Icons.Default.Settings, "設定")
        )

        items.forEach { (tab, icon, label) ->
            val isSelected = currentTab == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSelected) NeonCyan else SilentGray
                    )
                },
                label = {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) NeonCyan else SilentGray
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = NeonCyan.copy(alpha = 0.15f)
                )
            )
        }
    }
}

// --- Active Call Overlay Panel (OpenAI + Tesla + Cyberpunk UI) ---

@Composable
fun ActiveCallOverlayScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val callState by viewModel.telecomCallManager.currentCallState.collectAsStateWithLifecycle()
    val callerName by viewModel.telecomCallManager.callerName.collectAsStateWithLifecycle()
    val callerNumber by viewModel.telecomCallManager.callerNumber.collectAsStateWithLifecycle()
    val aiMode by viewModel.telecomCallManager.aiTakeoverMode.collectAsStateWithLifecycle()
    val isMuted by viewModel.telecomCallManager.isMuted.collectAsStateWithLifecycle()
    val transcript by viewModel.webSocketClient.realtimeTranscript.collectAsStateWithLifecycle()

    var activeCallDuration by remember { mutableStateOf(0) }
    LaunchedEffect(callState) {
        if (callState == TelecomCallManager.CallState.AI_TAKEOVER || callState == TelecomCallManager.CallState.ACTIVE) {
            activeCallDuration = 0
            while (true) {
                delay(1000)
                activeCallDuration++
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF070B14))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Call Indicator Card
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (callState == TelecomCallManager.CallState.AI_TAKEOVER) NeonCyan.copy(alpha = 0.12f)
                        else GlowGreen.copy(alpha = 0.12f)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (callState == TelecomCallManager.CallState.AI_TAKEOVER) NeonCyan else GlowGreen)
                    )
                    Text(
                        text = if (callState == TelecomCallManager.CallState.AI_TAKEOVER) "AI TAKEOVER ACTIVE" else "LIVE TRADITIONAL CALL",
                        color = if (callState == TelecomCallManager.CallState.AI_TAKEOVER) NeonCyan else GlowGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Contact Identity Display
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = callerName.ifEmpty { "王小明" },
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = callerNumber.ifEmpty { "+886 912 345 678" },
                    color = SilentGray,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Call Timer or Dialer ring indicator
            Text(
                text = when (callState) {
                    TelecomCallManager.CallState.RINGING -> "📞 來電中 INCOMING CALL..."
                    TelecomCallManager.CallState.DIALING -> "📡 撥號中 DIALING OUT..."
                    else -> String.format("%02d:%02d", activeCallDuration / 60, activeCallDuration % 60)
                },
                color = if (callState == TelecomCallManager.CallState.RINGING) NeonMagenta else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(10.dp))

            // AI Mode interactive visualizer
            DynamicAudioVisualizerPanel(isActive = (callState == TelecomCallManager.CallState.AI_TAKEOVER))

            // Realtime Live Transcript display
            CyberCard(
                borderColor = if (callState == TelecomCallManager.CallState.AI_TAKEOVER) NeonCyan else GridLine,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Text(
                    text = "🤖 REALTIME CHAT TRANSCRIPT:",
                    color = if (callState == TelecomCallManager.CallState.AI_TAKEOVER) NeonCyan else SilentGray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(10.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            val displayText = transcript.ifEmpty {
                                if (callState == TelecomCallManager.CallState.AI_TAKEOVER) {
                                    "「您好，我是 AI 助理...」\n(正在聽取對話並即時以 OpenAI Realtime 串流回覆)"
                                } else {
                                    "(點選下方 [AI 接手] 開啟對話監聽與語音代理客戶)"
                                }
                            }
                            Text(
                                text = displayText,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.SansSerif,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            // Call Mode Selection inside call panel
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SELECT AI TAKEOVER CO-PROCESSING MODE:",
                    color = SilentGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                ModeTogglePanel(
                    currentMode = aiMode,
                    onModeSelected = { viewModel.telecomCallManager.setAiMode(it) }
                )
            }

            // Interactive Bottom Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (callState == TelecomCallManager.CallState.RINGING) {
                    // Answer Call
                    NeonButton(
                        text = "傳統接聽",
                        onClick = { viewModel.telecomCallManager.answerCall() },
                        color = GlowGreen,
                        modifier = Modifier.weight(1f),
                        testTag = "answer_normal_button"
                    )

                    // Answer with Full AI Takeover
                    NeonButton(
                        text = "AI 完全接手",
                        onClick = {
                            viewModel.telecomCallManager.setAiMode(AiMode.FULL_AI)
                            viewModel.telecomCallManager.answerCall()
                        },
                        color = NeonCyan,
                        modifier = Modifier.weight(1.2f),
                        testTag = "answer_ai_button"
                    )
                } else if (callState == TelecomCallManager.CallState.AI_TAKEOVER) {
                    // Reclaim Call
                    NeonButton(
                        text = "人工接回 👤",
                        onClick = { viewModel.telecomCallManager.reclaimCallFromAi() },
                        color = NeonTeal,
                        modifier = Modifier.weight(1f),
                        testTag = "reclaim_call_button"
                    )
                } else if (callState == TelecomCallManager.CallState.ACTIVE) {
                    // Turn over call to AI
                    NeonButton(
                        text = "啟動 AI 接管 🤖",
                        onClick = { viewModel.telecomCallManager.activateAiTakeover() },
                        color = NeonCyan,
                        modifier = Modifier.weight(1f),
                        testTag = "activate_takeover_button"
                    )
                }

                // Mute logic
                if (callState == TelecomCallManager.CallState.ACTIVE || callState == TelecomCallManager.CallState.AI_TAKEOVER) {
                    Box(
                        modifier = Modifier
                            .testTag("mute_button")
                            .clickable { viewModel.telecomCallManager.toggleMute() }
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isMuted) AlertCoral.copy(alpha = 0.2f) else GridLine)
                            .border(1.dp, if (isMuted) AlertCoral else SilentGray, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute mic",
                            tint = if (isMuted) AlertCoral else Color.White
                        )
                    }
                }

                // Red Hangup / Disconnect button
                Box(
                    modifier = Modifier
                        .testTag("disconnect_call_button")
                        .clickable {
                            // Record the finished Call elements inside sqlite DB log for CRM persistence
                            viewModel.saveCallLog(
                                name = callerName.ifEmpty { "王小明" },
                                number = callerNumber.ifEmpty { "+886 912 345 678" },
                                duration = activeCallDuration.toLong(),
                                mode = aiMode,
                                transcript = transcript.ifEmpty { "對話記錄空。" },
                                summary = if (aiMode == AiMode.FULL_AI) {
                                    "AI 助理自動接管，通話時長 " + activeCallDuration + " 秒。任務已同步。"
                                } else {
                                    "傳統聯絡，無 AI 代理介入。"
                                }
                            )
                            viewModel.telecomCallManager.disconnectCall()
                        }
                        .height(48.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AlertCoral.copy(alpha = 0.15f))
                        .border(1.2.dp, AlertCoral, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "掛斷 📞",
                        color = AlertCoral,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun DynamicAudioVisualizerPanel(isActive: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudioWavesVisualizer(isActive = isActive)
        Text(
            text = if (isActive) "🤖 AI IS CONVERSING OVER VOIP STREAM..." else "🎙️ LOCAL MIC WAITING ON USER RECLAIM...",
            color = if (isActive) NeonTeal else SilentGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ==================================================
// DASHBOARD TAB
// ==================================================

@Composable
fun DashboardTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val aiMode by viewModel.telecomCallManager.aiTakeoverMode.collectAsStateWithLifecycle()
    val webSocketState by viewModel.webSocketClient.connectionState.collectAsStateWithLifecycle()
    val serverAddress by viewModel.serverAddress.collectAsStateWithLifecycle()

    var dialNumber by remember { mutableStateOf("") }
    var outboundTargetName by remember { mutableStateOf("VIP Customer") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Config and Status Card
        item {
            CyberCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "HERMES GATEWAY IP",
                            color = SilentGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = serverAddress,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (webSocketState != HermesWebSocketClient.ConnectionState.CONNECTED) {
                        NeonButton(
                            text = "連線 CLOUD",
                            onClick = { viewModel.connectToHermes() },
                            color = NeonCyan,
                            testTag = "connect_gateway_dashboard"
                        )
                    } else {
                        NeonButton(
                            text = "中斷",
                            onClick = { viewModel.disconnectFromHermes() },
                            color = AlertCoral,
                            testTag = "disconnect_gateway_dashboard"
                        )
                    }
                }
            }
        }

        // AI Modes Card selector
        item {
            CyberCard {
                Text(
                    text = "⚙️ AI CALL TAKEOVER CO-PROCESSOR",
                    color = NeonTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "設置當實體來電時，AI 是否自動幫您接聽、應答並即時同步通話詳細清單：",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                ModeTogglePanel(
                    currentMode = aiMode,
                    onModeSelected = { viewModel.telecomCallManager.setAiMode(it) }
                )
            }
        }

        // Simulated dialer and demo trigger
        item {
            CyberCard {
                Text(
                    text = "📞 TELECOM & DIALER SIMULATOR",
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "在此輸入手機門號，測試主動撥打（以 AI代理 對話），或觸發測試仿真來電：",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = dialNumber,
                    onValueChange = { dialNumber = it },
                    label = { Text("輸入測試電話 (如 0912345678)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = SilentGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Trigger Incoming Call Simulation
                    Box(
                        modifier = Modifier
                            .testTag("simulate_incoming_call")
                            .height(48.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonMagenta.copy(alpha = 0.12f))
                            .border(1.dp, NeonMagenta, RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.telecomCallManager.triggerSimulatedIncomingCall(
                                    name = "王小明 (客戶)",
                                    number = dialNumber.ifEmpty { "+886 912 345 678" }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "模擬來電 🔔",
                            color = NeonMagenta,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Dial outbound with AI Takeover immediately
                    Box(
                        modifier = Modifier
                            .testTag("dial_out_ai")
                            .height(48.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonCyan.copy(alpha = 0.12f))
                            .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                            .clickable {
                                viewModel.telecomCallManager.initiateSimulatedOutgoingCall(
                                    name = "林春嬌 (半導體合作商)",
                                    number = dialNumber.ifEmpty { "+886 923 456 789" }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AI 打出 📡",
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Active CRM Contact List
        item {
            Text(
                text = "⚡ QUICK DIAL REALTIME CONTACTS (CRM)",
                color = SilentGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        items(viewModel.contacts) { contact ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyberCard)
                    .border(0.5.dp, GridLine, RoundedCornerShape(10.dp))
                    .clickable {
                        dialNumber = contact.phoneNumber
                        outboundTargetName = contact.name
                        Toast
                            .makeText(context, "Contact selected: ${contact.name}", Toast.LENGTH_SHORT)
                            .show()
                    }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = contact.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = contact.phoneNumber,
                                color = SilentGray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "• ${contact.company}",
                                color = NeonTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = contact.role,
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==================================================
// ASSISTANT CHAT TAB
// ==================================================

@Composable
fun AssistantTab(viewModel: MainViewModel) {
    val transcript by viewModel.webSocketClient.realtimeTranscript.collectAsStateWithLifecycle()
    var userMessageText by remember { mutableStateOf("") }
    val isMicActive = remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Direct Speech assistant status
        CyberCard {
            Text(
                text = "🤖 HERMES VOICE ASSISTANT CLIENT",
                color = NeonCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "可以直接向 Hermes AI 助理說話以指派任務、讀取當前告警。AI 可透過 tool calling 在背景自動處理。",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            AudioWavesVisualizer(isActive = isMicActive.value)
        }

        // Assistant feed chat
        CyberCard(modifier = Modifier.weight(1f)) {
            Text(
                text = "💬 ASSISTANT DIALOGUE LOGS",
                color = SilentGray,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Hermes Co-processor: Master, how can I assist you today?\n(You can ask me to \"Create a task...\" or \"Check system alerts...\")",
                            color = NeonTeal,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (transcript.isNotEmpty()) {
                        item {
                            Text(
                                text = transcript,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Text & Mic controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speech Button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isMicActive.value) NeonTeal.copy(alpha = 0.2f) else GridLine)
                    .border(1.dp, if (isMicActive.value) NeonTeal else SilentGray, RoundedCornerShape(8.dp))
                    .clickable {
                        isMicActive.value = !isMicActive.value
                        if (isMicActive.value) {
                            viewModel.connectToHermes()
                            Toast
                                .makeText(viewModel.getApplication(), "Microphone active. Streaming pcm...", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            Toast
                                .makeText(viewModel.getApplication(), "Microphone muted.", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMicActive.value) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = "Mic input",
                    tint = if (isMicActive.value) NeonTeal else Color.White
                )
            }

            // Text input
            OutlinedTextField(
                value = userMessageText,
                onValueChange = { userMessageText = it },
                label = { Text("傳送訊息給 Hermes助理...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = SilentGray
                ),
                modifier = Modifier.weight(1f)
            )

            // Send
            Box(
                modifier = Modifier
                    .testTag("send_assistant_message_button")
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonCyan.copy(alpha = 0.15f))
                    .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                    .clickable {
                        if (userMessageText.isNotEmpty()) {
                            viewModel.sendAssistantMessage(userMessageText)
                            userMessageText = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = NeonCyan
                )
            }
        }
    }
}

// ==================================================
// HISTORICAL CALLS TAB
// ==================================================

@Composable
fun CallLogTab(viewModel: MainViewModel) {
    val logs by viewModel.callRecords.collectAsStateWithLifecycle()
    var selectedRecord by remember { mutableStateOf<CallRecord?>(null) }

    if (selectedRecord != null) {
        // Detailed log overlay popup dialog
        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = {
                Text(
                    text = "通話詳細記錄: " + (selectedRecord?.contactName ?: "王小明"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "電話: " + (selectedRecord?.phoneNumber ?: ""),
                        color = SilentGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "時長: " + (selectedRecord?.duration ?: 0) + " 秒  /  接管模式: " + (selectedRecord?.mode ?: ""),
                        color = NeonTeal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    Divider(color = GridLine)
                    Text(
                        text = "AI 摘要與結論:",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = selectedRecord?.summary ?: "無摘要。",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Divider(color = GridLine)
                    Text(
                        text = "Realtime Transcript 對話紀錄:",
                        color = NeonMagenta,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color.Black.copy(alpha = 0.3f))
                            .padding(8.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = selectedRecord?.transcript ?: "無記錄資料。",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRecord = null }) {
                    Text("關閉", color = NeonCyan)
                }
            },
            containerColor = CyberCard
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "📞 HISTORICAL CALL SYNC (CRM PERSISTED)",
            color = SilentGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("無通話記錄歷史。", color = SilentGray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs) { record ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyberCard)
                            .border(0.5.dp, GridLine, RoundedCornerShape(10.dp))
                            .clickable { selectedRecord = record }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.contactName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = record.phoneNumber + " • 通話時長: " + record.duration + "s",
                                    color = SilentGray,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "摘要: " + record.summary,
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val badgeColor = if (record.mode == "FULL_AI") NeonCyan else SilentGray
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(badgeColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = record.mode,
                                        color = badgeColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                IconButton(onClick = { viewModel.deleteCallRecord(record.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete call log",
                                        tint = AlertCoral.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================================================
// EMAIL ALERTS TAB
// ==================================================

@Composable
fun AlertsTab(viewModel: MainViewModel) {
    val alertsList by viewModel.alerts.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔔 EMAIL SUMMARY & SECURITY ALERTS",
                color = SilentGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            GlowBadge(text = alertsList.size.toString() + " ACTIVE", color = NeonTeal)
        }

        if (alertsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("安全中心目前無任何待處理告警事件。", color = SilentGray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alertsList) { alert ->
                    val glowColor = if (alert.category == "ALERT") AlertCoral else NeonCyan
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyberCard)
                            .border(
                                width = if (!alert.isRead) 1.dp else (0.5).dp,
                                color = if (!alert.isRead) glowColor else GridLine,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { viewModel.markAlertAsRead(alert.id) }
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = alert.sender,
                                    color = glowColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )

                                IconButton(
                                    onClick = { viewModel.deleteAlert(alert.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = SilentGray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Text(
                                text = alert.subject,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Text(
                                text = alert.content,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================================================
// TASKS TAB (Local Todo Sync Board)
// ==================================================

@Composable
fun TasksTab(viewModel: MainViewModel) {
    val tasksList by viewModel.tasks.collectAsStateWithLifecycle()
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskDesc by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增 AI 協同任務", color = Color.White) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newTaskTitle,
                        onValueChange = { newTaskTitle = it },
                        label = { Text("任務標題") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = SilentGray
                        )
                    )
                    OutlinedTextField(
                        value = newTaskDesc,
                        onValueChange = { newTaskDesc = it },
                        label = { Text("細節說明 (可空白)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = SilentGray
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newTaskTitle.isNotEmpty()) {
                            viewModel.addTask(newTaskTitle, newTaskDesc)
                            newTaskTitle = ""
                            newTaskDesc = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("儲存", color = NeonCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消", color = SilentGray)
                }
            },
            containerColor = CyberCard
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📋 CLIENT WORKLIST BOARD",
                color = SilentGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Task",
                    tint = NeonCyan
                )
            }
        }

        if (tasksList.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("工作清單為空。AI將在通話中幫您累積事項。", color = SilentGray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasksList) { task ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyberCard)
                            .border(
                                width = (0.5).dp,
                                color = if (task.isCompleted) SilentGray.copy(alpha = 0.4f) else GridLine,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { viewModel.toggleTask(task) }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.title,
                                    color = if (task.isCompleted) SilentGray else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                )
                                if (task.description.isNotEmpty()) {
                                    Text(
                                        text = task.description,
                                        color = if (task.isCompleted) SilentGray.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Toggle completion",
                                    tint = if (task.isCompleted) NeonTeal else SilentGray,
                                    modifier = Modifier.size(20.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(onClick = { viewModel.deleteTask(task.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = AlertCoral.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================================================
// SETTINGS TAB (Configuration Details)
// ==================================================

@Composable
fun SettingsTab(viewModel: MainViewModel) {
    val serverAddress by viewModel.serverAddress.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val webSocketState by viewModel.webSocketClient.connectionState.collectAsStateWithLifecycle()

    var addressInput by remember { mutableStateOf(serverAddress) }
    var keyInput by remember { mutableStateOf(apiKey) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CyberCard {
                Text(
                    text = "⚙️ HERMES CO-PROCESSOR CONNECTOR",
                    color = NeonTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "在此設定阿里雲(Alibaba ECS)上部的 Hermes AI Agent Gateway 的 Socket 位址。支援 TLS 安全連線及 OpenAI Realtime API。",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    label = { Text("Hermes Socket URL") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = SilentGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("JWT Token Security Key") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = SilentGray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NeonButton(
                        text = "更新配置 💾",
                        onClick = {
                            viewModel.serverAddress.value = addressInput
                            viewModel.apiKey.value = keyInput
                            Toast.makeText(viewModel.getApplication(), "Gateway config updated locally.", Toast.LENGTH_SHORT).show()
                        },
                        color = NeonCyan,
                        modifier = Modifier.weight(1f)
                    )

                    if (webSocketState == HermesWebSocketClient.ConnectionState.CONNECTED) {
                        NeonButton(
                            text = "中斷通道 🚨",
                            onClick = { viewModel.disconnectFromHermes() },
                            color = AlertCoral,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        NeonButton(
                            text = "連線通道 ⚡",
                            onClick = {
                                viewModel.serverAddress.value = addressInput
                                viewModel.apiKey.value = keyInput
                                viewModel.connectToHermes()
                            },
                            color = NeonTeal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // About the Gateway & Architecture summary
        item {
            CyberCard {
                Text(
                    text = "🖥️ SYSTEM ARCHITECTURE SPECS",
                    color = SilentGray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                val details = listOf(
                    "• OS Target: Android 12+ (Samsung / Pixel)",
                    "• Telecom integration: Telecom self-managed service provider",
                    "• Voice Engine: Duplex low-latency Voice Activity Detection (VAD)",
                    "• Sampling rate: 16k mono 16-bit linear PCM",
                    "• Security: Keystore JWT authentications with encrypted payload",
                    "• Remote Orchestrator: Hermes AI deployed on Alibaba Cloud ECS",
                    "• Core LLMs: OpenAI GPT-4o / GPT-5 proxy integration"
                )

                details.forEach { detail ->
                    Text(
                        text = detail,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}
