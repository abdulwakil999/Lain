package com.lain.assistant.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lain.assistant.automation.AccessibilityMonitor
import com.lain.assistant.automation.AccessibilityState
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainSalmon

private val corePermissions: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.CALL_PHONE)
    // Declared in the manifest but never requested, which meant every call-state
    // read returned IDLE and every successful call was reported as having failed.
    // Only used to answer "did that call actually connect".
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_CONTACTS)
    // Coarse only, and only so "where am I" can be answered. Lain reads the last
    // known area and never transmits it.
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    // Read only, in the launch prompt. Writing to the diary is asked for at the
    // moment something is being written, because it is a far bigger thing to grant
    // than reading and most people never need it.
    add(Manifest.permission.READ_CALENDAR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * Remembers which permissions have already been put in front of the user.
 *
 * Deliberately its own tiny SharedPreferences rather than the DataStore the rest of
 * the app uses: this is read during composition of the first frame, and DataStore's
 * read is a suspending flow, so the dialog would fire before the answer arrived —
 * which is exactly the bug this exists to fix.
 */
private const val PERMISSION_PREFS = "lain_permission_prompts"
private const val KEY_ASKED = "asked"

private fun askedBefore(context: Context): Set<String> =
    context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY_ASKED, emptySet()) ?: emptySet()

private fun rememberAsked(context: Context, permissions: Collection<String>) {
    val prefs = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    val merged = (prefs.getStringSet(KEY_ASKED, emptySet()) ?: emptySet()) + permissions
    prefs.edit().putStringSet(KEY_ASKED, merged).apply()
}

/**
 * Asks for the runtime permissions Lain's fast paths need — once each, ever.
 *
 * "Once" used to mean once per composition, held in [remember], so every cold start
 * re-opened the system dialog for anything the user had declined. Location was the
 * one people noticed: a permission wanted for exactly one question ("where am I")
 * was demanded on every launch. The grant set is now persisted across process death,
 * and a permission is only raised if it has never been raised before.
 *
 * Recording the set rather than a single flag keeps a later version's *new*
 * permission askable without re-nagging for the ones already refused.
 */
@Composable
fun RequestCorePermissionsOnce() {
    val context = LocalContext.current
    var handled by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (handled) return@LaunchedEffect
        handled = true
        val alreadyAsked = askedBefore(context)
        val pending = corePermissions.filter { permission ->
            permission !in alreadyAsked &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        // Nothing left to ask is the steady state after the first launch; still record
        // the granted ones so a re-grant elsewhere doesn't reopen the question here.
        rememberAsked(context, corePermissions.toList())
        if (pending.isNotEmpty()) launcher.launch(pending.toTypedArray())
    }
}

/**
 * Raises a single permission the user previously declined, from the place that needs it.
 *
 * The once-ever rule above means a refusal is final for the launch prompt, which is
 * the right default and the wrong absolute: someone who says "where am I" a month
 * later is asking for the thing location is for. Callers use this to re-offer that
 * one permission at the moment it is actually relevant. Android still caps repeat
 * prompts — a twice-denied permission shows no dialog at all — so a caller must
 * treat a silent result as "still denied" and say so rather than assume a grant.
 */
fun clearPermissionPromptMemory(context: Context, permission: String) {
    val prefs = context.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    val remaining = (prefs.getStringSet(KEY_ASKED, emptySet()) ?: emptySet()) - permission
    prefs.edit().putStringSet(KEY_ASKED, remaining).apply()
}

/**
 * Opens Lain's own entry in Accessibility settings, not the app-data page.
 *
 * Settings supports deep-linking to a specific service via the (undocumented but
 * long-stable, AOSP-wide) `:settings:fragment_args_key` extra carrying the
 * flattened component name; where an OEM honours it the user lands directly on
 * Lain's on/off screen, and where it doesn't they land on the Accessibility list
 * with Lain highlighted. Either way it's the screen with the toggle on it.
 *
 * Sending people to App Info was a workaround for Android 13+'s "restricted
 * settings" gate, which only bites on a fresh sideload and only until it's
 * cleared once. Making every user walk through app data forever to fix a
 * momentary service stall was the wrong trade — that path is still available
 * from [openAppInfo], which the banner offers as a secondary link.
 */
fun openAccessibilitySettingsForLain(context: Context) {
    val component = ComponentName(context, LainAccessibilityService::class.java).flattenToString()
    val bundle = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) }

    val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
        putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
    }
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        openAppInfo(context)
    }
}

private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

/**
 * The App Info page. Only useful for one thing: clearing Android 13+'s
 * "restricted settings" gate on a freshly sideloaded build, which needs a manual
 * ⋮ → "Allow restricted settings" tap that no Intent can perform for the user.
 */
fun openAppInfo(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Kept so older call sites keep compiling; new code should say which page it wants. */
fun openAppInfoForAccessibility(context: Context) = openAccessibilitySettingsForLain(context)

/**
 * Whether Android has stopped applying background restrictions to Lain.
 *
 * Relevant to Accessibility specifically: when an OEM battery manager kills the
 * app's process, the Accessibility binding dies with it, and the user sees a
 * service that "randomly turns itself off". Knowing which side of that line we
 * are on is the difference between useful advice and a shrug.
 */
fun isIgnoringBatteryOptimisations(context: Context): Boolean = runCatching {
    val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}.getOrDefault(false)

/**
 * Opens the battery-optimisation *list*, where the user picks Lain themselves.
 *
 * Deliberately not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: that is the
 * one-tap "allow?" dialog, it needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * permission, and Play policy limits it to apps whose core function genuinely
 * requires it. Lain works fine without the exemption — it just drops the
 * Accessibility binding more often on aggressive OEMs — so this offers the
 * setting rather than requesting the grant.
 */
fun openBatteryOptimisationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM ROMs drop the AOSP screen and keep only their own battery UI.
        openAppInfo(context)
    }
}

/**
 * Banner shown whenever screen automation isn't currently available.
 *
 * It distinguishes "never switched on" from "switched on but the binding died",
 * because the second is the state the user kept hitting and the instruction for it
 * is different — off-and-on-again, not turn-it-on. Re-checks on every resume, so
 * coming back from Settings updates it without a restart.
 */
@Composable
fun AccessibilityServiceBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observed, not polled. The previous version only re-checked on ON_RESUME, so a
    // service that dropped while the app was open showed nothing until the user left
    // and came back — which is precisely the moment the user is wondering why Lain
    // stopped responding.
    val state by AccessibilityMonitor.state.collectAsState()

    // Settings can change without any callback reaching this process (the user
    // switching it off, or Android killing us), so reconcile against Settings.Secure
    // whenever the app comes forward.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AccessibilityMonitor.reconcile(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        AccessibilityMonitor.reconcile(context)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (state == AccessibilityState.CONNECTED) return
    val stalled = state == AccessibilityState.CONNECTING

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LainNavy)
            .clickable { openAccessibilitySettingsForLain(context) }
            .padding(12.dp)
    ) {
        Text(
            if (stalled) {
                "Lain's Accessibility Service is on but not connected — Android does this after an update " +
                    "or when it reclaims memory. Tap here, then switch Lain off and back on. Five seconds."
            } else {
                "Turn on Lain's Accessibility Service so she can read and tap your screen. Tap here to go " +
                    "straight to her switch."
            },
            color = LainCream,
            style = MaterialTheme.typography.bodyMedium
        )
        if (!stalled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Toggle greyed out? Open app info → ⋮ → Allow restricted settings first.",
                color = LainSalmon,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { openAppInfo(context) }.padding(vertical = 2.dp)
            )
        }
    }
}
