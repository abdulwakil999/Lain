package com.lain.assistant.ui.onboarding

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lain.assistant.data.Gender
import com.lain.assistant.data.Provider
import com.lain.assistant.ui.common.HoveringPanel
import com.lain.assistant.ui.common.PixelBackground
import com.lain.assistant.automation.AccessibilityMonitor
import com.lain.assistant.automation.AccessibilityState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.common.PixelChoiceChip
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.common.rememberLainWindow
import com.lain.assistant.ui.common.openAccessibilitySettingsForLain
import com.lain.assistant.ui.common.openAppInfo
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainMuted
import androidx.compose.ui.platform.LocalContext

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.complete) {
        if (state.complete) onFinished()
    }

    val window = rememberLainWindow()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The panel hangs off the base of the portrait, so it's anchored to the same
        // measured art size the background draws (LainWindow.artSize) rather than to the
        // raw window width — which drifted mid-face on wide devices and past the image on
        // short ones.
        val panelTopOffset = window.artSize * 0.62f

        // The scrolling area has to be capped, not left to size itself.
        //
        // Unbounded, a long step (the model picker can list twenty-odd free models)
        // grew the panel taller than the screen. The panel is bottom-anchored, so the
        // overflow went off *both* ends: the first chip was clipped at the top and
        // Continue disappeared off the bottom, leaving the step with no visible way
        // forward.
        //
        // Capping it to whatever is left after the title, spacing and button means the
        // button is always on screen and the list scrolls inside its own area.
        // Computed here because BoxWithConstraints' scope is out of reach inside the
        // panel's content lambda.
        // HoveringPanel applies navigationBarsPadding to itself, so that inset has to
        // come out of the budget too — otherwise the panel is exactly one nav bar taller
        // than the space it has, which is enough to push Continue back off the edge.
        val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val panel = OnboardingLayout.measure(
            screenHeight = maxHeight,
            preferredTopOffset = panelTopOffset,
            systemBottom = systemBottom
        )

        PixelBackground(artSize = window.artSize)
        HoveringPanel(
            modifier = Modifier.padding(top = panel.topOffset),
            maxWidth = window.contentMaxWidth,
            horizontalPadding = window.gutter
        ) {
            Text(
                text = questionFor(state.step),
                style = MaterialTheme.typography.headlineMedium,
                color = LainCream
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .heightIn(max = panel.scrollHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                when (state.step) {
                    OnboardingStep.NAME -> PixelTextField(state.name, viewModel::setName, "Your name")
                    OnboardingStep.AGE -> PixelTextField(state.age, viewModel::setAge, "Your age", keyboardType = KeyboardType.Number)
                    OnboardingStep.GENDER -> GenderChoice(state.gender, viewModel::setGender)
                    OnboardingStep.NICKNAME -> PixelTextField(state.nickname, viewModel::setNickname, "What should Lain call you?")
                    OnboardingStep.PROVIDER -> ProviderChoice(state.provider, viewModel::setProvider)
                    OnboardingStep.MODEL -> ModelChoice(state.availableModels, state.isLoadingModels, state.modelId, viewModel::setModelId)
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

/**
 * The panel's height arithmetic, kept out of the composable so it can be checked
 * against real device sizes rather than only in the emulator.
 *
 * Worth extracting because getting it wrong is invisible until it isn't: the
 * first version of this ignored the navigation-bar inset, which made the panel
 * exactly one nav bar taller than its space and put Continue back off the screen
 * on precisely the devices that have one.
 */
internal object OnboardingLayout {

    /**
     * Vertical space the panel needs around its scrolling content: the question,
     * the spacing either side, the Continue button and the panel's own padding.
     * If any of those change size, this is the number to revisit.
     */
    val PANEL_CHROME_HEIGHT = 150.dp

    /** Below this the list is too cramped to be worth scrolling inside. */
    val MIN_SCROLL_HEIGHT = 120.dp

    /** The panel never rides higher than this, so it can't cover the whole portrait. */
    val MIN_TOP_OFFSET = 24.dp

    /**
     * Where the panel sits and how much of it may scroll.
     *
     * @param topOffset where the panel would ideally start — under the portrait's
     *   base. Reduced when the screen is too short to honour it.
     * @param scrollHeight how tall the scrolling content may grow before it starts
     *   scrolling instead.
     */
    data class Measurements(val topOffset: Dp, val scrollHeight: Dp)

    /**
     * Fits the panel to the screen, preferring to slide it up over the artwork
     * rather than let it grow off the bottom.
     *
     * The ordering matters. Continue being reachable is non-negotiable — losing it
     * strands the user on the step — whereas the portrait being partly covered is
     * merely a shame. So when a short window (landscape, split screen) can't hold
     * the ideal layout, the panel takes space from the picture, and only once it
     * has run out of picture does the list get squeezed below its comfortable
     * minimum.
     *
     * @param systemBottom the navigation-bar inset, which HoveringPanel adds as
     *   padding to itself and therefore has to come out of the budget here.
     */
    fun measure(screenHeight: Dp, preferredTopOffset: Dp, systemBottom: Dp): Measurements {
        val forContent = screenHeight - systemBottom - PANEL_CHROME_HEIGHT

        // What the list would get if the panel stayed under the portrait.
        val idealScroll = forContent - preferredTopOffset
        if (idealScroll >= MIN_SCROLL_HEIGHT) {
            return Measurements(preferredTopOffset, idealScroll.coerceAtLeast(MIN_SCROLL_HEIGHT))
        }

        // Not enough room: pull the panel up over the art to buy the list back.
        val neededTop = (forContent - MIN_SCROLL_HEIGHT).coerceAtLeast(MIN_TOP_OFFSET)
        val scroll = (forContent - neededTop).coerceAtLeast(0.dp)
        return Measurements(neededTop, scroll)
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderChoice(selected: Provider, onSelect: (Provider) -> Unit) {
    // Five provider names don't fit one line on a phone; wrapping beats clipping.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Provider.entries.forEach { provider ->
            PixelChoiceChip(provider.displayName, selected == provider, { onSelect(provider) })
        }
    }
}

@Composable
private fun ModelChoice(
    models: List<com.lain.assistant.data.ModelInfo>,
    isLoading: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isLoading) {
            Text("Checking what's actually free right now…", style = MaterialTheme.typography.bodyMedium, color = LainMuted)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            "★ = reliably handles multi-step phone tasks. Free models often stall or just describe actions instead of doing them.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainMuted
        )
        Spacer(Modifier.height(4.dp))
        models.forEach { model ->
            val badge = when {
                model.strongAtTools -> "  ★"
                model.isFree -> "  ·  FREE"
                else -> ""
            }
            PixelChoiceChip(
                text = model.label + badge,
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
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Android 13 gates sideloaded apps out of Accessibility until the user clears
    // "restricted settings" from App Info. Before 13 there is no gate at all, so
    // showing that step would be sending people to a screen with nothing to do on it.
    val gated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val a11yState by AccessibilityMonitor.state.collectAsState()
    val connected = a11yState == AccessibilityState.CONNECTED

    // Whether the user has been to App Info. Not a claim that the gate is cleared —
    // nothing readable tells us that — just that they've been shown where it is, so
    // step two can stop being the greyed-out one.
    var visitedAppInfo by remember { mutableStateOf(false) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                AccessibilityMonitor.reconcile(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        AccessibilityMonitor.reconcile(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.foundation.layout.Column {
        Text(
            "Lain will ask for a few permissions next — microphone, camera, phone, SMS — so she can act on " +
                "your phone rather than just talk about it. Grant them now or later; anything that needs one " +
                "asks again when you use it.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainCream
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Then her Accessibility Service, which is how she reads and taps your screen — and what makes her " +
                "usable hands-free and eyes-free.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainCream
        )

        if (connected) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Accessibility is already on. Nothing else to do.",
                style = MaterialTheme.typography.bodyMedium,
                color = LainSalmon
            )
            return@Column
        }

        if (gated) {
            // Two numbered steps in the order they have to happen. Presented as one
            // route rather than a button plus a footnote, because the footnote was
            // the step people actually needed first: on Android 13+ the Accessibility
            // toggle is greyed out until restricted settings are cleared, so anyone
            // who pressed the obvious button landed on a switch they could not move.
            Spacer(Modifier.height(16.dp))
            OnboardingStepRow(
                number = "1",
                title = "Allow restricted settings",
                body = "Android blocks sideloaded apps from Accessibility until you clear this. In app info, " +
                    "tap ⋮ in the top corner, then \"Allow restricted settings\". No Intent can do it for you.",
                done = visitedAppInfo,
                button = "Open app info",
                emphasised = true,
                onClick = {
                    visitedAppInfo = true
                    openAppInfo(context)
                }
            )
            Spacer(Modifier.height(14.dp))
            OnboardingStepRow(
                number = "2",
                title = "Turn on Lain's switch",
                body = "Takes you straight to Lain in Accessibility settings. If her toggle is still greyed " +
                    "out, step 1 hasn't been cleared yet.",
                done = false,
                button = "Open accessibility settings",
                emphasised = visitedAppInfo,
                onClick = { openAccessibilitySettingsForLain(context) }
            )
        } else {
            Spacer(Modifier.height(12.dp))
            PixelButton(
                text = "Turn on Accessibility",
                onClick = { openAccessibilitySettingsForLain(context) }
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Both are optional — Lain works without them, she just can't see or touch the screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainMuted
        )
    }
}

/**
 * One numbered step.
 *
 * [emphasised] is what carries the ordering: the step that isn't the user's next
 * move is drawn quietly rather than disabled, because a disabled button on a
 * screen full of optional setup reads as "broken" rather than "later".
 */
@Composable
private fun OnboardingStepRow(
    number: String,
    title: String,
    body: String,
    done: Boolean,
    button: String,
    emphasised: Boolean,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Column {
        Text(
            "$number. $title" + if (done) "  ✓" else "",
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasised) LainSalmon else LainCream
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = LainMuted
        )
        Spacer(Modifier.height(8.dp))
        PixelButton(text = button, onClick = onClick)
    }
}
