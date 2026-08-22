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
 * Idle listening runs only while the screen is on. That is not a setting and not
 * a compromise the user is asked to make — it is the only correct behaviour.
 * Active audio capture holds the audio HAL's wake lock, so a recognizer looping
 * with the screen off stops the device suspending at all; the phone then discharges
 * overnight in a pocket while hearing nothing useful. Nothing is lost that matters:
 * a task already running is driven by AgentForegroundService and is never cut off
 * by the screen going dark, and the Quick Settings tile, the home-screen widget and
 * the floating bubble all reach Lain in one tap regardless.
 *
 * Where the platform has an on-device recogniser (Android 13+) it is used in
 * preference to the default one, which keeps every listen cycle off the network.
 * Repeated recognizer errors back off exponentially rather than hot-looping.
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
     * Screen state gates *idle wake-word listening only*. A task already running is
     * driven by AgentForegroundService and is never interrupted by the screen
     * going off.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> resumeListening()
                Intent.ACTION_SCREEN_OFF -> pauseListening()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        // Explicitly not exported. These are protected system broadcasts so the flag
        // isn't strictly required, but being implicit here is what trips apps up on
        // Android 14, and an unexported receiver is what we actually want.
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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
        // On-device where the platform has it: every listen cycle then costs no
        // network, no radio wake-up and no round trip, which on a loop like this is
        // the difference between a background drain and a negligible one.
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for \"Hello Lain\"")
            .setContentText("Pauses while the screen is off")
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
