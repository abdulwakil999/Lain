package com.lain.assistant.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lain.assistant.MiniActivity
import com.lain.assistant.R
import com.lain.assistant.agent.LainName
import com.lain.assistant.voice.VoiceState
import com.lain.assistant.voice.WakeWordDetector
import com.lain.assistant.voice.WakeWordManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The foreground service that owns wake-word listening, and nothing else.
 *
 * It used to *be* the detector — a SpeechRecognizer loop transcribing the room
 * continuously. The detection now lives in [WakeWordDetector], which listens on raw
 * audio energy and only runs a recogniser once somebody has actually spoken. What
 * is left here is the part that genuinely belongs to a service: the foreground
 * notification Android requires before an app may touch the microphone from the
 * background, and the four ways the microphone can be taken away.
 *
 *  - **Another app takes it.** A call, a voice note, a recording app. Detected
 *    through [AudioManager.AudioRecordingCallback] on Android 10+, and by the
 *    recorder simply failing to open below that. Either way she waits and retries
 *    rather than fighting for a device she cannot have.
 *  - **The permission is revoked.** Checked on every loop, not once at start —
 *    the user can revoke it from Settings while this is running, and on Android 12+
 *    that kills the app's recording without telling it why.
 *  - **The headset changes.** A Bluetooth microphone disconnecting invalidates the
 *    open recorder. [AudioDeviceCallback] restarts detection on the new route.
 *  - **The screen goes off.** Not a failure but a deliberate pause: an open
 *    microphone holds the audio path awake, so idle listening with the screen off
 *    is a flat overnight battery in exchange for hearing nothing useful. Nothing is
 *    lost — the tile, the widgets and the bubble all reach Lain in one tap.
 *
 * Android's own restrictions are respected rather than worked around. There is no
 * path here that records without the permission, and none that runs a microphone
 * foreground service without the notification.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "lain_wake_word"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.lain.assistant.action.STOP_WAKE_WORD"

        /** Grace period after the mic is freed by another app before retrying. */
        private const val MIC_RETURN_DELAY_MS = 1_200L

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /**
         * Whether Lain was addressed by name.
         *
         * Deliberately not a fixed phrase. The requirement is her *name*, so
         * "hello Lain", "hi Lain", "sup Lain", "Lain, open WhatsApp" and the bare
         * name all count. [LainName] already knows the difference between the name
         * and the road it sounds like, and asking it keeps one list rather than two
         * that drift apart — which they had.
         */
        fun matchesWakePhrase(heard: String): Boolean = LainName.isAddressed(heard)
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var detector: WakeWordDetector? = null
    private var micTakenByOther = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> resume()
                Intent.ACTION_SCREEN_OFF -> pause()
            }
        }
    }

    /**
     * Notices when Android silences Lain's own recording.
     *
     * This is the *documented* way to find out that something else has taken the
     * microphone, and it is worth being precise about why. From Android 10 an app
     * is only told about its own recordings — you cannot enumerate other apps' use
     * of the mic, and an implementation that claims to is reading its own
     * configuration back. What Android does tell you is that a higher-priority
     * client (a phone call, the system assistant) has taken over and your stream is
     * now being fed silence: `isClientSilenced`.
     *
     * That is exactly the condition worth reacting to. Lain stands down rather than
     * sitting there processing a silent stream and concluding the room is quiet, and
     * comes back when the flag clears.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            val silenced = configs.orEmpty().any { it.isClientSilenced }
            // Only act on the transition, so this doesn't thrash on every update.
            if (silenced == micTakenByOther) return
            micTakenByOther = silenced
            if (silenced) pause() else scope.launch {
                delay(MIC_RETURN_DELAY_MS)
                resume()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = restartRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = restartRoute()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        if (!runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess) {
            isRunning = false
            stopSelf()
            return
        }

        // Never silently. Without the permission there is nothing to run, and the
        // state says so rather than the service sitting there doing nothing.
        if (!hasMicPermission()) {
            WakeWordManager.enter(VoiceState.ERROR, "Microphone permission isn't granted")
            stopSelf()
            return
        }

        detector = WakeWordDetector(
            context = applicationContext,
            scope = scope,
            onWake = { heard -> onWakeWord(heard) },
            onError = { reason -> WakeWordManager.enter(VoiceState.ERROR, reason) }
        )

        // The watchdog's way back: whatever went wrong, this is what "recovered"
        // means, and it is the same path a normal turn ends on.
        WakeWordManager.onRecover = { returnToWakeListening() }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val audio = getSystemService(AudioManager::class.java)
        runCatching { audio?.registerAudioDeviceCallback(deviceCallback, null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { audio?.registerAudioRecordingCallback(recordingCallback, null) }
        }

        // Stage one keeps running while she is thinking, working and talking, so
        // saying her name over the top of a reply interrupts it. It only stands down
        // for LISTENING_FOR_COMMAND, where the command recogniser owns the mic.
        scope.launch {
            WakeWordManager.state.collect { state ->
                when (state) {
                    VoiceState.LISTENING_FOR_COMMAND -> detector?.suspendDetection()
                    VoiceState.PROCESSING, VoiceState.EXECUTING, VoiceState.SPEAKING ->
                        if (isScreenOn() && !micTakenByOther) detector?.resumeDetection()
                    else -> Unit
                }
            }
        }

        if (isScreenOn()) resume() else WakeWordManager.enter(VoiceState.IDLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // STICKY so a service killed under memory pressure comes back listening,
        // which is the entire promise of leaving it on.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        val audio = getSystemService(AudioManager::class.java)
        runCatching { audio?.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { audio?.unregisterAudioRecordingCallback(recordingCallback) }
        }
        detector?.stop()
        detector = null
        WakeWordManager.shutDown()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ transitions

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isScreenOn(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun resume() {
        val d = detector ?: return
        if (!hasMicPermission()) {
            WakeWordManager.enter(VoiceState.ERROR, "Microphone permission isn't granted")
            return
        }
        if (micTakenByOther) return
        d.start()
        d.resumeDetection()
        WakeWordManager.enter(VoiceState.LISTENING_FOR_WAKE_WORD)
    }

    private fun pause() {
        detector?.suspendDetection()
        if (WakeWordManager.state.value == VoiceState.LISTENING_FOR_WAKE_WORD) {
            WakeWordManager.enter(VoiceState.IDLE)
        }
    }

    /**
     * A headset arriving or leaving invalidates an open recorder, so detection is
     * bounced onto whatever route now exists rather than left holding a dead one.
     */
    private fun restartRoute() {
        if (WakeWordManager.state.value != VoiceState.LISTENING_FOR_WAKE_WORD) return
        scope.launch {
            detector?.suspendDetection()
            delay(300)
            if (isScreenOn() && !micTakenByOther) detector?.resumeDetection()
        }
    }

    /**
     * Her name was heard. The turn runs here, with no screen involved.
     *
     * This used to call `startActivity` to open the mini surface and let *that* start
     * listening — and that is why hands-free did nothing at all. **A background
     * service may not start an activity on Android 10 and later** unless the app
     * holds "display over other apps" or something of the app is already in the
     * foreground. The service was running, the microphone was open, the wake word was
     * heard, and the launch was silently refused. From outside: the mic indicator
     * lit, and Lain never answered. This codebase had already learned the same
     * lesson in ScheduledTaskReceiver and it was not applied here.
     *
     * A voice turn needs no activity. [com.lain.assistant.agent.ChatEngine] is
     * application-scoped: it owns the recogniser, the router, the tool loop and the
     * voice, and every one of those works from a service. So the conversation is
     * driven directly and the screen is left out of it entirely — which also means
     * hands-free works with the phone face-down on a table, which is the whole point
     * of hands-free.
     *
     * The mini surface is still attempted afterwards, as visual feedback for the case
     * where Android does allow it. It failing changes nothing.
     */
    private fun onWakeWord(heard: String) {
        val engine = com.lain.assistant.agent.ChatEngine.activeInstance
        if (engine == null) {
            // Nothing to drive. Say so rather than sitting in WAKE_WORD_DETECTED until
            // the watchdog notices.
            WakeWordManager.enter(VoiceState.ERROR, "Lain isn't running")
            returnToWakeListening()
            return
        }

        // Barge-in. Saying her name while she is mid-sentence stops the sentence:
        // anything else means the old reply talks over the new one, which is the
        // single most irritating way for a voice assistant to behave.
        val was = WakeWordManager.state.value
        if (was == VoiceState.SPEAKING || was == VoiceState.PROCESSING || was == VoiceState.EXECUTING) {
            engine.silence()
        }

        WakeWordManager.enter(VoiceState.WAKE_WORD_DETECTED)

        // "Lain, open WhatsApp" is one sentence. If the wake already carried the
        // instruction, run it rather than asking the person to say it again.
        val carried = LainName.commandAfterName(heard)
        if (!carried.isNullOrBlank()) {
            engine.sendFromVoice(carried)
        } else {
            // One turn per wake: she answers, then goes back to listening for her
            // name rather than holding the microphone open for a follow-up.
            engine.setConversationMode(false)
            engine.startVoiceInput()
        }

        showMiniSurface(heard)
    }

    /**
     * Best-effort visual feedback. Never load-bearing.
     *
     * Android refuses this from the background unless the app holds "display over
     * other apps" — which the floating bubble asks for and many users will not have
     * granted. The turn is already running by the time this is attempted, so a
     * refusal costs a window and nothing else.
     */
    private fun showMiniSurface(heard: String) {
        runCatching {
            startActivity(
                Intent(this, MiniActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    // Deliberately not EXTRA_AUTO_LISTEN: the service has already
                    // started listening, and a second recogniser would fight it for
                    // the microphone.
                    putExtra(MiniActivity.EXTRA_WOKEN_BY, heard)
                    putExtra(MiniActivity.EXTRA_ALREADY_LISTENING, true)
                }
            )
        }
    }

    /**
     * Back to waiting for her name.
     *
     * Called when a turn finishes, and by the watchdog when one doesn't. The
     * cooldown keeps her own last words from waking her again.
     */
    fun returnToWakeListening() {
        scope.launch {
            detector?.cooldown()
            if (isScreenOn() && hasMicPermission() && !micTakenByOther) {
                detector?.resumeDetection()
                WakeWordManager.enter(VoiceState.LISTENING_FOR_WAKE_WORD)
            } else {
                WakeWordManager.enter(VoiceState.IDLE)
            }
        }
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Listening for \"Lain\"", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MiniActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Listening for your voice")
            // States exactly what is happening, because a microphone notification
            // that is vague about it is worse than none.
            .setContentText("Say \"Lain\". The mic stays open but nothing is transcribed or sent until she hears her name. Pauses when the screen is off.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Say \"Lain\" — \"hey Lain\", \"sup Lain\", or just her name. The microphone " +
                        "stays open in one steady stream rather than switching on and off, which is " +
                        "both cheaper on battery and why the mic indicator stays lit. Until she hears " +
                        "her name that stream is only measured for loudness on this phone: no " +
                        "transcription, nothing stored, nothing sent. Pauses while the screen is off."
                )
            )
            .setContentIntent(open)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
