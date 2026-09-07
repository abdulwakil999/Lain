package com.lain.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "lain_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("name")
        val AGE = intPreferencesKey("age")
        val GENDER = stringPreferencesKey("gender")
        val NICKNAME = stringPreferencesKey("nickname")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LEGAL_VERSION = intPreferencesKey("legal_version")
        val DEVELOPER_KNOWN = booleanPreferencesKey("developer_known")
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL_ID = stringPreferencesKey("model_id")
        val KOKORO_ENDPOINT = stringPreferencesKey("kokoro_endpoint")
        val FISH_VOICE_ID = stringPreferencesKey("fish_voice_id")
        val MUTED = booleanPreferencesKey("muted")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val ALWAYS_ON = booleanPreferencesKey("always_on")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val MODEL_FALLBACK = booleanPreferencesKey("model_fallback")
        val BROKEN_MODELS = stringSetPreferencesKey("broken_models")
        // A snapshot of the selected model's catalogue facts, so the capability layer
        // has real data offline instead of guessing from the slug.
        val MODEL_LABEL = stringPreferencesKey("model_label")
        val MODEL_CONTEXT = intPreferencesKey("model_context")
        val MODEL_VISION = booleanPreferencesKey("model_vision")
        val MODEL_FREE = booleanPreferencesKey("model_free")
        val MODEL_STRONG_TOOLS = booleanPreferencesKey("model_strong_tools")
    }

    val isOnboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    /**
     * Which version of the privacy policy and terms the user has actually agreed to.
     *
     * Zero for anyone who has not, including everyone who onboarded before this was
     * recorded — which is the correct reading of "we do not know that they agreed to
     * the current one". The documents promise they will be shown again when they
     * change; this is the number that keeps that promise honest.
     */
    val acceptedLegalVersion: Flow<Int> = context.dataStore.data.map { it[Keys.LEGAL_VERSION] ?: 0 }

    suspend fun acceptLegalVersion(version: Int) {
        context.dataStore.edit { it[Keys.LEGAL_VERSION] = version }
    }

    /**
     * Whether whoever is holding the phone has answered the developer challenge.
     *
     * Persisted so he is not re-interrogated on every launch, and it changes only
     * how she addresses him — nothing in the app grants any capability on the
     * strength of it. See [com.lain.assistant.agent.DeveloperGate] for why that
     * separation is deliberate.
     */
    val isDeveloperKnown: Flow<Boolean> = context.dataStore.data.map { it[Keys.DEVELOPER_KNOWN] ?: false }

    suspend fun setDeveloperKnown(known: Boolean) {
        context.dataStore.edit { it[Keys.DEVELOPER_KNOWN] = known }
    }

    val userProfile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        UserProfile(
            name = prefs[Keys.NAME] ?: "",
            age = prefs[Keys.AGE] ?: 0,
            gender = prefs[Keys.GENDER]?.let { runCatching { Gender.valueOf(it) }.getOrNull() } ?: Gender.FEMALE,
            nickname = prefs[Keys.NICKNAME] ?: ""
        )
    }

    val selectedProvider: Flow<Provider> = context.dataStore.data.map { prefs ->
        prefs[Keys.PROVIDER]?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: ModelCatalog.defaultProvider
    }

    val selectedModelId: Flow<String?> = context.dataStore.data.map { it[Keys.MODEL_ID] }

    /** Optional self-hosted Kokoro TTS server URL. Falls back to the on-device Android voice when unset. */
    val kokoroEndpoint: Flow<String?> = context.dataStore.data.map { it[Keys.KOKORO_ENDPOINT] }

    suspend fun saveKokoroEndpoint(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.KOKORO_ENDPOINT) else prefs[Keys.KOKORO_ENDPOINT] = url
        }
    }

    /**
     * Which Fish Audio voice to use, or blank for the model's default.
     *
     * Stored rather than hardcoded because the library is theirs and its ids are not
     * ours to guess. It also keys the audio cache, so changing it correctly stops the
     * old voice being replayed from disk.
     */
    val fishVoiceId: Flow<String> = context.dataStore.data.map { it[Keys.FISH_VOICE_ID] ?: "" }

    suspend fun saveFishVoiceId(id: String) {
        context.dataStore.edit { prefs ->
            if (id.isBlank()) prefs.remove(Keys.FISH_VOICE_ID) else prefs[Keys.FISH_VOICE_ID] = id.trim()
        }
    }

    /** Silences Lain's voice without disabling voice input. */
    val isMuted: Flow<Boolean> = context.dataStore.data.map { it[Keys.MUTED] ?: false }

    suspend fun setMuted(muted: Boolean) {
        context.dataStore.edit { it[Keys.MUTED] = muted }
    }

    /** Floating bubble so Lain can be commanded from outside the app. */
    val isOverlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.OVERLAY_ENABLED] ?: false }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }

    /**
     * Hands-free: whether Lain listens for her own name.
     *
     * Off by default and it has to be, because it is the microphone. Turning it on
     * is a decision with a permission attached, and an assistant that starts
     * listening because it was installed is not one anybody asked for.
     */
    val isWakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.WAKE_WORD] ?: false }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_WORD] = enabled }
    }

    /**
     * Whether Lain stays loaded between turns.
     *
     * Off by default and deliberately so: it is a foreground service and an ongoing
     * notification, which costs battery. On, alarms fire on the minute and the wake
     * word stops going deaf; off, the system is free to reap the process. Neither
     * one is the right answer for everybody, which is why it is a switch.
     */
    val isAlwaysOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALWAYS_ON] ?: false }

    suspend fun setAlwaysOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALWAYS_ON] = enabled }
    }

    /**
     * Off by default: switching models changes behaviour and cost, so it shouldn't
     * happen behind the user's back. When on, a fallback is still reported afterwards.
     */
    val isModelFallbackEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MODEL_FALLBACK] ?: false }

    suspend fun setModelFallbackEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MODEL_FALLBACK] = enabled }
    }

    suspend fun saveProfileStep(name: String? = null, age: Int? = null, gender: Gender? = null, nickname: String? = null) {
        context.dataStore.edit { prefs ->
            name?.let { prefs[Keys.NAME] = it }
            age?.let { prefs[Keys.AGE] = it }
            gender?.let { prefs[Keys.GENDER] = it.name }
            nickname?.let { prefs[Keys.NICKNAME] = it }
        }
    }

    /**
     * The catalogue facts for whichever model is selected.
     *
     * OpenRouter's free tier rotates constantly, so hardcoding what each model can
     * do goes stale within weeks. The picker already fetches the truth from the
     * provider; storing it alongside the selection means the engine can shape every
     * request from real numbers rather than pattern-matching the model's name — and
     * can still do so with no network.
     */
    val selectedModelInfo: Flow<ModelInfo?> = context.dataStore.data.map { prefs ->
        val id = prefs[Keys.MODEL_ID] ?: return@map null
        val provider = prefs[Keys.PROVIDER]?.let { runCatching { Provider.valueOf(it) }.getOrNull() }
            ?: ModelCatalog.defaultProvider
        ModelInfo(
            id = id,
            label = prefs[Keys.MODEL_LABEL] ?: id,
            provider = provider,
            isFree = prefs[Keys.MODEL_FREE] ?: id.endsWith(":free"),
            strongAtTools = prefs[Keys.MODEL_STRONG_TOOLS] ?: false,
            supportsVision = prefs[Keys.MODEL_VISION] ?: false,
            contextTokens = prefs[Keys.MODEL_CONTEXT] ?: 0
        )
    }

    /**
     * @param info the picker's catalogue entry for this model, when there is one.
     *   Passing null keeps whatever facts were already stored rather than wiping
     *   them, since a caller without the entry knows less, not more.
     */
    suspend fun saveModelSelection(provider: Provider, modelId: String, info: ModelInfo? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = provider.name
            val changed = prefs[Keys.MODEL_ID] != modelId
            prefs[Keys.MODEL_ID] = modelId
            when {
                info != null -> {
                    prefs[Keys.MODEL_LABEL] = info.label
                    prefs[Keys.MODEL_CONTEXT] = info.contextTokens
                    prefs[Keys.MODEL_VISION] = info.supportsVision
                    prefs[Keys.MODEL_FREE] = info.isFree
                    prefs[Keys.MODEL_STRONG_TOOLS] = info.strongAtTools
                }
                // Switching model without catalogue data: stale facts about the
                // previous model would be worse than none.
                changed -> {
                    prefs.remove(Keys.MODEL_LABEL)
                    prefs.remove(Keys.MODEL_CONTEXT)
                    prefs.remove(Keys.MODEL_VISION)
                    prefs.remove(Keys.MODEL_FREE)
                    prefs.remove(Keys.MODEL_STRONG_TOOLS)
                }
            }
        }
    }

    /**
     * Models that returned a "this model does not exist here" error in anger.
     *
     * Free-tier slugs are retired without notice, and the picker's live fetch only
     * tells us what exists now — not that the thing already selected has since
     * stopped existing. Remembering the failure means the dead entry is hidden from
     * the picker and never silently re-selected, instead of the user hitting the
     * same HTTP 404 on every message until they work out what changed.
     */
    val brokenModels: Flow<Set<String>> = context.dataStore.data.map { it[Keys.BROKEN_MODELS] ?: emptySet() }

    suspend fun markModelBroken(modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BROKEN_MODELS] = (prefs[Keys.BROKEN_MODELS] ?: emptySet()) + modelId
        }
    }

    /** Called when a model is deliberately re-selected, so a since-restored slug gets another chance. */
    suspend fun clearModelBroken(modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BROKEN_MODELS] = (prefs[Keys.BROKEN_MODELS] ?: emptySet()) - modelId
        }
    }

    suspend fun markOnboarded() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDED] = true
            // Finishing setup means the documents were accepted on the way through, so
            // the same write records which version — otherwise a brand new user would
            // be shown the re-consent screen on their second launch.
            prefs[Keys.LEGAL_VERSION] = com.lain.assistant.legal.LegalText.VERSION
        }
    }
}
