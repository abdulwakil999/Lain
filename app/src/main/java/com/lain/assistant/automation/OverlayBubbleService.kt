package com.lain.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lain.assistant.LainApplication
import com.lain.assistant.R
import com.lain.assistant.agent.ChatEngine
import com.lain.assistant.data.Sender
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Lain, available on top of whatever app you're in.
 *
 * Tapping the bubble expands a slim panel with a mic, a live status line and the
 * last thing said — enough to run a hands-free chain like "open chrome" ->
 * "search anime heaven" -> "open that result" without ever leaving Chrome.
 *
 * Crucially this is a *window overlay*, not an Activity, and it is
 * FLAG_NOT_FOCUSABLE: the app underneath keeps input focus, so when Lain types
 * the text lands in Chrome's search box rather than in her own UI. Launching a
 * real Activity here would put Lain in the foreground and break exactly that.
 */
class OverlayBubbleService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "lain_overlay"
        private const val NOTIFICATION_ID = 77

        var isRunning: Boolean = false
            private set

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            if (!canDrawOverlays(context)) return
            ContextCompat.startForegroundService(context, Intent(context, OverlayBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayBubbleService::class.java))
        }
    }

    private lateinit var engine: ChatEngine
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var micButton: TextView? = null
    private var expanded = false

    override fun onCreate() {
        super.onCreate()
        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }
        engine = (application as LainApplication).container.chatEngine
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WindowManager::class.java)
        showBubble()
        observeEngine()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun observeEngine() {
        lifecycleScope.launch {
            engine.state.collectLatest { state ->
                statusText?.text = when {
                    state.isListening -> "Listening…"
                    state.statusLine != null -> state.statusLine
                    state.error != null -> state.error
                    else -> if (state.conversationMode) "Hands-free on — say the next thing" else "Tap the mic and talk"
                }
                transcriptText?.text = state.messages.lastOrNull { it.sender == Sender.LAIN }?.text.orEmpty()
                micButton?.text = when {
                    state.isBusy -> "STOP"
                    state.conversationMode -> "PAUSE"
                    else -> "MIC"
                }
                // Idle bubble stays subtle; active bubble is obvious at a glance.
                (bubbleView?.background as? GradientDrawable)?.setColor(
                    if (state.isBusy) 0xFFC97367.toInt() else 0xFFE08F82.toInt()
                )
            }
        }
    }

    // ------------------------------------------------------------------ bubble

    private fun showBubble() {
        val wm = windowManager ?: return
        val size = resources.getDimensionPixelSize(R.dimen.overlay_bubble_size)

        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE08F82.toInt())
            }
            addView(
                TextView(this@OverlayBubbleService).apply {
                    text = "L"
                    textSize = 20f
                    setTextColor(0xFF14161C.toInt())
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            // NOT_FOCUSABLE is the whole trick: the app underneath keeps keyboard focus,
            // so Lain's typing goes where the user is actually looking.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 320
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 14 || abs(dy) > 14) dragged = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(view, params) }
        bubbleView = view
    }

    // ------------------------------------------------------------------- panel

    private fun togglePanel() {
        if (expanded) collapsePanel() else expandPanel()
    }

    private fun expandPanel() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21B222E.toInt())
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))

            addView(
                TextView(this@OverlayBubbleService).apply {
                    text = "Lain"
                    textSize = 15f
                    setTextColor(0xFFE08F82.toInt())
                }
            )
            statusText = TextView(this@OverlayBubbleService).apply {
                text = "Tap the mic and talk"
                textSize = 13f
                setTextColor(0xFF8891A1.toInt())
                setPadding(0, dp(4), 0, 0)
            }
            addView(statusText)

            transcriptText = TextView(this@OverlayBubbleService).apply {
                textSize = 13f
                setTextColor(0xFFF3EEDF.toInt())
                maxLines = 3
                setPadding(0, dp(6), 0, dp(8))
            }
            addView(transcriptText)

            addView(
                LinearLayout(this@OverlayBubbleService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    micButton = pillButton("MIC", 0xFFE08F82.toInt(), 0xFF14161C.toInt()) {
                        val state = engine.state.value
                        when {
                            state.isBusy -> engine.stop()
                            state.conversationMode -> engine.setConversationMode(false)
                            else -> engine.startVoiceInput()
                        }
                    }
                    addView(micButton)
                    addView(
                        pillButton("HANDS-FREE", 0xFF333F52.toInt(), 0xFFF3EEDF.toInt()) {
                            engine.setConversationMode(!engine.state.value.conversationMode)
                        }.apply {
                            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
                        }
                    )
                    addView(
                        pillButton("CLOSE", 0xFF333F52.toInt(), 0xFF8891A1.toInt()) {
                            collapsePanel()
                        }.apply {
                            (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8)
                        }
                    )
                }
            )
        }

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Same deal as the bubble — never take focus from the app being driven.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }

        runCatching { wm.addView(panel, params) }
        panelView = panel
        panelParams = params
        expanded = true
    }

    private fun collapsePanel() {
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        statusText = null
        transcriptText = null
        micButton = null
        expanded = false
    }

    private fun pillButton(
        label: String,
        background: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            this.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(background)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    override fun onDestroy() {
        isRunning = false
        collapsePanel()
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lain bubble", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lain is one tap away")
            .setContentText("Turn this off in Lain's settings")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
