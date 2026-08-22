package com.lain.assistant.automation

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lain.assistant.MiniActivity
import com.lain.assistant.R

/**
 * Home-screen widgets.
 *
 * All three open the compact mini surface rather than the whole app, so asking a
 * quick question doesn't take over the screen.
 *
 * Every one of these must be `exported="true"` in the manifest. A widget provider
 * is a receiver the *launcher* broadcasts to, from its own process; with export
 * off the system silently cannot deliver APPWIDGET_UPDATE, the widget never binds
 * its click targets, and tapping it does nothing at all. That was the bug.
 */
abstract class LainBaseWidgetProvider : AppWidgetProvider() {

    /** Which layout this variant draws. */
    protected abstract val layout: Int

    /** Wires the tap targets in [views]; called once per widget instance. */
    protected abstract fun bind(context: Context, views: RemoteViews, widgetId: Int)

    final override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, layout)
            bind(context, views, id)
            runCatching { appWidgetManager.updateAppWidget(id, views) }
        }
    }

    /**
     * A PendingIntent per widget *and* per action.
     *
     * The request code has to differ for both, because PendingIntent equality
     * ignores extras — two intents that differ only in an extra collapse into one,
     * and the mic button would inherit whatever the screen button asked for.
     */
    protected fun openMini(
        context: Context,
        widgetId: Int,
        action: Int,
        autoListen: Boolean = false,
        command: String? = null
    ): PendingIntent {
        val intent = Intent(context, MiniActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MiniActivity.EXTRA_AUTO_LISTEN, autoListen)
            command?.let { putExtra(MiniActivity.EXTRA_COMMAND, it) }
            // Also part of what makes each PendingIntent distinct, since filterEquals
            // does compare the data URI.
            data = android.net.Uri.parse("lain://widget/$widgetId/$action")
        }
        return PendingIntent.getActivity(
            context,
            widgetId * 10 + action,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** 2x2: Lain's face and a label. One tap, straight into listening. */
class LainWidgetProvider : LainBaseWidgetProvider() {
    override val layout = R.layout.widget_lain
    override fun bind(context: Context, views: RemoteViews, widgetId: Int) {
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openMini(context, widgetId, action = 0, autoListen = true)
        )
    }
}

/** 1x1: the glyph alone, sized like a launcher icon but it listens instead. */
class LainMicWidgetProvider : LainBaseWidgetProvider() {
    override val layout = R.layout.widget_lain_mic
    override fun bind(context: Context, views: RemoteViews, widgetId: Int) {
        views.setOnClickPendingIntent(
            R.id.widget_root,
            openMini(context, widgetId, action = 0, autoListen = true)
        )
    }
}

/**
 * 4x1: a bar with three separate targets.
 *
 * The point of the extra width is that the common jobs become one tap rather than
 * one tap and then a sentence — "read the screen" in particular, which is the
 * thing a blind user needs fastest and which no amount of speed elsewhere helps if
 * it still has to be spoken first.
 */
class LainBarWidgetProvider : LainBaseWidgetProvider() {
    override val layout = R.layout.widget_lain_bar
    override fun bind(context: Context, views: RemoteViews, widgetId: Int) {
        views.setOnClickPendingIntent(R.id.widget_icon, openMini(context, widgetId, action = 1))
        views.setOnClickPendingIntent(R.id.widget_type, openMini(context, widgetId, action = 2))
        views.setOnClickPendingIntent(
            R.id.widget_mic,
            openMini(context, widgetId, action = 3, autoListen = true)
        )
        views.setOnClickPendingIntent(
            R.id.widget_screen,
            openMini(context, widgetId, action = 4, command = "what's on screen")
        )
    }
}
