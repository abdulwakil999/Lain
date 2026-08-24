package com.lain.assistant.ui.common

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainSalmon
import java.io.File

/**
 * The attach button, and the three ways in.
 *
 * GetContent with a wildcard MIME filter is what "all file types" actually means
 * on Android — every picker the user has, not a curated list. Photos get their own
 * entry because it is by far the commonest case and the system photo picker is
 * quicker and needs no storage permission at all.
 *
 * The camera path writes through a FileProvider: handing another app a bare
 * `file://` URI throws FileUriExposedException from Android 7, and the camera can't
 * write anywhere it doesn't own.
 */
@Composable
fun AttachButton(
    enabled: Boolean,
    onAttach: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    val pickAnyFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onAttach) }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onAttach) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        // Only keep it if the camera reported success — a cancelled capture leaves an
        // empty file behind, and attaching that would look like a broken photo.
        if (saved) pendingCapture?.let(onAttach)
        pendingCapture = null
    }

    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = newCaptureUri(context)
            pendingCapture = uri
            takePhoto.launch(uri)
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (enabled) LainSalmon.copy(alpha = 0.85f) else LainSalmon.copy(alpha = 0.4f))
                .clickable(enabled = enabled) { menuOpen = true }
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = "Attach a photo or a file" }
        ) {
            Text("+", color = LainInk, style = MaterialTheme.typography.labelLarge)
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(LainNavy)
        ) {
            AttachOption("Take a photo") {
                menuOpen = false
                // Asked at the point of use rather than up front, so the reason for
                // the prompt is obvious.
                requestCamera.launch(android.Manifest.permission.CAMERA)
            }
            AttachOption("Photo or video") {
                menuOpen = false
                pickPhoto.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            }
            AttachOption("Any file") {
                menuOpen = false
                pickAnyFile.launch("*/*")
            }
        }
    }
}

@Composable
private fun AttachOption(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, color = LainCream) },
        onClick = onClick
    )
}

/** A fresh file in the cache for the camera to write into. */
private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
