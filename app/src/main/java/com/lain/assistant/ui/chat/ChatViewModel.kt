package com.lain.assistant.ui.chat

import androidx.lifecycle.ViewModel
import com.lain.assistant.AppContainer
import com.lain.assistant.agent.ChatState

/**
 * A window onto [com.lain.assistant.agent.ChatEngine], nothing more.
 *
 * All conversation state and the tool loop live on the engine (application
 * scope) rather than here, so nothing is lost when this ViewModel is destroyed —
 * screen lock, rotation, swiping the app away, or hopping between the main app,
 * the mini surface and the floating bubble.
 */
class ChatViewModel(container: AppContainer) : ViewModel() {

    private val engine = container.chatEngine

    val state = engine.state

    fun attachTts(context: android.content.Context) {
        // The engine owns TTS now; kept so existing screens don't need to change.
    }

    fun onInputChange(value: String) = engine.onInputChange(value)
    fun onAwaken() = engine.onAwaken()
    fun startVoiceInput() = engine.startVoiceInput()
    fun send() = engine.send()
    fun stop() = engine.stop()
    fun toggleMute() = engine.toggleMute()
    fun setConversationMode(enabled: Boolean) = engine.setConversationMode(enabled)
}

/** Kept as an alias so screens can keep referring to the shape they already use. */
typealias ChatUiState = ChatState
