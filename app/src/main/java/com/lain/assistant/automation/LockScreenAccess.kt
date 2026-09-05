package com.lain.assistant.automation

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * Using Lain from the lock screen, on phones that have nothing to unlock.
 *
 * Plenty of people run no PIN, pattern or biometric at all. On those phones the
 * keyguard is a swipe, not a barrier, and there is no security reason to make
 * someone dismiss it before they can ask for the torch — the phone is already open
 * to whoever is holding it.
 *
 * Where a credential *is* set, none of this applies and nothing here tries to get
 * around it. `requestDismissKeyguard` shows the user's own unlock prompt and the
 * device unlocks only if they satisfy it; there is no path that skips that, and
 * looking for one would be the exact thing this project refuses to do.
 *
 * So the rule is simply stated: no credential, and Lain works from the lock screen.
 * A credential, and she asks Android to prompt for it and reports honestly whether
 * the user answered.
 */
class LockScreenAccess(private val context: Context) {

    private val keyguard: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    /** Whether the phone is locked at all right now. */
    fun isLocked(): Boolean = keyguard?.isKeyguardLocked == true

    /**
     * Whether the user has set any credential.
     *
     * The whole distinction rests on this. False means the keyguard is a swipe and
     * dismissing it protects nothing; true means it is the user's own security and
     * only they can answer it.
     */
    fun hasCredential(): Boolean = keyguard?.isDeviceSecure == true

    /**
     * Asks Android to take the keyguard away.
     *
     * On a phone with no credential this is immediate and total. On a secured one it
     * raises the user's own prompt — Android decides, not Lain — and the result says
     * which of the two happened rather than assuming.
     *
     * @param activity the visible activity; the system needs one to show a prompt on.
     */
    fun unlock(activity: Activity, onResult: (Boolean) -> Unit) {
        val manager = keyguard
        if (manager == null) {
            onResult(false)
            return
        }
        if (!manager.isKeyguardLocked) {
            onResult(true)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = onResult(true)
                    override fun onDismissError() = onResult(false)
                    override fun onDismissCancelled() = onResult(false)
                }
            )
        } else {
            // Below Oreo there is no callback and no honest way to know, so it is
            // reported as unknown rather than as success.
            onResult(false)
        }
    }

    /**
     * What to say when asked to unlock, without an activity to prompt on.
     *
     * Used by the tool path, which runs without a visible surface. It reports the
     * real state instead of guessing — the distinction between "there is nothing to
     * unlock" and "only you can unlock this" is the whole answer.
     */
    fun describe(): ToolResult = when {
        !isLocked() -> ToolResult.ok("The phone is already unlocked.")
        !hasCredential() -> ToolResult.ok(
            "No PIN or pattern is set on this phone, so the lock screen is only a swipe — " +
                "I can work from it. Say what you need."
        )
        else -> ToolResult.fail(
            FailureKind.PERMISSION,
            "There's a PIN or pattern on this phone, and only you can answer it. Android won't " +
                "let any app unlock it for you, and I wouldn't want to be able to."
        )
    }
}
