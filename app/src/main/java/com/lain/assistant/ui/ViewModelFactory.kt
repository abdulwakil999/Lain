package com.lain.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.lain.assistant.AppContainer
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.onboarding.OnboardingViewModel
import com.lain.assistant.ui.settings.SettingsViewModel

class LainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when (modelClass) {
        OnboardingViewModel::class.java -> OnboardingViewModel(container) as T
        ChatViewModel::class.java -> ChatViewModel(container) as T
        SettingsViewModel::class.java -> SettingsViewModel(container) as T
        com.lain.assistant.ui.knows.KnowsViewModel::class.java ->
            com.lain.assistant.ui.knows.KnowsViewModel(container) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
