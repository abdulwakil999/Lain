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

    /**
     * Cleaned on the way in, so no caller has to remember to.
     *
     * It used to be stored verbatim and every call site was expected to trim it.
     * That is the kind of rule that holds until somebody adds a sixth call site,
     * and [ApiKeys] removes more than a trim can anyway.
     */
    fun saveApiKey(provider: Provider, apiKey: String) {
        prefs.edit().putString(keyFor(provider), ApiKeys.clean(apiKey)).apply()
    }

    /**
     * Cleaned on the way out as well, and rewritten when it needed cleaning.
     *
     * The keys that are failing right now were stored before any of this existed,
     * and their owners have no way to see what is wrong with them — the field shows
     * dots. Repairing on read fixes those phones on the next request instead of
     * asking everybody to notice, delete and retype a key that looks fine.
     */
    fun getApiKey(provider: Provider): String? {
        val stored = prefs.getString(keyFor(provider), null) ?: return null
        val cleaned = ApiKeys.clean(stored)
        if (cleaned != stored) {
            prefs.edit().putString(keyFor(provider), cleaned).apply()
        }
        return cleaned.takeIf { it.isNotEmpty() }
    }

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
        prefs.edit().putString(VOICE_KEY, ApiKeys.clean(apiKey)).apply()
    }

    fun getVoiceKey(): String? =
        prefs.getString(VOICE_KEY, null)?.let(ApiKeys::clean)?.takeIf { it.isNotEmpty() }

    fun clearVoiceKey() {
        prefs.edit().remove(VOICE_KEY).apply()
    }

    private fun keyFor(provider: Provider) = "api_key_${provider.name}"

    /**
     * Credentials for the channels that publish a real API.
     *
     * Encrypted alongside the model keys and never leaving the device except to the
     * service they belong to. Kept as free-form named slots rather than typed fields
     * so adding a platform later is a string, not a schema change.
     */
    /**
     * Trimmed, not key-cleaned. One of these slots holds a Reddit password, and a
     * password is allowed to contain a space — stripping one the way [ApiKeys] does
     * would silently lock somebody out of their own account. Tokens that must be
     * header-safe are cleaned by whoever supplies them.
     */
    fun saveChannel(slot: String, value: String) {
        prefs.edit().putString("channel_$slot", value.trim()).apply()
    }

    fun channel(slot: String): String? =
        prefs.getString("channel_$slot", null)?.takeIf { it.isNotBlank() }

    fun clearChannel(slot: String) {
        prefs.edit().remove("channel_$slot").apply()
    }

    companion object {
        const val VOICE_KEY = "api_key_FISH_AUDIO"

        /**
         * AudD, for identifying a song from the microphone.
         *
         * Stored in the same encrypted store as every other key, and used only when
         * the user has asked Lain what is playing — this is the one path in the app
         * where microphone audio leaves the phone, so it is never on a timer and
         * never in the background.
         */
        const val AUDD_KEY = "api_key_AUDD"
    }
}
