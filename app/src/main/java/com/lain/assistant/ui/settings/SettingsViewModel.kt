package com.lain.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.Gender
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
    val loaded: Boolean = false,
    val justSaved: Boolean = false,
    /** null = use the static fallback catalog; non-null = live models fetched from OpenRouter. */
    val liveOpenRouterModels: List<ModelInfo>? = null,
    val isLoadingModels: Boolean = false
) {
    val availableModels: List<ModelInfo>
        get() = if (provider == Provider.OPENROUTER) {
            liveOpenRouterModels ?: ModelCatalog.forProvider(provider)
        } else {
            ModelCatalog.forProvider(provider)
        }

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
                    loaded = true
                )
            }
        }
        fetchLiveModelsIfNeeded()
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

    fun setModelId(modelId: String) = _state.update { it.copy(modelId = modelId, justSaved = false) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, justSaved = false) }
    fun setKokoroEndpoint(value: String) = _state.update { it.copy(kokoroEndpoint = value, justSaved = false) }

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
            container.userPreferencesRepository.saveModelSelection(s.provider, modelId)
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            container.userPreferencesRepository.saveKokoroEndpoint(s.kokoroEndpoint.trim())
            _state.update { it.copy(justSaved = true) }
        }
    }
}
