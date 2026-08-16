package com.lain.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lain.assistant.AppContainer
import com.lain.assistant.data.ModelCatalog
import com.lain.assistant.data.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val provider: Provider = ModelCatalog.defaultProvider,
    val modelId: String? = null,
    val apiKey: String = "",
    val kokoroEndpoint: String = "",
    val loaded: Boolean = false,
    val justSaved: Boolean = false
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val provider = container.userPreferencesRepository.selectedProvider.first()
            val modelId = container.userPreferencesRepository.selectedModelId.first()
            val apiKey = container.secureKeyStore.getApiKey(provider).orEmpty()
            val kokoroEndpoint = container.userPreferencesRepository.kokoroEndpoint.first().orEmpty()
            _state.update {
                it.copy(provider = provider, modelId = modelId, apiKey = apiKey, kokoroEndpoint = kokoroEndpoint, loaded = true)
            }
        }
    }

    fun setProvider(provider: Provider) {
        // Switching provider means the previously-typed key belongs to a different service —
        // reload whatever key is already saved for the new provider instead of carrying it over.
        viewModelScope.launch {
            val existingKey = container.secureKeyStore.getApiKey(provider).orEmpty()
            _state.update {
                it.copy(provider = provider, modelId = ModelCatalog.recommendedFor(provider)?.id, apiKey = existingKey, justSaved = false)
            }
        }
    }

    fun setModelId(modelId: String) = _state.update { it.copy(modelId = modelId, justSaved = false) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, justSaved = false) }
    fun setKokoroEndpoint(value: String) = _state.update { it.copy(kokoroEndpoint = value, justSaved = false) }

    fun save() {
        val s = _state.value
        val modelId = s.modelId ?: return
        viewModelScope.launch {
            container.userPreferencesRepository.saveModelSelection(s.provider, modelId)
            container.secureKeyStore.saveApiKey(s.provider, s.apiKey.trim())
            container.userPreferencesRepository.saveKokoroEndpoint(s.kokoroEndpoint.trim())
            _state.update { it.copy(justSaved = true) }
        }
    }
}
