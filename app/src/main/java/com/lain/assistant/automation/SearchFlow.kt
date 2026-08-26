package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * "Open Chrome and search animeheaven" as one action instead of six.
 *
 * Driven through the generic tools, that request cost a model round trip per step:
 * open the app, read the screen, tap the search box, read again, type, read again to
 * confirm. On a free model that is most of a minute — and every one of those steps
 * is a chance to stall. It did stall: after the tap, Chrome's focused omnibox exposes
 * no nodes the compact reader keeps, the screen came back blank, and the model asked
 * the user what to do rather than typing into the field that was sitting there ready.
 *
 * The sequence never varies, so it doesn't need a language model in the loop. It runs
 * here at device speed, each step checking what actually happened, and the model makes
 * one call and gets one verified answer.
 */
class SearchFlow(private val context: Context) {

    private val apps = AppLauncher(context)

    companion object {
        /** Labels app search boxes actually use, in the order worth trying. */
        private val SEARCH_LABELS = listOf(
            "Search Google or type URL", "Search or type web address", "Search or type URL",
            "Search", "Search…", "Search...", "Type a search", "Search here",
            "Ask anything", "Find", "Query"
        )

        /**
         * Apps whose search is reachable by a documented deep link.
         *
         * Always preferred where one exists: a URL that lands directly on results
         * cannot mis-tap, cannot be defeated by a redesign, and needs no Accessibility
         * Service at all — so it works for users who never switched it on.
         */
        private val DEEP_LINKS = mapOf(
            "youtube" to "https://www.youtube.com/results?search_query=%s",
            "spotify" to "spotify:search:%s",
            "google maps" to "geo:0,0?q=%s",
            "maps" to "geo:0,0?q=%s",
            "play store" to "market://search?q=%s",
            "google play" to "market://search?q=%s"
        )
    }

    /**
     * @param appName which app to search in; blank means the browser
     * @param query   what to search for
     */
    suspend fun search(appName: String, query: String): ToolResult {
        if (query.isBlank()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "Nothing to search for.")
        }
        val target = appName.trim()

        // A browser search is just a URL. No app driving, no accessibility, no taps.
        if (target.isBlank() || isBrowser(target)) {
            return openUrl(
                "https://www.google.com/search?q=" + Uri.encode(query),
                "Searched the web for \"$query\"."
            )
        }

        DEEP_LINKS.entries.firstOrNull { target.lowercase().contains(it.key) }?.let { (name, template) ->
            val uri = template.format(Uri.encode(query))
            val opened = openUrl(uri, "Searched $name for \"$query\".")
            if (opened.success) return opened
            // Deep link refused (app missing, or it doesn't handle the scheme) — fall
            // through and drive the UI instead.
        }

        return driveAppSearch(target, query)
    }

    private fun isBrowser(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("chrome") || n.contains("browser") || n.contains("firefox") ||
            n.contains("edge") || n.contains("opera") || n.contains("brave") ||
            n.contains("duckduckgo") || n == "web" || n == "internet" || n == "google"
    }

    private fun openUrl(url: String, success: String): ToolResult = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ToolResult.ok(success)
    } catch (t: Throwable) {
        ToolResult.fail(FailureKind.APP_UNAVAILABLE, "Couldn't open that", t.message)
    }

    /**
     * Opens the app and drives its search box.
     *
     * Every step verifies rather than assumes, so a failure reports where it stopped
     * instead of claiming a search that never ran.
     */
    private suspend fun driveAppSearch(appName: String, query: String): ToolResult {
        val launch = apps.openApp(appName)
        if (launch !is AutomationResult.Success) {
            return ToolResult.fail(
                FailureKind.APP_UNAVAILABLE,
                (launch as? AutomationResult.Failure)?.reason ?: "Couldn't open $appName"
            )
        }

        val service = LainAccessibilityService.instance
            ?: return ToolResult.ok(
                "$appName is open, but searching inside it needs the Accessibility Service, which isn't " +
                    "connected. Type \"$query\" into its search box yourself, or switch the service on in Settings."
            )

        service.awaitSettle(maxWait = 3500L)

        // Some apps land with the search field already focused. If so, skip the tap
        // entirely — a tap on an already-focused field can dismiss the keyboard.
        if (!service.hasEditableField()) {
            val tapped = SEARCH_LABELS.any { service.tapByText(it) } || service.tapSearchAffordance()
            if (!tapped) {
                return ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "Opened $appName but couldn't find its search box. Here's the screen — tap it by label.",
                    data = mapOf("screen" to service.readScreenCompact())
                )
            }
            // Short settle: a focused field redraws quickly, and the omnibox dropdown
            // that follows never settles fully because it animates.
            service.awaitSettle(maxWait = 1500L, quietPeriod = 150L)
        }

        if (!service.typeText(query)) {
            return ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Tapped the search box in $appName but nothing accepted the text.",
                data = mapOf("screen" to service.readScreenCompact())
            )
        }

        service.awaitSettle(maxWait = 1200L, quietPeriod = 150L)
        val submitted = service.pressImeAction() ||
            listOf("Search", "Go", "Enter", "Find").any { service.tapByText(it) }
        service.awaitSettle(maxWait = 3000L)

        return if (submitted) {
            ToolResult.ok(
                "Searched $appName for \"$query\".",
                data = mapOf("screen" to service.readScreenCompact())
            )
        } else {
            ToolResult.ok(
                "Typed \"$query\" into $appName's search box, but nothing accepted a submit — " +
                    "press enter yourself.",
                data = mapOf("screen" to service.readScreenCompact())
            )
        }
    }
}
