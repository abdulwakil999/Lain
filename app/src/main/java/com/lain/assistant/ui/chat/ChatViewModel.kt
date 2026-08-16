package com.lain.assistant.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Provider
import com.lain.assistant.data.Sender
import com.lain.assistant.data.UserProfile
import com.lain.assistant.network.LlmClientFactory
import com.lain.assistant.network.LlmMessage
import com.lain.assistant.network.LlmResult
import com.lain.assistant.network.ToolCall
import com.lain.assistant.tools.ToolDefinitions
import com.lain.assistant.tools.ToolDispatcher
import com.lain.assistant.tts.TtsEngine
import com.lain.assistant.tts.TtsEngineProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val hasAwakened: Boolean = false, // flips once, triggers the strip-transition + background swap
    val profile: UserProfile? = null,
    val error: String? = null
)

private const val MAX_TOOL_ROUNDS = 5

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var ttsEngine: TtsEngine? = null
    private var conversation = mutableListOf<LlmMessage>()

    init {
        viewModelScope.launch {
            val profile = container.userPreferencesRepository.userProfile.first()
            _state.update { it.copy(profile = profile) }
        }
    }

    /** Compose can't hand a Context into the constructor cleanly via the factory, so it's wired in from the screen. */
    fun attachTts(context: android.content.Context) {
        if (ttsEngine != null) return
        viewModelScope.launch {
            val endpoint = container.userPreferencesRepository.kokoroEndpoint.first()
            ttsEngine = TtsEngineProvider.create(context, endpoint)
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    /** Called once when the chat screen first appears — fires the strip-transition + spoken/shown greeting. */
    fun onAwaken() {
        if (_state.value.hasAwakened) return
        val greeting = ChatMessage(sender = Sender.LAIN, text = "What's up niceo?")
        _state.update { it.copy(hasAwakened = true, messages = it.messages + greeting) }
        speak(greeting.text)
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isBlank() || _state.value.isSending) return

        val userMessage = ChatMessage(sender = Sender.USER, text = text)
        _state.update { it.copy(messages = it.messages + userMessage, input = "", isSending = true, error = null) }
        conversation.add(LlmMessage(role = LlmMessage.Role.USER, text = text))

        viewModelScope.launch {
            val provider = container.userPreferencesRepository.selectedProvider.first()
            val modelId = container.userPreferencesRepository.selectedModelId.first()
            val apiKey = container.secureKeyStore.getApiKey(provider)

            if (modelId.isNullOrBlank() || apiKey.isNullOrBlank()) {
                _state.update { it.copy(isSending = false, error = "No model/API key configured yet") }
                return@launch
            }

            val client = LlmClientFactory.create(provider)
            val systemPrompt = buildSystemPrompt(_state.value.profile)

            var rounds = 0
            var finalText: String? = null
            while (rounds < MAX_TOOL_ROUNDS && finalText == null) {
                rounds++
                when (val result = client.send(apiKey, modelId, systemPrompt, conversation, ToolDefinitions.all)) {
                    is LlmResult.Message -> finalText = result.text
                    is LlmResult.Error -> {
                        _state.update { it.copy(isSending = false, error = result.message) }
                        return@launch
                    }
                    is LlmResult.ToolCalls -> {
                        conversation.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = "", toolCalls = result.calls))
                        val capturedImages = mutableListOf<String>()
                        for (call: ToolCall in result.calls) {
                            val output = container.toolDispatcher.execute(call)
                            if (output.startsWith(ToolDispatcher.IMAGE_RESULT_PREFIX)) {
                                capturedImages += output.removePrefix(ToolDispatcher.IMAGE_RESULT_PREFIX)
                                conversation.add(
                                    LlmMessage(role = LlmMessage.Role.TOOL, text = "Captured — attached below.", toolCallId = call.id)
                                )
                            } else {
                                conversation.add(LlmMessage(role = LlmMessage.Role.TOOL, text = output, toolCallId = call.id))
                            }
                        }
                        // Tool results are text-only across these APIs, so an actual image has to ride
                        // in as its own user turn right after the tool results it belongs to.
                        if (capturedImages.isNotEmpty()) {
                            conversation.add(
                                LlmMessage(role = LlmMessage.Role.USER, text = "(image just captured, see attached)", images = capturedImages)
                            )
                        }
                    }
                }
            }

            val replyText = finalText ?: "I hit a wall running that through a few steps — try rephrasing?"
            conversation.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = replyText))
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(sender = Sender.LAIN, text = replyText),
                    isSending = false
                )
            }
            speak(replyText)
        }
    }

    private fun speak(text: String) {
        val engine = ttsEngine ?: return
        viewModelScope.launch { engine.speak(text) }
    }

    private fun buildSystemPrompt(profile: UserProfile?): String {
        val nickname = profile?.nickname?.takeIf { it.isNotBlank() } ?: "you"
        val name = profile?.name?.takeIf { it.isNotBlank() } ?: "the user"
        val age = profile?.age?.takeIf { it > 0 }
        val gender = profile?.gender

        return """
            You are Lain — short for "Leave-it-to-Artificial-intelligence-Niceo". You live on the
            user's Android phone and can genuinely act on it, not just describe what you'd do: open
            and close apps, place calls, send SMS, message WhatsApp contacts, set reminders, write
            notes, read the screen's text, actually look at the screen (use look_at_screen whenever
            layout/images/colors/a game board matter more than raw text — read_screen alone can't see
            those), tap and swipe, take photos, and listen through the microphone. Call the tools
            you're given to actually do these things.

            The user goes by "$nickname" — address them that way by default in everything you say.
            Only use their real name, "$name", when a moment genuinely calls for formality: confirming
            something official, a serious or emergency situation, or drafting something written in
            their name. ${if (age != null) "They're $age years old." else ""} ${if (gender != null) "Gender: ${gender.name.lowercase()}." else ""}

            Tone: cool, capable, a little dry. Not bubbly, not robotic, not over-explaining — the
            assistant that just handles it.
        """.trimIndent()
    }
}
