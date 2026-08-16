package com.lain.assistant.automation

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lain.assistant.MainActivity
import com.lain.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Best-effort "Hello Lain" hotword listener.
 *
 * This is NOT a low-power, always-on wake-word engine like Porcupine/Snowboy
 * — those need a licensed keyword-spotting model and an API key we don't
 * have. Instead this loops Android's own SpeechRecognizer: listen, check
 * whatever it transcribed (partial results included, so it doesn't need to
 * wait for a pause) for something that sounds like "hello lain", restart if
 * not, hand off to the app if so. That means real battery/network cost
 * while running (some devices do this on-device, some hit the network) and
 * a brief gap between each listen cycle where speech could be missed — a
 * genuine tradeoff of not depending on a third-party SDK, not a hidden bug.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "lain_wake_word"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.lain.assistant.action.STOP_WAKE_WORD"

        private val WAKE_PHRASES = listOf("hello lain", "hello lane", "hey lain", "hi lain", "hello lynn")

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun matchesWakePhrase(heard: String): Boolean {
            val normalized = heard.trim().lowercase()
            return WAKE_PHRASES.any { normalized.contains(it) }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var recognizer: SpeechRecognizer? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        startListenLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        recognizer?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    private fun startListenLoop() {
        if (stopping || !SpeechRecognizer.isRecognitionAvailable(this)) return

        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) = checkForWakeWord(partialResults)
            override fun onResults(results: Bundle) = checkForWakeWord(results)

            override fun onError(error: Int) {
                r.destroy()
                if (!stopping) scope.launch { delay(300); startListenLoop() }
            }

            override fun onEndOfSpeech() = Unit
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        r.startListening(intent)
    }

    private fun checkForWakeWord(bundle: Bundle) {
        val heard = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.any { matchesWakePhrase(it) }) {
            recognizer?.destroy()
            launchAppListening()
            if (!stopping) scope.launch { delay(1000); startListenLoop() }
        }
    }

    private fun launchAppListening() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
        }
        startActivity(intent)
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "\"Hello Lain\" listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Listening for \"Hello Lain\"")
            .setContentText("Tap the tile or this to stop")
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }
}
