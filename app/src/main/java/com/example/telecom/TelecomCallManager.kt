package com.example.telecom

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import com.example.ai.AiMode
import com.example.audio.AudioEngineManager
import com.example.websocket.HermesWebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TelecomCallManager(
    private val context: Context,
    private val audioEngine: AudioEngineManager,
    private val webSocketClient: HermesWebSocketClient
) {
    private val TAG = "TelecomCallManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // UI state flows
    private val _currentCallState = MutableStateFlow(CallState.IDLE)
    val currentCallState: StateFlow<CallState> = _currentCallState

    private val _callerName = MutableStateFlow("")
    val callerName: StateFlow<String> = _callerName

    private val _callerNumber = MutableStateFlow("")
    val callerNumber: StateFlow<String> = _callerNumber

    private val _aiTakeoverMode = MutableStateFlow(AiMode.OFF)
    val aiTakeoverMode: StateFlow<AiMode> = _aiTakeoverMode

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    enum class CallState {
        IDLE,
        RINGING, // Incoming Call
        DIALING, // Outgoing Call
        ACTIVE,  // Handled by User
        AI_TAKEOVER // Handled by AI Assistant
    }

    init {
        // Feed WebSocket incoming audio directly to playback engine
        scope.launch {
            webSocketClient.inboundAudioFlow.collect { pcmBytes ->
                if (_currentCallState.value == CallState.AI_TAKEOVER || _aiTakeoverMode.value == AiMode.FULL_AI) {
                    audioEngine.playAudio(pcmBytes)
                }
            }
        }

        // Bridge recording to WebSocket
        audioEngine.setOnAudioRecordListener { pcmBytes ->
            if (_currentCallState.value == CallState.AI_TAKEOVER && !_isMuted.value) {
                webSocketClient.sendAudio(pcmBytes)
            }
        }
    }

    // Triggered when an incoming call is simulated or registered via Telecom connection
    fun triggerSimulatedIncomingCall(name: String, number: String) {
        _callerName.value = name
        _callerNumber.value = number
        _currentCallState.value = CallState.RINGING
        Log.i(TAG, "Incoming Call Triggered: $name ($number)")
    }

    fun initiateSimulatedOutgoingCall(name: String, number: String) {
        _callerName.value = name
        _callerNumber.value = number
        _currentCallState.value = CallState.DIALING
        Log.i(TAG, "Outgoing Call Triggered: $name ($number)")

        // Instantly connects and takes over if FULL AI or transitions to ACTIVE
        scope.launch {
            delay(2000) // Dialing simulation
            if (_aiTakeoverMode.value == AiMode.FULL_AI) {
                _currentCallState.value = CallState.AI_TAKEOVER
                startAiTakeover()
            } else {
                _currentCallState.value = CallState.ACTIVE
            }
        }
    }

    fun answerCall() {
        if (_currentCallState.value == CallState.RINGING) {
            if (_aiTakeoverMode.value == AiMode.FULL_AI) {
                _currentCallState.value = CallState.AI_TAKEOVER
                startAiTakeover()
            } else {
                _currentCallState.value = CallState.ACTIVE
            }
            Log.i(TAG, "Call Answered. State: ${_currentCallState.value}")
        }
    }

    fun activateAiTakeover() {
        if (_currentCallState.value == CallState.ACTIVE) {
            _currentCallState.value = CallState.AI_TAKEOVER
            startAiTakeover()
            Log.i(TAG, "AI Takeover Activated dynamically.")
        }
    }

    fun reclaimCallFromAi() {
        if (_currentCallState.value == CallState.AI_TAKEOVER) {
            _currentCallState.value = CallState.ACTIVE
            // Stop sound engine recording so AI stops hearing user, clear audio streams
            audioEngine.stopEngine()
            Log.i(TAG, "User reclaimed call. AI Takeover Terminated.")
        }
    }

    private fun startAiTakeover() {
        // Start streaming mic audio and playing AI response
        audioEngine.startEngine()
        
        // Notify Hermes that AI has took over dialogue
        webSocketClient.clearTranscript()
        webSocketClient.sendTextMessage("[System: AI has answered the call on behalf of the user. Greets the caller politely as an AI assistant.]")
    }

    fun setAiMode(mode: AiMode) {
        _aiTakeoverMode.value = mode
        Log.i(TAG, "AI Mode changed to: $mode")
        
        // Dynamic reaction while in-call
        if (_currentCallState.value == CallState.ACTIVE && mode == AiMode.FULL_AI) {
            activateAiTakeover()
        } else if (_currentCallState.value == CallState.AI_TAKEOVER && mode == AiMode.OFF) {
            reclaimCallFromAi()
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        Log.i(TAG, "Muted state changed to: ${_isMuted.value}")
    }

    fun disconnectCall() {
        Log.i(TAG, "Disconnecting call...")
        _currentCallState.value = CallState.IDLE
        audioEngine.stopEngine()
        webSocketClient.clearTranscript()
    }
}
