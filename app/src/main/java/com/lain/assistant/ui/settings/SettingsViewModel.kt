package com.lain.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.Gender
import com.lain.assistant.data.ModelCapabilities
import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.ModelCatalog
import com.lain.assistant.data.ModelInfo
import com.lain.assistant.data.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.lain.assistant.tts.FishAudioTtsEngine
import com.lain.assistant.tts.VoicePack
import kotlinx.coroutines.Job
import com.lain.assistant.data.StoredChannelCredentials
import kotlinx.coroutines.launch

data class SettingsUiState(
    val name: String = "",
    val age: String = "",
    val gender: Gender? = null,
    val nickname: String = "",
    val provider: Provider = ModelCatalog.defaultProvider,
    val modelId: String? = null,
    val apiKey: String = "",
    val kokoroEndpoint: String = "",
    val fishKey: String = "",
    val fishVoiceId: String = "",
    /** null while nothing is downloading. */
    val voiceDownload: VoicePack.Progress? = null,
    val voiceLinesHeld: Int = 0,
    val voiceBytesHeld: Long = 0,
    val discordWebhook: String = "",
    val redditClientId: String = "",
    val redditSecret: String = "",
    val redditUser: String = "",
    val redditPassword: String = "",
    val overlayEnabled: Boolean = false,
    val alwaysOn: Boolean = false,
    val loaded: Boolean = false,
    val justSaved: Boolean = false,
    val testResult: String? = null,
    val isTesting: Boolean = false,
    val memories: List<com.lain.assistant.data.db.MemoryEntity> = emptyList(),
    val modelFallback: Boolean = false,
    /** Slugs that already failed as retired, hidden so they can't be picked again by accident. */
    val brokenModels: Set<String> = emptySet(),
    /** null = use the static fallback catalog; non-null = live models fetched from OpenRouter. */
    val liveOpenRouterModels: List<ModelInfo>? = null,
    val isLoadingModels: Boolean = false
) {
    /**
     * The live fetch only returns free models, so the curated strong (paid) ones
     * are kept in front of it — otherwise the models that actually drive
     * multi-step automation would vanish from the picker entirely.
     */
    val availableModels: List<ModelInfo>
        get() = if (provider == Provider.OPENROUTER && liveOpenRouterModels != null) {
            (ModelCatalog.forProvider(provider).filter { it.strongAtTools } + liveOpenRouterModels)
                .filterNot { it.id in brokenModels }
        } else {
            ModelCatalog.forProvider(provider).filterNot { it.id in brokenModels }
        }

    /** The catalogue entry for a model id, so its real capabilities are stored with the choice. */
    fun infoFor(id: String): ModelInfo? = availableModels.firstOrNull { it.id == id }

    /**
     * What the currently selected model can do, derived live from the picker's own
     * catalogue data so it updates the moment the selection changes.
     */
    val selectedCapabilities: ModelCapabilities?
        get() = modelId?.let { ModelCapabilityRegistry.forModel(it, provider, infoFor(it)) }

    val canSave: Boolean
        get() = name.isNotBlank() && nickname.isNotBlank() && gender != null &&
            age.toIntOrNull()?.let { it in 1..120 } == true
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = container.userPreferencesRepository.userProfile.first()
            val provider = container.userPreferencesRepository.selectedProvider.first()
            val modelId = container.userPreferencesRepository.selectedModelId.first()
            val apiKey = container.secureKeyStore.getApiKey(provider).orEmpty()
            val kokoroEndpoint = container.userPreferencesRepository.kokoroEndpoint.first().orEmpty()
            val fishKey = container.secureKeyStore.getVoiceKey().orEmpty()
            val fishVoiceId = container.userPreferencesRepository.fishVoiceId.first()
            val pack = VoicePack(container.appContext)
            val keys = container.secureKeyStore
            val overlayEnabled = container.userPreferencesRepository.isOverlayEnabled.first()
            val alwaysOn = container.userPreferencesRepository.isAlwaysOn.first()
            val fallback = container.userPreferencesRepository.isModelFallbackEnabled.first()
            val broken = container.userPreferencesRepository.brokenModels.first()
            _state.update {
                it.copy(
                    name = profile.name,
                    age = if (profile.age > 0) profile.age.toString() else "",
                    gender = profile.gender,
                    nickname = profile.nickname,
                    provider = provider,
                    modelId = modelId,
                    apiKey = apiKey,
                    kokoroEndpoint = kokoroEndpoint,
                    fishKey = fishKey,
                    fishVoiceId = fishVoiceId,
                    voiceLinesHeld = pack.heldCount(),
                    voiceBytesHeld = pack.heldBytes(),
                    discordWebhook = keys.channel(StoredChannelCredentials.DISCORD_WEBHOOK).orEmpty(),
                    redditClientId = keys.channel(StoredChannelCredentials.REDDIT_ID).orEmpty(),
                    redditSecret = keys.channel(StoredChannelCredentials.REDDIT_SECRET).orEmpty(),
                    redditUser = keys.channel(StoredChannelCredentials.REDDIT_USER).orEmpty(),
                    redditPassword = keys.channel(StoredChannelCredentials.REDDIT_PASSWORD).orEmpty(),
                    overlayEnabled = overlayEnabled,
                    alwaysOn = alwaysOn,
                    modelFallback = fallback,
                    brokenModels = broken,
                    loaded = true
                )
            }
        }
        // Keeps the memory list live as Lain learns or forgets things.
        viewModelScope.launch {
            container.memoryStore.observeAll.collect { list ->
                _state.update { it.copy(memories = list) }
            }
        }
        fetchLiveModelsIfNeeded()
    }

    fun setModelFallback(enabled: Boolean) {
        _state.update { it.copy(modelFallback = enabled) }
        viewModelScope.launch { container.userPreferencesRepository.setModelFallbackEnabled(enabled) }
    }

    /** Corrects a stored fact in place, keeping its id and provenance. */
    fun editMemory(id: String, fact: String) {
        viewModelScope.launch { container.memoryStore.edit(id = id, fact = fact) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { container.memoryStore.deleteById(id) }
    }

    fun clearMemories() {
        viewModelScope.launch { container.memoryStore.clear() }
    }

    fun setName(v: String) = _state.update { it.copy(name = v, justSaved = false) }
    fun setAge(v: String) = _state.update { it.copy(age = v.filter { c -> c.isDigit() }, justSaved = false) }
    fun setGender(v: Gender) = _state.update { it.copy(gender = v, justSaved = false) }
    fun setNickname(v: String) = _state.update { it.copy(nickname = v, justSaved = false) }

    fun setProvider(provider: Provider) {
        // Switching provider means the previously-typed key belongs to a different service —
        // reload whatever key is already saved for the new provider instead of carrying it over.
        viewModelScope.launch {
            val existingKey = container.secureKeyStore.getApiKey(provider).orEmpty()
            _state.update {
                it.copy(provider = provider, modelId = ModelCatalog.recommendedFor(provider)?.id, apiKey = existingKey, justSaved = false)
            }
            fetchLiveModelsIfNeeded()
        }
    }

    /**
     * Choosing a model explicitly also clears any "this one is dead" mark on it —
     * a retired slug occasionally comes back, and the user asking for it by name is
     * the right moment to give it another chance.
     */
    fun setModelId(modelId: String) {
        _state.update { it.copy(modelId = modelId, brokenModels = it.brokenModels - modelId, justSaved = false) }
        viewModelScope.launch { container.userPreferencesRepository.clearModelBroken(modelId) }
    }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, justSaved = false) }
    fun setKokoroEndpoint(value: String) = _state.update { it.copy(kokoroEndpoint = value, justSaved = false) }
    fun setFishKey(value: String) = _state.update { it.copy(fishKey = value, justSaved = false) }
    fun setFishVoiceId(value: String) = _state.update { it.copy(fishVoiceId = value, justSaved = false) }
    fun setDiscordWebhook(v: String) = _state.update { it.copy(discordWebhook = v, justSaved = false) }
    fun setRedditClientId(v: String) = _state.update { it.copy(redditClientId = v, justSaved = false) }
    fun setRedditSecret(v: String) = _state.update { it.copy(redditSecret = v, justSaved = false) }
    fun setRedditUser(v: String) = _state.update { it.copy(redditUser = v, justSaved = false) }
    fun setRedditPassword(v: String) = _state.update { it.copy(redditPassword = v, justSaved = false) }

    /**
     * Writes the channel credentials, clearing any slot the user emptied.
     *
     * Emptying a field has to actually revoke it. Leaving the old value in encrypted
     * storage because the box now looks blank would mean Lain keeps posting with
     * credentials the user believes they removed — a silent difference between what
     * the screen says and what the app does.
     */
    private fun saveChannelCredentials(s: SettingsUiState) {
        val keys = container.secureKeyStore
        mapOf(
            StoredChannelCredentials.DISCORD_WEBHOOK to s.discordWebhook,
            StoredChannelCredentials.REDDIT_ID to s.redditClientId,
            StoredChannelCredentials.REDDIT_SECRET to s.redditSecret,
            StoredChannelCredentials.REDDIT_USER to s.redditUser,
            StoredChannelCredentials.REDDIT_PASSWORD to s.redditPassword
        ).forEach { (slot, value) ->
            if (value.isBlank()) keys.clearChannel(slot) else keys.saveChannel(slot, value)
        }
    }

    /**
     * Renders every fixed line once, so the voice keeps working with no signal.
     *
     * Saves the key and voice id first: someone who typed both and pressed download
     * has plainly asked for both to be kept, and making them press Save separately is
     * a step that exists only because the code was written that way.
     */
    fun downloadVoice() {
        val s = _state.value
        if (s.fishKey.isBlank() || s.voiceDownload != null) return

        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            container.secureKeyStore.saveVoiceKey(s.fishKey.trim())
            container.userPreferencesRepository.saveFishVoiceId(s.fishVoiceId.trim())
            saveChannelCredentials(s)

            val pack = VoicePack(container.appContext)
            val engine = FishAudioTtsEngine(
                container.appContext,
                s.fishKey.trim(),
                s.fishVoiceId.trim()
            )
            val voiceKey = s.fishVoiceId.trim().ifBlank { FishAudioTtsEngine.DEFAULT_VOICE_KEY }

            pack.download(engine, voiceKey).collect { progress ->
                _state.update {
                    it.copy(
                        voiceDownload = if (progress.finished) null else progress,
                        voiceLinesHeld = pack.heldCount(),
                        voiceBytesHeld = pack.heldBytes(),
                        // Reported rather than swallowed: a partly-downloaded voice
                        // still works, and the lines that failed will speak in the
                        // device voice, which the user should hear about here rather
                        // than notice later and wonder about.
                        testResult = if (progress.finished && progress.failed > 0) {
                            "Voice downloaded, but ${progress.failed} of ${progress.total} lines " +
                                "wouldn't render. Those fall back to the device voice."
                        } else if (progress.finished) {
                            "Voice downloaded. She speaks offline now."
                        } else {
                            it.testResult
                        }
                    )
                }
            }
        }
    }

    fun cancelVoiceDownload() {
        voiceJob?.cancel()
        voiceJob = null
        _state.update { it.copy(voiceDownload = null) }
    }

    fun clearVoice() {
        viewModelScope.launch {
            VoicePack(container.appContext).clear()
            _state.update { it.copy(voiceLinesHeld = 0, voiceBytesHeld = 0) }
        }
    }

    private var voiceJob: Job? = null

    /**
     * Applied immediately rather than on Save — the bubble is a visible, running
     * thing, so the toggle should reflect reality the moment it's flipped.
     */
    fun setAlwaysOn(enabled: Boolean) {
        _state.update { it.copy(alwaysOn = enabled) }
        viewModelScope.launch { container.userPreferencesRepository.setAlwaysOn(enabled) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
        viewModelScope.launch { container.userPreferencesRepository.setOverlayEnabled(enabled) }
    }

    private fun fetchLiveModelsIfNeeded() {
        val s = _state.value
        if (s.provider != Provider.OPENROUTER || s.liveOpenRouterModels != null || s.isLoadingModels) return
        _state.update { it.copy(isLoadingModels = true) }
        viewModelScope.launch {
            container.openRouterModelsClient.fetchFreeToolCapableModels().fold(
                onSuccess = { fetched ->
                    _state.update { current ->
                        val models = fetched.ifEmpty { ModelCatalog.forProvider(Provider.OPENROUTER) }
                        val stillValidSelection = current.modelId != null && models.any { it.id == current.modelId }
                        current.copy(
                            liveOpenRouterModels = models,
                            isLoadingModels = false,
                            modelId = if (stillValidSelection) current.modelId else models.firstOrNull()?.id
                        )
                    }
                },
                onFailure = { _state.update { it.copy(isLoadingModels = false) } }
            )
        }
    }

    /**
     * Saves first, then sends a real request — so what's tested is exactly what
     * Lain will use, not whatever was configured before the edits.
     */
    fun testConnection() {
        if (_state.value.isTesting) return
        _state.update { it.copy(isTesting = true, testResult = null) }
        viewModelScope.launch {
            val s = _state.value
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            s.modelId?.let { id ->
                container.userPreferencesRepository.saveModelSelection(s.provider, id, s.infoFor(id))
            }
            val result = container.connectionTester.test(s.provider, s.modelId, s.apiKey.trim())
            _state.update { it.copy(isTesting = false, testResult = result) }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val modelId = s.modelId ?: return
        viewModelScope.launch {
            container.userPreferencesRepository.saveProfileStep(
                name = s.name.trim(),
                age = s.age.toIntOrNull() ?: 0,
                gender = s.gender ?: Gender.FEMALE,
                nickname = s.nickname.trim()
            )
            container.userPreferencesRepository.saveModelSelection(s.provider, modelId, s.infoFor(modelId))
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            container.userPreferencesRepository.saveKokoroEndpoint(s.kokoroEndpoint.trim())
            container.secureKeyStore.saveVoiceKey(s.fishKey.trim())
            container.userPreferencesRepository.saveFishVoiceId(s.fishVoiceId.trim())
            saveChannelCredentials(s)
            _state.update { it.copy(justSaved = true) }
        }
    }
}
