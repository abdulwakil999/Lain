package com.lain.assistant.automation

import android.content.Context
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.lain.assistant.agent.FuzzyMatch
import kotlinx.coroutines.delay

/** Native telephony actions — these have real platform hooks, no accessibility trickery needed. */
class PhoneController(private val context: Context) {

    private companion object {
        /** Long enough for a SIM picker to be answered, short enough not to hang a turn. */
        const val CALL_WAIT_MS = 12_000L
        const val CALL_POLL_MS = 400L

        /** Ceiling on the full-scan fallback, so a 5,000-contact phone can't stall a turn. */
        const val MAX_SCAN = 2_000

        val DIALER_HINTS = listOf(
            "dialer", "telecom", "incallui", "phone", "truecaller", "contacts"
        )
    }

    private val sims = SimPreference(context)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Places a call and then finds out whether it actually started.
     *
     * Three things get in the way of "did it work", and the previous version fell
     * into all of them:
     *
     *  - **READ_PHONE_STATE was never requested at runtime.** It sat in the manifest
     *    and nothing ever asked for it, so the state read returned IDLE every single
     *    time and every successful call was reported as a failure. That is the worst
     *    class of bug this app can have: Lain telling the user something didn't
     *    happen when it did. It is now requested with the other core permissions,
     *    and when it genuinely isn't granted the answer is "placed, can't verify" —
     *    never "didn't work".
     *  - **A SIM picker on a dual-SIM phone.** The call is waiting on a tap, which
     *    can take as long as the user takes. A single check at 2.5s calls that a
     *    failure.
     *  - **A third-party dialer** (Truecaller and friends) intercepting the intent
     *    and showing its own screen.
     *
     * So this polls to a deadline rather than sampling once, and distinguishes
     * "connected", "waiting for you", and "we cannot tell".
     */
    suspend fun placeCall(phoneNumber: String): AutomationResult {
        if (!granted(android.Manifest.permission.CALL_PHONE)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.CALL_PHONE)
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // A hint, not a command. The system dialler honours the phone-account
                // extra and skips its SIM chooser; some OEM and third-party diallers
                // ignore it and ask anyway. Passing it costs nothing and removes the
                // prompt on the phones that respect it — Lain does not claim more.
                sims.preferredBlocking()?.let { sim ->
                    sims.phoneAccountFor(sim)?.let { handle ->
                        putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                }
            }
            context.startActivity(intent)

            if (!granted(android.Manifest.permission.READ_PHONE_STATE)) {
                // Honest: the intent went, and we have no way to observe the outcome.
                // Reporting failure here would be a lie, and reporting success would
                // be a guess.
                return AutomationResult.Success(
                    "Dialling $phoneNumber. Lain can't confirm the call connected — that needs the phone-state " +
                        "permission, which hasn't been granted. If a SIM picker appears, choose a SIM."
                )
            }

            when (awaitCallState()) {
                CallOutcome.CONNECTED ->
                    AutomationResult.Success("Calling $phoneNumber — the call is connecting.")

                CallOutcome.PENDING_USER -> AutomationResult.Success(
                    "The dialler is up for $phoneNumber and waiting on you — pick a SIM, or tap the call " +
                        "button if a third-party dialler is showing its own screen. Lain can't choose a SIM " +
                        "for you; Android hands that decision to the user."
                )

                CallOutcome.IDLE -> AutomationResult.Failure(
                    "The dialler opened for $phoneNumber but no call started within ${CALL_WAIT_MS / 1000}s. " +
                        "Check the screen: a third-party dialler may be waiting for a tap."
                )
            }
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not place call")
        }
    }

    private enum class CallOutcome { CONNECTED, PENDING_USER, IDLE }

    /**
     * Polls until the call is up or the deadline passes.
     *
     * Returns as soon as it goes off-hook, so a call that connects in 400ms costs
     * 400ms rather than the whole window. Anything still idle at the deadline while
     * a dialler is on screen is reported as waiting on the user rather than as
     * having failed — on a dual-SIM phone that is the normal case, not an error.
     */
    private suspend fun awaitCallState(): CallOutcome {
        val deadline = android.os.SystemClock.elapsedRealtime() + CALL_WAIT_MS
        var sawDialler = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            when (callState()) {
                TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING ->
                    return CallOutcome.CONNECTED
                else -> Unit
            }
            // A dialler in the foreground with no call yet means a decision is pending.
            if (!sawDialler) sawDialler = dialerInForeground()
            delay(CALL_POLL_MS)
        }
        return if (sawDialler) CallOutcome.PENDING_USER else CallOutcome.IDLE
    }

    /**
     * Whether a dialler is what's on screen, via the Accessibility Service if it is
     * connected. Absent that this returns false and the caller degrades to the
     * plain timeout answer — it never asserts anything it can't see.
     */
    private fun dialerInForeground(): Boolean {
        val service = LainAccessibilityService.instance ?: return false
        val pkg = runCatching { service.foregroundApp()?.first }.getOrNull() ?: return false
        return DIALER_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun callState(): Int = try {
        if (granted(android.Manifest.permission.READ_PHONE_STATE)) {
            context.getSystemService(TelephonyManager::class.java).callState
        } else {
            TelephonyManager.CALL_STATE_IDLE
        }
    } catch (_: Throwable) {
        TelephonyManager.CALL_STATE_IDLE
    }

    /**
     * An SmsManager bound to the user's preferred SIM, or null when there is no
     * preference to apply — a single-SIM phone, or one where the saved SIM has since
     * been removed.
     */
    private fun subscriptionSms(): SmsManager? {
        val sim = sims.preferredBlocking() ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(sim.subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(sim.subscriptionId)
            }
        }.getOrNull()
    }

    fun sendSms(phoneNumber: String, message: String): AutomationResult {
        if (!granted(android.Manifest.permission.SEND_SMS)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.SEND_SMS)
        }
        return try {
            // Unlike calls, the SIM is fully controllable here: a subscription-scoped
            // SmsManager sends from the chosen SIM with no prompt at all. On a dual-SIM
            // phone that is the difference between a text that just goes and one that
            // stops to ask which line to bill.
            val smsManager = subscriptionSms() ?: context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            AutomationResult.Success("Texted $phoneNumber")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not send SMS")
        }
    }

    /** One saved contact. */
    data class Contact(val name: String, val number: String)

    /**
     * Resolves a spoken name to a saved number so calls/texts don't need the user
     * to recite digits.
     *
     * Contacts provider matching is a prefix search that respects neither case
     * consistently across OEMs nor the way people say names — "moyo" would miss
     * "MOYO" on some devices and "MoyOma" on all of them. So this pulls the
     * candidates and scores them in [FuzzyMatch], where case, accents and
     * punctuation stop being differences.
     */
    fun lookupContact(name: String): AutomationResult {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.READ_CONTACTS)
        }
        return when (val result = resolveContact(name)) {
            is ContactMatch.One -> AutomationResult.Success("${result.contact.name}: ${result.contact.number}")
            is ContactMatch.Several -> AutomationResult.Success(
                result.contacts.joinToString("\n") { "${it.name}: ${it.number}" }
            )
            ContactMatch.NoPermission ->
                AutomationResult.MissingPermission(android.Manifest.permission.READ_CONTACTS)
            ContactMatch.None -> AutomationResult.Failure("No contact matching \"$name\"")
        }
    }

    sealed class ContactMatch {
        data class One(val contact: Contact) : ContactMatch()

        /** Genuinely ambiguous — two different people, similarly named. Ask, don't dial. */
        data class Several(val contacts: List<Contact>) : ContactMatch()

        object None : ContactMatch()
        object NoPermission : ContactMatch()
    }

    /**
     * Finds who the user meant.
     *
     * Returns [ContactMatch.Several] rather than guessing when two different people
     * score alike. Opening the wrong app costs a back-press; ringing the wrong
     * person cannot be taken back, so a coin-flip is not an acceptable answer here.
     */
    fun resolveContact(spokenName: String): ContactMatch {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) return ContactMatch.NoPermission
        val query = spokenName.trim()
        if (query.isEmpty()) return ContactMatch.None

        val candidates = loadCandidates(query)
        if (candidates.isEmpty()) return ContactMatch.None

        return when (val result = FuzzyMatch.best(query, candidates) { it.name }) {
            is FuzzyMatch.Result.Found -> ContactMatch.One(result.hit.value)
            is FuzzyMatch.Result.Ambiguous -> {
                // Several numbers for one person is not an ambiguity about *who*.
                val people = result.hits.map { it.value }
                    .distinctBy { FuzzyMatch.normalise(it.name) }
                if (people.size <= 1) ContactMatch.One(result.hits.first().value)
                else ContactMatch.Several(people)
            }
            FuzzyMatch.Result.None -> ContactMatch.None
        }
    }

    /**
     * Candidate contacts for a query.
     *
     * Asks the provider's filter first — it is indexed and fast — then falls back to
     * a full scan when that returns nothing, because the provider's own matching is
     * prefix-based and misses exactly the cases this is here to fix ("moyo" for
     * "MoyOma"). The scan is bounded and only ever runs when the fast path failed.
     */
    private fun loadCandidates(query: String): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        fun read(uri: Uri, limit: Int): List<Contact> = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val displayName = cursor.getString(0) ?: continue
                        val number = cursor.getString(1) ?: continue
                        add(Contact(displayName, number))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())

        val filtered = read(
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(query)
            ),
            limit = 40
        )
        if (filtered.isNotEmpty()) return filtered

        return read(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, limit = MAX_SCAN)
    }

    /**
     * WhatsApp has no personal-automation API, so this opens a chat with the
     * message pre-filled using WhatsApp's documented `wa.me` deep link. Sending
     * still requires tapping Send — which Lain can now do herself via the
     * Accessibility Service, but it has to be an explicit follow-up step.
     */
    fun openWhatsAppChat(phoneNumber: String, message: String): AutomationResult {
        return try {
            val uri = Uri.parse("https://wa.me/${Uri.encode(phoneNumber)}?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AutomationResult.Success(
                "WhatsApp chat with $phoneNumber is open with the message drafted. It is NOT sent yet — " +
                    "call read_screen, then tap_text(\"Send\") to send it."
            )
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "WhatsApp isn't installed or couldn't be opened")
        }
    }

    fun openUrl(url: String): AutomationResult {
        return try {
            val normalized = if (url.startsWith("http")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AutomationResult.Success("Opened $normalized in the browser. Call read_screen to see the page.")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not open URL")
        }
    }
}
