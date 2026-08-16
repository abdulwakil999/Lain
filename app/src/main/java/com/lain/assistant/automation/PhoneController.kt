package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/** Native telephony actions — these have real platform hooks, no accessibility trickery needed. */
class PhoneController(private val context: Context) {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun placeCall(phoneNumber: String): AutomationResult {
        if (!granted(android.Manifest.permission.CALL_PHONE)) {
            return AutomationResult.MissingPermission(android.Manifest.permission.CALL_PHONE)
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AutomationResult.Success("Calling $phoneNumber")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not place call")
        }
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

    /**
     * WhatsApp has no personal-automation API, so this opens a chat with the
     * message pre-filled using WhatsApp's documented `wa.me` deep link. The
     * user (or, with the Accessibility Service, Lain itself) still has to
     * tap Send — WhatsApp doesn't expose a "send without opening" hook.
     */
    fun openWhatsAppChat(phoneNumber: String, message: String): AutomationResult {
        return try {
            val uri = Uri.parse("https://wa.me/${Uri.encode(phoneNumber)}?text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AutomationResult.Success("Opened WhatsApp chat with $phoneNumber, message drafted — tap send (or let Lain's Accessibility Service do it once enabled).")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "WhatsApp isn't installed or couldn't be opened")
        }
    }
}
