package com.lain.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The same mechanism screen readers like TalkBack are built on: reads the
 * on-screen node tree and can dispatch synthetic taps/swipes. This is what
 * lets Lain (a) narrate/operate the screen for a blind user and (b) drive
 * apps — chess, Chrome, WhatsApp's Send button — that expose no other
 * automation hook.
 *
 * The system only allows one node tree read / gesture at a time and only
 * while the service is actually enabled by the user in
 * Settings > Accessibility, so every caller must null-check [instance].
 */
class LainAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LainAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Flattens the current screen's visible text/labels into one readable blob for the model. */
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return "(nothing readable on screen right now)"
        val out = StringBuilder()
        collect(root, out)
        return out.toString().ifBlank { "(screen has no readable text)" }
    }

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int = 0) {
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            out.append("- \"$label\" @ (${bounds.centerX()}, ${bounds.centerY()})\n")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1) }
        }
    }

    suspend fun tap(x: Float, y: Float): Boolean = dispatchGestureAwait {
        val path = Path().apply { moveTo(x, y) }
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean = dispatchGestureAwait {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
    }

    /**
     * A single "let me look" glance for the model — this is
     * AccessibilityService's own takeScreenshot() (API 30+), which needs
     * nothing beyond the service already being enabled, unlike
     * MediaProjection's real-time mirroring which needs a fresh consent
     * dialog every session. Returns a JPEG-encoded, base64 string ready to
     * attach to an LLM vision call, or null if unsupported/failed.
     */
    suspend fun captureScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = captureScreenshotBitmap() ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private suspend fun captureScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        )
    }

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Best-effort "close app": opens Recents and swipes the top card away.
     * Card position varies by OEM launcher, so this is a heuristic, not a
     * guarantee — it's the only path a non-system app has for dismissing a
     * foreground app it doesn't own.
     */
    suspend fun closeCurrentApp() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        kotlinx.coroutines.delay(400)
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        swipe(screenWidth / 2f, screenHeight / 2f, screenWidth / 2f, screenHeight * 0.15f)
        goHome()
    }

    private suspend fun dispatchGestureAwait(build: () -> GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(
                build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!ok && cont.isActive) cont.resume(false)
        }
}
