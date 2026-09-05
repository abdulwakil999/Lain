package com.lain.assistant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lain.assistant.automation.AccessibilityMonitor
import com.lain.assistant.automation.AccessibilityState
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.LainNotificationListener
import com.lain.assistant.automation.OverlayBubbleService
import com.lain.assistant.data.Gender
import com.lain.assistant.data.Provider
import com.lain.assistant.automation.QuickToggles
import com.lain.assistant.automation.AssistantRole
import com.lain.assistant.automation.Scheduler
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.common.PixelChoiceChip
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.common.rememberLainWindow
import com.lain.assistant.ui.common.openAccessibilitySettingsForLain
import com.lain.assistant.ui.common.openAppInfo
import com.lain.assistant.ui.common.isIgnoringBatteryOptimisations
import com.lain.assistant.ui.common.openBatteryOptimisationSettings
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
            Spacer(Modifier.height(6.dp))
            // The step that otherwise stops people using the app: a field asking for a
            // key, and nowhere to get one. Points at whichever provider is selected.
            Text(
                "Register your API key here",
                style = MaterialTheme.typography.bodyMedium,
                color = LainSalmon,
                modifier = Modifier
                    .clickable { openLink(context, state.provider.keyPageUrl) }
                    .padding(vertical = 4.dp)
                    .semantics {
                        contentDescription = "Open ${state.provider.displayName}'s API key page"
                    }
            )

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
            SectionLabel("Lain's voice (Fish Audio)")
            Text(
                "Fish Audio runs on their servers. Download her voice and every line she says " +
                    "without a model is saved here, so it works with no signal. Model replies " +
                    "still need the network.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(10.dp))
            PixelTextField(state.fishKey, viewModel::setFishKey, "Fish Audio API key")
            Spacer(Modifier.height(8.dp))
            PixelTextField(state.fishVoiceId, viewModel::setFishVoiceId, "Voice ID (blank = default)")
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick a voice at fish.audio and paste its ID. Leave blank for the default. " +
                    "Something low and flat suits her.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )

            val download = state.voiceDownload
            Spacer(Modifier.height(10.dp))
            if (download != null) {
                Text(
                    "Downloading her voice — ${download.done} of ${download.total}" +
                        if (download.failed > 0) ", ${download.failed} failed" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LainCream
                )
                Spacer(Modifier.height(8.dp))
                PixelButton(text = "Stop", onClick = viewModel::cancelVoiceDownload)
            } else {
                Text(
                    if (state.voiceLinesHeld > 0) {
                        "${state.voiceLinesHeld} lines held, ${state.voiceBytesHeld / 1024} KB."
                    } else {
                        "Nothing downloaded yet — she'll use the phone's voice offline."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.voiceLinesHeld > 0) LainCream else LainMuted
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PixelButton(
                        text = "Download her voice",
                        onClick = viewModel::downloadVoice,
                        // Nothing to authenticate with is nothing to download.
                        enabled = state.fishKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    )
                    if (state.voiceLinesHeld > 0) {
                        PixelButton(
                            text = "Delete",
                            onClick = viewModel::clearVoice,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

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
                        // Editing in place rather than delete-and-retype: a wrong fact is
                        // usually wrong in one detail, and retyping it loses when Lain
                        // learned it and which conversation it came from.
                        var editing by remember(m.id) { mutableStateOf(false) }
                        var draft by remember(m.id) { mutableStateOf(m.fact) }

                        if (editing) {
                            Column {
                                PixelTextField(draft, { draft = it }, "What Lain should remember")
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "Save",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = LainSalmon,
                                        modifier = Modifier.clickable {
                                            if (draft.isNotBlank()) viewModel.editMemory(m.id, draft)
                                            editing = false
                                        }.padding(vertical = 4.dp)
                                    )
                                    Text(
                                        "Cancel",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = LainMuted,
                                        modifier = Modifier.clickable {
                                            draft = m.fact
                                            editing = false
                                        }.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        } else {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    "[${m.category.lowercase()}] ${m.fact}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LainCream,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "Edit",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LainSalmon,
                                    modifier = Modifier
                                        .clickable { draft = m.fact; editing = true }
                                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
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
            SectionLabel("Notification access")
            val notificationsOn = LainNotificationListener.isEnabledInSettings(context)
            Text(
                if (notificationsOn) {
                    "Granted — Lain can tell you what's in your notification shade."
                } else {
                    "Off. Without it Lain can't answer \"what did I miss\". Android only allows this to be " +
                        "granted from its own settings screen, and nothing is stored or sent anywhere — " +
                        "Lain reads the shade live when you ask, and not otherwise."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (notificationsOn) LainCream else LainMuted
            )
            Spacer(Modifier.height(8.dp))
            PixelButton(
                text = if (notificationsOn) "Manage notification access" else "Grant notification access",
                onClick = { LainNotificationListener.openSettings(context) }
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("Privacy and terms")
            // Reachable after setup, not only at it. A policy you agreed to once and
            // can never find again is not much of a policy.
            var showingLegal by remember { mutableStateOf<String?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelButton(
                    text = "Privacy policy",
                    onClick = {
                        showingLegal = if (showingLegal == "privacy") null else "privacy"
                    },
                    modifier = Modifier.weight(1f)
                )
                PixelButton(
                    text = "Terms",
                    onClick = { showingLegal = if (showingLegal == "terms") null else "terms" },
                    modifier = Modifier.weight(1f)
                )
            }
            showingLegal?.let { which ->
                Spacer(Modifier.height(10.dp))
                Text(
                    if (which == "privacy") com.lain.assistant.legal.LegalText.PRIVACY
                    else com.lain.assistant.legal.LegalText.TERMS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LainMuted
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Channels")
            Text(
                "Reddit and Discord let her post with your own credentials. Instagram, Facebook, " +
                    "X and Threads don't — they need a business account and app review, so no key " +
                    "will unlock them.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
            Spacer(Modifier.height(10.dp))
            Text("Discord — a channel webhook is the easy way, no bot needed.",
                style = MaterialTheme.typography.bodyMedium, color = LainCream)
            Spacer(Modifier.height(6.dp))
            PixelTextField(
                state.discordWebhook,
                viewModel::setDiscordWebhook,
                "Discord webhook URL"
            )
            Spacer(Modifier.height(12.dp))
            Text("Reddit — a \"script\" app at reddit.com/prefs/apps.",
                style = MaterialTheme.typography.bodyMedium, color = LainCream)
            Spacer(Modifier.height(6.dp))
            PixelTextField(state.redditClientId, viewModel::setRedditClientId, "Reddit client ID")
            Spacer(Modifier.height(6.dp))
            PixelTextField(state.redditSecret, viewModel::setRedditSecret, "Reddit client secret")
            Spacer(Modifier.height(6.dp))
            PixelTextField(state.redditUser, viewModel::setRedditUser, "Reddit username")
            Spacer(Modifier.height(6.dp))
            PixelTextField(state.redditPassword, viewModel::setRedditPassword, "Reddit password")
            Spacer(Modifier.height(6.dp))
            Text(
                "Encrypted on your phone, sent only to Reddit. Every post is confirmed first.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )

            Spacer(Modifier.height(24.dp))
            SectionLabel("The assistant key")
            // Read from the system every time this screen appears, never remembered.
            // The user can change their assistant in Settings without coming back
            // here, and a cached "you're the assistant" would then be a lie.
            var isAssistant by remember { mutableStateOf(AssistantRole.isLain(context)) }
            LaunchedEffect(Unit) { isAssistant = AssistantRole.isLain(context) }

            Text(
                if (isAssistant) {
                    "She's your assistant. Hold the power button and she opens listening."
                } else {
                    "Only you can choose your assistant — an app can't pick itself. Lain's in the " +
                        "list; select her there."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAssistant) LainCream else LainMuted
            )
            if (!isAssistant) {
                Spacer(Modifier.height(8.dp))
                PixelButton(
                    text = "Open the assistant picker",
                    onClick = { AssistantRole.openPicker(context) }
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Alarms and Do Not Disturb")
            // Two grants Lain can't give herself. Both are offered, neither is
            // requested silently, and the current state is read from the system
            // rather than remembered — a permission revoked outside the app has to
            // show as revoked here.
            val scheduler = remember { Scheduler(context) }
            val toggles = remember { QuickToggles(context) }
            var exactAlarms by remember { mutableStateOf(scheduler.canScheduleExact()) }
            var dndAccess by remember { mutableStateOf(toggles.hasDndAccess()) }
            LaunchedEffect(Unit) {
                exactAlarms = scheduler.canScheduleExact()
                dndAccess = toggles.hasDndAccess()
            }

            Text(
                if (exactAlarms) {
                    "Alarms fire on the minute."
                } else {
                    "Android is batching her alarms to save power, so they can be minutes late."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (exactAlarms) LainCream else LainMuted
            )
            if (!exactAlarms) {
                Spacer(Modifier.height(8.dp))
                PixelButton(
                    text = "Allow exact alarms",
                    onClick = { scheduler.openExactAlarmSettings() }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (dndAccess) {
                    "She can switch Do Not Disturb without leaving the app."
                } else {
                    "Do Not Disturb needs notification-policy access. Without it she'll tell you " +
                        "rather than pretend."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (dndAccess) LainCream else LainMuted
            )
            if (!dndAccess) {
                Spacer(Modifier.height(8.dp))
                PixelButton(
                    text = "Allow Do Not Disturb access",
                    onClick = { toggles.openDndAccessSettings() }
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Accessibility Service")
            // Four states, not two. "On in Settings but not connected" is its own
            // problem with its own fix, and calling it "not enabled" is what sent
            // people looking for a switch that was already flipped.
            val a11yState by AccessibilityMonitor.state.collectAsState()
            LaunchedEffect(Unit) { AccessibilityMonitor.reconcile(context) }

            Text(
                AccessibilityMonitor.advice(),
                style = MaterialTheme.typography.bodyMedium,
                color = when (a11yState) {
                    AccessibilityState.CONNECTED -> LainCream
                    AccessibilityState.CONNECTING -> LainSalmon
                    else -> LainMuted
                }
            )
            Spacer(Modifier.height(8.dp))
            PixelButton(
                text = when (a11yState) {
                    AccessibilityState.CONNECTING -> "Reconnect (off, then on)"
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

            // Aggressive OEM battery managers (Xiaomi, Huawei, Samsung, Oppo) kill the
            // host process, which takes the Accessibility Service with it. That is the
            // most common cause of it "randomly" disconnecting, and the only fix is a
            // user-granted exemption — offered here, never requested silently.
            if (!isIgnoringBatteryOptimisations(context)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Android is allowed to shut Lain down in the background on this device, which also " +
                        "disconnects the Accessibility Service. If it keeps dropping, exempting Lain from " +
                        "battery optimisation usually stops it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LainMuted
                )
                Spacer(Modifier.height(8.dp))
                PixelButton(
                    text = "Battery optimisation settings",
                    onClick = { openBatteryOptimisationSettings(context) }
                )
            }

            Spacer(Modifier.height(12.dp))
            var showLog by remember { mutableStateOf(false) }
            Text(
                if (showLog) "Hide diagnostic log" else "Show diagnostic log",
                style = MaterialTheme.typography.labelLarge,
                color = LainSalmon,
                modifier = Modifier.clickable { showLog = !showLog }.padding(vertical = 4.dp)
            )
            if (showLog) {
                val log = remember(showLog) { AccessibilityMonitor.dump() }
                Spacer(Modifier.height(6.dp))
                Text(
                    log,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LainMuted,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                PixelButton(
                    text = "Copy log",
                    onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Lain accessibility log", log))
                    }
                )
            }

            Spacer(Modifier.height(28.dp))
            PixelButton(text = if (state.justSaved) "Saved" else "Save", onClick = viewModel::save, enabled = state.loaded && state.canSave)
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * Opens a link in the browser.
 *
 * Wrapped rather than called inline because a phone with no browser at all is a
 * real thing — a locked-down work device, a stripped ROM — and a crash there would
 * be a worse outcome than a link that quietly does nothing.
 */
private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LainSalmon)
    Spacer(Modifier.height(8.dp))
}
