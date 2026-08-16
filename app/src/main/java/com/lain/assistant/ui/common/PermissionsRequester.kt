package com.lain.assistant.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainNavy

private val corePermissions: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** Fires the standard Android runtime-permission dialog for everything Lain's fast-path tools need, once. */
@Composable
fun RequestCorePermissionsOnce() {
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(corePermissions)
        }
    }
}

/**
 * Sideloaded apps (installed outside the Play Store) hit Android 13+'s
 * "Restricted settings" gate: the Accessibility toggle for a freshly
 * sideloaded app is greyed out/hidden until the user visits the app's own
 * App Info page and explicitly taps the overflow menu (⋮) → "Allow
 * restricted settings". There's no Intent that can trigger that tap for
 * them — it's a deliberate manual security step — so the most useful thing
 * this can do is land them exactly on that App Info page instead of
 * dropping them into the generic Accessibility list where the option may
 * not even be selectable yet.
 */
fun openAppInfoForAccessibility(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

/** Small dismissible banner nudging the user to enable the Accessibility Service Lain needs for on-screen automation. */
@Composable
fun AccessibilityServiceBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // isRunning is a plain var, not observable state — reading resumeTick here just forces
    // this composable to re-check it every time the user comes back from Settings.
    if (resumeTick >= 0 && LainAccessibilityService.isRunning) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LainNavy)
            .clickable { openAppInfoForAccessibility(context) }
            .padding(12.dp)
    ) {
        Text(
            "Turn on Lain's Accessibility Service so she can read and tap your screen. Tap here for Lain's app info — if you don't see \"Allow restricted settings\" in the ⋮ menu, skip that and go straight to Accessibility; if you do, tap it first, then open Accessibility and turn Lain on.",
            color = LainCream,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
