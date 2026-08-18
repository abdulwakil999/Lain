package com.lain.assistant.agent

import android.content.Context
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.MemoryRepository
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.Sender
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.data.UserProfile
import com.lain.assistant.network.LlmClientFactory
import com.lain.assistant.network.LlmMessage
import com.lain.assistant.network.LlmResult
import com.lain.assistant.network.ToolCall
import com.lain.assistant.tools.ToolDefinitions
import com.lain.assistant.tools.ToolDispatcher
import com.lain.assistant.tts.TtsEngine
import com.lain.assistant.tts.TtsEngineProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val isListening: Boolean = false,
    val isMuted: Boolean = false,
    val conversationMode: Boolean = false,
    val statusLine: String? = null,
    val hasAwakened: Boolean = false,
    val profile: UserProfile? = null,
    val error: String? = null
) {
    val isBusy: Boolean get() = isSending || isListening
}

private const val MAX_TOOL_ROUNDS = 14
private const val MAX_HISTORY_MESSAGES = 40

/**
 * The brain, deliberately living on the Application and not in a ViewModel.
 *
 * A ViewModel dies with its Activity, which meant a multi-step task was killed
 * the moment the screen locked or the user swiped away — mid-download, mid-message.
 * Everything runs here on an application-scoped coroutine instead, with a
 * foreground service holding a wake lock while a task is in flight, so work
 * continues with the display off.
 *
 * It's a singleton on purpose: the main app, the mini surface and the floating
 * bubble are three windows onto the *same* conversation, so you can start a
 * request in one and carry it on in another without losing the thread.
 */
class ChatEngine(
    private val appContext: Context,
    private val prefs: UserPreferencesRepository,
    private val memory: MemoryRepository,
    private val keyStore: SecureKeyStore,
    private val toolDispatcher: ToolDispatcher,
    private val voiceInput: VoiceInputController
) {

    companion object {
        /** Lets the foreground service's Stop action reach the running task. */
        @Volatile
        var activeInstance: ChatEngine? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val conversation = mutableListOf<LlmMessage>()
    private var activeJob: Job? = null
    private var ttsEngine: TtsEngine? = null

    init {
        activeInstance = this
        scope.launch {
            _state.update {
                it.copy(
                    profile = prefs.userProfile.first(),
                    isMuted = prefs.isMuted.first()
                )
            }
            ttsEngine = TtsEngineProvider.create(appContext, prefs.kokoroEndpoint.first())
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun onAwaken() {
        if (_state.value.hasAwakened) return
        val greeting = ChatMessage(sender = Sender.LAIN, text = "What's up niceo?")
        _state.update { it.copy(hasAwakened = true, messages = it.messages + greeting) }
        speak(greeting.text)
    }

    /**
     * Hands-free back-and-forth: after Lain finishes a step she starts listening
     * again on her own, so a chain like "open Chrome" → "search anime heaven" →
     * "tap the first result" works without touching the phone between commands.
     */
    fun setConversationMode(enabled: Boolean) {
        _state.update { it.copy(conversationMode = enabled) }
        if (enabled && !_state.value.isBusy) startVoiceInput()
    }

    fun startVoiceInput() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isListening = true, error = null, statusLine = "Listening…") }
        activeJob = scope.launch {
            voiceInput.listenOnce().fold(
                onSuccess = { heard ->
                    _state.update { it.copy(isListening = false, input = heard, statusLine = null) }
                    send()
                },
                onFailure = { err ->
                    _state.update { it.copy(isListening = false, statusLine = null, error = "Didn't catch that: ${err.message}") }
                    // A failed listen shouldn't silently end a hands-free session.
                    if (_state.value.conversationMode) {
                        delay(700)
                        if (!_state.value.isBusy && _state.value.conversationMode) startVoiceInput()
                    }
                }
            )
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        ttsEngine?.stop()
        AgentForegroundService.stop(appContext)
        _state.update { it.copy(isSending = false, isListening = false, statusLine = null, conversationMode = false) }
    }

    fun toggleMute() {
        val next = !_state.value.isMuted
        if (next) ttsEngine?.stop()
        _state.update { it.copy(isMuted = next) }
        scope.launch { prefs.setMuted(next) }
    }

    fun send(text: String? = null) {
        val message = (text ?: _state.value.input).trim()
        if (message.isBlank() || _state.value.isSending) return

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(sender = Sender.USER, text = message),
                input = "",
                isSending = true,
                error = null,
                statusLine = "Thinking…"
            )
        }
        conversation.add(LlmMessage(role = LlmMessage.Role.USER, text = message))

        // The service is what keeps this alive past a screen lock.
        AgentForegroundService.start(appContext, "Thinking…")

        activeJob = scope.launch {
            try {
                runAgentLoop()
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(
                        isSending = false,
                        statusLine = null,
                        messages = it.messages + ChatMessage(sender = Sender.LAIN, text = "Stopped.")
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSending = false, statusLine = null, error = t.message ?: "Something broke") }
            } finally {
                if (_state.value.isSending) _state.update { it.copy(isSending = false, statusLine = null) }
                AgentForegroundService.stop(appContext)
            }
        }
    }

    private suspend fun runAgentLoop() {
        val provider = prefs.selectedProvider.first()
        val modelId = prefs.selectedModelId.first()
        val apiKey = keyStore.getApiKey(provider)

        if (modelId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            _state.update { it.copy(isSending = false, statusLine = null, error = "No model/API key configured yet — open Settings.") }
            return
        }

        val client = LlmClientFactory.create(provider)
        val systemPrompt = buildSystemPrompt(_state.value.profile, memory.asPromptBlock())

        var rounds = 0
        var finalText: String? = null

        while (rounds < MAX_TOOL_ROUNDS && finalText == null) {
            rounds++
            trimConversation()
            if (rounds > 1) setStatus("Working… (step $rounds)")

            when (val result = client.send(apiKey, modelId, systemPrompt, conversation, ToolDefinitions.all)) {
                is LlmResult.Message -> finalText = result.text
                is LlmResult.Error -> {
                    _state.update { it.copy(isSending = false, statusLine = null, error = result.message) }
                    return
                }
                is LlmResult.ToolCalls -> {
                    conversation.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = "", toolCalls = result.calls))
                    val capturedImages = mutableListOf<String>()

                    for (call: ToolCall in result.calls) {
                        setStatus(statusFor(call))
                        val output = toolDispatcher.execute(call)
                        if (output.startsWith(ToolDispatcher.IMAGE_RESULT_PREFIX)) {
                            capturedImages += output.removePrefix(ToolDispatcher.IMAGE_RESULT_PREFIX)
                            conversation.add(LlmMessage(role = LlmMessage.Role.TOOL, text = "Captured — image attached below.", toolCallId = call.id))
                        } else {
                            conversation.add(LlmMessage(role = LlmMessage.Role.TOOL, text = output, toolCallId = call.id))
                        }
                    }

                    if (capturedImages.isNotEmpty()) {
                        dropOlderImages()
                        conversation.add(
                            LlmMessage(
                                role = LlmMessage.Role.USER,
                                text = "(current screen, just captured)",
                                images = capturedImages.takeLast(1)
                            )
                        )
                    }
                }
            }
        }

        val replyText = finalText
            ?: "I got partway through that and ran out of steps. Tell me what you can see and I'll pick it up."
        conversation.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = replyText))
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(sender = Sender.LAIN, text = replyText),
                isSending = false,
                statusLine = null
            )
        }
        speak(replyText)

        // Hands-free chains continue here rather than dead-ending after one command.
        if (_state.value.conversationMode) {
            delay(1200)
            if (!_state.value.isBusy && _state.value.conversationMode) startVoiceInput()
        }
    }

    private fun setStatus(status: String) {
        _state.update { it.copy(statusLine = status) }
        AgentForegroundService.updateStatus(appContext, status)
    }

    private fun statusFor(call: ToolCall): String = when (call.name) {
        "open_app" -> "Opening app…"
        "read_screen" -> "Reading the screen…"
        "look_at_screen" -> "Looking at the screen…"
        "tap_text", "tap_screen" -> "Tapping…"
        "type_text" -> "Typing…"
        "make_call" -> "Placing the call…"
        "send_sms" -> "Sending the text…"
        "send_whatsapp_message" -> "Opening WhatsApp…"
        "lookup_contact" -> "Checking contacts…"
        "open_url" -> "Opening the page…"
        "wait" -> "Waiting for the screen…"
        else -> "Working…"
    }

    /**
     * Keeps the payload bounded. Free models especially have small context
     * windows and tight rate limits, so old turns are dropped rather than
     * blowing the limit mid-task.
     */
    private fun trimConversation() {
        if (conversation.size <= MAX_HISTORY_MESSAGES) return
        var cut = conversation.size - MAX_HISTORY_MESSAGES
        // Never strand a TOOL result whose ASSISTANT tool_calls turn was removed —
        // providers reject that shape. Cut forward to a clean USER boundary.
        while (cut < conversation.size && conversation[cut].role != LlmMessage.Role.USER) cut++
        if (cut >= conversation.size) return
        repeat(cut) { conversation.removeAt(0) }
    }

    private fun dropOlderImages() {
        for (i in conversation.indices) {
            val msg = conversation[i]
            if (msg.images.isNotEmpty()) {
                conversation[i] = msg.copy(images = emptyList(), text = "(an earlier screen, no longer attached)")
            }
        }
    }

    private fun speak(text: String) {
        if (_state.value.isMuted) return
        val engine = ttsEngine ?: return
        scope.launch { engine.speak(text) }
    }

    private fun buildSystemPrompt(profile: UserProfile?, memoryBlock: String): String {
        val nickname = profile?.nickname?.takeIf { it.isNotBlank() } ?: "you"
        val name = profile?.name?.takeIf { it.isNotBlank() } ?: "the user"
        val age = profile?.age?.takeIf { it > 0 }
        val gender = profile?.gender

        val memorySection = if (memoryBlock.isBlank()) {
            "You haven't saved anything about them yet. When you learn something durable — who a nickname means, a number, an app they prefer — call `remember` straight away, without being asked."
        } else {
            "What you already know about them:\n$memoryBlock"
        }

        return """
            You are Lain — short for "Leave-it-to-Artificial-intelligence-Niceo". You live on the
            user's Android phone and you genuinely operate it. You are not a chatbot describing
            actions; you take them.

            HOW YOU OPERATE THE PHONE
            You drive apps the way a person does: look, act, look again. The loop is always
            read_screen (or look_at_screen) -> act (tap_text / type_text / swipe) -> read_screen
            again to confirm what changed -> continue. Never fire one action and assume it worked;
            the screen is the source of truth.

            Typing is two moves: tap_text the field so it takes focus, then type_text. Prefer
            tap_text over tap_screen — matching a visible label beats guessing pixels. After
            opening an app, call wait, then read_screen; apps need a beat to draw.

            YOU ARE OFTEN MID-CONVERSATION WHILE THE USER IS INSIDE ANOTHER APP
            They talk to you in short steps: "open chrome", then "search anime heaven", then "open
            that site", then "search bleach", then "download episode 3". Each instruction applies to
            whatever is on screen RIGHT NOW. Always read_screen first to see where you actually are
            before acting — do not assume the screen still looks like it did last turn, and do not
            restart the whole task from scratch. If they say "search X", find the search field on the
            CURRENT screen, tap it, type X, and submit it.

            FINISH WHAT YOU START
            A task is done when the goal is achieved, not when you've made progress toward it.
            "Message Ade on WhatsApp" is finished when the message is SENT. "Play a song on Spotify"
            is finished when audio is playing — opening Spotify is not enough. If a step fails, read
            the screen and try another route instead of stopping. Come back to the user only when the
            task is complete, when you need a decision only they can make, or when you're genuinely
            blocked — and if blocked, say exactly what you saw and what you tried.

            Never claim you did something you didn't. If a tool reports failure or says something
            still needs tapping, believe the tool over your own expectations.

            WHO YOU'RE TALKING TO
            They go by "$nickname" — use that. Their real name, "$name", is for moments that call for
            formality: something official, something serious, or writing in their name.
            ${if (age != null) "They're $age." else ""} ${if (gender != null) "Gender: ${gender.name.lowercase()}." else ""}

            $memorySection

            VOICE
            Cool, capable, a little dry. Talk like someone already handling it, not a service
            announcing itself. Short sentences — most of your replies are being spoken aloud while
            the user is looking at another app, so keep them to a line or two. No "I'd be happy to",
            no repeating the request back, no bullet-point reports unless asked. If something went
            sideways, say so plainly.
        """.trimIndent()
    }
}
