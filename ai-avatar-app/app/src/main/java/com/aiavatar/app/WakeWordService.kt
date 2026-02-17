package com.aiavatar.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWord"
        const val CHANNEL_ID = "wake_word"
        const val NOTIFICATION_ID = 3001
        const val EXTRA_WAKE_WORDS = "wake_words"

        @Volatile var isListening = false
        @Volatile var lastHeard = ""
        @Volatile var wakeDetected = false
        @Volatile var onWakeDetected: (() -> Unit)? = null

        private var wakeWords = listOf("小助手", "你好", "嗨")
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRestarting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringArrayListExtra(EXTRA_WAKE_WORDS)?.let { words ->
            if (words.isNotEmpty()) wakeWords = words
        }
        Log.d(TAG, "Starting with wake words: $wakeWords")

        val notification = buildNotification("🎤 等待唤醒...")
        startForeground(NOTIFICATION_ID, notification)

        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopListening()
        isListening = false
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                isRestarting = false
                Log.d(TAG, "Listening for wake word...")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.lowercase() ?: ""
                lastHeard = text
                Log.d(TAG, "Heard: $text")

                if (wakeWords.any { text.contains(it.lowercase()) }) {
                    Log.d(TAG, "🔔 WAKE WORD DETECTED: $text")
                    wakeDetected = true
                    updateNotification("🔔 唤醒词检测到！")
                    onWakeDetected?.invoke()
                }

                // Restart listening
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.lowercase() ?: ""
                if (text.isNotEmpty()) lastHeard = text
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                    SpeechRecognizer.ERROR_NETWORK -> "network"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    else -> "error_$error"
                }
                Log.d(TAG, "ASR error: $errorMsg")
                // Restart on most errors (normal for continuous listening)
                if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    restartListening()
                }
            }

            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
        }
    }

    private fun restartListening() {
        if (isRestarting) return
        isRestarting = true
        android.os.Handler(mainLooper).postDelayed({
            if (!isDestroyed()) {
                startListening()
            }
        }, 500)
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun isDestroyed(): Boolean {
        return try {
            // Service is alive if we can access mainLooper
            mainLooper != null
            false
        } catch (_: Exception) {
            true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI Avatar 后台语音唤醒"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Avatar")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
