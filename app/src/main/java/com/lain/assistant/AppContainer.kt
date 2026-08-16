package com.lain.assistant

import android.content.Context
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.network.OpenRouterModelsClient
import com.lain.assistant.tools.ToolDispatcher

/** Manual service locator — deliberately no DI framework, the object graph here is small and static. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val userPreferencesRepository = UserPreferencesRepository(appContext)
    val secureKeyStore = SecureKeyStore(appContext)
    val toolDispatcher = ToolDispatcher(appContext)
    val voiceInputController = VoiceInputController(appContext)
    val openRouterModelsClient = OpenRouterModelsClient()
}
