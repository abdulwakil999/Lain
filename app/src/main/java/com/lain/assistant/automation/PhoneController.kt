package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Native telephony actions — these have real platform hooks, no accessibility trickery needed. */
class PhoneController(private val context: Context) {

    private companion object {
        /** Long enough for a SIM picker to be answered, short enough not to hang a turn. */
        const val CALL_WAIT_MS = 12_000L
        const val CALL_POLL_MS = 400L

        val DIALER_HINTS = listOf(
            "dialer", "telecom", "incallui", "phone", "truecaller", "contacts"
        )
    }

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

    fun sendSms(phoneNumber: String, message: String): AutomationResult {
        if (!granted(android.Manifest.permission.SEND_SMS)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.SEND_SMS)
        }
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            AutomationResult.Success("Texted $phoneNumber")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not send SMS")
        }
    }

    /** Resolves a spoken name to a saved number so calls/texts don't need the user to recite digits. */
    fun lookupContact(name: String): AutomationResult {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.READ_CONTACTS)
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(name.trim())
            )
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val matches = mutableListOf<String>()
                while (cursor.moveToNext() && matches.size < 5) {
                    val displayName = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    matches += "$displayName: $number"
                }
                if (matches.isEmpty()) {
                    AutomationResult.Failure("No contact matching \"$name\"")
                } else {
                    AutomationResult.Success(matches.joinToString("\n"))
                }
            } ?: AutomationResult.Failure("Couldn't read contacts")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Contact lookup failed")
        }
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
