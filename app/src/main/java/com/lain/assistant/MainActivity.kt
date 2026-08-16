package com.lain.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lain.assistant.ui.LainApp
import com.lain.assistant.ui.theme.LainTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the widget and the "Hello Lain" wake-word service to drop straight into listening mode. */
        const val EXTRA_AUTO_LISTEN = "com.lain.assistant.EXTRA_AUTO_LISTEN"
    }

    private var autoListenToken by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LainApplication).container
        consumeAutoListenExtra(intent)
        setContent {
            LainTheme {
                LainApp(container = container, autoListenToken = autoListenToken)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoListenExtra(intent)
    }

    private fun consumeAutoListenExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) {
            autoListenToken = System.currentTimeMillis()
        }
    }
}
