package com.lain.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The same mechanism screen readers like TalkBack are built on: reads the
 * on-screen node tree and dispatches synthetic taps, swipes, text entry and
 * global navigation. This is what lets Lain (a) narrate/operate the screen
 * for a blind user and (b) drive apps — WhatsApp, Spotify, Chrome, chess —
 * that expose no other automation hook.
 */
class LainAccessibilityService : AccessibilityService() {

    companion object {
        var instance: LainAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** Screen-reading caps. An unbounded tree walk on a dense app is both an ANR risk and a token bomb. */
        private const val MAX_NODES = 220
        private const val MAX_DEPTH = 28
        private const val MAX_LABEL_CHARS = 90

        /**
         * The authoritative check. [isRunning] only reflects whether our process
         * currently holds a bound service instance — after an app update, a process
         * restart, or a system-initiated service kill, the user can have Lain switched
         * ON in Settings while `instance` is momentarily null. Reporting "turn on
         * Accessibility" in that state is exactly what made Lain insist the service was
         * off while the toggle was visibly already on.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, LainAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                val parsed = ComponentName.unflattenFromString(entry) ?: continue
                if (parsed == expected) return true
            }
            return false
        }

        /** Human-readable explanation of *why* the service isn't usable right now. */
        fun unavailableReason(context: Context): String = when {
            !isEnabledInSettings(context) ->
                "Lain's Accessibility Service is switched off. Tell the user to enable it: Settings > Apps > Lain > (⋮ menu) Allow restricted settings, then Settings > Accessibility > Lain > On."
            else ->
                "Lain's Accessibility Service is enabled in Settings but isn't connected right now (this happens for a few seconds after an app update or a restart). Tell the user to toggle it off and back on in Settings > Accessibility > Lain, then retry."
        }
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

    // ---------------------------------------------------------------- reading

    /**
     * The window Lain should be operating on — which is almost never her own.
     *
     * When the bubble panel or the mini surface is showing, `rootInActiveWindow`
     * can resolve to Lain herself, so a "type this" would land in her own input
     * box instead of Chrome's search bar. This walks the window list and picks
     * the frontmost window that isn't ours, so commands always target the app
     * the user is actually looking at.
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val self = packageName?.toString()

        // The focused window is the right answer almost always — check it FIRST.
        rootInActiveWindow?.let { active ->
            val pkg = active.packageName?.toString()
            if (pkg != null && pkg != self && !isSystemChrome(pkg)) return active
        }

        // Only fall back to scanning. getWindows() is NOT in z-order, so an unsorted
        // firstOrNull{} happily returns the status bar, nav bar, wallpaper or IME —
        // which is what made Lain "read" the system chrome instead of the app and then
        // tap at nonsense coordinates. Restrict to real application windows and take
        // the frontmost by layer.
        return runCatching {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .mapNotNull { it.root }
                .firstOrNull { root ->
                    val pkg = root.packageName?.toString()
                    pkg != null && pkg != self && !isSystemChrome(pkg)
                }
        }.getOrNull()
    }

    /** System UI surfaces are never a task target — they're decoration around one. */
    private fun isSystemChrome(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg == "android" || pkg.endsWith(".inputmethod.latin")

    /** True when Lain's own UI is what's on screen, so there's no other app to drive. */
    fun isOwnUiInForeground(): Boolean {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return false
        return pkg == packageName?.toString()
    }

    /**
     * Flattens the visible screen into a compact, model-readable list. Every
     * interactive node gets a stable index the model can act on by number, which
     * is far more reliable than asking it to guess pixel coordinates.
     */
    fun readScreenText(): String {
        val root = targetRoot() ?: return if (isOwnUiInForeground()) {
            "Lain's own chat screen is what's on display — there is no other app to operate. " +
                "If the task needs an app, call open_app first. Do NOT tap or swipe: there is nothing here to tap."
        } else {
            "Can't read the screen right now. Do NOT tap or swipe blindly — wait a moment and read again."
        }
        val out = StringBuilder()
        val counter = intArrayOf(0)
        out.append("App: ${root.packageName ?: "unknown"}\n")
        collect(root, out, 0, counter)
        if (counter[0] >= MAX_NODES) out.append("… (screen truncated — this is the top of the list)\n")
        return out.toString().ifBlank { "(screen has no readable text)" }
    }

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int, counter: IntArray) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return

        val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (raw != null) {
            val label = if (raw.length > MAX_LABEL_CHARS) raw.take(MAX_LABEL_CHARS) + "…" else raw
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // Skip off-screen/zero-size nodes; they're noise the model can't act on anyway.
            if (bounds.width() > 0 && bounds.height() > 0) {
                val kind = when {
                    node.isEditable -> "INPUT"
                    node.isClickable -> "BUTTON"
                    else -> "text"
                }
                out.append("[$kind] \"${label.replace('\n', ' ')}\" @(${bounds.centerX()},${bounds.centerY()})\n")
                counter[0]++
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1, counter) }
        }
    }

    /** Finds the node whose visible label best matches [query] and returns its tap point. */
    fun findTapPointByText(query: String): Pair<Int, Int>? {
        val root = targetRoot() ?: return null
        val needle = query.trim().lowercase()
        var best: Rect? = null
        var bestScore = Int.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || best != null && bestScore == 0) return
            val label = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()
            if (!label.isNullOrBlank() && label.contains(needle)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0) {
                    // Prefer the tightest match (exact label beats a long paragraph containing it).
                    val score = label.length - needle.length
                    if (score < bestScore) {
                        bestScore = score
                        best = bounds
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best?.let { it.centerX() to it.centerY() }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Types into the focused (or first editable) field. This is the capability
     * whose absence made every "message X on WhatsApp" style task dead-end: Lain
     * could open the app and tap the box, then had no way to put words in it.
     */
    fun typeText(text: String): Boolean {
        val root = targetRoot() ?: return false
        // Focused field first, but only if it belongs to the target app — the input
        // focus can still sit in Lain's own box while the user is looking at Chrome.
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.packageName != packageName }
        val target = focused ?: findFirstEditable(root, 0) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // Some apps (WhatsApp's composer among them) ignore SET_TEXT on a node they
        // don't consider focused — focus it first, then retry once.
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findFirstEditable(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------- navigation

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

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
     * Best-effort "close app": opens Recents and swipes the top card away.
     * Card position varies by OEM launcher, so this is a heuristic, not a
     * guarantee — it's the only path a non-system app has for dismissing a
     * foreground app it doesn't own.
     */
    suspend fun closeCurrentApp() {
        openRecents()
        kotlinx.coroutines.delay(500)
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        swipe(screenWidth / 2f, screenHeight / 2f, screenWidth / 2f, screenHeight * 0.1f, durationMs = 250)
        kotlinx.coroutines.delay(200)
        goHome()
    }

    // ----------------------------------------------------------------- vision

    /**
     * A single "let me look" glance for the model, downscaled and JPEG-compressed
     * before base64 so a screenshot costs a sane number of tokens and bytes rather
     * than shipping a full-resolution panel.
     */
    suspend fun captureScreenshotBase64(maxDimension: Int = 900): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = captureScreenshotBitmap() ?: return null
        val scaled = downscale(bitmap, maxDimension)
        if (scaled !== bitmap) bitmap.recycle()
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun downscale(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
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
