package com.lain.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    suspend fun saveProfileStep(name: String? = null, age: Int? = null, gender: Gender? = null, nickname: String? = null) {
        context.dataStore.edit { prefs ->
            name?.let { prefs[Keys.NAME] = it }
            age?.let { prefs[Keys.AGE] = it }
            gender?.let { prefs[Keys.GENDER] = it.name }
            nickname?.let { prefs[Keys.NICKNAME] = it }
        }
    }

    suspend fun saveModelSelection(provider: Provider, modelId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = provider.name
            prefs[Keys.MODEL_ID] = modelId
        }
    }

    suspend fun markOnboarded() {
        context.dataStore.edit { prefs -> prefs[Keys.ONBOARDED] = true }
    }
}
