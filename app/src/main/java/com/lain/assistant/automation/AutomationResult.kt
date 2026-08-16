package com.lain.assistant.automation

sealed class AutomationResult {
    data class Success(val message: String) : AutomationResult()
    data class Failure(val reason: String) : AutomationResult()
    data class MissingPermission(val permission: String) : AutomationResult()

    fun toToolOutput(): String = when (this) {
        is Success -> message
        is Failure -> "Failed: $reason"
        is MissingPermission -> "Needs permission $permission — ask the user to grant it in Lain's settings."
    }
}
