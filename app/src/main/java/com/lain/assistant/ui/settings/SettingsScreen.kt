package com.lain.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.OverlayBubbleService
import com.lain.assistant.data.Gender
import com.lain.assistant.data.Provider
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.common.PixelChoiceChip
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.common.rememberLainWindow
import com.lain.assistant.ui.common.openAccessibilitySettingsForLain
import com.lain.assistant.ui.common.openAppInfo
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainNavyDeep
import com.lain.assistant.ui.theme.LainSalmon

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val window = rememberLainWindow()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LainNavyDeep)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // A settings form is a column of controls; letting it run the full width of
                // a landscape tablet puts the label and its field a hand-span apart.
                .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
                .widthIn(max = window.contentMaxWidth)
                .padding(horizontal = window.gutter, vertical = window.topInset)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBack() }
                        .padding(8.dp)
                ) {
                    Text("< Back", color = LainSalmon, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Settings", style = MaterialTheme.typography.displayMedium, color = LainCream)
            Spacer(Modifier.height(24.dp))

            SectionLabel("Your name")
            PixelTextField(state.name, viewModel::setName, "Your name")

            Spacer(Modifier.height(20.dp))
            SectionLabel("Age")
            PixelTextField(state.age, viewModel::setAge, "Your age", keyboardType = KeyboardType.Number)

            Spacer(Modifier.height(20.dp))
            SectionLabel("Gender")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelChoiceChip("Male", state.gender == Gender.MALE, { viewModel.setGender(Gender.MALE) }, modifier = Modifier.weight(1f))
                PixelChoiceChip("Female", state.gender == Gender.FEMALE, { viewModel.setGender(Gender.FEMALE) }, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Nickname (what Lain calls you)")
            PixelTextField(state.nickname, viewModel::setNickname, "Nickname")

            Spacer(Modifier.height(24.dp))
            SectionLabel("Provider")
            // Wraps rather than clipping: five names never fit one line on a phone.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Provider.entries.forEach { provider ->
                    PixelChoiceChip(provider.displayName, state.provider == provider, { viewModel.setProvider(provider) })
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Model")
            Text(
                "★ models reliably chain multi-step phone tasks. Free models can accept the same tools but often stall, repeat themselves, or describe an action instead of doing it — if Lain feels dumb, this is usually why.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isLoadingModels) {
                    Text("Checking what's actually free right now…", style = MaterialTheme.typography.bodyMedium, color = LainMuted)
                }
                if (state.brokenModels.isNotEmpty()) {
                    Text(
                        "${state.brokenModels.size} model(s) stopped existing on the provider and are hidden. " +
                            "Free models get retired without notice; Lain switched you off them automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LainSalmon
                    )
                }
                state.availableModels.forEach { model ->
                    // Context size is the one honest capability signal a free model gives
                    // us, and it's what decides whether a long task survives.
                    val badge = when {
                        model.strongAtTools -> "  ★"
                        model.isFree && model.contextTokens > 0 -> "  ·  FREE · ${model.contextTokens / 1000}k"
                        model.isFree -> "  ·  FREE"
                        else -> ""
                    }
                    PixelChoiceChip(
                        text = model.label + badge,
                        selected = state.modelId == model.id,
                        onClick = { viewModel.setModelId(model.id) }
                    )
                }
            }

            // What the chosen model can actually do, stated before it matters rather
            // than discovered when something fails.
            state.selectedCapabilities?.let { caps ->
                Spacer(Modifier.height(12.dp))
                Text(caps.summary(), style = MaterialTheme.typography.bodyMedium, color = LainCream)
                caps.limitations.forEach { limitation ->
                    Text("· $limitation", style = MaterialTheme.typography.bodyMedium, color = LainMuted)
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("${state.provider.displayName} API key")
            PixelTextField(state.apiKey, viewModel::setApiKey, "API key", isPassword = true)

            Spacer(Modifier.height(16.dp))
            PixelButton(
                text = if (state.isTesting) "Testing…" else "Test connection",
                onClick = viewModel::testConnection,
                enabled = !state.isTesting
            )
            state.testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.startsWith("Working")) LainCream else LainSalmon
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Kokoro TTS endpoint (optional)")
            PixelTextField(state.kokoroEndpoint, viewModel::setKokoroEndpoint, "https://your-kokoro-server")
            Text(
                "Leave blank to use the on-device Android voice.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("Automatic model fallback")
            Text(
                "If the selected model is rate limited or unavailable, try another one instead of failing. Lain always tells you when this happened — she never switches silently.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelChoiceChip("On", state.modelFallback, { viewModel.setModelFallback(true) }, modifier = Modifier.weight(1f))
                PixelChoiceChip("Off", !state.modelFallback, { viewModel.setModelFallback(false) }, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("What Lain remembers (${state.memories.size})")
            Text(
                "Durable facts Lain saved on her own or because you asked. Only the ones relevant to what you're saying get used in any given message.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(8.dp))
            if (state.memories.isEmpty()) {
                Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium, color = LainMuted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.memories.take(40).forEach { m ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                "[${m.category.lowercase()}] ${m.fact}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LainCream,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Forget",
                                style = MaterialTheme.typography.labelLarge,
                                color = LainSalmon,
                                modifier = Modifier
                                    .clickable { viewModel.deleteMemory(m.id) }
                                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PixelButton(text = "Clear all memories", onClick = viewModel::clearMemories)
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Control Lain from anywhere")
            Text(
                "Puts a small draggable bubble over other apps so you can give Lain an order without leaving what you're doing. Needs the \"Display over other apps\" permission.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelChoiceChip("On", state.overlayEnabled, {
                    if (OverlayBubbleService.canDrawOverlays(context)) {
                        viewModel.setOverlayEnabled(true)
                        OverlayBubbleService.start(context)
                    } else {
                        // Special permission — can't be granted by a runtime prompt.
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }, modifier = Modifier.weight(1f))
                PixelChoiceChip("Off", !state.overlayEnabled, {
                    viewModel.setOverlayEnabled(false)
                    OverlayBubbleService.stop(context)
                }, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Battery saver")
            Text(
                "Stops \"Hello Lain\" listening while the screen is off. Leaving this on costs you hands-free wake-ups in your pocket, but saves a lot of battery — continuous listening is genuinely expensive.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelChoiceChip("On", state.batterySaver, { viewModel.setBatterySaver(true) }, modifier = Modifier.weight(1f))
                PixelChoiceChip("Off", !state.batterySaver, { viewModel.setBatterySaver(false) }, modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Accessibility Service")
            // Three states, not two: "on in Settings but not connected" is its own
            // problem with its own fix, and calling it "not enabled" is what sent people
            // looking for a switch that was already flipped.
            val health = LainAccessibilityService.health(context)
            Text(
                when (health) {
                    LainAccessibilityService.ServiceHealth.READY ->
                        "Connected — Lain can read and tap your screen."
                    LainAccessibilityService.ServiceHealth.STALLED ->
                        "Switched on, but not connected right now. Android drops the service after an update or " +
                            "when it reclaims memory. Toggle Lain off and back on below to restore it."
                    LainAccessibilityService.ServiceHealth.OFF ->
                        "Not enabled. Without it Lain can't read or tap your screen."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (health) {
                    LainAccessibilityService.ServiceHealth.READY -> LainCream
                    LainAccessibilityService.ServiceHealth.STALLED -> LainSalmon
                    LainAccessibilityService.ServiceHealth.OFF -> LainMuted
                }
            )
            Spacer(Modifier.height(8.dp))
            PixelButton(
                text = when (health) {
                    LainAccessibilityService.ServiceHealth.STALLED -> "Reconnect (off, then on)"
                    else -> "Open Lain's accessibility settings"
                },
                onClick = { openAccessibilitySettingsForLain(context) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Toggle greyed out on a fresh install? Open app info → ⋮ → Allow restricted settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted,
                modifier = Modifier.clickable { openAppInfo(context) }.padding(vertical = 4.dp)
            )

            Spacer(Modifier.height(28.dp))
            PixelButton(text = if (state.justSaved) "Saved" else "Save", onClick = viewModel::save, enabled = state.loaded && state.canSave)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LainSalmon)
    Spacer(Modifier.height(8.dp))
}
