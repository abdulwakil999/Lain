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
 * One tap on the home-screen widget drops straight into listening mode — via
 * the compact mini surface rather than launching the entire app, so asking a
 * quick question doesn't take over the screen.
 */
class LainWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            val launchIntent = Intent(context, MiniActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MiniActivity.EXTRA_AUTO_LISTEN, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, id, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_lain).apply {
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
