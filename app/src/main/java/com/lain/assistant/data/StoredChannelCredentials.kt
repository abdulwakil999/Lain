package com.lain.assistant.data

import com.lain.assistant.network.ChannelCredentials

/**
 * Channel credentials, read from encrypted storage on demand.
 *
 * Read each time rather than cached: a user who has just pasted a token in Settings
 * expects the next message to use it, and a cached null would make the feature look
 * broken until the app restarted.
 */
class StoredChannelCredentials(private val keys: SecureKeyStore) : ChannelCredentials {

    override fun reddit(): ChannelCredentials.Reddit? {
        val id = keys.channel(REDDIT_ID) ?: return null
        val secret = keys.channel(REDDIT_SECRET) ?: return null
        val user = keys.channel(REDDIT_USER) ?: return null
        val password = keys.channel(REDDIT_PASSWORD) ?: return null
        return ChannelCredentials.Reddit(id, secret, user, password)
    }

    override fun discordWebhook(): String? = keys.channel(DISCORD_WEBHOOK)

    override fun discordBot(): String? = keys.channel(DISCORD_BOT)

    companion object {
        const val REDDIT_ID = "reddit_client_id"
        const val REDDIT_SECRET = "reddit_client_secret"
        const val REDDIT_USER = "reddit_username"
        const val REDDIT_PASSWORD = "reddit_password"
        const val DISCORD_WEBHOOK = "discord_webhook"
        const val DISCORD_BOT = "discord_bot_token"
    }
}
