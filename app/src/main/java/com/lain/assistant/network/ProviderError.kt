package com.lain.assistant.network

import com.lain.assistant.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a failed HTTP response into something the person holding the phone can act
 * on — using the provider's own words rather than a guess about them.
 *
 * This exists because the guess was wrong. Every 4xx that carried an auth-ish status
 * was reported as "your API key was rejected, check it in Settings", and a 403 is
 * not that. Checked against the live API: OpenRouter answers a key it has never seen
 * with **401 "User not found."** A 403 means something else entirely — the request
 * was refused after the key was accepted, or it never reached the provider at all —
 * and sending somebody to re-paste a key that was never the problem is worse than
 * saying nothing, because it looks like an answer.
 *
 * So the rule here is: say the status, say what the provider said, and only name a
 * cause where the cause is actually known.
 *
 * The other half is the body that is not JSON. A network filter, a captive portal, a
 * VPN or an ISP's DNS blocker answers with an HTML page and whatever status it likes
 * — often 403. That is not the provider talking, and the difference matters: no
 * amount of fiddling with the key fixes a request that never arrived.
 */
object ProviderError {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param code the HTTP status.
     * @param body the response body, as far as it was read.
     * @param provider whose API answered.
     * @return one or two sentences: what happened, and what to do about it.
     */
    fun describe(code: Int, body: String, provider: Provider): String {
        val said = messageIn(body)
        val name = provider.displayName

        // Nothing that parses as an API error, and the body looks like a web page.
        // Whatever answered, it was not the API.
        if (said == null && looksLikeWebPage(body)) {
            return "The request never reached $name — something in between answered " +
                "instead (HTTP $code). That is usually a VPN, an ad-blocking or " +
                "family-filter DNS, a captive portal, or a network that blocks the " +
                "host. Try mobile data instead of Wi-Fi, or turn the VPN off. Your " +
                "API key is not involved in this one."
        }

        val quoted = said?.let { " $name said: \"$it\"" }.orEmpty()

        return when (code) {
            401 -> "$name did not recognise that key (HTTP 401).$quoted Check it hasn't " +
                "been deleted or regenerated, and that it belongs to $name."

            403 -> "$name accepted the key and refused the request (HTTP 403).$quoted " +
                "This is not the key — a key it doesn't know comes back as a 401. It is " +
                "usually the account itself: a spend or region restriction, moderation on " +
                "the model, or a key limited to something other than chat."

            402 -> "No credit left on $name (HTTP 402).$quoted Top up, or pick a free model."

            404 -> if (said != null && said.contains("data policy", ignoreCase = true)) {
                // The one that catches everybody on free models, and looks exactly like
                // a broken app rather than a setting.
                "$name won't route this model until you allow it (HTTP 404).$quoted " +
                    "Free endpoints need the privacy setting that permits them — on " +
                    "OpenRouter it is Settings → Privacy. Turn it on, or pick a paid model."
            } else {
                "That model isn't available on this account (HTTP 404).$quoted Pick another one."
            }

            429 -> "Rate limited (HTTP 429).$quoted Common on free models — wait a moment, " +
                "or switch to a ★ model."

            in 500..599 -> "$name is having trouble (HTTP $code).$quoted Nothing to fix at " +
                "this end; try again shortly."

            else -> "HTTP $code from $name.$quoted"
        }
    }

    /**
     * Whether a failure is worth telling the user to look at their key.
     *
     * Only 401 is. Kept next to [describe] so the two can never drift into saying
     * different things about the same status.
     */
    fun isAboutTheKey(code: Int): Boolean = code == 401

    /**
     * The provider's own error text, dug out of whichever shape it used.
     *
     * OpenAI-compatible APIs nest it under `error.message`; Anthropic uses the same
     * shape; some proxies return a bare `message` or `detail`. Anything else, and
     * this returns null rather than inventing a summary of a body it did not
     * understand.
     */
    fun messageIn(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val root = runCatching { lenient.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null

        val direct = root["error"]?.let { element ->
            runCatching { element.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: runCatching { element.jsonPrimitive.content }.getOrNull()
        }
        val fallback = listOf("message", "detail", "error_description").firstNotNullOfOrNull { key ->
            runCatching { root[key]?.jsonPrimitive?.content }.getOrNull()
        }
        return (direct ?: fallback)?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
    }

    /** A body that is markup, not an API answer. */
    private fun looksLikeWebPage(body: String): Boolean {
        val head = body.trimStart().take(200).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") ||
            head.contains("<head") || head.contains("cloudflare") ||
            head.contains("access denied") || head.contains("blocked")
    }
}
