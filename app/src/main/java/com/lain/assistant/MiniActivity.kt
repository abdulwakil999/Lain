package com.lain.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lain.assistant.automation.LockScreenAccess
import com.lain.assistant.ui.LainViewModelFactory
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.mini.MiniChatScreen
import com.lain.assistant.ui.theme.LainTheme

/**
 * The compact surface opened by the widgets, the Quick Settings tile, the
 * floating bubble — an input bar plus recent messages over a
 * translucent scrim, instead of launching the whole app just to say one thing.
 */
class MiniActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_LISTEN = "com.lain.assistant.MINI_AUTO_LISTEN"

        /** A command to run straight away, for widget buttons like "read the screen". */
        const val EXTRA_COMMAND = "com.lain.assistant.MINI_COMMAND"

        /**
         * The transcript that contained her name, when hands-free opened this.
         *
         * Carried so a wake that already held the whole command — "Lain, open
         * WhatsApp" — does not make the user say it a second time. Without it the
         * detector hears the instruction, throws it away, and asks for it again.
         */
        const val EXTRA_WOKEN_BY = "com.lain.assistant.MINI_WOKEN_BY"

        /**
         * The service already started the turn; this window is only here to watch it.
         *
         * Without it the screen would start a second recogniser and the two would
         * fight over one microphone.
         */
        const val EXTRA_ALREADY_LISTENING = "com.lain.assistant.MINI_ALREADY_LISTENING"

    }

    /**
     * Held in state rather than read once in onCreate.
     *
     * This activity is singleTask, so a second launch reuses the existing instance
     * and onCreate never runs again — which meant the first widget tap worked and
     * every tap after it did nothing at all. onNewIntent republishes the request so
     * the composition reacts to it.
     */
    private var request by mutableStateOf(Request(autoListen = false, command = null))

    private data class Request(
        val autoListen: Boolean,
        val command: String?,
        val wokenBy: String? = null,
        val nonce: Long = System.nanoTime()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Usable from the lock screen on phones that have no credential set.
        //
        // On those the keyguard is a swipe and dismissing it protects nothing, so
        // asking someone to unlock before they can say "torch on" is a step that
        // guards nothing. Where a PIN or pattern *is* set this asks Android to raise
        // the user's own prompt and gets nowhere unless they answer it — there is no
        // path here that goes around it, and there should not be.
        val lock = LockScreenAccess(this)
        if (lock.isLocked() && !lock.hasCredential()) {
            lock.unlock(this) { }
        }

        request = readRequest(intent)
        val container = (application as LainApplication).container

        setContent {
            LainTheme {
                val factory = LainViewModelFactory(container)
                val chatViewModel: ChatViewModel = viewModel(factory = factory)
                val current = request
                MiniChatScreen(
                    viewModel = chatViewModel,
                    autoListen = current.autoListen,
                    command = current.command,
                    wokenBy = current.wokenBy,
                    requestKey = current.nonce,
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = readRequest(intent)
    }

    private fun readRequest(intent: Intent?) = Request(
        autoListen = intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true &&
            intent?.getBooleanExtra(EXTRA_ALREADY_LISTENING, false) != true,
        command = intent?.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() },
        // Suppressed when the service is already running the turn, or the screen
        // would send the command a second time.
        wokenBy = intent?.getStringExtra(EXTRA_WOKEN_BY)
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { intent.getBooleanExtra(EXTRA_ALREADY_LISTENING, false) != true }
    )
}
