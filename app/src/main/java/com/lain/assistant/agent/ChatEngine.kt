package com.lain.assistant.agent

import android.content.Context
import com.lain.assistant.automation.Attachment
import com.lain.assistant.automation.AttachmentReader
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.LocalActions
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.ConversationStore
import com.lain.assistant.data.MemoryCategory
import com.lain.assistant.data.MemoryStore
import com.lain.assistant.data.ModelCapabilities
import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.Provider
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.Sender
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.data.UserProfile
import com.lain.assistant.data.db.MessageEntity
import com.lain.assistant.network.Http
import com.lain.assistant.network.LlmClient
import com.lain.assistant.network.LlmClientFactory
import com.lain.assistant.network.LlmMessage
import com.lain.assistant.network.LlmResult
import com.lain.assistant.network.RequestTuning
import com.lain.assistant.network.StreamEvent
import com.lain.assistant.network.ToolCall
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolDefinitions
import com.lain.assistant.tools.ToolDispatcher
import com.lain.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.lain.assistant.tts.StreamingSpeaker
import com.lain.assistant.tts.TtsEngine
import com.lain.assistant.tts.TtsEngineProvider

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    /** Files the user has attached but not sent yet. */
    val attachments: List<Attachment> = emptyList(),
    val isSending: Boolean = false,
    val isListening: Boolean = false,
    val isMuted: Boolean = false,
    /** True only while audio is actually coming out, so the UI can offer to stop just the voice. */
    val isSpeaking: Boolean = false,
    /**
     * The reply being generated right now, shown as it arrives. Null when nothing is
     * streaming. Kept out of [messages] so a cancelled turn leaves no half-message
     * behind in the transcript.
     */
    val streamingText: String? = null,
    val conversationMode: Boolean = false,
    val statusLine: String? = null,
    val hasAwakened: Boolean = false,
    val profile: UserProfile? = null,
    val error: String? = null,
    /** Set when automatic fallback swapped the model, so the user is told rather than silently switched. */
    val activeModelNotice: String? = null,
    /**
     * What the currently selected model can actually do, so the UI can be honest
     * about it up front rather than only after something fails.
     */
    val capabilities: ModelCapabilities? = null,
    /** Which model produced the most recent reply — "local" when Android answered it. */
    val lastAnsweredBy: String? = null,
    /**
     * Set when an irreversible action is waiting on the user. The UI shows this as
     * a question with the exact thing that will happen.
     */
    val pendingConfirmation: String? = null
) {
    val isBusy: Boolean get() = isSending || isListening
}

/**
 * The brain. Application-scoped, not a ViewModel, so a running task survives screen
 * lock, rotation and backgrounding, and so the main app, mini surface and floating
 * bubble are three windows onto one conversation.
 *
 * Context sent to the model is assembled per request rather than accumulated:
 *   system prompt + rolling summary + relevant memories + recent turns
 * Full history lives in the database, so trimming context never destroys anything.
 */
class ChatEngine(
    private val appContext: Context,
    private val prefs: UserPreferencesRepository,
    private val memory: MemoryStore,
    private val conversations: ConversationStore,
    private val keyStore: SecureKeyStore,
    private val toolDispatcher: ToolDispatcher,
    private val voiceInput: VoiceInputController
) {

    companion object {
        @Volatile
        var activeInstance: ChatEngine? = null
            private set

        /** Beyond this, an identical repeated call is treated as a loop. */
        private const val REPEAT_LIMIT = 2
        private const val MAX_APP_SWITCHES = 4

        /** Ceiling on waiting for TTS to finish before re-opening the mic in hands-free mode. */
        private const val MAX_SPEECH_WAIT_MS = 60_000L

        /** How many past tool results are replayed as context, and how much of each. */
        /** Ceiling on pictures sent in one turn; each is a large slice of the window. */
        private const val MAX_ATTACHED_IMAGES = 3
        /** Said when a model narrates twice instead of acting. */
        private const val STALLED_MESSAGE =
            "That model kept describing what it was going to do instead of doing it, so nothing has " +
                "happened. Its working is below. A stronger model in Settings handles this better."

        /** Said when a model runs out of room twice over. */
        private const val TRUNCATED_MESSAGE =
            "That model ran out of room before it got to the action, twice — so nothing has happened. " +
                "Its working is below. Try a shorter request, or a stronger model in Settings."

        /** Ceiling on stashed working, so a stalling model can't fill the transcript. */
        private const val MAX_WORKING_CHARS = 4_000

        /**
         * Words that make a request depend on the one before it. Padded with spaces
         * at the call site so "it" doesn't match inside "with" or "digital".
         */
        private val BACKWARD_REFERENCES = listOf(
            " it ", " it.", " it?", " that ", " that.", " that?", " them ", " those ",
            " again", " same ", " her ", " him ", " they ", " this one", " the one",
            " instead", " too ", " as well", " also send", " and send", " resend",
            " like before", " like last", " previous", " earlier", " just did",
            " you sent", " you called", " you opened", " undo", " cancel that"
        )

        private const val RECENT_ACTIONS = 3
        private const val ACTION_RECAP_CHARS = 140
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Reads picked and captured files into something the model can actually use. */
    private val attachments = AttachmentReader(appContext)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var activeJob: Job? = null
    /** Tracked separately from [activeJob] so speech can be stopped without stopping work. */
    private var speakJob: Job? = null
    private var ttsEngine: TtsEngine? = null
    private var speaker: StreamingSpeaker? = null
    private var conversationId: String? = null
    private val working = WorkingMemory()
    private val localActions = LocalActions(appContext)

    /** The irreversible action waiting on the user, if any. */
    private var pendingConfirmation: PendingConfirmation? = null

    /** Call ids the user has approved, so the retry actually runs instead of re-asking. */
    private val approvedThisTurn = mutableSetOf<String>()

    /** Set when the current turn came from speech, so the reply is kept speakable. */
    private var deliveryMode = DeliveryMode.TEXT

    init {
        activeInstance = this
        scope.launch {
            _state.update {
                it.copy(profile = prefs.userProfile.first(), isMuted = prefs.isMuted.first())
            }
            ttsEngine = TtsEngineProvider.create(appContext, prefs.kokoroEndpoint.first()).also { engine ->
                speaker = StreamingSpeaker(engine, scope) { speaking ->
                    _state.update { it.copy(isSpeaking = speaking) }
                }
            }
            // Open the TLS connection to the provider now, so the first message doesn't
            // pay for a handshake on top of everything else.
            Http.prewarm(prefs.selectedProvider.first().apiBaseUrl)
            // Binding the recognition service takes a few hundred milliseconds; doing it
            // now moves that off the gap between tapping the mic and it going live.
            voiceInput.prewarm()

            // Keeps the UI's picture of the model current without any screen having to
            // ask, including after a fallback rewrites the selection.
            scope.launch {
                kotlinx.coroutines.flow.combine(
                    prefs.selectedModelId,
                    prefs.selectedProvider,
                    prefs.selectedModelInfo
                ) { id, provider, info -> ModelCapabilityRegistry.forModel(id, provider, info) }
                    .collect { caps -> _state.update { it.copy(capabilities = caps) } }
            }

            val convo = conversations.activeConversation()
            conversationId = convo.id
            toolDispatcher.currentConversationId = convo.id

            // Restore the visible transcript so a restart resumes where it left off.
            val restored = conversations.recentMessages(convo.id, ConversationStore.RECENT_WINDOW)
                .filter { !it.hidden && (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                .map {
                    // Same id as the stored row. Without this the transcript and the
                    // database each had their own UUID for the same message, and a
                    // delete could only ever remove one of them — the message would
                    // vanish from the screen and come back on the next restart, still
                    // in the model's context the whole time.
                    ChatMessage(
                        id = it.id,
                        sender = if (it.role == "user") Sender.USER else Sender.LAIN,
                        text = it.content,
                        timestamp = it.createdAt
                    )
                }
            if (restored.isNotEmpty()) {
                _state.update { it.copy(messages = restored, hasAwakened = true) }
            }
        }
    }

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    // ------------------------------------------------------ message actions
    //
    // A transcript the user cannot touch is one they have to work around: a typo
    // means retyping the whole request, a reply worth keeping has to be
    // screenshotted, and something said by mistake stays in the model's context
    // for the rest of the conversation. These four cover it.

    /**
     * Copies a message into the composer so it can be edited and sent again.
     *
     * Deliberately not sent straight away — the reason to reach for an old message
     * is usually that it needs a word changed.
     */
    fun copyToInput(text: String) = _state.update { it.copy(input = text) }

    // ---------------------------------------------------------- attachments

    /**
     * Reads a picked or captured file and holds it until the next message is sent.
     *
     * Reading happens now rather than at send time so the user finds out
     * immediately if Lain can't make anything of the file — a chip that says
     * "can't read a .docx" beats sending it and getting nothing back.
     */
    fun attach(uri: android.net.Uri) {
        scope.launch {
            val attachment = runCatching { attachments.read(uri) }.getOrNull() ?: return@launch
            _state.update { it.copy(attachments = it.attachments + attachment) }
        }
    }

    fun removeAttachment(uri: android.net.Uri) {
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.uri == uri }) }
    }

    /**
     * Sends a message again as a fresh turn.
     *
     * On one of Lain's replies this re-runs the request that produced it, which is
     * what "try that again" means. Nothing is rewritten in place: the new turn is
     * appended and visible, because silently replacing a reply hides the fact that
     * two different answers were given to the same question.
     */
    fun resend(messageId: String) {
        if (_state.value.isSending) return
        val request = MessageActions.resendTarget(_state.value.messages, messageId) ?: return
        send(request)
    }

    /**
     * Removes a message from the transcript and from storage.
     *
     * Both, or it isn't a delete: dropping it from the list alone would leave the
     * row in the database, still feeding the model and reappearing on the next
     * restart. Safe mid-conversation — model history is rebuilt from user and
     * assistant rows, and tool rows are replayed as a plain recap rather than as
     * tool messages, so removing a turn cannot orphan a tool call.
     */
    fun deleteMessage(messageId: String) {
        _state.update { it.copy(messages = it.messages.filterNot { m -> m.id == messageId }) }
        scope.launch {
            runCatching { conversations.deleteMessage(messageId) }
        }
    }

    fun onAwaken() {
        if (_state.value.hasAwakened) return
        val greeting = ChatMessage(sender = Sender.LAIN, text = "What's up niceo?")
        _state.update { it.copy(hasAwakened = true, messages = it.messages + greeting) }
        speak(greeting.text)
    }

    fun setConversationMode(enabled: Boolean) {
        _state.update { it.copy(conversationMode = enabled) }
        if (enabled && !_state.value.isBusy) startVoiceInput()
    }

    fun startVoiceInput() {
        if (_state.value.isBusy) return
        // Barge-in: if Lain is mid-sentence and the user hits the mic, she stops talking
        // rather than recording herself.
        silence()
        _state.update { it.copy(isListening = true, error = null, statusLine = "Listening…") }
        activeJob = scope.launch {
            // Partial hypotheses go straight into the input box, so the user can see
            // they're being heard instead of watching a static "Listening…".
            voiceInput.listenOnce(onPartial = { partial ->
                _state.update { if (it.isListening) it.copy(input = partial) else it }
            }).fold(
                onSuccess = { heard ->
                    _state.update { it.copy(isListening = false, input = heard, statusLine = null) }
                    send(heard, fromVoice = true)
                },
                onFailure = { err ->
                    _state.update { it.copy(isListening = false, statusLine = null, error = "Didn't catch that: ${err.message}") }
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
        clearPendingConfirmation()
        silence()
        AgentForegroundService.stop(appContext)
        _state.update { it.copy(isSending = false, isListening = false, statusLine = null, conversationMode = false) }
    }

    fun toggleMute() {
        val next = !_state.value.isMuted
        if (next) silence()
        _state.update { it.copy(isMuted = next) }
        scope.launch { prefs.setMuted(next) }
    }

    /**
     * Shuts the voice up and nothing else.
     *
     * STOP cancels the running task, and the mute pill is a persistent preference —
     * neither is what someone wants when Lain is three sentences into reading a long
     * answer out loud and they've finished reading it themselves. This kills the
     * current utterance only: the task keeps running, and the next reply still speaks.
     */
    fun silence() {
        speakJob?.cancel()
        speakJob = null
        speaker?.stop()
        ttsEngine?.stop()
        if (_state.value.isSpeaking) _state.update { it.copy(isSpeaking = false) }
    }

    fun send(text: String? = null, fromVoice: Boolean = false) {
        val message = (text ?: _state.value.input).trim()
        val attached = _state.value.attachments
        // An attachment on its own is a complete request — "look at this" is implied
        // by the act of sending it, and demanding a caption for a photo is friction
        // for nothing.
        if ((message.isBlank() && attached.isEmpty()) || _state.value.isSending) return

        deliveryMode = if (fromVoice) DeliveryMode.VOICE else DeliveryMode.TEXT

        // A reply to a pending confirmation is an answer, not a new request — it must
        // not be re-routed, re-planned, or treated as a fresh objective.
        pendingConfirmation?.let { pending ->
            activeJob = scope.launch { resolveConfirmation(pending, message) }
            return
        }

        val outbound = if (attached.isEmpty()) message else buildString {
            append(message.ifBlank { "Look at this." })
            // Non-image files are described in the text, because that is the only
            // channel every model has. Images ride along properly where the model can
            // see, and are described here where it can't.
            attached.forEach { append("\n\n").append(it.describeForModel()) }
        }

        working.begin(outbound)
        val trace = Trace.start(message)

        // Minted here and used for both the transcript entry and the stored row.
        val messageId = java.util.UUID.randomUUID().toString()

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = messageId,
                    sender = Sender.USER,
                    // The transcript shows what the user typed plus what they sent,
                    // not the expanded payload the model receives.
                    text = message.ifBlank { attached.joinToString(", ") { a -> a.displayName } }
                        .let { t -> if (message.isNotBlank() && attached.isNotEmpty()) "$t\n(${attached.size} attached)" else t }
                ),
                input = "",
                attachments = emptyList(),
                isSending = true,
                error = null,
                activeModelNotice = null,
                streamingText = null,
                statusLine = "Thinking…"
            )
        }

        // Routing happens before the foreground service starts and before anything
        // touches the network, because the whole point is that a local command never
        // reaches either. This is plain string work — microseconds, no I/O.
        val route = trace.time("route") { FastRouter.route(message) }
        trace.route = route::class.simpleName ?: "unknown"

        if (route is Route.Local) {
            activeJob = scope.launch { runLocal(route.intent, message, trace) }
            return
        }

        AgentForegroundService.start(appContext, "Thinking…")

        activeJob = scope.launch {
            try {
                val cid = ensureConversation()
                conversations.append(
                    MessageEntity(id = messageId, conversationId = cid, role = "user", content = outbound)
                )
                runAgentLoop(cid, outbound, route, trace, attached)
            } catch (_: CancellationException) {
                _state.update {
                    it.copy(
                        isSending = false, statusLine = null,
                        messages = it.messages + ChatMessage(sender = Sender.LAIN, text = "Stopped.")
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSending = false, statusLine = null, error = t.message ?: "Something broke") }
            } finally {
                if (_state.value.isSending) _state.update { it.copy(isSending = false, statusLine = null) }
                AgentForegroundService.stop(appContext)
                trace.finish()
            }
        }
    }

    /**
     * Handles the user's answer to a pending confirmation.
     *
     * Approval re-runs the exact call that was held — same arguments, same id — so
     * what happens is precisely what was described, not the model's second attempt
     * at expressing it. A refusal drops the action and says so; it never gets
     * quietly retried later in the turn.
     */
    private suspend fun resolveConfirmation(pending: PendingConfirmation, reply: String) {
        pendingConfirmation = null
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(sender = Sender.USER, text = reply),
                input = "",
                pendingConfirmation = null
            )
        }

        val cid = ensureConversation()
        conversations.append(MessageEntity(conversationId = cid, role = "user", content = reply))

        if (!PendingConfirmation.isApproval(reply)) {
            finishTurn(cid, "Left it. Nothing was sent.", usedModel = "Lain (on-device)", fellBack = false)
            return
        }

        approvedThisTurn += pending.call.id
        _state.update { it.copy(isSending = true, statusLine = "Doing it…") }
        try {
            val images = mutableListOf<String>()
            val outcome = executeCall(cid, pending.call, images)
            // Report what the tool actually returned rather than assuming success —
            // approval means "you may try", not "it worked".
            val spoken = if (outcome.startsWith("SUCCESS")) {
                outcome.removePrefix("SUCCESS: ").trim()
            } else {
                "That didn't go through. " + outcome.substringAfter(": ").trim()
            }
            finishTurn(cid, spoken, usedModel = "Lain (on-device)", fellBack = false)
        } catch (_: CancellationException) {
            _state.update { it.copy(isSending = false, statusLine = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(isSending = false, statusLine = null, error = t.message ?: "That failed") }
        } finally {
            approvedThisTurn.remove(pending.call.id)
            if (_state.value.isSending) _state.update { it.copy(isSending = false, statusLine = null) }
        }
    }

    /** Drops a pending confirmation, e.g. when the user hits STOP. */
    private fun clearPendingConfirmation() {
        pendingConfirmation = null
        approvedThisTurn.clear()
        if (_state.value.pendingConfirmation != null) {
            _state.update { it.copy(pendingConfirmation = null) }
        }
    }

    private suspend fun ensureConversation(): String {
        conversationId?.let { return it }
        val convo = conversations.activeConversation()
        conversationId = convo.id
        toolDispatcher.currentConversationId = convo.id
        return convo.id
    }

    // --------------------------------------------------------- the local path

    /**
     * Answers without a model at all.
     *
     * This is the whole point of the router: "what's my battery" was two network
     * round trips (one to decide to call device_status, one to phrase the result)
     * for a value BatteryManager returns instantly. Here it is a local read and a
     * sentence, so it lands in single-digit milliseconds and works with no signal.
     *
     * If the action can't complete for a real reason — no accessibility service, a
     * permission not granted — it returns null and the turn falls through to the
     * model, which can at least explain the problem.
     */
    private suspend fun runLocal(intent: LocalIntent, message: String, trace: Trace.Turn) {
        try {
            val reply = trace.time("local_action") { localActions.execute(intent) }
            if (reply == null) {
                // Not a failure: just not answerable locally after all.
                trace.mark("local_fallthrough")
                trace.route = "Model(after-local-miss)"
                val cid = ensureConversation()
                conversations.append(MessageEntity(conversationId = cid, role = "user", content = message))
                AgentForegroundService.start(appContext, "Thinking…")
                try {
                    runAgentLoop(cid, message, Route.Model, trace)
                } finally {
                    AgentForegroundService.stop(appContext)
                }
                return
            }

            val cid = ensureConversation()
            conversations.append(MessageEntity(conversationId = cid, role = "user", content = message))
            finishTurn(cid, reply, usedModel = "Lain (on-device)", fellBack = false)
            resumeListeningIfHandsFree()
        } catch (_: CancellationException) {
            _state.update { it.copy(isSending = false, statusLine = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(isSending = false, statusLine = null, error = t.message ?: "Something broke") }
        } finally {
            if (_state.value.isSending) _state.update { it.copy(isSending = false, statusLine = null) }
            trace.finish()
        }
    }

    // ------------------------------------------------------------- the loop

    private suspend fun runAgentLoop(
        cid: String,
        userMessage: String,
        route: Route,
        trace: Trace.Turn,
        attached: List<Attachment> = emptyList()
    ) {
        val provider = prefs.selectedProvider.first()
        val modelId = prefs.selectedModelId.first()
        val apiKey = keyStore.getApiKey(provider)

        if (modelId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            _state.update { it.copy(isSending = false, statusLine = null, error = "No model/API key configured — open Settings.") }
            return
        }

        // Capabilities come from the provider's own catalogue entry where we have one,
        // so budgets are set from real context sizes and real vision support rather
        // than from pattern-matching the model's name.
        val caps = ModelCapabilityRegistry.forModel(modelId, provider, prefs.selectedModelInfo.first())
        val client = LlmClientFactory.create(provider)
        val accessibilityReady = LainAccessibilityService.isRunning

        // Assemble context fresh each turn rather than growing a list forever.
        val relevantMemories = memory.retrieveRelevant(userMessage, caps.memoryBudget)
        val summary = conversations.summaryOf(cid)
        val systemPrompt = PromptBuilder.build(
            profile = _state.value.profile,
            memories = relevantMemories,
            conversationSummary = summary,
            capabilities = caps,
            mode = deliveryMode,
            accessibilityReady = accessibilityReady
        )

        val history = buildModelHistory(cid, caps)

        // Attached pictures go in as a proper image turn where the model can see, and
        // are described in text where it can't. Silently dropping them on a text-only
        // model is how an assistant ends up answering a question about a photo it
        // never received.
        val pictures = attached.mapNotNull { it.imageBase64 }
        if (pictures.isNotEmpty()) {
            if (caps.supportsVision) {
                history.add(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        text = "(the file the user just attached)",
                        images = pictures.takeLast(MAX_ATTACHED_IMAGES)
                    )
                )
            } else {
                history.add(
                    LlmMessage(
                        role = LlmMessage.Role.USER,
                        text = "(The user attached ${pictures.size} image(s), but the selected model can't see " +
                            "images. Say so plainly and suggest switching to a vision model in Settings.)"
                    )
                )
            }
        }

        // Plain conversation doesn't need the toolbox, the planning, or the step
        // budget. Stream one tool-free answer; the model can bail out to the full
        // loop itself if it turns out it needed something.
        if (route is Route.Chat) {
            val quick = tryDirectAnswer(client, modelId, apiKey, systemPrompt, history, trace)
            if (quick != null) {
                finishTurn(cid, quick, usedModel = modelId, fellBack = false, alreadySpoken = spokeWhileStreaming())
                scope.launch { maintainContext(cid, client, provider, modelId, apiKey, userMessage, quick) }
                resumeListeningIfHandsFree()
                return
            }
        }

        val tools = ToolDefinitions.forCapabilities(caps, accessibilityReady)

        var rounds = 0
        var finalText: String? = null
        var lastSignature: String? = null
        var repeatCount = 0
        // Whether any tool actually ran this turn. Reflection *after* doing something
        // is a summary; the same words with nothing done is a stall.
        var toolsRan = false
        var nudgedForDeliberation = false
        var retriedAfterTruncation = false
        // Kept, not discarded: the user asked to be able to open it up and look.
        var monologue: String? = null
        // Explicit type: the null check above smart-casts the val, but `var` inference
        // still picks up the nullable declared type.
        var usedModel: String = modelId
        var fellBack = false

        while (rounds < caps.maxToolRounds && finalText == null) {
            rounds++
            if (rounds > 1) setStatus("Working… (step $rounds)")

            // Choosing the next tool call needs decisiveness, not deliberation. The
            // ceiling is lifted only for the final round, where the model is likely
            // writing the actual answer rather than picking an action.
            val tuning = when {
                // Given room precisely once, and only after being cut off. Raising the
                // ceiling for everyone would buy longer monologues rather than fewer;
                // the instruction sent with it is what does the work.
                retriedAfterTruncation -> RequestTuning.TOOL_STEP.copy(maxTokens = 2000)
                deliveryMode == DeliveryMode.VOICE -> RequestTuning.SPOKEN
                rounds == 1 -> RequestTuning.TOOL_STEP
                else -> RequestTuning.TOOL_STEP.copy(maxTokens = 1000)
            }
            // Streamed, so the final answer starts appearing as the model writes it
            // rather than after it has finished. Intermediate tool-selection rounds
            // stream too; they simply produce no prose to show.
            val outcome = streamWithFallback(
                client, provider, usedModel, apiKey, systemPrompt, history, tools, tuning, trace,
                speakable = tools.isEmpty() || toolsRan
            )
            usedModel = outcome.modelUsed
            if (outcome.fellBack) fellBack = true

            when (val result = outcome.result) {
                is StreamOutcome.Text -> {
                    // A model that narrates its plan instead of acting has not
                    // answered. Showing that paragraph as the reply is how Lain ends up
                    // talking to herself in front of the user, so it never becomes the
                    // reply body: it is stashed as working, and the model is pushed to
                    // act. If it stalls again the turn ends with a plain statement of
                    // what went wrong and the narration folded away behind it.
                    if (route !is Route.Chat &&
                        Deliberation.isThinkingOutLoud(result.text, actedThisTurn = toolsRan)
                    ) {
                        speaker?.stop()
                        monologue = appendWorking(monologue, result.text)
                        if (!nudgedForDeliberation) {
                            nudgedForDeliberation = true
                            history.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = result.text))
                            history.add(LlmMessage(role = LlmMessage.Role.USER, text = Deliberation.NUDGE))
                        } else {
                            // Twice is a pattern, not a slip. Stop rather than loop.
                            finalText = STALLED_MESSAGE
                        }
                    } else {
                        finalText = result.text
                    }
                }

                is StreamOutcome.Truncated -> {
                    // Ran out of room mid-sentence. This is the failure behind "it
                    // replies with its thinking and never does the thing": a model that
                    // spends its whole budget narrating gets cut off before it ever
                    // emits the tool call, and the half-written thought was being shown
                    // as the answer.
                    //
                    // Retried once with real room and an instruction to lead with the
                    // tool call — not simply a bigger number, which would just buy a
                    // longer monologue. The partial goes to working, never to the reply.
                    speaker?.stop()
                    monologue = appendWorking(monologue, result.partial)
                    if (!retriedAfterTruncation) {
                        retriedAfterTruncation = true
                        history.add(
                            LlmMessage(
                                role = LlmMessage.Role.USER,
                                text = "Your last reply was cut off — you used the whole budget before doing " +
                                    "anything. Start with the tool call itself. No preamble, no plan, no " +
                                    "restating the request."
                            )
                        )
                    } else {
                        finalText = TRUNCATED_MESSAGE
                    }
                }

                is StreamOutcome.Failed -> {
                    _state.update { it.copy(isSending = false, statusLine = null, error = result.message) }
                    return
                }

                is StreamOutcome.Tools -> {
                    toolsRan = true
                    history.add(LlmMessage(role = LlmMessage.Role.ASSISTANT, text = "", toolCalls = result.calls))
                    persistAssistantToolCalls(cid, result.calls)

                    val signature = result.calls.joinToString("|") { "${it.name}(${it.argumentsJson})" }
                    repeatCount = if (signature == lastSignature) repeatCount + 1 else 0
                    lastSignature = signature

                    if (repeatCount >= REPEAT_LIMIT) {
                        result.calls.forEach { call ->
                            history.add(
                                LlmMessage(
                                    role = LlmMessage.Role.TOOL,
                                    text = "FAILED [loop]: you have made this identical call ${repeatCount + 1} times and " +
                                        "nothing changed. Stop. Either take a different action or tell the user what is blocking you.",
                                    toolCallId = call.id
                                )
                            )
                        }
                        repeatCount = 0
                        continue
                    }

                    val images = mutableListOf<String>()
                    trace.time("tools[${result.calls.joinToString(",") { it.name }}]") {
                        executeCalls(cid, result.calls, images).forEach { (call, toolText) ->
                            history.add(LlmMessage(role = LlmMessage.Role.TOOL, text = toolText, toolCallId = call.id))
                        }
                    }

                    // Working memory (layer 1) goes back as its own turn, refreshed every
                    // round so it describes the state *now*. This is what stops a weak
                    // model re-deriving "what have I already tried" from raw transcript.
                    working.digest()?.let { digest ->
                        history.add(LlmMessage(role = LlmMessage.Role.USER, text = digest))
                    }

                    // A nudge on top, once a task starts drifting. Separate because it's
                    // advice rather than state.
                    working.progressNote()?.takeIf { rounds >= 4 }?.let { note ->
                        history.add(LlmMessage(role = LlmMessage.Role.USER, text = note))
                    }

                    if (images.isNotEmpty() && caps.supportsVision) {
                        history.add(
                            LlmMessage(
                                role = LlmMessage.Role.USER,
                                text = "(current screen, just captured)",
                                images = images.takeLast(1)
                            )
                        )
                    }
                }
            }
        }

        val replyText = finalText ?: gaveUpMessage(caps)

        // finalText came from a stream that already spoke it in voice mode; the
        // give-up message did not, so it still needs reading aloud.
        finishTurn(
            cid, replyText, usedModel, fellBack,
            alreadySpoken = finalText != null && spokeWhileStreaming(),
            monologue = monologue
        )

        // Housekeeping runs after the reply so the user never waits on it.
        scope.launch { maintainContext(cid, client, provider, usedModel, apiKey, userMessage, replyText) }

        resumeListeningIfHandsFree()
    }

    /**
     * One short, tool-free request for messages that are plainly conversation.
     *
     * @return the reply, or null if the model asked for the full agent loop instead.
     */
    private suspend fun tryDirectAnswer(
        client: LlmClient,
        modelId: String,
        apiKey: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        trace: Trace.Turn
    ): String? {
        val prompt = systemPrompt + "\n\n" + PromptBuilder.directAnswerRule()
        val tuning = if (deliveryMode == DeliveryMode.VOICE) RequestTuning.SPOKEN else RequestTuning.ANSWER
        // The escape hatch: a model that decides it needs the phone or the live web
        // says so, and the caller falls through to the full loop — so a
        // misclassification costs one cheap request, never a wrong answer.
        val outcome = streamAnswer(client, modelId, apiKey, prompt, history, emptyList(), tuning, trace)
        return (outcome as? StreamOutcome.Text)?.text?.takeUnless { it.contains(IntentClassifier.NEEDS_TOOLS) }
    }

    /** How a streaming request ended. */
    private sealed class StreamOutcome {
        data class Text(val text: String) : StreamOutcome()
        data class Tools(val calls: List<ToolCall>) : StreamOutcome()
        data class Failed(val message: String) : StreamOutcome()

        /** Cut off at the token ceiling. Not an answer; the caller retries with room. */
        data class Truncated(val partial: String) : StreamOutcome()
    }

    /**
     * Runs a streaming request, showing and speaking the reply as it arrives.
     *
     * This is where time-to-first-response actually gets fixed. The old path called
     * `send()`, which blocks on `body.string()` until generation is complete — so
     * nothing appeared until everything was ready, and on a free model that is a
     * multi-second stare at an empty screen. Here the first fragment is rendered the
     * moment it lands, and in voice mode the first sentence starts playing while the
     * rest is still being written.
     */
    private suspend fun streamAnswer(
        client: LlmClient,
        modelId: String,
        apiKey: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<com.lain.assistant.network.ToolDefinition>,
        tuning: RequestTuning,
        trace: Trace.Turn,
        /**
         * Whether this round's text is safe to read aloud as it streams.
         *
         * False on a round that still has tools to pick from, because that is where a
         * model emits its planning as prose — and streaming speech would read that
         * planning out before anyone could see it wasn't the answer. Hands-free users
         * would have got the monologue and nothing else. Later rounds, once tools have
         * run, are the model writing the reply, so streaming speech resumes there and
         * the latency win is kept where it matters.
         */
        speakable: Boolean = true
    ): StreamOutcome {
        trace.countLlmRequest()
        val speakAloud = speakable && !_state.value.isMuted && deliveryMode == DeliveryMode.VOICE
        var started = false
        var outcome: StreamOutcome = StreamOutcome.Failed("The model returned nothing.")

        try {
            client.sendStreaming(apiKey, modelId, systemPrompt, history, tools, tuning).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        if (!started) {
                            started = true
                            trace.mark("first_token")
                            // Clear the placeholder the instant real text exists.
                            _state.update { it.copy(statusLine = null, streamingText = "") }
                            if (speakAloud) speaker?.begin()
                        }
                        _state.update { it.copy(streamingText = (it.streamingText ?: "") + event.text) }
                        // Speaking starts at the first sentence boundary, not at the end
                        // of generation — see StreamingSpeaker.
                        if (speakAloud) speaker?.offer(event.text)
                    }

                    is StreamEvent.Done -> {
                        trace.mark("generation_complete")
                        if (speakAloud) {
                            speaker?.finish()
                            trace.mark("tts_flushed")
                        }
                        val text = event.text.trim()
                        outcome = if (text.isBlank()) {
                            StreamOutcome.Failed("The model returned an empty reply.")
                        } else {
                            StreamOutcome.Text(text)
                        }
                    }

                    is StreamEvent.Tools -> {
                        trace.mark("tools_requested")
                        // Anything already queued was preamble to a tool call, not an
                        // answer. Drop it rather than let it finish playing.
                        if (speakAloud) speaker?.stop()
                        outcome = StreamOutcome.Tools(event.calls)
                    }

                    is StreamEvent.Truncated -> {
                        trace.mark("truncated")
                        // Whatever was queued to speak was half a thought. Drop it.
                        if (speakAloud) speaker?.stop()
                        outcome = StreamOutcome.Truncated(event.partial)
                    }

                    is StreamEvent.Failed -> {
                        trace.mark("failed")
                        outcome = StreamOutcome.Failed(event.message)
                    }
                }
            }
        } catch (c: CancellationException) {
            speaker?.stop()
            throw c
        } catch (t: Throwable) {
            outcome = StreamOutcome.Failed(t.message ?: "Streaming failed")
        } finally {
            // The partial is now either a finished message or abandoned; either way it
            // stops being "in progress" so the transcript can own the final text.
            _state.update { it.copy(streamingText = null) }
        }
        return outcome
    }

    /**
     * Accumulates the model's working across the rounds of one turn.
     *
     * Bounded, because a model that stalls repeatedly can produce a great deal of it
     * and none of it is the answer.
     */
    private fun appendWorking(existing: String?, addition: String): String {
        val next = if (existing.isNullOrBlank()) addition else "$existing\n\n---\n\n$addition"
        return next.takeLast(MAX_WORKING_CHARS)
    }

    /** The single place a completed turn lands in the transcript, the database and the speaker. */
    /**
     * @param alreadySpoken true when [StreamingSpeaker] has already read this reply
     *   aloud as it streamed. Speaking it again here would play the whole answer a
     *   second time.
     */
    private suspend fun finishTurn(
        cid: String,
        replyText: String,
        usedModel: String,
        fellBack: Boolean,
        alreadySpoken: Boolean = false,
        /** The model's working, shown folded away. Stored with the turn, never spoken. */
        monologue: String? = null
    ) {
        val messageId = java.util.UUID.randomUUID().toString()
        conversations.append(
            MessageEntity(id = messageId, conversationId = cid, role = "assistant", content = replyText)
        )
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(
                    id = messageId,
                    sender = Sender.LAIN,
                    text = replyText,
                    monologue = monologue?.takeIf { m -> m.isNotBlank() }
                ),
                isSending = false,
                statusLine = null,
                streamingText = null,
                lastAnsweredBy = usedModel,
                activeModelNotice = if (fellBack) {
                    "Answered by $usedModel — your selected model was unavailable."
                } else {
                    null
                }
            )
        }
        if (!alreadySpoken) speak(replyText)
    }

    /**
     * Hands the mic back in hands-free mode — but not while Lain is still talking,
     * or she transcribes her own voice and answers herself.
     */
    private suspend fun resumeListeningIfHandsFree() {
        if (!_state.value.conversationMode) return

        // Wait out the reply rather than a fixed guess. Streamed speech finishes when
        // the last queued utterance does, which is not knowable up front.
        val waitUntil = System.currentTimeMillis() + MAX_SPEECH_WAIT_MS
        while (_state.value.isSpeaking && System.currentTimeMillis() < waitUntil) {
            delay(150)
        }
        delay(400)
        if (!_state.value.isBusy && !_state.value.isSpeaking && _state.value.conversationMode) {
            startVoiceInput()
        }
    }

    /**
     * Tools that change what is on screen, and therefore cannot overlap.
     *
     * Two taps dispatched at once land in an undefined order on an undefined screen;
     * a tap racing a read produces a listing of a UI mid-transition. Anything that
     * mutates or observes the screen is kept strictly sequential. Everything else —
     * a web search, a contact lookup, a battery read, a file write — is independent
     * and safe to overlap.
     */
    private val serialTools = setOf(
        "open_app", "close_app", "tap_text", "tap_screen", "type_text", "press_key",
        "swipe_screen", "wait", "read_screen", "look_at_screen", "current_app",
        "message_contact", "send_whatsapp_message", "open_url", "open_settings_page", "open_contacts"
    )

    /**
     * Runs a batch of tool calls, overlapping the ones that can safely overlap.
     *
     * Models increasingly emit several calls in one turn — "look up this contact and
     * check the battery" — and running them one after another made the round cost the
     * sum of their latencies when it could cost the maximum. Web research in
     * particular is seconds of waiting on a socket that the CPU spends idle.
     *
     * Results come back in the order the model asked for them regardless of the order
     * they completed, because the tool messages have to line up with the tool_calls
     * turn they answer.
     */
    private suspend fun executeCalls(
        cid: String,
        calls: List<ToolCall>,
        images: MutableList<String>
    ): List<Pair<ToolCall, String>> {
        if (calls.size == 1) {
            val only = calls.first()
            return listOf(only to executeCall(cid, only, images))
        }

        val (serial, parallel) = calls.partition { it.name in serialTools }

        // Independent work starts first so it overlaps the screen work rather than
        // waiting behind it.
        val deferred = coroutineScope {
            parallel.map { call ->
                async { call to executeCall(cid, call, images) }
            }
        }
        val serialResults = serial.map { call -> call to executeCall(cid, call, images) }
        val parallelResults = deferred.map { it.await() }

        val byId = (serialResults + parallelResults).associateBy { it.first.id }
        return calls.mapNotNull { byId[it.id] }
    }

    private suspend fun executeCall(cid: String, call: ToolCall, images: MutableList<String>): String {
        // Refuse app churn before it happens rather than explaining it afterwards.
        if ((call.name == "open_app" || call.name == "close_app") && working.appSwitches >= MAX_APP_SWITCHES) {
            return "FAILED [loop]: too many app switches for one request. Work with the screen you're on, or stop and explain."
        }
        if (working.isExhausted(call.name, call.argumentsJson)) {
            return "FAILED [exhausted]: this exact call has already failed twice. Do not try it again — change approach or stop."
        }

        // Irreversible, outward-facing actions stop here and wait for the user. The
        // tool has NOT run at this point, and the model is told so plainly, because
        // the failure this exists to prevent is Lain reporting a call it never placed.
        if (ToolRegistry.requiresConfirmation(call.name, call.argumentsJson) && !approvedThisTurn.contains(call.id)) {
            val args = runCatching {
                json.parseToJsonElement(call.argumentsJson).jsonObject
                    .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            }.getOrDefault(emptyMap())

            val summary = PendingConfirmation.describe(call, args)
            pendingConfirmation = PendingConfirmation(call, summary, cid)
            _state.update { it.copy(pendingConfirmation = summary) }
            return "AWAITING CONFIRMATION: this action needs the user's approval and has NOT been performed. " +
                "Lain has asked them: \"$summary\". Stop here and say nothing more — do not describe it as done, " +
                "and do not try another way round it."
        }

        setStatus(statusFor(call))
        val result = toolDispatcher.execute(call)

        working.record(
            tool = call.name,
            args = call.argumentsJson,
            succeeded = result.success,
            failure = result.failure,
            note = result.result
        )

        result.image?.let { images += it }

        conversations.append(
            MessageEntity(
                conversationId = cid,
                role = "tool",
                content = "${call.name}: ${result.result.take(400)}",
                toolCallId = call.id,
                hidden = true
            )
        )
        return result.toModelText()
    }

    // ------------------------------------------------------ model + fallback

    /** Why a request failed, since the right response differs sharply between these. */
    private enum class ErrorClass {
        /** The slug no longer resolves on this provider — retrying it is pointless forever. */
        MODEL_GONE,

        /** Busy or throttled. The same model will work again later. */
        TRANSIENT,

        /** Bad or missing key. */
        AUTH,

        /** Out of credit. */
        CREDIT,

        OTHER
    }

    private fun classify(message: String): ErrorClass {
        val m = message.lowercase()
        return when {
            m.contains("404") || m.contains("no endpoints") || m.contains("model_not_found") ||
                m.contains("not a valid model") || m.contains("is not available") -> ErrorClass.MODEL_GONE
            m.contains("429") || m.contains("rate") || m.contains("503") || m.contains("502") ||
                m.contains("overloaded") || m.contains("timeout") -> ErrorClass.TRANSIENT
            m.contains("401") || m.contains("403") || m.contains("invalid api key") -> ErrorClass.AUTH
            m.contains("402") || m.contains("insufficient") || m.contains("credit") -> ErrorClass.CREDIT
            else -> ErrorClass.OTHER
        }
    }

    private data class StreamOutcomeWithModel(
        val result: StreamOutcome,
        val modelUsed: String,
        val fellBack: Boolean
    )

    /**
     * The streaming path's equivalent of [requestWithFallback].
     *
     * Fallback only kicks in when the stream failed outright — a stream that produced
     * text has already shown it to the user, so retrying it on another model would
     * mean rewriting an answer they are part-way through reading.
     */
    private suspend fun streamWithFallback(
        client: LlmClient,
        provider: Provider,
        modelId: String,
        apiKey: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<com.lain.assistant.network.ToolDefinition>,
        tuning: RequestTuning,
        trace: Trace.Turn,
        speakable: Boolean = true
    ): StreamOutcomeWithModel {
        val first = streamAnswer(client, modelId, apiKey, systemPrompt, history, tools, tuning, trace, speakable)
        if (first !is StreamOutcome.Failed) return StreamOutcomeWithModel(first, modelId, false)

        return when (classify(first.message)) {
            ErrorClass.AUTH -> StreamOutcomeWithModel(
                StreamOutcome.Failed("Your ${provider.displayName} API key was rejected. Check it in Settings."),
                modelId, false
            )

            ErrorClass.CREDIT -> StreamOutcomeWithModel(
                StreamOutcome.Failed("No credit left on ${provider.displayName}. Top up, or switch to a free model in Settings."),
                modelId, false
            )

            ErrorClass.MODEL_GONE -> {
                prefs.markModelBroken(modelId)
                val alternative = pickAlternative(provider, modelId)
                    ?: return StreamOutcomeWithModel(
                        StreamOutcome.Failed(
                            "\"$modelId\" no longer exists on ${provider.displayName} — free models get retired " +
                                "without notice. Open Settings and pick another one."
                        ),
                        modelId, false
                    )
                setStatus("That model's gone — using ${alternative.label}…")
                val second = streamAnswer(client, alternative.id, apiKey, systemPrompt, history, tools, tuning, trace, speakable)
                if (second is StreamOutcome.Failed) {
                    StreamOutcomeWithModel(
                        StreamOutcome.Failed(
                            "\"$modelId\" has been retired and ${alternative.label} didn't answer either. " +
                                "Open Settings and pick a model."
                        ),
                        modelId, false
                    )
                } else {
                    prefs.saveModelSelection(provider, alternative.id)
                    StreamOutcomeWithModel(second, alternative.id, true)
                }
            }

            ErrorClass.TRANSIENT -> {
                // Rate limits only trigger a switch when the user opted in — that's a
                // cost/quality substitution, unlike a retired model, which is the only
                // way to answer at all.
                val alternative = (if (prefs.isModelFallbackEnabled.first()) pickAlternative(provider, modelId) else null)
                    ?: return StreamOutcomeWithModel(first, modelId, false)
                setStatus("Switching to ${alternative.label}…")
                val second = streamAnswer(client, alternative.id, apiKey, systemPrompt, history, tools, tuning, trace, speakable)
                StreamOutcomeWithModel(second, alternative.id, second !is StreamOutcome.Failed)
            }

            ErrorClass.OTHER -> StreamOutcomeWithModel(first, modelId, false)
        }
    }

    /**
     * What Lain says when she runs out of rounds without finishing.
     *
     * The important property is honesty about *why*. A weak model failing a
     * ten-step automation is a capability limit, not a mystery, and saying so —
     * along with what a stronger model would do differently — respects the user
     * more than a vague apology and is far better than the alternative failure
     * mode, which is claiming the task succeeded.
     *
     * It never nags: the suggestion appears when the model genuinely hit its
     * ceiling on a multi-step job, not on every hiccup.
     */
    private fun gaveUpMessage(caps: ModelCapabilities): String = buildString {
        append("I've stopped rather than keep going in circles. Here's where I got to: ")
        append(working.progressNote() ?: "no progress to report.")
        if (!caps.handlesMultiStepAutomation) {
            append(" Being straight with you: ${caps.label} is a small model, and long ")
            append("multi-step phone tasks are where it struggles — it loops instead of ")
            append("moving on. It's fine for chat and single actions. If you need this ")
            append("kind of task to work reliably, a ★ model in Settings will do it.")
        } else {
            append(" Tell me what you can see and I'll pick it up.")
        }
    }

    /** True when the streaming speaker handled this turn's audio. */
    private fun spokeWhileStreaming(): Boolean =
        deliveryMode == DeliveryMode.VOICE && !_state.value.isMuted && speaker?.hasStarted == true

    /** Best available stand-in, skipping the current pick and anything already known dead. */
    private suspend fun pickAlternative(
        provider: Provider,
        exclude: String
    ): com.lain.assistant.data.ModelInfo? {
        val broken = prefs.brokenModels.first()
        val candidates = com.lain.assistant.data.ModelCatalog.forProvider(provider)
            .filter { it.id != exclude && it.id !in broken }
        return candidates.firstOrNull { it.strongAtTools }
            ?: candidates.filter { it.isFree }.maxByOrNull { it.contextTokens }
            ?: candidates.firstOrNull()
    }

    // ------------------------------------------------------ context assembly

    /**
     * Rebuilds the model's view of the thread: the recent window only. Everything
     * older is represented by the summary that was folded into the system prompt.
     */
    private suspend fun buildModelHistory(cid: String, caps: ModelCapabilities): MutableList<LlmMessage> {
        // Read wider than the window, because the stored tail is mostly tool rows and
        // the window is meant to hold *conversation*. Budgeting them together is how a
        // single ten-step task used to evict everything the user had actually said —
        // on a small model with a ten-turn window, the whole conversation.
        val raw = conversations.recentMessages(cid, (caps.recentWindow * 3).coerceAtMost(72))

        val turns = raw.filter { it.role == "user" || it.role == "assistant" }
            .filter { it.content.isNotBlank() }
            .takeLast(caps.recentWindow)

        val out = mutableListOf<LlmMessage>()
        for (m in turns) {
            when (m.role) {
                "user" -> out += LlmMessage(role = LlmMessage.Role.USER, text = m.content)
                "assistant" -> out += LlmMessage(role = LlmMessage.Role.ASSISTANT, text = m.content)
            }
        }

        // A short tail of what was actually done recently, replayed as plain context —
        // re-sending these as tool messages would orphan them from the tool_calls turn
        // they belong to and the provider would reject the request. Kept brief: their
        // value is "you already opened WhatsApp", not the screen dump from four steps ago.
        //
        // Inserted *before* the user's latest turn, and this ordering is the whole
        // point. Appending it at the end left the recap as the final thing in the
        // context window, so after "text Moyo" then "call Ade" the last line the model
        // read was "(already done: sent to Moyo)". Models weight the tail heavily, and
        // it would answer about the message it had already sent instead of placing the
        // call — which is exactly the request mix-up this caused. The request must be
        // the last thing in the window; everything else is background.
        // Only when the request actually reaches backwards.
        //
        // Including it unconditionally is what made older requests bleed into new
        // ones: "call Ade" arrived with a paragraph about the message just sent to
        // Moyo attached, and a weak model treated the nearest concrete detail as the
        // subject. A fresh, self-contained instruction needs none of that history —
        // the turns themselves are already in the window. It is added only when the
        // wording depends on something previous ("send it again", "the same one"),
        // which is exactly when leaving it out would break the request instead.
        val lastUserTurn = turns.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (refersBackwards(lastUserTurn)) {
            val actions = raw.filter { it.role == "tool" && it.content.isNotBlank() }.takeLast(RECENT_ACTIONS)
            if (actions.isNotEmpty()) {
                val recap = LlmMessage(
                    role = LlmMessage.Role.USER,
                    // Marked as finished history rather than outstanding work, so a
                    // completed task cannot read as a pending one.
                    text = "(earlier in this conversation, already finished: " +
                        actions.joinToString("; ") { it.content.take(ACTION_RECAP_CHARS) } + ")"
                )
                val lastUser = out.indexOfLast { it.role == LlmMessage.Role.USER }
                if (lastUser >= 0) out.add(lastUser, recap) else out += recap
            }
        }

        // A provider will reject a history that opens on an assistant turn.
        while (out.isNotEmpty() && out.first().role != LlmMessage.Role.USER) out.removeAt(0)
        return out
    }

    /**
     * Whether a request depends on what came before it.
     *
     * A bare pronoun or a word like "again" means the sentence cannot be understood
     * on its own; anything else is self-contained, and handing it the previous
     * task's details only gives a weak model something wrong to latch onto.
     *
     * Errs towards including: a false positive costs a few tokens of context, while
     * a false negative breaks "send it again" outright.
     */
    private fun refersBackwards(message: String): Boolean {
        val t = " ${message.lowercase().trim()} "
        if (t.isBlank()) return false
        return BACKWARD_REFERENCES.any { t.contains(it) }
    }

    private suspend fun persistAssistantToolCalls(cid: String, calls: List<ToolCall>) {
        conversations.append(
            MessageEntity(
                conversationId = cid,
                role = "assistant",
                content = "",
                toolCallsJson = calls.joinToString(",") { it.name },
                hidden = true
            )
        )
    }

    // -------------------------------------------------------- housekeeping

    /**
     * Post-turn maintenance: compress aged-out turns into the rolling summary and
     * extract anything durable into long-term memory.
     *
     * These are extra network calls, so they are throttled hard. Left unchecked they
     * would roughly triple the requests per message — the single most expensive thing
     * in this design for both battery and free-tier rate limits. Nothing here runs
     * unless there is genuinely something new to record.
     */
    private suspend fun maintainContext(
        cid: String,
        client: LlmClient,
        provider: Provider,
        modelId: String,
        apiKey: String,
        userMessage: String,
        assistantReply: String
    ) {
        // Don't spend power on housekeeping when the phone is nearly flat.
        if (batteryTooLowForMaintenance()) return
        runCatching { extractMemories(client, modelId, apiKey, cid, userMessage, assistantReply) }
        runCatching { summarizeIfNeeded(client, modelId, apiKey, cid) }
    }

    private fun batteryTooLowForMaintenance(): Boolean = runCatching {
        val bm = appContext.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging == true
        level in 1..15 && !charging
    }.getOrDefault(false)

    private var turnsSinceExtraction = 0

    private suspend fun extractMemories(
        client: LlmClient,
        modelId: String,
        apiKey: String,
        cid: String,
        userMessage: String,
        assistantReply: String
    ) {
        turnsSinceExtraction++

        // Short throwaway exchanges rarely contain anything durable.
        if (userMessage.length < 24) return

        // Device commands ("open spotify", "call mum") are actions, not facts about
        // the user — extracting from them burns a request to learn nothing.
        val commandLike = Regex(
            "^(open|close|call|text|message|play|search|tap|type|send|set|turn|go|scroll|swipe|read|take|show)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(userMessage.trim())
        if (commandLike && userMessage.length < 80) return

        // Beyond that, sample rather than running every turn.
        if (turnsSinceExtraction < 3) return
        turnsSinceExtraction = 0

        val payload = "User: $userMessage\nLain: ${assistantReply.take(600)}"
        val result = client.send(
            apiKey, modelId,
            PromptBuilder.memoryExtractionInstruction(),
            listOf(LlmMessage(role = LlmMessage.Role.USER, text = payload)),
            emptyList(),
            RequestTuning.HOUSEKEEPING
        )
        val text = (result as? LlmResult.Message)?.text?.trim() ?: return
        if (text.isBlank() || text.equals("NONE", ignoreCase = true)) return

        text.lines().forEach { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size >= 3 && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                memory.remember(
                    subject = parts[1],
                    fact = parts[2],
                    category = MemoryCategory.parse(parts[0]),
                    importance = parts.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 5) ?: 3,
                    sourceConversationId = cid
                )
            }
        }
    }

    private suspend fun summarizeIfNeeded(client: LlmClient, modelId: String, apiKey: String, cid: String) {
        val window = conversations.pendingSummaryWindow(cid) ?: return
        if (window.isEmpty()) return

        val transcript = window.joinToString("\n") { m ->
            val who = when (m.role) {
                "user" -> "User"; "assistant" -> "Lain"; else -> "Action"
            }
            "$who: ${m.content.take(400)}"
        }
        val existing = conversations.summaryOf(cid)

        val result = client.send(
            apiKey, modelId,
            PromptBuilder.summaryInstruction(existing),
            listOf(LlmMessage(role = LlmMessage.Role.USER, text = transcript)),
            emptyList(),
            RequestTuning.HOUSEKEEPING
        )
        val summary = (result as? LlmResult.Message)?.text?.trim().orEmpty()
        if (summary.isNotBlank()) {
            val convo = conversations.conversation(cid) ?: return
            conversations.saveSummary(cid, summary, convo.summarizedUpTo + window.size)
        }
    }

    // -------------------------------------------------------------- helpers

    private fun setStatus(status: String) {
        _state.update { it.copy(statusLine = status) }
        AgentForegroundService.updateStatus(appContext, status)
    }

    private fun statusFor(call: ToolCall): String = when (call.name) {
        "open_app" -> "Opening app…"
        "read_screen", "current_app" -> "Reading the screen…"
        "look_at_screen" -> "Looking at the screen…"
        "tap_text", "tap_screen" -> "Tapping…"
        "type_text" -> "Typing…"
        "make_call" -> "Placing the call…"
        "send_sms" -> "Sending the text…"
        "send_whatsapp_message" -> "Opening WhatsApp…"
        "lookup_contact" -> "Checking contacts…"
        "web_search", "fetch_page" -> "Searching the web…"
        "device_status" -> "Checking the device…"
        "remember", "forget", "recall" -> "Updating memory…"
        "wait" -> "Waiting for the screen…"
        else -> "Working…"
    }

    private fun speak(text: String) {
        if (_state.value.isMuted) return
        val engine = ttsEngine ?: return
        speakJob?.cancel()
        _state.update { it.copy(isSpeaking = true) }
        speakJob = scope.launch {
            try {
                engine.speak(text)
            } finally {
                // Covers the natural end, a cancellation from silence(), and a TTS error
                // alike — the button must never be left showing over silence.
                _state.update { it.copy(isSpeaking = false) }
            }
        }
    }
}
