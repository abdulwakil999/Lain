package com.lain.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lain.assistant.ui.LainViewModelFactory
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.mini.MiniChatScreen
import com.lain.assistant.ui.theme.LainTheme

/**
 * The compact surface opened by the widget, the Quick Settings tile, the
 * floating bubble and the wake word — an input bar plus recent messages over a
 * translucent scrim, instead of launching the whole app just to say one thing.
 */
class MiniActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_LISTEN = "com.lain.assistant.MINI_AUTO_LISTEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoListen = intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true
        val container = (application as LainApplication).container

        setContent {
            LainTheme {
                val factory = LainViewModelFactory(container)
                val chatViewModel: ChatViewModel = viewModel(factory = factory)
                MiniChatScreen(
                    viewModel = chatViewModel,
                    autoListen = autoListen,
                    onDismiss = { finish() }
                )
            }
        }
    }
}
