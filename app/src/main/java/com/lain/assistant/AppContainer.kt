package com.lain.assistant

import android.content.Context
import com.lain.assistant.agent.ChatEngine
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.MemoryRepository
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.network.ConnectionTester
import com.lain.assistant.network.OpenRouterModelsClient
import com.lain.assistant.tools.ToolDispatcher

/** Manual service locator — deliberately no DI framework, the object graph here is small and static. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val userPreferencesRepository = UserPreferencesRepository(appContext)
    val memoryRepository = MemoryRepository(appContext)
    val secureKeyStore = SecureKeyStore(appContext)
    val toolDispatcher = ToolDispatcher(appContext)
    val voiceInputController = VoiceInputController(appContext)
    val openRouterModelsClient = OpenRouterModelsClient()
    val connectionTester = ConnectionTester(appContext)

    /**
     * Application-scoped so a running task survives screen lock, rotation and
     * the app being backgrounded, and so the main app, mini surface and floating
     * bubble all share one continuous conversation.
     */
    val chatEngine = ChatEngine(
        appContext = appContext,
        prefs = userPreferencesRepository,
        memory = memoryRepository,
        keyStore = secureKeyStore,
        toolDispatcher = toolDispatcher,
        voiceInput = voiceInputController
    )
}
