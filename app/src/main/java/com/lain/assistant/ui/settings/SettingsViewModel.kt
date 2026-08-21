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
    val overlayEnabled: Boolean = false,
    val batterySaver: Boolean = true,
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
            val overlayEnabled = container.userPreferencesRepository.isOverlayEnabled.first()
            val batterySaver = container.userPreferencesRepository.isBatterySaver.first()
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
                    overlayEnabled = overlayEnabled,
                    batterySaver = batterySaver,
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

    /**
     * Applied immediately rather than on Save — the bubble is a visible, running
     * thing, so the toggle should reflect reality the moment it's flipped.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        _state.update { it.copy(overlayEnabled = enabled) }
        viewModelScope.launch { container.userPreferencesRepository.setOverlayEnabled(enabled) }
    }

    fun setBatterySaver(enabled: Boolean) {
        _state.update { it.copy(batterySaver = enabled) }
        viewModelScope.launch { container.userPreferencesRepository.setBatterySaver(enabled) }
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
            _state.update { it.copy(justSaved = true) }
        }
    }
}
