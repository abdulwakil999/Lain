package com.lain.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lain.assistant.ui.LainViewModelFactory
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.mini.MiniChatScreen
import com.lain.assistant.ui.theme.LainTheme

/**
 * The compact surface opened by the widgets, the Quick Settings tile, the
 * floating bubble and the wake word — an input bar plus recent messages over a
 * translucent scrim, instead of launching the whole app just to say one thing.
 */
class MiniActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_LISTEN = "com.lain.assistant.MINI_AUTO_LISTEN"

        /** A command to run straight away, for widget buttons like "read the screen". */
        const val EXTRA_COMMAND = "com.lain.assistant.MINI_COMMAND"
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

    private data class Request(val autoListen: Boolean, val command: String?, val nonce: Long = System.nanoTime())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        autoListen = intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true,
        command = intent?.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
    )
}
