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

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * ACTION_CALL normally dials immediately, but when a third-party dialer like
     * Truecaller is installed it can intercept the intent and just show its own
     * screen — the banner appears and nothing is dialled. We can't force that
     * app's hand, so instead of assuming success we check the telephony state
     * afterwards and report what actually happened.
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
            delay(2500) // give the dialer time to actually go off-hook

            when (callState()) {
                TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING ->
                    AutomationResult.Success("Calling $phoneNumber — the call is connecting.")
                else -> AutomationResult.Failure(
                    "The dialer opened for $phoneNumber but no call started — a third-party dialer (Truecaller or similar) " +
                        "is probably showing its own screen waiting for a tap. Use read_screen to see what's on screen and " +
                        "tap the call button, or tell the user to tap it."
                )
            }
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not place call")
        }
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
