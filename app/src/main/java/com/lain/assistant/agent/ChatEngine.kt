package com.lain.assistant.agent

import android.content.Context
import com.lain.assistant.automation.LainAccessibilityService
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
import com.lain.assistant.network.LlmClient
import com.lain.assistant.network.LlmClientFactory
import com.lain.assistant.network.LlmMessage
import com.lain.assistant.network.LlmResult
import com.lain.assistant.network.RequestTuning
import com.lain.assistant.network.ToolCall
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolDefinitions
import com.lain.assistant.tools.ToolDispatcher
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.lain.assistant.tts.TtsEngine
import com.lain.assistant.tts.TtsEngineProvider

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val isListening: Boolean = false,
    val isMuted: Boolean = false,
    /** True only while audio is actually coming out, so the UI can offer to stop just the voice. */
    val isSpeaking: Boolean = false,
    val conversationMode: Boolean = false,
    val statusLine: String? = null,
    val hasAwakened: Boolean = false,
    val profile: UserProfile? = null,
    val error: String? = null,
    /** Set when automatic fallback swapped the model, so the user is told rather than silently switched. */
    val activeModelNotice: String? = null
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

        /** How many past tool results are replayed as context, and how much of each. */
        private const val RECENT_ACTIONS = 3
        private const val ACTION_RECAP_CHARS = 140
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private var activeJob: Job? = null
    /** Tracked separately from [activeJob] so speech can be stopped without stopping work. */
    private var speakJob: Job? = null
    private var ttsEngine: TtsEngine? = null
    private var conversationId: String? = null
    private val taskState = TaskState()

    /** Set when the current turn came from speech, so the reply is kept speakable. */
    private var deliveryMode = DeliveryMode.TEXT

    init {
        activeInstance = this
        scope.launch {
            _state.update {
                it.copy(profile = prefs.userProfile.first(), isMuted = prefs.isMuted.first())
            }
            ttsEngine = TtsEngineProvider.create(appContext, prefs.kokoroEndpoint.first())

            val convo = conversations.activeConversation()
            conversationId = convo.id
            toolDispatcher.currentConversationId = convo.id

            // Restore the visible transcript so a restart resumes where it left off.
            val restored = conversations.recentMessages(convo.id, ConversationStore.RECENT_WINDOW)
                .filter { !it.hidden && (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                .map {
                    ChatMessage(
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
            voiceInput.listenOnce().fold(
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
        ttsEngine?.stop()
        if (_state.value.isSpeaking) _state.update { it.copy(isSpeaking = false) }
    }

    fun send(text: String? = null, fromVoice: Boolean = false) {
        val message = (text ?: _state.value.input).trim()
        if (message.isBlank() || _state.value.isSending) return

        deliveryMode = if (fromVoice) DeliveryMode.VOICE else DeliveryMode.TEXT
        taskState.reset()

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(sender = Sender.USER, text = message),
                input = "",
                isSending = true,
                error = null,
                activeModelNotice = null,
                statusLine = "Thinking…"
            )
        }

        AgentForegroundService.start(appContext, "Thinking…")

        activeJob = scope.launch {
            try {
                val cid = ensureConversation()
                conversations.append(MessageEntity(conversationId = cid, role = "user", content = message))
                runAgentLoop(cid, message)
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
            }
        }
    }

    private suspend fun ensureConversation(): String {
        conversationId?.let { return it }
        val convo = conversations.activeConversation()
        conversationId = convo.id
        toolDispatcher.currentConversationId = convo.id
        return convo.id
    }

    // ------------------------------------------------------------- the loop

    private suspend fun runAgentLoop(cid: String, userMessage: String) {
        val provider = prefs.selectedProvider.first()
        val modelId = prefs.selectedModelId.first()
        val apiKey = keyStore.getApiKey(provider)

        if (modelId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            _state.update { it.copy(isSending = false, statusLine = null, error = "No model/API key configured — open Settings.") }
            return
        }

        val caps = ModelCapabilityRegistry.forModel(modelId)
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

        // Plain conversation doesn't need the toolbox, the planning, or the step
        // budget. Try answering directly first; the model can bail out to the full
        // loop itself if it turns out it needed something.
        if (IntentClassifier.classify(userMessage, accessibilityReady) == TurnIntent.CHAT) {
            val quick = tryDirectAnswer(client, provider, modelId, apiKey, systemPrompt, history)
            if (quick != null) {
                finishTurn(cid, quick, usedModel = modelId, fellBack = false)
                scope.launch { maintainContext(cid, client, provider, modelId, apiKey, userMessage, quick) }
                resumeListeningIfHandsFree()
                return
            }
        }

        val tools = ToolDefinitions.forTier(caps.useCompactPrompt, caps.supportsVision, accessibilityReady)

        var rounds = 0
        var finalText: String? = null
        var lastSignature: String? = null
        var repeatCount = 0
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
                deliveryMode == DeliveryMode.VOICE -> RequestTuning.SPOKEN
                rounds == 1 -> RequestTuning.TOOL_STEP
                else -> RequestTuning.TOOL_STEP.copy(maxTokens = 1000)
            }
            val outcome = requestWithFallback(client, provider, usedModel, apiKey, systemPrompt, history, tools, tuning)
            usedModel = outcome.modelUsed
            if (outcome.fellBack) fellBack = true

            when (val result = outcome.result) {
                is LlmResult.Message -> finalText = result.text

                is LlmResult.Error -> {
                    _state.update { it.copy(isSending = false, statusLine = null, error = result.message) }
                    return
                }

                is LlmResult.ToolCalls -> {
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
                    for (call in result.calls) {
                        val toolText = executeCall(cid, call, images)
                        history.add(LlmMessage(role = LlmMessage.Role.TOOL, text = toolText, toolCallId = call.id))
                    }

                    // Nudge with concrete progress once a task starts drifting.
                    taskState.progressNote()?.takeIf { rounds >= 4 }?.let { note ->
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

        val replyText = finalText
            ?: "I've stopped rather than keep going in circles on that. Here's where I got to: " +
            (taskState.progressNote() ?: "no progress to report.") + " Tell me what you can see and I'll pick it up."

        finishTurn(cid, replyText, usedModel, fellBack)

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
        provider: Provider,
        modelId: String,
        apiKey: String,
        systemPrompt: String,
        history: List<LlmMessage>
    ): String? {
        val prompt = systemPrompt + "\n\n" + PromptBuilder.directAnswerRule()
        val tuning = if (deliveryMode == DeliveryMode.VOICE) RequestTuning.SPOKEN else RequestTuning.ANSWER

        val result = runCatching {
            client.send(apiKey, modelId, prompt, history, emptyList(), tuning)
        }.getOrNull() ?: return null

        val text = (result as? LlmResult.Message)?.text?.trim() ?: return null
        // The escape hatch. A model that decides it needs the phone or the live web
        // says so, and the caller falls through to the full loop — so a
        // misclassification costs one cheap request, never a wrong answer.
        if (text.isBlank() || text.contains(IntentClassifier.NEEDS_TOOLS)) return null
        return text
    }

    /** The single place a completed turn lands in the transcript, the database and the speaker. */
    private suspend fun finishTurn(cid: String, replyText: String, usedModel: String, fellBack: Boolean) {
        conversations.append(MessageEntity(conversationId = cid, role = "assistant", content = replyText))
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(sender = Sender.LAIN, text = replyText),
                isSending = false,
                statusLine = null,
                activeModelNotice = if (fellBack) "Used $usedModel — your selected model was unavailable." else null
            )
        }
        speak(replyText)
    }

    private suspend fun resumeListeningIfHandsFree() {
        if (!_state.value.conversationMode) return
        delay(1200)
        if (!_state.value.isBusy && _state.value.conversationMode) startVoiceInput()
    }

    private suspend fun executeCall(cid: String, call: ToolCall, images: MutableList<String>): String {
        // Refuse app churn before it happens rather than explaining it afterwards.
        if ((call.name == "open_app" || call.name == "close_app") && taskState.appSwitches >= MAX_APP_SWITCHES) {
            return "FAILED [loop]: too many app switches for one request. Work with the screen you're on, or stop and explain."
        }
        if (taskState.isExhausted(call.name, call.argumentsJson)) {
            return "FAILED [exhausted]: this exact call has already failed twice. Do not try it again — change approach or stop."
        }

        setStatus(statusFor(call))
        val result = toolDispatcher.execute(call)

        taskState.record(
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

    private data class Outcome(val result: LlmResult, val modelUsed: String, val fellBack: Boolean)

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

    /**
     * Tries the selected model, and falls back when that's the only way to answer.
     *
     * The opt-in toggle governs *quality and cost* substitutions — swapping a busy
     * model for a different one is a decision the user should own, so a rate limit
     * only triggers a switch if they asked for that. A retired slug is a different
     * situation: the selected model cannot answer this message or any future one, so
     * refusing to switch just means the app is broken until someone opens Settings.
     * Those get switched regardless, recorded so the dead entry stops being offered,
     * and reported afterwards — never silently.
     */
    private suspend fun requestWithFallback(
        client: LlmClient,
        provider: Provider,
        modelId: String,
        apiKey: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<com.lain.assistant.network.ToolDefinition>,
        tuning: RequestTuning
    ): Outcome {
        val first = client.send(apiKey, modelId, systemPrompt, history, tools, tuning)
        if (first !is LlmResult.Error) return Outcome(first, modelId, false)

        return when (val kind = classify(first.message)) {
            ErrorClass.AUTH -> Outcome(
                LlmResult.Error("Your ${provider.displayName} API key was rejected. Check it in Settings."),
                modelId, false
            )

            ErrorClass.CREDIT -> Outcome(
                LlmResult.Error("No credit left on ${provider.displayName}. Top up, or switch to a free model in Settings."),
                modelId, false
            )

            ErrorClass.MODEL_GONE -> {
                prefs.markModelBroken(modelId)
                val alternative = pickAlternative(provider, modelId)
                if (alternative == null) {
                    Outcome(
                        LlmResult.Error(
                            "\"$modelId\" no longer exists on ${provider.displayName} — free models get retired " +
                                "without notice. Open Settings and pick another one."
                        ),
                        modelId, false
                    )
                } else {
                    setStatus("That model's gone — using ${alternative.label}…")
                    val second = client.send(apiKey, alternative.id, systemPrompt, history, tools, tuning)
                    if (second is LlmResult.Error) {
                        Outcome(
                            LlmResult.Error(
                                "\"$modelId\" has been retired and ${alternative.label} didn't answer either. " +
                                    "Open Settings and pick a model."
                            ),
                            modelId, false
                        )
                    } else {
                        // Make it stick, so the next message doesn't repeat the whole dance.
                        prefs.saveModelSelection(provider, alternative.id)
                        Outcome(second, alternative.id, true)
                    }
                }
            }

            ErrorClass.TRANSIENT, ErrorClass.OTHER -> {
                val fallbackEnabled = prefs.isModelFallbackEnabled.first()
                val alternative = if (fallbackEnabled && kind == ErrorClass.TRANSIENT) {
                    pickAlternative(provider, modelId)
                } else {
                    null
                } ?: return Outcome(first, modelId, false)

                // One alternative only — hammering a rate-limited account wastes requests
                // and makes the limit worse.
                setStatus("Switching to ${alternative.label}…")
                val second = client.send(apiKey, alternative.id, systemPrompt, history, tools, tuning)
                Outcome(second, alternative.id, second !is LlmResult.Error)
            }
        }
    }

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
        val actions = raw.filter { it.role == "tool" && it.content.isNotBlank() }.takeLast(RECENT_ACTIONS)
        if (actions.isNotEmpty()) {
            out += LlmMessage(
                role = LlmMessage.Role.USER,
                text = actions.joinToString("\n") { "(already done: ${it.content.take(ACTION_RECAP_CHARS)})" }
            )
        }

        // A provider will reject a history that opens on an assistant turn.
        while (out.isNotEmpty() && out.first().role != LlmMessage.Role.USER) out.removeAt(0)
        return out
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
