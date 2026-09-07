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

    /** A spoken command, into the same pipeline a typed one uses. */
    fun sendFromVoice(text: String) = engine.sendFromVoice(text)

    /**
     * The instruction inside a wake phrase, if it carried one.
     *
     * "Lain, open WhatsApp" arrives as one transcript; asking the user to repeat the
     * second half after she has already heard it is the thing that makes a voice
     * assistant feel broken.
     */
    fun commandInsideWakePhrase(heard: String): String? =
        com.lain.assistant.agent.LainName.commandAfterName(heard)
    fun send() = engine.send()

    /** Sends a specific reply — used by the confirmation buttons. */
    fun send(text: String) = engine.send(text)
    fun stop() = engine.stop()
    fun toggleMute() = engine.toggleMute()

    /** Stops the current utterance only — the task keeps running and the mute setting is untouched. */
    fun silence() = engine.silence()
    fun setConversationMode(enabled: Boolean) = engine.setConversationMode(enabled)

    /** Puts a message's text in the composer, ready to edit and send again. */
    fun copyToInput(text: String) = engine.copyToInput(text)

    /** Re-runs a request — either this user message, or the one Lain was answering. */
    fun resend(messageId: String) = engine.resend(messageId)

    /** Removes a message from the transcript, from storage and from the model's context. */
    fun deleteMessage(messageId: String) = engine.deleteMessage(messageId)

    /** Reads a picked or captured file and holds it for the next message. */
    fun attach(uri: android.net.Uri) = engine.attach(uri)

    fun removeAttachment(uri: android.net.Uri) = engine.removeAttachment(uri)

    fun onCloseHandled() = engine.onCloseHandled()
}

/** Kept as an alias so screens can keep referring to the shape they already use. */
typealias ChatUiState = ChatState
