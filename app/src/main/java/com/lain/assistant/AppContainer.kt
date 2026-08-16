package com.lain.assistant

import android.content.Context
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.data.UserPreferencesRepository
import com.lain.assistant.tools.ToolDispatcher

/** Manual service locator — deliberately no DI framework, the object graph here is small and static. */
class AppContainer(context: Context) {
    val userPreferencesRepository = UserPreferencesRepository(context)
    val secureKeyStore = SecureKeyStore(context)
    val toolDispatcher = ToolDispatcher(context)
}
