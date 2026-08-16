package com.lain.assistant.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lain.assistant.data.Gender
import com.lain.assistant.data.ModelCatalog
import com.lain.assistant.data.Provider
import com.lain.assistant.ui.common.HoveringPanel
import com.lain.assistant.ui.common.PixelBackground
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.common.PixelChoiceChip
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainMuted

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.complete) {
        if (state.complete) onFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackground()
        HoveringPanel(modifier = Modifier.padding(top = 220.dp)) {
            Text(
                text = questionFor(state.step),
                style = MaterialTheme.typography.headlineMedium,
                color = LainCream
            )
            Spacer(Modifier.height(16.dp))

            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (state.step) {
                    OnboardingStep.NAME -> PixelTextField(state.name, viewModel::setName, "Your name")
                    OnboardingStep.AGE -> PixelTextField(state.age, viewModel::setAge, "Your age", keyboardType = KeyboardType.Number)
                    OnboardingStep.GENDER -> GenderChoice(state.gender, viewModel::setGender)
                    OnboardingStep.NICKNAME -> PixelTextField(state.nickname, viewModel::setNickname, "What should Lain call you?")
                    OnboardingStep.PROVIDER -> ProviderChoice(state.provider, viewModel::setProvider)
                    OnboardingStep.MODEL -> ModelChoice(state.provider, state.modelId, viewModel::setModelId)
                    OnboardingStep.API_KEY -> ApiKeyStep(state.provider, state.apiKey, viewModel::setApiKey)
                    OnboardingStep.PERMISSIONS -> PermissionsPrimer()
                }
            }

            Spacer(Modifier.height(20.dp))
            PixelButton(
                text = if (state.step == OnboardingStep.PERMISSIONS) "Let's go" else "Continue",
                onClick = viewModel::next,
                enabled = state.canAdvance
            )
        }
    }
}

private fun questionFor(step: OnboardingStep): String = when (step) {
    OnboardingStep.NAME -> "What's your name?"
    OnboardingStep.AGE -> "How old are you?"
    OnboardingStep.GENDER -> "Are you male or female?"
    OnboardingStep.NICKNAME -> "What should I call you?"
    OnboardingStep.PROVIDER -> "Which AI provider are you using?"
    OnboardingStep.MODEL -> "Pick a model"
    OnboardingStep.API_KEY -> "Enter your API key"
    OnboardingStep.PERMISSIONS -> "One more thing"
}

@Composable
private fun GenderChoice(selected: Gender?, onSelect: (Gender) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PixelChoiceChip("Male", selected == Gender.MALE, { onSelect(Gender.MALE) }, modifier = Modifier.weight(1f))
        PixelChoiceChip("Female", selected == Gender.FEMALE, { onSelect(Gender.FEMALE) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProviderChoice(selected: Provider, onSelect: (Provider) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Provider.entries.forEach { provider ->
            PixelChoiceChip(provider.displayName, selected == provider, { onSelect(provider) })
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ModelChoice(provider: Provider, selectedId: String?, onSelect: (String) -> Unit) {
    val models = ModelCatalog.forProvider(provider)
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            PixelChoiceChip(
                text = model.label + if (model.isFree) "  ·  FREE" else "",
                selected = selectedId == model.id,
                onClick = { onSelect(model.id) }
            )
        }
    }
}

@Composable
private fun ApiKeyStep(provider: Provider, value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.layout.Column {
        PixelTextField(value, onChange, "${provider.displayName} API key", isPassword = true)
        Spacer(Modifier.height(8.dp))
        Text(
            "Stored encrypted on this device only, never leaves your phone except in calls to $provider directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainMuted
        )
    }
}

@Composable
private fun PermissionsPrimer() {
    Text(
        "Next, Lain will ask for a few permissions — microphone, camera, phone/SMS, and Accessibility Service — so she can actually act on your phone instead of just talking. You can grant them now or later; features that need one will just ask again when you use them.",
        style = MaterialTheme.typography.bodyMedium,
        color = LainCream
    )
}
