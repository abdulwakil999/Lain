package com.lain.assistant.network

import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The two social platforms that will actually let an app post as you.
 *
 * Reddit and Discord publish real APIs with credentials an individual can obtain in
 * a few minutes. Facebook, Instagram, X and Threads do not: posting on your behalf
 * needs a business account, an app review and, on some of them, a paid tier. There
 * is no key you can paste that makes them work, so they are absent rather than
 * present-and-broken — see [unsupported], which explains that rather than leaving a
 * user to conclude the feature is faulty.
 *
 * Both are gated behind the same confirmation as a text message. Posting in public
 * cannot be taken back, and a model that misreads a request can misread it into a
 * subreddit.
 */
class CommunityChannels(private val credentials: ChannelCredentials) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Posts to a subreddit using the user's own Reddit app credentials.
     *
     * Two calls: a script-grant token, then the submit. The token is fetched per
     * request rather than cached, which costs a round trip and removes a whole class
     * of problem — a stale token that fails halfway through a post, on an action that
     * cannot be safely retried because the first attempt may have succeeded.
     */
    suspend fun postToReddit(subreddit: String, title: String, body: String): ToolResult =
        withContext(Dispatchers.IO) {
            val creds = credentials.reddit()
                ?: return@withContext missing(
                    "Reddit",
                    "a client ID, client secret, username and password from reddit.com/prefs/apps"
                )
            if (subreddit.isBlank() || title.isBlank()) {
                return@withContext ToolResult.fail(
                    FailureKind.INVALID_INPUT, "A Reddit post needs a subreddit and a title."
                )
            }

            val token = redditToken(creds)
                ?: return@withContext ToolResult.fail(
                    FailureKind.PERMISSION,
                    "Reddit refused the credentials. Check the app is a \"script\" type and the password is right."
                )

            val form = FormBody.Builder()
                .add("sr", subreddit.removePrefix("r/").trim())
                .add("kind", "self")
                .add("title", title.take(300))
                .add("text", body)
                .add("api_type", "json")
                .build()

            val request = Request.Builder()
                .url("https://oauth.reddit.com/api/submit")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .post(form)
                .build()

            runCatching {
                Http.shared.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use ToolResult.fail(
                            FailureKind.TOOL_FAILURE,
                            "Reddit rejected the post (HTTP ${response.code})."
                        )
                    }
                    // Reddit returns 200 with the errors inside the body, so the status
                    // code alone would report a refused post as a success.
                    val errors = json.parseToJsonElement(payload)
                        .jsonObject["json"]?.jsonObject?.get("errors")?.toString().orEmpty()
                    if (errors.isNotBlank() && errors != "[]") {
                        ToolResult.fail(FailureKind.TOOL_FAILURE, "Reddit refused it: $errors")
                    } else {
                        ToolResult.ok("Posted to r/${subreddit.removePrefix("r/")}: \"$title\"")
                    }
                }
            }.getOrElse {
                ToolResult.fail(FailureKind.NETWORK, "Couldn't reach Reddit: ${it.message}")
            }
        }

    /**
     * Sends a message to a Discord channel through a bot token or a webhook.
     *
     * A webhook is the honest default for one channel: it is a URL the user creates
     * in their own channel settings, it needs no bot and no server permissions, and
     * it cannot read anything. A bot token is offered for people who already have one
     * and want more than a single channel.
     */
    suspend fun postToDiscord(channelId: String, message: String): ToolResult =
        withContext(Dispatchers.IO) {
            if (message.isBlank()) {
                return@withContext ToolResult.fail(FailureKind.INVALID_INPUT, "Nothing to send.")
            }

            credentials.discordWebhook()?.let { webhook ->
                val body = buildJsonObject { put("content", message.take(2000)) }
                val request = Request.Builder()
                    .url(webhook)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                return@withContext runCatching {
                    Http.shared.newCall(request).execute().use { response ->
                        if (response.isSuccessful) ToolResult.ok("Sent to Discord.")
                        else ToolResult.fail(
                            FailureKind.TOOL_FAILURE,
                            "Discord rejected it (HTTP ${response.code})."
                        )
                    }
                }.getOrElse {
                    ToolResult.fail(FailureKind.NETWORK, "Couldn't reach Discord: ${it.message}")
                }
            }

            val token = credentials.discordBot()
                ?: return@withContext missing(
                    "Discord",
                    "either a channel webhook URL (easiest — make one in the channel's settings) or a bot token"
                )
            if (channelId.isBlank()) {
                return@withContext ToolResult.fail(
                    FailureKind.INVALID_INPUT,
                    "A bot needs a channel ID. A webhook doesn't — set one instead if that's easier."
                )
            }

            val body = buildJsonObject { put("content", message.take(2000)) }
            val request = Request.Builder()
                .url("https://discord.com/api/v10/channels/${channelId.trim()}/messages")
                .header("Authorization", "Bot $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            runCatching {
                Http.shared.newCall(request).execute().use { response ->
                    if (response.isSuccessful) ToolResult.ok("Sent to Discord.")
                    else ToolResult.fail(
                        FailureKind.TOOL_FAILURE,
                        "Discord rejected it (HTTP ${response.code}). Usually the bot isn't in that channel."
                    )
                }
            }.getOrElse {
                ToolResult.fail(FailureKind.NETWORK, "Couldn't reach Discord: ${it.message}")
            }
        }

    private suspend fun redditToken(creds: ChannelCredentials.Reddit): String? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", creds.username)
            .add("password", creds.password)
            .build()
        val basic = okhttp3.Credentials.basic(creds.clientId, creds.clientSecret)
        val request = Request.Builder()
            .url("https://www.reddit.com/api/v1/access_token")
            .header("Authorization", basic)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()

        runCatching {
            Http.shared.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body?.string() ?: return@use null
                (json.parseToJsonElement(payload) as? JsonObject)
                    ?.get("access_token")?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    private fun missing(platform: String, needs: String) = ToolResult.fail(
        FailureKind.PERMISSION,
        "$platform isn't set up. It needs $needs, entered in Settings. Nothing is sent until it is."
    )

    companion object {
        /**
         * Reddit requires a descriptive agent and rate-limits generic ones hard.
         * Naming the app honestly is also the condition of their API terms.
         */
        const val USER_AGENT = "android:com.lain.assistant:v1.10 (personal assistant)"

        /**
         * Platforms people will ask for that genuinely cannot work.
         *
         * Returned as an explanation rather than a shrug, because "I can't post to
         * Instagram" reads as a bug in Lain when it is a decision by Meta.
         */
        fun unsupported(platform: String): ToolResult = ToolResult.fail(
            FailureKind.CAPABILITY_UNAVAILABLE,
            "$platform has no API that lets an app post as you from a phone. Instagram, Facebook and " +
                "Threads need a business account and Meta's app review; X's write access is a paid tier. " +
                "None of them can be unlocked with a key you paste in. Reddit and Discord do work, and I " +
                "can open $platform with a draft ready for you to post."
        )
    }
}

/** Where the channel credentials come from, so the network layer never touches storage. */
interface ChannelCredentials {
    data class Reddit(
        val clientId: String,
        val clientSecret: String,
        val username: String,
        val password: String
    )

    fun reddit(): Reddit?
    fun discordWebhook(): String?
    fun discordBot(): String?
}
