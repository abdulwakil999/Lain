package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The chat apps a message can be sent through, and which of them this phone has.
 *
 * This exists because of one hardcoded string. `setPackage("com.whatsapp")` is
 * correct on most phones and wrong on every phone running WhatsApp Business, which
 * is `com.whatsapp.w4b` — a different package with the same deep links. On those
 * phones the intent resolved to nothing, `startActivity` threw, and the failure
 * surfaced as "WhatsApp isn't installed" about an app sitting on the home screen.
 * The same shape of bug was waiting for every other messenger, so the packages are
 * a list per app now, and the list is checked against what is actually installed.
 *
 * What each app can be driven to do differs, and the difference is not hidden:
 *
 *  - **WhatsApp** takes the recipient *and* the text in one link, so the message is
 *    composed before anything is on screen.
 *  - **Telegram** opens a chat by phone number but has no documented way to prefill
 *    the text, so the text is typed in afterwards. Without the Accessibility Service
 *    that step cannot happen, and the caller is told so rather than being told the
 *    message went.
 *  - **Signal** accepts the `smsto:` form with a body, the same one the SMS apps use.
 *
 * Facebook Messenger and Instagram are deliberately absent: both address people by
 * an internal account id rather than a phone number, so a phone number cannot open
 * the right conversation, and opening the wrong one is worse than saying no.
 */
object Messengers {

    /** How the text gets into the composer, which decides what can be promised. */
    enum class Compose {
        /** The link carries the message; nothing has to be typed. */
        PREFILLED,

        /** The link opens the chat only; the text is typed by the Accessibility Service. */
        TYPED
    }

    data class Messenger(
        val key: String,
        val label: String,
        /** Package candidates, most common first. */
        val packages: List<String>,
        val compose: Compose,
        /** Content descriptions the send control is known by, tried in order. */
        val sendLabels: List<String>,
        /**
         * The intent action the app's link is registered under.
         *
         * Not always ACTION_VIEW: an `smsto:` link is registered as ACTION_SENDTO,
         * and sending it as a VIEW resolves to nothing on some builds.
         */
        val action: String = Intent.ACTION_VIEW,
        private val link: (String, String) -> Uri
    ) {
        fun uriFor(number: String, message: String): Uri = link(number, message)
    }

    val all: List<Messenger> = listOf(
        Messenger(
            key = "whatsapp",
            label = "WhatsApp",
            // Business is a separate package that speaks the identical deep link. It
            // is the one this list was written for.
            packages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            compose = Compose.PREFILLED,
            sendLabels = listOf("Send"),
            link = { number, message ->
                Uri.parse("https://wa.me/${Uri.encode(number.trimStart('+'))}?text=${Uri.encode(message)}")
            }
        ),
        Messenger(
            key = "telegram",
            label = "Telegram",
            packages = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus"),
            compose = Compose.TYPED,
            sendLabels = listOf("Send", "Send message"),
            link = { number, _ -> Uri.parse("tg://resolve?phone=${Uri.encode(number.trimStart('+'))}") }
        ),
        Messenger(
            key = "signal",
            label = "Signal",
            packages = listOf("org.thoughtcrime.securesms"),
            compose = Compose.PREFILLED,
            sendLabels = listOf("Send"),
            action = Intent.ACTION_SENDTO,
            link = { number, message ->
                Uri.parse("smsto:${Uri.encode(number)}?body=${Uri.encode(message)}")
            }
        )
    )

    fun byKey(key: String): Messenger? {
        val needle = key.trim().lowercase()
        return all.firstOrNull { needle.contains(it.key) }
    }

    /**
     * The package of [messenger] that is actually on this phone, or null.
     *
     * Asked before any intent is fired, so "WhatsApp isn't installed" is a fact
     * about the phone rather than an exception message from a failed launch.
     */
    fun installedPackage(context: Context, messenger: Messenger): String? {
        val pm = context.packageManager
        return messenger.packages.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    /** Every messenger this phone can actually send through, for offering a choice. */
    fun installed(context: Context): List<Messenger> =
        all.filter { installedPackage(context, it) != null }

    /**
     * The intent that opens a conversation, aimed at the installed package.
     *
     * Returns null when the app is absent, which is the distinction that matters:
     * nothing is attempted, so nothing can be half-done.
     */
    fun composeIntent(context: Context, messenger: Messenger, number: String, message: String): Intent? {
        val pkg = installedPackage(context, messenger) ?: return null
        return Intent(messenger.action, messenger.uriFor(number, message)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Signal reads the body from the extra as well as the URI; some builds
            // honour only one of the two.
            if (messenger.key == "signal") putExtra("sms_body", message)
        }
    }
}
