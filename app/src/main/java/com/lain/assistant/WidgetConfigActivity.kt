package com.lain.assistant

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle

/**
 * What a long-press on a Lain widget lands on: Lain.
 *
 * Holding a widget offered the launcher's own menu, and following it through
 * arrived at a settings screen — App Info on most launchers, Lain's Settings on
 * some. Neither is what someone holding down her face is asking for. A widget
 * declaring a configuration activity gets that activity instead, so the gesture
 * opens the app.
 *
 * This has no UI of its own. It answers the launcher, starts [MainActivity] and
 * finishes, which is the whole job — a configuration screen for a widget that has
 * nothing to configure would be a worse answer than the settings screen it
 * replaces.
 *
 * The result is set to OK before anything else. A configuration activity that
 * finishes without one tells the launcher the user backed out, and on first
 * placement the launcher responds by deleting the widget it just placed — so a
 * missing result here would mean the widget vanishes as it is dropped.
 */
class WidgetConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )

        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
