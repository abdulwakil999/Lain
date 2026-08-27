package com.lain.assistant.ui.onboarding

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { LEGAL, NAME, AGE, GENDER, NICKNAME, PROVIDER, MODEL, API_KEY, PERMISSIONS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LEGAL,
    /** Both documents have to be accepted before anything is asked for or stored. */
    val legalAccepted: Boolean = false,
    val name: String = "",
    val age: String = "",
    val gender: Gender? = null,
    val nickname: String = "",
    val provider: Provider = ModelCatalog.defaultProvider,
    val modelId: String? = ModelCatalog.recommendedFor(ModelCatalog.defaultProvider)?.id,
    val apiKey: String = "",
    val complete: Boolean = false,
    /** null = use the static fallback catalog; non-null = live models fetched from OpenRouter. */
    val liveOpenRouterModels: List<ModelInfo>? = null,
    val isLoadingModels: Boolean = false
) {
    /**
     * The live fetch only returns free models, so the curated strong (paid) ones
     * stay pinned in front of it — those are the only ones that reliably drive
     * multi-step automation.
     */
    val availableModels: List<ModelInfo>
        get() = if (provider == Provider.OPENROUTER && liveOpenRouterModels != null) {
            ModelCatalog.forProvider(provider).filter { it.strongAtTools } + liveOpenRouterModels
        } else {
            ModelCatalog.forProvider(provider)
        }

    val canAdvance: Boolean
        get() = when (step) {
            // Gated deliberately: the very next question asks for their name, and
            // asking for anything before the policy is shown makes the policy a
            // formality after the fact.
            OnboardingStep.LEGAL -> legalAccepted
            OnboardingStep.NAME -> name.isNotBlank()
            OnboardingStep.AGE -> age.toIntOrNull()?.let { it in 1..120 } == true
            OnboardingStep.GENDER -> gender != null
            OnboardingStep.NICKNAME -> nickname.isNotBlank()
            OnboardingStep.PROVIDER -> true
            OnboardingStep.MODEL -> modelId != null
            OnboardingStep.API_KEY -> apiKey.isNotBlank()
            OnboardingStep.PERMISSIONS -> true
        }
}

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val stepOrder = OnboardingStep.entries

    init {
        fetchLiveModelsIfNeeded()
    }

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setAge(v: String) = _state.update { it.copy(age = v.filter { c -> c.isDigit() }) }
    fun setGender(v: Gender) = _state.update { it.copy(gender = v) }
    fun setNickname(v: String) = _state.update { it.copy(nickname = v) }
    fun setProvider(v: Provider) = _state.update {
        it.copy(provider = v, modelId = ModelCatalog.recommendedFor(v)?.id)
    }.also { fetchLiveModelsIfNeeded() }
    fun setModelId(v: String) = _state.update { it.copy(modelId = v) }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v) }

    /** OpenRouter's free tier rotates too fast for a hardcoded list — pull the live one instead. */
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
                onFailure = { _state.update { it.copy(isLoadingModels = false) } } // falls back to the static list
            )
        }
    }

    fun setLegalAccepted(accepted: Boolean) =
        _state.update { it.copy(legalAccepted = accepted) }

    fun next() {
        val current = _state.value
        if (!current.canAdvance) return
        val idx = stepOrder.indexOf(current.step)
        if (idx == stepOrder.lastIndex) {
            finish()
        } else {
            _state.update { it.copy(step = stepOrder[idx + 1]) }
        }
    }

    fun back() {
        val idx = stepOrder.indexOf(_state.value.step)
        if (idx > 0) _state.update { it.copy(step = stepOrder[idx - 1]) }
    }

    private fun finish() {
        val s = _state.value
        viewModelScope.launch {
            container.userPreferencesRepository.saveProfileStep(
                name = s.name.trim(),
                age = s.age.toIntOrNull() ?: 0,
                gender = s.gender ?: Gender.FEMALE,
                nickname = s.nickname.trim()
            )
            val chosen = s.modelId ?: return@launch
            container.userPreferencesRepository.saveModelSelection(
                s.provider,
                chosen,
                s.availableModels.firstOrNull { it.id == chosen }
            )
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            container.userPreferencesRepository.markOnboarded()
            _state.update { it.copy(complete = true) }
        }
    }
}
