package com.lain.assistant.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.simStore by preferencesDataStore(name = "lain_sim")
private val PREFERRED_SUBSCRIPTION = intPreferencesKey("preferred_subscription_id")

/**
 * Which SIM to use on a dual-SIM phone.
 *
 * The gap this closes: Lain places a call, Android puts up its "which SIM?" chooser,
 * the user picks the same one they always pick, and next time Lain asks again. That
 * chooser is also why call verification looked like a failure — the call is waiting
 * on a human, not failing.
 *
 * What is and isn't possible here is worth being precise about, because it would be
 * easy to overpromise:
 *
 *  - **SMS: fully controllable.** `SmsManager.createForSubscriptionId` sends from a
 *    chosen SIM with no prompt at all. Remembering the preference genuinely removes
 *    a step.
 *  - **Calls: only a hint.** `EXTRA_PHONE_ACCOUNT_HANDLE` on the dial intent tells
 *    the dialler which account to use, and the system dialler honours it. Some OEM
 *    and third-party diallers ignore it and ask anyway. Lain passes the hint and
 *    does not claim more than that.
 *
 * Nothing here changes the system default. The preference is Lain's own, so it can't
 * surprise the user in other apps.
 */
class SimPreference(private val context: Context) {

    /** One SIM, described the way the user would recognise it. */
    data class Sim(
        val subscriptionId: Int,
        val label: String,
        val slot: Int,
        val number: String?
    ) {
        /** "SIM 1 — MTN" */
        fun describe(): String = "SIM ${slot + 1} — $label"
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The SIMs actually in the phone.
     *
     * Empty when the permission isn't granted or the device is single-SIM — both of
     * which mean "there is nothing to choose", so callers need no special case.
     */
    fun available(): List<Sim> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return runCatching {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val active: List<SubscriptionInfo> = manager.activeSubscriptionInfoList.orEmpty()
            active.map {
                Sim(
                    subscriptionId = it.subscriptionId,
                    label = it.carrierName?.toString()?.ifBlank { null } ?: "SIM ${it.simSlotIndex + 1}",
                    slot = it.simSlotIndex,
                    number = it.number?.takeIf { n -> n.isNotBlank() }
                )
            }.sortedBy { it.slot }
        }.getOrDefault(emptyList())
    }

    val hasChoice: Boolean get() = available().size > 1

    suspend fun preferred(): Sim? {
        val sims = available()
        if (sims.isEmpty()) return null
        val saved = context.simStore.data.map { it[PREFERRED_SUBSCRIPTION] }.first()
        // A saved SIM that has since been removed must not be used — falling back to
        // whatever is in the phone now beats sending from a subscription that no
        // longer exists.
        return sims.firstOrNull { it.subscriptionId == saved }
            ?: sims.singleOrNull()
    }

    /** Blocking read for the call path, which runs inside a receiver with no scope. */
    fun preferredBlocking(): Sim? = runCatching { runBlocking { preferred() } }.getOrNull()

    suspend fun remember(subscriptionId: Int) {
        context.simStore.edit { it[PREFERRED_SUBSCRIPTION] = subscriptionId }
    }

    suspend fun forget() {
        context.simStore.edit { it.remove(PREFERRED_SUBSCRIPTION) }
    }

    /**
     * Matches a spoken SIM description — "the MTN one", "SIM 2", "airtel".
     *
     * Goes through [com.lain.assistant.agent.FuzzyMatch] like every other name in
     * the app, so carrier names are case- and spacing-insensitive.
     */
    fun match(spoken: String): Sim? {
        val sims = available()
        if (sims.isEmpty()) return null

        // "SIM 1" / "sim two" — a slot number is exact, so it wins outright.
        Regex("\\bsim\\s*([12])\\b").find(spoken.lowercase())?.let { m ->
            val slot = m.groupValues[1].toInt() - 1
            sims.firstOrNull { it.slot == slot }?.let { return it }
        }
        if (spoken.lowercase().contains("sim one")) return sims.firstOrNull { it.slot == 0 }
        if (spoken.lowercase().contains("sim two")) return sims.firstOrNull { it.slot == 1 }

        return when (val result = com.lain.assistant.agent.FuzzyMatch.best(spoken, sims) { it.label }) {
            is com.lain.assistant.agent.FuzzyMatch.Result.Found -> result.hit.value
            is com.lain.assistant.agent.FuzzyMatch.Result.Ambiguous -> null
            com.lain.assistant.agent.FuzzyMatch.Result.None -> null
        }
    }

    /**
     * The phone-account handle for [sim], used as a hint on the dial intent.
     *
     * Null when the accounts can't be read — the call then goes out as before, with
     * the system asking. That is a worse experience, not a broken one.
     */
    fun phoneAccountFor(sim: Sim): android.telecom.PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return null
        return runCatching {
            val telecom = context.getSystemService(android.telecom.TelecomManager::class.java) ?: return null
            @Suppress("MissingPermission")
            val handles = telecom.callCapablePhoneAccounts
            handles.firstOrNull { handle ->
                // The handle id is the subscription id on every implementation that
                // supports multi-SIM; compared as text because some OEMs pad it.
                handle.id.trim() == sim.subscriptionId.toString()
            }
        }.getOrNull()
    }
}
