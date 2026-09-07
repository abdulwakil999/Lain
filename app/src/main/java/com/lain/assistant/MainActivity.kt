package com.lain.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
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
        /** Set by the widget and the Quick Settings tile to drop straight into listening mode. */
        const val EXTRA_AUTO_LISTEN = "com.lain.assistant.EXTRA_AUTO_LISTEN"
    }

    private var autoListenToken by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LainApplication).container
        consumeAutoListenExtra(intent)
        consumeSharedContent(intent)
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
        consumeSharedContent(intent)
    }

    /**
     * Takes what another app sent through the share sheet.
     *
     * Lain had no ACTION_SEND filter at all, so she never appeared in the sheet and
     * the only route in was her own attach button — which means "share this to Lain"
     * from a gallery, a browser or WhatsApp simply wasn't a thing the app could do.
     *
     * Shared text lands in the message box rather than being sent, and files become
     * attachments. Nothing is sent automatically: a share is the user handing over
     * material, not an instruction, and firing a request off the back of it would be
     * acting on something they haven't finished saying.
     */
    private fun consumeSharedContent(intent: Intent?) {
        if (intent == null) return
        val engine = (application as LainApplication).container.chatEngine

        when (intent.action) {
            Intent.ACTION_SEND -> {
                streamUri(intent, Intent.EXTRA_STREAM)?.let(engine::attach)
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                    ?.let(engine::copyToInput)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                streamUris(intent).forEach(engine::attach)
            }

            else -> return
        }

        // Cleared so a rotation or a return from the background doesn't re-attach the
        // same file, which is what makes a share arrive three times.
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun consumeAutoListenExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) {
            autoListenToken = System.currentTimeMillis()
        }
    }
}
