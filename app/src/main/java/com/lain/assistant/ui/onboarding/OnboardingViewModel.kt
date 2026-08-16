package com.lain.assistant.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.Gender
import com.lain.assistant.data.ModelCatalog
import com.lain.assistant.data.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { NAME, AGE, GENDER, NICKNAME, PROVIDER, MODEL, API_KEY, PERMISSIONS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.NAME,
    val name: String = "",
    val age: String = "",
    val gender: Gender? = null,
    val nickname: String = "",
    val provider: Provider = ModelCatalog.defaultProvider,
    val modelId: String? = ModelCatalog.recommendedFor(ModelCatalog.defaultProvider)?.id,
    val apiKey: String = "",
    val complete: Boolean = false
) {
    val canAdvance: Boolean
        get() = when (step) {
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

    fun setName(v: String) = _state.update { it.copy(name = v) }
    fun setAge(v: String) = _state.update { it.copy(age = v.filter { c -> c.isDigit() }) }
    fun setGender(v: Gender) = _state.update { it.copy(gender = v) }
    fun setNickname(v: String) = _state.update { it.copy(nickname = v) }
    fun setProvider(v: Provider) = _state.update {
        it.copy(provider = v, modelId = ModelCatalog.recommendedFor(v)?.id)
    }
    fun setModelId(v: String) = _state.update { it.copy(modelId = v) }
    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v) }

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
            container.userPreferencesRepository.saveModelSelection(s.provider, s.modelId ?: return@launch)
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            container.userPreferencesRepository.markOnboarded()
            _state.update { it.copy(complete = true) }
        }
    }
}
