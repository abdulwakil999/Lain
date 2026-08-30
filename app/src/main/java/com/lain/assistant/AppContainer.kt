package com.lain.assistant

import android.content.Context
import com.lain.assistant.agent.ChatEngine
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.ConversationStore
import com.lain.assistant.data.MemoryRepository
import com.lain.assistant.data.MemoryStore
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.network.ConnectionTester
import com.lain.assistant.network.OpenRouterModelsClient
import com.lain.assistant.tools.ToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual service locator — deliberately no DI framework, the object graph here is small and static. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val userPreferencesRepository = UserPreferencesRepository(appContext)
    val memoryStore = MemoryStore(appContext)
    val conversationStore = ConversationStore(appContext)
    val secureKeyStore = SecureKeyStore(appContext)
    val toolDispatcher = ToolDispatcher(appContext)
    val voiceInputController = VoiceInputController(appContext)
    val openRouterModelsClient = OpenRouterModelsClient()
    val connectionTester = ConnectionTester(appContext)
    val scheduler = com.lain.assistant.automation.Scheduler(appContext)
    val actionLog = com.lain.assistant.data.ActionLog(appContext)

    /** Alias, so screens read as "what she remembers" rather than "the store". */
    val memory: MemoryStore get() = memoryStore

    /**
     * Application-scoped so a running task survives screen lock and backgrounding,
     * and so every surface shares one continuous conversation.
     */
    val chatEngine = ChatEngine(
        appContext = appContext,
        prefs = userPreferencesRepository,
        memory = memoryStore,
        conversations = conversationStore,
        keyStore = secureKeyStore,
        toolDispatcher = toolDispatcher,
        voiceInput = voiceInputController
    )

    init {
        // Carry across facts saved by the pre-Room key/value store, once, so upgrading
        // never silently drops what Lain already knew about the user.
        val legacy = MemoryRepository(appContext)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                memoryStore.migrateLegacyIfNeeded {
                    legacy.memories.first().associate { it.key to it.value }
                }
            }
        }
    }
}
