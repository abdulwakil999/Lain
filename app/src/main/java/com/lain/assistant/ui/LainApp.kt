package com.lain.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lain.assistant.AppContainer
import com.lain.assistant.legal.LegalText
import com.lain.assistant.ui.chat.ChatScreen
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.onboarding.OnboardingScreen
import com.lain.assistant.ui.legal.LegalUpdateScreen
import com.lain.assistant.ui.onboarding.OnboardingViewModel
import com.lain.assistant.ui.theme.LainNavyDeep
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun LainApp(container: AppContainer, autoListenToken: Long? = null) {
    val isOnboarded by container.userPreferencesRepository.isOnboarded.collectAsState(initial = null)
    val factory = remember { LainViewModelFactory(container) }

    Box(modifier = Modifier.fillMaxSize().background(LainNavyDeep)) {
        when (isOnboarded) {
            null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            false -> {
                var justFinished by remember { mutableStateOf(false) }
                val onboardingViewModel: OnboardingViewModel = viewModel(factory = factory)
                if (!justFinished) {
                    OnboardingScreen(viewModel = onboardingViewModel, onFinished = { justFinished = true })
                } else {
                    val chatViewModel: ChatViewModel = viewModel(factory = factory)
                    ChatScreen(viewModel = chatViewModel, container = container, autoListenToken = autoListenToken)
                }
            }
            true -> {
                // Someone who agreed to an older policy is shown the new one before
                // anything else, which is what the policy itself says happens. Null
                // while the stored version is still being read, so the chat never
                // flashes up in front of a screen that is about to replace it.
                val acceptedLegal by container.userPreferencesRepository.acceptedLegalVersion
                    .collectAsState(initial = null)
                val scope = rememberCoroutineScope()

                when {
                    acceptedLegal == null ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    acceptedLegal!! < LegalText.VERSION -> LegalUpdateScreen(
                        onAccept = {
                            scope.launch {
                                container.userPreferencesRepository.acceptLegalVersion(LegalText.VERSION)
                            }
                        }
                    )

                    else -> {
                        val chatViewModel: ChatViewModel = viewModel(factory = factory)
                        ChatScreen(
                            viewModel = chatViewModel,
                            container = container,
                            autoListenToken = autoListenToken
                        )
                    }
                }
            }
        }
    }
}
