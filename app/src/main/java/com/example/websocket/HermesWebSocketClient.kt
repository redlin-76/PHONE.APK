package com.example.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HermesWebSocketClient {

    private val TAG = "HermesWebSocketClient"
    private var client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep connection alive
        .writeTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // WebSocket state tracking
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Realtime outputs
    private val _realtimeTranscript = MutableStateFlow("")
    val realtimeTranscript: StateFlow<String> = _realtimeTranscript

    // Inbound audio stream (PCM 16k bytes) queue/flow
    private val _inboundAudioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val inboundAudioFlow: SharedFlow<ByteArray> = _inboundAudioFlow

    // Tool call notification
    private val _toolCallEvent = MutableSharedFlow<ToolCall>(extraBufferCapacity = 16)
    val toolCallEvent: SharedFlow<ToolCall> = _toolCallEvent

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    data class ToolCall(
        val functionName: String,
        val arguments: String,
        val callId: String
    )

    fun connect(serverAddress: String, apiKey: String) {
        if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connecting or connected.")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        val wsUrl = if (!serverAddress.startsWith("ws://") && !serverAddress.startsWith("wss://")) {
            "ws://$serverAddress/v1/realtime"
        } else {
            serverAddress
        }

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Hermes-Client", "Android-App-Telecom")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to Hermes!")
                _connectionState.value = ConnectionState.CONNECTED
                
                // Send a handshake or greeting configuration
                sendJsonMessage(mapOf(
                    "type" to "session.update",
                    "session" to mapOf(
                        "modalities" to listOf("text", "audio"),
                        "instructions" to "You are a professional Cyberpunk-themed Tesla AI Phone Assistant named Hermes. Help user answer callers efficiently.",
                        "input_audio_format" to "pcm16",
                        "output_audio_format" to "pcm16"
                    )
                ))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Message received text: $text")
                parseTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.v(TAG, "Received binary packet: ${bytes.size} bytes")
                scope.launch {
                    _inboundAudioFlow.emit(bytes.toByteArray())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket is closing: $code / $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket is closed: $code / $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.message}", t)
                _connectionState.value = ConnectionState.FAILED
            }
        })
    }

    private fun parseTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "transcript", "response.text.delta" -> {
                    val delta = json.optString("text") ?: json.optString("delta") ?: ""
                    if (delta.isNotEmpty()) {
                        _realtimeTranscript.value = _realtimeTranscript.value + delta
                    }
                }
                "response.transcript.partial", "transcript.partial" -> {
                    val partialText = json.optString("text")
                    if (partialText.isNotEmpty()) {
                        _realtimeTranscript.value = partialText
                    }
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta")
                    _realtimeTranscript.value = _realtimeTranscript.value + delta
                }
                "tool_call" -> {
                    val functionName = json.optString("function")
                    val arguments = json.optString("arguments")
                    val callId = json.optString("id")
                    scope.launch {
                        _toolCallEvent.emit(ToolCall(functionName, arguments, callId))
                    }
                }
                "response.done" -> {
                    // Turn finished
                    Log.d(TAG, "AI thinking/speaking response completed")
                }
                "session.created" -> {
                    Log.d(TAG, "Hermes Session initialized.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    fun sendAudio(pcmData: ByteArray) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            webSocket?.send(pcmData.toByteString(0, pcmData.size))
        }
    }

    fun sendTextMessage(text: String) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val payload = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                })
            }
            webSocket?.send(payload.toString())
            
            // Also trigger model response request
            val trigger = JSONObject().apply {
                put("type", "response.create")
            }
            webSocket?.send(trigger.toString())
        }
    }

    private fun sendJsonMessage(map: Map<String, Any>) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            val json = JSONObject(map)
            webSocket?.send(json.toString())
        }
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "App requested disconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        } finally {
            webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun clearTranscript() {
        _realtimeTranscript.value = ""
    }
}
