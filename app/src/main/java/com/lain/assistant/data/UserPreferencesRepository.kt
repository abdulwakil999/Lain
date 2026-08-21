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
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL_ID = stringPreferencesKey("model_id")
        val KOKORO_ENDPOINT = stringPreferencesKey("kokoro_endpoint")
        val MUTED = booleanPreferencesKey("muted")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
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
     * When on, the wake-word listener stops entirely while the screen is off
     * rather than looping the recognizer in the user's pocket.
     */
    val isBatterySaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.BATTERY_SAVER] ?: true }

    suspend fun setBatterySaver(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BATTERY_SAVER] = enabled }
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
        context.dataStore.edit { prefs -> prefs[Keys.ONBOARDED] = true }
    }
}
