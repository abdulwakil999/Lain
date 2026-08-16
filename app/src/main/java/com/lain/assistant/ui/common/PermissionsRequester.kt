package com.lain.assistant.ui.common

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
 * Jumps as directly as possible to the toggle for Lain's own Accessibility
 * Service, instead of dropping the user into the generic list they'd have
 * to scroll through to find "Lain". The highlighted-component extras are
 * what stock/Pixel Settings uses to deep-link straight to one item's detail
 * screen; not every OEM settings app honors them, so this still falls back
 * to the plain Accessibility settings screen if nothing handles it.
 */
fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, LainAccessibilityService::class.java)
    val deepLink = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        putExtra(":settings:fragment_args_key", component.flattenToString())
        putExtra(
            ":settings:show_fragment_args",
            Bundle().apply { putString(":settings:fragment_args_key", component.flattenToString()) }
        )
    }
    try {
        context.startActivity(deepLink)
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
            .clickable { openAccessibilitySettings(context) }
            .padding(12.dp)
    ) {
        Text(
            "Turn on Lain's Accessibility Service to let her read and tap your screen — tap here to jump straight to it, then come back.",
            color = LainCream,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
