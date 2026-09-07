package com.lain.assistant.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.lain.assistant.data.SecureKeyStore
import com.lain.assistant.network.Http
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import com.lain.assistant.voice.VoiceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * "What song is this" — recorded here, matched by a fingerprinting service.
 *
 * Identifying music from audio is not something an app can do on its own. It needs a
 * fingerprint index of tens of millions of recordings, and there is no offline
 * shortcut: the whole method is comparing against a catalogue nobody can ship. So
 * this records a short clip and sends it to [AudD](https://audd.io), which does the
 * matching and has a free tier.
 *
 * Two consequences are stated rather than hidden. **It needs a key**, entered in
 * Settings like every other provider, and without one this says so and offers to
 * open Shazam or SoundHound if either is installed rather than pretending. **It
 * sends audio**, which is the one place in the app where microphone audio leaves the
 * phone — so it happens only when the user has asked for it in that moment, never in
 * the background, and only for as long as the sample takes.
 *
 * The clip is the shortest that reliably matches. Longer costs the user data and
 * their patience for no better result.
 */
class MusicIdentifier(private val context: Context) {

    companion object {
        private const val ENDPOINT = "https://api.audd.io/"
        private const val SAMPLE_RATE = 44_100
        private const val SECONDS = 8

        /** Apps that do this natively, offered when Lain can't. */
        private val RIVALS = listOf(
            "com.shazam.android" to "Shazam",
            "com.melodis.midomiMusicIdentifier.freemium" to "SoundHound",
            "com.google.android.googlequicksearchbox" to "Google"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Records, uploads, and reports what is playing.
     *
     * Every failure is a different sentence on purpose: no key, no permission, no
     * microphone, nothing recognised and no network are five different problems with
     * five different fixes, and collapsing them into "couldn't identify it" leaves
     * the user with nowhere to go.
     */
    suspend fun identify(): ToolResult = withContext(Dispatchers.IO) {
        val key = SecureKeyStore(context).channel(SecureKeyStore.AUDD_KEY)
        if (key.isNullOrBlank()) {
            return@withContext ToolResult.fail(
                FailureKind.PERMISSION,
                "Identifying a song needs an AudD key, which isn't set. It's free for a few " +
                    "hundred lookups a month at audd.io — add it under Settings → Song " +
                    "identification. " + suggestRival()
            )
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext ToolResult.fail(
                FailureKind.PERMISSION,
                "Listening to the music needs microphone permission, which hasn't been granted."
            )
        }

        // Through the same arbiter as everything else, so this can't fight the
        // command recogniser for the microphone.
        if (!VoiceSession.claimMicrophone(OWNER)) {
            return@withContext ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "The microphone is busy — something else on the phone has it."
            )
        }

        val clip = try {
            record()
        } catch (t: Throwable) {
            return@withContext ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                t.message ?: "Couldn't record any audio to identify."
            )
        } finally {
            VoiceSession.releaseMicrophone(OWNER)
        }

        upload(clip, key)
    }

    /**
     * Raw PCM from the microphone, wrapped as a WAV.
     *
     * MIC rather than VOICE_RECOGNITION: the recognition source applies noise
     * suppression and an aggressive band filter tuned for a person talking, which is
     * precisely wrong for music — it strips the bass and the cymbals that a
     * fingerprint is built from.
     */
    private fun record(): ByteArray {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("This device won't record at 44.1 kHz.")

        @Suppress("MissingPermission")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw IllegalStateException("Another app is using the microphone.")
        }

        val pcm = ByteArrayOutputStream()
        try {
            recorder.startRecording()
            val buffer = ByteArray(4096)
            val target = SAMPLE_RATE * 2 * SECONDS
            while (pcm.size() < target) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                pcm.write(buffer, 0, read)
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val body = pcm.toByteArray()
        if (body.size < SAMPLE_RATE) throw IllegalStateException("Didn't capture enough audio to match.")
        return wav(body)
    }

    /** A 44-byte RIFF header in front of the samples — what the service expects. */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val byteRate = SAMPLE_RATE * 2
        fun int(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun short(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        int(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        int(16)          // PCM header size
        short(1)         // PCM, uncompressed
        short(1)         // mono
        int(SAMPLE_RATE)
        int(byteRate)
        short(2)         // block align
        short(16)        // bits per sample
        out.write("data".toByteArray())
        int(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun upload(clip: ByteArray, key: String): ToolResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("api_token", key)
            .addFormDataPart("return", "apple_music,spotify")
            .addFormDataPart(
                "file", "clip.wav",
                clip.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val response = runCatching {
            Http.shared.newCall(Request.Builder().url(ENDPOINT).post(body).build()).execute()
        }.getOrElse {
            return ToolResult.fail(
                FailureKind.NETWORK,
                "Couldn't reach the song service — identifying music needs a connection."
            )
        }

        response.use {
            val text = runCatching { it.body?.string() }.getOrNull().orEmpty()
            if (!it.isSuccessful) {
                return ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "The song service answered ${it.code}. If that keeps happening the key may be " +
                        "wrong or out of free lookups."
                )
            }
            return describe(text)
        }
    }

    private fun describe(payload: String): ToolResult {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ToolResult.fail(FailureKind.TOOL_FAILURE, "The song service sent something unreadable.")

        val status = root["status"]?.jsonPrimitive?.contentOrNullSafe()
        if (status != "success") {
            val why = root["error"]?.jsonObject?.get("error_message")?.jsonPrimitive?.contentOrNullSafe()
            return ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                why ?: "The song service refused that request."
            )
        }

        val result = root["result"] as? JsonObject
            ?: return ToolResult.ok(
                "Nothing matched. That happens with live versions, covers, very quiet playback and " +
                    "anything not in the catalogue — worth trying again with the music louder."
            )

        val title = result["title"]?.jsonPrimitive?.contentOrNullSafe()
        val artist = result["artist"]?.jsonPrimitive?.contentOrNullSafe()
        if (title.isNullOrBlank() || artist.isNullOrBlank()) {
            return ToolResult.ok("Something matched but came back without a title. Try again.")
        }

        val album = result["album"]?.jsonPrimitive?.contentOrNullSafe()
        val released = result["release_date"]?.jsonPrimitive?.contentOrNullSafe()?.take(4)

        return ToolResult.ok(
            buildString {
                append(artist).append(" — ").append(title)
                if (!album.isNullOrBlank() && album != title) append(", from ").append(album)
                if (!released.isNullOrBlank()) append(" (").append(released).append(")")
                append(".")
            }
        )
    }

    /** Something that already does this, if the phone has one. */
    private fun suggestRival(): String {
        val pm = context.packageManager
        val installed = RIVALS.firstOrNull { (pkg, _) ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        } ?: return ""
        return "You have ${installed.second} installed — say \"open ${installed.second}\" and it'll do it now."
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content.takeIf { it.isNotBlank() && it != "null" } }.getOrNull()
}

/** Named holder for the microphone claim, so a leak says who leaked it. */
private const val OWNER = "song-id"
