package com.lain.assistant.automation

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils

/**
 * Reads what's currently in the notification shade, when the user has explicitly
 * allowed it.
 *
 * Notification access is one of the most sensitive permissions Android grants —
 * it exposes message previews, one-time codes and banking alerts — so it is
 * deliberately gated behind a separate Settings screen that no app can navigate
 * on the user's behalf. Nothing here tries to work around that: without the
 * grant, the tool reports that it's off and says where to turn it on.
 *
 * Nothing is stored, logged or sent anywhere. The service holds no state; a
 * request reads the live shade and returns it to the caller for that one answer.
 * That is a deliberate constraint rather than an omission: a cache of everyone's
 * notifications would be a far more attractive thing to leak than a live read.
 */
class LainNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: LainNotificationListener? = null

        /** Notifications Lain will never report — its own, and silent system chrome. */
        private val IGNORED_CATEGORIES = setOf(Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT)

        /** True when the user has granted notification access AND the service is bound. */
        val isConnected: Boolean get() = instance != null

        /**
         * Whether the grant exists, independent of whether the service happens to be
         * bound right now — the same distinction the Accessibility Service needs, and
         * for the same reason: telling someone to enable something they already
         * enabled is how an app looks broken.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, LainNotificationListener::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (entry in splitter) {
                if (ComponentName.unflattenFromString(entry) == expected) return true
            }
            return false
        }

        /** The one screen where this can be granted. No Intent can flip it directly. */
        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /** One notification, reduced to what a person would actually want read back. */
        data class Item(
            val app: String,
            val title: String,
            val text: String,
            val postedAt: Long,
            val ongoing: Boolean
        )

        /**
         * @return the current shade, newest first, or null when access isn't granted
         *   — which the caller must distinguish from "no notifications".
         */
        fun current(context: Context, limit: Int = 20): List<Item>? {
            val service = instance ?: return null
            val active = runCatching { service.activeNotifications }.getOrNull() ?: return null

            return active
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filterNot { it.notification.category in IGNORED_CATEGORIES }
                .mapNotNull { service.summarise(it) }
                // Persistent chrome (music players, sync icons) is noise when someone
                // asks "what did I miss".
                .filterNot { it.ongoing && it.title.isBlank() }
                .sortedByDescending { it.postedAt }
                .take(limit)
                .toList()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun summarise(sbn: StatusBarNotification): Item? {
        val extras = sbn.notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val label = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        return Item(
            app = label,
            title = title.take(120),
            text = text.take(240),
            postedAt = sbn.postTime,
            ongoing = sbn.isOngoing
        )
    }
}
