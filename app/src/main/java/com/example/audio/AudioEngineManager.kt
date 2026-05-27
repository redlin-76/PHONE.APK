package com.example.audio

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue

class AudioEngineManager {

    private val TAG = "AudioEngineManager"

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var isRecording = false
    private var isPlaying = false

    private var recordJob: Job? = null
    private var playJob: Job? = null

    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val audioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Callback when local audio is recorded
    private var onAudioRecordCallback: ((ByteArray) -> Unit)? = null

    fun setOnAudioRecordListener(callback: (ByteArray) -> Unit) {
        this.onAudioRecordCallback = callback
    }

    @SuppressLint("MissingPermission")
    fun startEngine() {
        Log.i(TAG, "Starting Audio Engine...")
        try {
            setupAudioRecord()
            setupAudioTrack()
            
            isRecording = true
            isPlaying = true

            startRecordingLoop()
            startPlayingLoop()
            
            Log.i(TAG, "Audio Engine active.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Audio Engine", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for VOIP/AI call
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state not initialized. Fallback to MIC source.")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
        }

        // Apply pre-processors if supported
        val audioSessionId = audioRecord?.audioSessionId ?: 0
        if (audioSessionId != 0) {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId).apply {
                    enabled = true
                    Log.d(TAG, "NoiseSuppressor activated.")
                }
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId).apply {
                    enabled = true
                    Log.d(TAG, "AcousticEchoCanceler activated.")
                }
            }
        }
    }

    private fun setupAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized.")
        }
    }

    private fun startRecordingLoop() {
        recordJob = audioScope.launch {
            audioRecord?.let { recorder ->
                try {
                    recorder.startRecording()
                    Log.d(TAG, "AudioRecord started recording loop.")
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord start failed $e")
                    return@launch
                }

                // Chunks of 640 bytes represent 20ms of audio (16000 * 2 bytes/sample * 0.02s)
                val buffer = ByteArray(640)
                while (isRecording && isActive) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val packet = ByteArray(bytesRead)
                        System.arraycopy(buffer, 0, packet, 0, bytesRead)
                        onAudioRecordCallback?.invoke(packet)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord error on read: $bytesRead")
                        delay(20)
                    }
                }
            }
        }
    }

    private fun startPlayingLoop() {
        playJob = audioScope.launch {
            audioTrack?.let { tracker ->
                try {
                    tracker.play()
                    Log.d(TAG, "AudioTrack started playback loop.")
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack play failed $e")
                    return@launch
                }

                while (isPlaying && isActive) {
                    val data = playQueue.poll()
                    if (data != null) {
                        var bytesWritten = 0
                        while (bytesWritten < data.size && isPlaying) {
                            val result = tracker.write(data, bytesWritten, data.size - bytesWritten)
                            if (result < 0) {
                                Log.e(TAG, "AudioTrack error on write: $result")
                                break
                            }
                            bytesWritten += result
                        }
                    } else {
                        // Avoid CPU lockup but stay responsive
                        delay(10)
                    }
                }
            }
        }
    }

    fun playAudio(pcmData: ByteArray) {
        if (isPlaying) {
            playQueue.offer(pcmData)
        }
    }

    fun clearPlaybackQueue() {
        playQueue.clear()
    }

    fun stopEngine() {
        Log.i(TAG, "Stopping Audio Engine...")
        isRecording = false
        isPlaying = false

        recordJob?.cancel()
        playJob?.cancel()
        recordJob = null
        playJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }

        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null

        playQueue.clear()
        Log.i(TAG, "Audio Engine stopped.")
    }
}
