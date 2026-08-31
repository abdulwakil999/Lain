package com.lain.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Holds the user's LLM provider API key, encrypted at rest via the Android Keystore. */
class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "lain_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(provider: Provider, apiKey: String) {
        prefs.edit().putString(keyFor(provider), apiKey).apply()
    }

    fun getApiKey(provider: Provider): String? = prefs.getString(keyFor(provider), null)

    fun clearApiKey(provider: Provider) {
        prefs.edit().remove(keyFor(provider)).apply()
    }

    /**
     * The Fish Audio key, kept beside the model keys and under the same encryption.
     *
     * Its own accessor rather than a [Provider] entry because Fish Audio is not one:
     * it synthesises speech, it never sees a conversation, and folding it into the
     * provider enum would put a voice service in every list that means "the thing
     * answering your questions".
     */
    fun saveVoiceKey(apiKey: String) {
        prefs.edit().putString(VOICE_KEY, apiKey.trim()).apply()
    }

    fun getVoiceKey(): String? = prefs.getString(VOICE_KEY, null)?.takeIf { it.isNotBlank() }

    fun clearVoiceKey() {
        prefs.edit().remove(VOICE_KEY).apply()
    }

    private fun keyFor(provider: Provider) = "api_key_${provider.name}"

    private companion object {
        const val VOICE_KEY = "api_key_FISH_AUDIO"
    }
}
