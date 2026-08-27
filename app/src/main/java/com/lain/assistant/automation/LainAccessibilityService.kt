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
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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
        @Volatile
        var instance: LainAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** Screen-reading caps. An unbounded tree walk on a dense app is both an ANR risk and a token bomb. */
        private const val MAX_NODES = 220
        private const val MAX_DEPTH = 28
        private const val MAX_LABEL_CHARS = 90

        /**
         * Interactive-only budget used for the screen that rides along with every
         * action result. The model needs to know what it can press next, not the
         * full text of the page — that's what an explicit read_screen is for.
         */
        private const val COMPACT_NODES = 28
        private const val COMPACT_PROSE = 8
        private const val COMPACT_LABEL_CHARS = 48

        /**
         * Whether the user has granted the service, regardless of whether it is
         * bound right now.
         *
         * [isRunning] only reflects a live binding in this process. After an app
         * update, a process restart, or the system reclaiming the service, the user
         * can have Lain switched ON in Settings while `instance` is momentarily
         * null — and telling them to enable something already enabled is exactly
         * what made this look broken.
         */
        fun isEnabledInSettings(context: Context): Boolean =
            AccessibilityMonitor.isEnabledInSettings(context)

        /**
         * The current state, reconciled against Settings first so a disconnect that
         * arrived without a callback (process killed) is caught.
         */
        fun currentState(context: Context): AccessibilityState {
            AccessibilityMonitor.reconcile(context)
            return AccessibilityMonitor.state.value
        }

        /** Why the service isn't usable right now, phrased for the user. */
        fun unavailableReason(context: Context): String {
            AccessibilityMonitor.reconcile(context)
            return AccessibilityMonitor.advice()
        }
    }

    // ------------------------------------------------------------- lifecycle
    //
    // Every transition is recorded, because a disconnect previously left no
    // evidence at all and there was nothing to investigate afterwards. None of
    // this tries to keep the service alive or restart it — an app cannot do that,
    // and shouldn't: the user owns this permission. It observes and reports.

    override fun onCreate() {
        super.onCreate()
        AccessibilityMonitor.onCreated()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastEventAt = SystemClock.uptimeMillis()
        AccessibilityMonitor.onConnected()
    }

    /**
     * onInterrupt means "stop announcing what you were announcing" — it does NOT
     * mean the binding is gone. Treating it as a disconnect would report a fault
     * that hasn't happened, so it is recorded and otherwise ignored.
     */
    override fun onInterrupt() {
        AccessibilityMonitor.onInterrupted()
    }

    /**
     * Fires when the system tears the binding down — a settings toggle, an app
     * update, or the framework reclaiming the service. Clearing `instance` here
     * rather than waiting for onDestroy closes the window where an action could
     * be dispatched into a service that is already going away.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AccessibilityMonitor.onUnbound()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        AccessibilityMonitor.onDestroyed()
        super.onDestroy()
    }

    // -------------------------------------------------------------- settling

    /**
     * When the UI last changed. Written from the (now narrow) event stream and read
     * by [awaitSettle]. A plain volatile long is the entire mechanism — no handler,
     * no allocation, so subscribing to these events costs effectively nothing.
     */
    @Volatile
    private var lastEventAt: Long = 0L

    /**
     * The entire event handler: one volatile write.
     *
     * This runs on the service's main thread for every window change on the
     * device, so anything expensive here would slow the whole system down. It
     * records only when the UI last changed, which is what [awaitSettle] reads —
     * no allocation, no I/O, no parsing.
     *
     * It also serves as a liveness signal: events only arrive on a bound service,
     * so a stale `instance` gets corrected here. That correction is recorded
     * rather than silent, because if it ever fires it means a lifecycle callback
     * was missed and that is worth knowing about.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
        if (instance !== this) {
            instance = this
            AccessibilityMonitor.record(
                AccessibilityMonitor.Event.SERVICE_RESTARTED,
                "recovered a stale instance from the event stream"
            )
        }
    }

    /**
     * Waits until the screen stops changing, then returns.
     *
     * This replaced the fixed sleeps the tool loop used to run on — 1.6s after every
     * app launch, 700ms after every tap — which were sized for the worst case and
     * therefore paid on every single step. A launcher icon that resolves in 180ms now
     * costs 180ms. The [maxWait] ceiling keeps a genuinely slow screen from hanging
     * the loop, and [quietPeriod] is how long the tree must hold still to count as done.
     *
     * @return true if the screen settled, false if we gave up at [maxWait].
     */
    suspend fun awaitSettle(maxWait: Long = 2500L, quietPeriod: Long = 180L): Boolean {
        val deadline = SystemClock.uptimeMillis() + maxWait
        // Anything before this call is history; only changes caused by the action count.
        lastEventAt = SystemClock.uptimeMillis()
        var polls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            // 35ms rather than 60. A settle is paid after every tap, type and launch —
            // often a dozen times in one task — so the poll interval and the minimum
            // poll count are both pure per-step latency. Two polls is still enough to
            // stop an action that hasn't begun rendering being mistaken for one that
            // already finished, and the floor drops from 180ms to 70ms.
            delay(35)
            polls++
            val quietFor = SystemClock.uptimeMillis() - lastEventAt
            if (quietFor >= quietPeriod && polls >= 2) return true
        }
        return false
    }

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

    /**
     * Package and human label of the app currently in front (excluding Lain).
     * Lets the agent confirm an app actually opened instead of assuming it did.
     */
    fun foregroundApp(): Pair<String, String>? {
        val pkg = targetRoot()?.packageName?.toString() ?: return null
        val label = runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        return pkg to label
    }

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
    fun readScreenText(): String = render(compact = false)

    /**
     * The version that rides along with an action result: interactive elements
     * only, short labels, hard node cap.
     *
     * Every tap used to return the full screen dump, so a ten-step task re-sent
     * ten dense screens through the model — the single biggest contributor to a
     * simple "message someone" job taking minutes. What the model needs after a
     * tap is what it can press next; the prose is available on request.
     */
    fun readScreenCompact(): String = render(compact = true)

    private fun render(compact: Boolean): String {
        val root = targetRoot() ?: return if (isOwnUiInForeground()) {
            "Lain's own chat screen is what's on display — there is no other app to operate. " +
                "If the task needs an app, call open_app first. Do NOT tap or swipe: there is nothing here to tap."
        } else {
            "Can't read the screen right now. Do NOT tap or swipe blindly — wait a moment and read again."
        }

        val labelCap = if (compact) COMPACT_LABEL_CHARS else MAX_LABEL_CHARS
        // Always walk the same budget, and let the *selection* differ. Capping the walk
        // itself in compact mode starves the useful half: on a WhatsApp thread the first
        // 28 nodes are all message text, so the composer and the send button — the only
        // two things the model actually needs — would never be reached.
        val collected = mutableListOf<Element>()
        collect(root, collected, 0, MAX_NODES, labelCap, compact)

        val out = StringBuilder()
        out.append("App: ${root.packageName ?: "unknown"}\n")
        if (compact) {
            // Interactive first, and it is what survives truncation. Prose is context;
            // buttons are what the next step depends on.
            val interactive = collected.filter { it.kind != "text" }.take(COMPACT_NODES)
            val prose = collected.filter { it.kind == "text" }
            if (interactive.isEmpty() && prose.isEmpty()) {
                // Empty is not the same as stuck, and saying only "nothing readable"
                // made it look like one. Chrome's omnibox is the case that exposed it:
                // once focused, its dropdown exposes no nodes the compact filter keeps,
                // so the screen reads blank — while a text field is sitting there
                // focused and ready. type_text targets the focused node directly and
                // needs none of this, so the reply now says so instead of leaving the
                // model to conclude it is blocked and ask the user what to do.
                out.append("(no readable controls on this screen)\n")
                if (hasEditableField()) {
                    out.append(
                        "A text field IS focused and ready. Call type_text now — it types into the " +
                            "focused field directly and does not need anything listed here.\n"
                    )
                } else {
                    out.append("Nothing to type into either. Wait briefly and read again.\n")
                }
            }
            interactive.forEach { out.append(it.line()).append('\n') }
            prose.take(COMPACT_PROSE).forEach { out.append(it.line()).append('\n') }
            if (prose.size > COMPACT_PROSE) {
                out.append("… (${prose.size - COMPACT_PROSE} more text elements — call read_screen for all of them)\n")
            }
        } else {
            collected.forEach { out.append(it.line()).append('\n') }
            if (collected.size >= MAX_NODES) out.append("… (screen truncated — this is the top of the list)\n")
        }
        return out.toString()
    }

    private class Element(val kind: String, val label: String, val x: Int, val y: Int) {
        fun line(): String = "[$kind] \"$label\" @($x,$y)"
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableList<Element>,
        depth: Int,
        nodeCap: Int,
        labelCap: Int,
        compact: Boolean
    ) {
        if (out.size >= nodeCap || depth > MAX_DEPTH) return

        val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (raw != null) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            // Skip off-screen/zero-size nodes; they're noise the model can't act on anyway.
            if (bounds.width() > 0 && bounds.height() > 0) {
                val clickable = node.isClickable || node.parent?.isClickable == true
                val kind = when {
                    node.isEditable -> "INPUT"
                    clickable -> "BUTTON"
                    else -> "text"
                }
                // flagIncludeNotImportantViews widened the tree so real composers stay
                // visible; the cost is decorative nodes, dropped here rather than shipped.
                val keep = !compact || kind != "text" || raw.length in 2..labelCap * 2
                if (keep) {
                    val flat = raw.replace('\n', ' ').trim()
                    val label = if (flat.length > labelCap) flat.take(labelCap) + "…" else flat
                    // Two nodes with the same label at the same spot is one control seen twice.
                    val duplicate = out.any { it.label == label && kotlin.math.abs(it.y - bounds.centerY()) < 8 }
                    if (!duplicate) out += Element(kind, label, bounds.centerX(), bounds.centerY())
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1, nodeCap, labelCap, compact) }
        }
    }

    /** Finds the node whose visible label best matches [query] and returns its tap point. */
    fun findTapPointByText(query: String): Pair<Int, Int>? = findNodeByText(query)?.let { node ->
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        bounds.centerX() to bounds.centerY()
    }

    /**
     * Label matching, best-match-wins. Exact beats prefix beats contains, so
     * tap_text("Send") lands on the send button rather than on a message that
     * happens to contain the word.
     */
    private fun findNodeByText(query: String): AccessibilityNodeInfo? {
        val root = targetRoot() ?: return null
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || bestScore == 0) return
            val label = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.lowercase()
            if (!label.isNullOrBlank() && label.contains(needle)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0) {
                    // Tightest match wins; a clickable node beats a static label of equal fit.
                    var score = label.length - needle.length
                    if (!node.isClickable && node.parent?.isClickable != true) score += 2
                    if (score < bestScore) {
                        bestScore = score
                        best = node
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best
    }

    /**
     * Taps a labelled element by performing its accessibility click when the node
     * supports one, and only falling back to a synthetic gesture otherwise.
     *
     * A real ACTION_CLICK is both faster and far more reliable than dispatching a
     * touch at the node's centre — a centre point can land on a sibling that
     * overlaps, or on a spot the app treats as scroll rather than press.
     */
    suspend fun tapByText(query: String): Boolean {
        val node = findNodeByText(query) ?: return false
        val clickTarget = generateSequence(node) { it.parent }.take(4).firstOrNull { it.isClickable }
        if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    // ---------------------------------------------------------------- writing

    /**
     * Types into the focused (or first editable) field. This is the capability
     * whose absence made every "message X on WhatsApp" style task dead-end: Lain
     * could open the app and tap the box, then had no way to put words in it.
     */
    fun typeText(text: String): Boolean {
        val target = editableTarget() ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // Some apps (WhatsApp's composer among them) ignore SET_TEXT on a node they
        // don't consider focused — focus it first, then retry once.
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun editableTarget(): AccessibilityNodeInfo? {
        val root = targetRoot() ?: return null
        // Focused field first, but only if it belongs to the target app — the input
        // focus can still sit in Lain's own box while the user is looking at Chrome.
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.packageName != packageName }
        return focused ?: findFirstEditable(root, 0)
    }

    /**
     * Taps a search control that carries no visible label.
     *
     * Most apps put search behind a magnifying-glass icon whose only identification is
     * a content description or a view id — neither of which tapByText finds, because
     * there is no text on screen to match. Without this, driving search in anything
     * other than a browser fails at the first step.
     */
    suspend fun tapSearchAffordance(): Boolean {
        val root = targetRoot() ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectSearchCandidates(root, candidates, 0)
        for (node in candidates) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                awaitSettle(maxWait = 1200L, quietPeriod = 150L)
                return true
            }
            // Some icons are decorative children of the real clickable container.
            var parent = node.parent
            var hops = 0
            while (parent != null && hops < 3) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    awaitSettle(maxWait = 1200L, quietPeriod = 150L)
                    return true
                }
                parent = parent.parent
                hops++
            }
        }
        return false
    }

    private fun collectSearchCandidates(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH || into.size >= 8) return
        val description = node.contentDescription?.toString()?.lowercase().orEmpty()
        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("").lowercase()
        if (description.contains("search") || viewId.contains("search")) into += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSearchCandidates(it, into, depth + 1) }
        }
    }

    /** True when the current screen has somewhere to type — lets a composite action verify before it acts. */
    fun hasEditableField(): Boolean = editableTarget() != null

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

    /**
     * Submits the focused field the way the keyboard's action key would.
     *
     * Hunting for a button labelled "Send"/"Go" works on some screens and not on
     * others; ACTION_IME_ENTER is the platform's own answer and needs no label.
     */
    fun pressImeAction(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val target = editableTarget() ?: return false
        return target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ------------------------------------------------------------- navigation

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Locks the screen. Android 9+; below that no app may do it. */
    fun lockScreen(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /**
     * Raises the system power menu.
     *
     * As far as any normal app can go towards "restart" or "shut down": there is no
     * API for either, and there shouldn't be. This puts the real menu up so the
     * choice stays a deliberate press by the person holding the phone.
     */
    fun showPowerMenu(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    /**
     * Opens the Quick Settings panel — the tiles, not just the notification shade.
     *
     * This is the only route left to Wi-Fi, Bluetooth and the rest: Android removed
     * programmatic control from ordinary apps, but the user's own Accessibility
     * Service may press the user's own tiles, which is what a screen reader does.
     *
     * GLOBAL_ACTION_QUICK_SETTINGS goes straight there. Below Android 11 it does not
     * exist, and pulling the shade twice is the long-standing equivalent — the first
     * opens notifications, the second expands to the tiles.
     */
    suspend fun openQuickSettings(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
                awaitSettle(maxWait = 1200L, quietPeriod = 150L)
                return true
            }
        }
        val opened = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        awaitSettle(maxWait = 900L, quietPeriod = 120L)
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        awaitSettle(maxWait = 900L, quietPeriod = 120L)
        return opened
    }
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
        awaitSettle(maxWait = 1200L)
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        swipe(screenWidth / 2f, screenHeight / 2f, screenWidth / 2f, screenHeight * 0.1f, durationMs = 250)
        awaitSettle(maxWait = 900L)
        goHome()
    }

    /**
     * Clears the recents list — the closest thing to "close everything".
     *
     * Android gives a normal app no way to enumerate, let alone stop, what other
     * people's apps are doing; `getRunningAppProcesses` has returned only our own
     * process since Android 5. What it does allow is the user's Accessibility Service
     * pressing the button the user would press. So this presses it.
     *
     * @return true only if a clear control was actually found and tapped.
     */
    suspend fun clearRecents(): Boolean {
        openRecents()
        awaitSettle(maxWait = 1500L)
        // Every OEM names it differently, and some put it behind an icon.
        val cleared = listOf(
            "Clear all", "Close all", "Clear All", "CLEAR ALL", "Clean up",
            "Clear", "Remove all"
        ).any { tapByText(it) }
        awaitSettle(maxWait = 1200L)
        goHome()
        return cleared
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

    /**
     * Executor for screenshot callbacks.
     *
     * Deliberately NOT the main executor, which is what this used to pass. The
     * callback copies the hardware buffer into a software bitmap — roughly 10MB on
     * a 1080x2400 screen — and doing that on the UI thread of the process that
     * hosts the Accessibility Service is a direct route to jank and, on a slower
     * device, an ANR. A single background thread is enough: screenshots are
     * sequential and infrequent.
     */
    private val screenshotExecutor: java.util.concurrent.Executor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "Lain-Screenshot").apply { isDaemon = true }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(softwareBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    AccessibilityMonitor.record(
                        AccessibilityMonitor.Event.COMMAND_REJECTED,
                        "takeScreenshot failed, code $errorCode"
                    )
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
