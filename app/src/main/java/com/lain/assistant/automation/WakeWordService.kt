package com.lain.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lain.assistant.LainApplication
import com.lain.assistant.MiniActivity
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
 * This is NOT a low-power keyword-spotting engine like Porcupine — those need a
 * licensed model. It loops Android's own SpeechRecognizer: listen, check the
 * transcript (partials included) for the wake phrase, restart if not, hand off
 * if so.
 *
 * Because that costs real power, it is aggressively gated: listening stops
 * entirely while the screen is off (the common case — a phone in a pocket), and
 * repeated recognizer errors back off exponentially instead of hot-looping. The
 * honest tradeoff is that it can't hear you with the screen off, which is what
 * makes it survivable for a battery.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "lain_wake_word"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.lain.assistant.action.STOP_WAKE_WORD"

        private const val BASE_RETRY_MS = 400L
        private const val MAX_RETRY_MS = 15_000L

        private val WAKE_PHRASES = listOf("hello lain", "hello lane", "hey lain", "hi lain", "hello lynn", "hello line")

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
    private var retryDelay = BASE_RETRY_MS
    private var listening = false

    /**
     * Screen state is the main battery lever, but it's the user's call: with
     * battery saver off, Lain keeps listening with the screen locked. Note this
     * only ever gates *idle wake-word listening* — a task already running is
     * driven by AgentForegroundService and is never interrupted by the screen
     * going off.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> resumeListening()
                Intent.ACTION_SCREEN_OFF -> if (batterySaver) pauseListening()
            }
        }
    }

    @Volatile
    private var batterySaver: Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )

        val prefs = (application as LainApplication).container.userPreferencesRepository
        scope.launch {
            prefs.isBatterySaver.collect { enabled ->
                batterySaver = enabled
                if (!enabled) resumeListening() else if (!isScreenOn()) pauseListening()
            }
        }

        if (isScreenOn()) resumeListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        teardownRecognizer()
        scope.cancel()
        super.onDestroy()
    }

    private fun isScreenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun resumeListening() {
        if (listening || stopping) return
        listening = true
        retryDelay = BASE_RETRY_MS
        startListenLoop()
    }

    private fun pauseListening() {
        listening = false
        teardownRecognizer()
    }

    private fun teardownRecognizer() {
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    private fun startListenLoop() {
        if (stopping || !listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        teardownRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) = checkForWakeWord(partialResults)

            override fun onResults(results: Bundle) {
                checkForWakeWord(results)
                retryDelay = BASE_RETRY_MS
                scheduleRestart()
            }

            override fun onError(error: Int) {
                // Exponential backoff: a recognizer that's erroring (no network, busy mic,
                // no match) would otherwise spin as fast as the CPU allows.
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_MS)
                scheduleRestart()
            }

            override fun onEndOfSpeech() = Unit
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { r.startListening(intent) }
    }

    private fun scheduleRestart() {
        if (stopping || !listening) return
        scope.launch {
            delay(retryDelay)
            if (!stopping && listening) startListenLoop()
        }
    }

    private fun checkForWakeWord(bundle: Bundle) {
        val heard = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.any { matchesWakePhrase(it) }) {
            teardownRecognizer()
            launchMiniListening()
            scope.launch {
                delay(2000) // don't immediately re-trigger on our own greeting
                if (!stopping && listening) startListenLoop()
            }
        }
    }

    /** Opens the compact surface, not the whole app. */
    private fun launchMiniListening() {
        startActivity(
            Intent(this, MiniActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MiniActivity.EXTRA_AUTO_LISTEN, true)
            }
        )
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
            .setContentText("Battery saver pauses this while the screen is off")
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
