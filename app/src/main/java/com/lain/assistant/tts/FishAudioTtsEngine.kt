package com.lain.assistant.tts

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Lain's voice, from Fish Audio, cached so it keeps working without a signal.
 *
 * Fish Audio is a hosted service — the model runs on their machines, not the phone,
 * and there is no honest way to make the synthesis itself offline without shipping a
 * neural TTS model in the app, which was ruled out as too heavy and would be.
 *
 * What is offline is everything she says on the fast path. Those lines are fixed and
 * countable, so [VoicePack] renders them all once and [VoiceCache] keeps the audio;
 * from then on the whole no-network half of the app speaks in her real voice. A
 * reply that came from a model still needs the network — but that reply needed the
 * network to exist at all, so nothing is lost that was ever available.
 *
 * When there is no cached audio and no connection, [speak] fails rather than
 * hanging, and [TtsEngineProvider] has already arranged for the device voice to take
 * over. Silence would be the worst outcome of all three.
 */
class FishAudioTtsEngine(
    private val context: Context,
    private val apiKey: String,
    /**
     * A voice from Fish Audio's library, or blank for the model's default.
     *
     * Not hardcoded to a particular voice: the library is theirs, the ids change,
     * and picking one by name from memory would ship an id that may not resolve —
     * a voice that silently fails is worse than the default that works.
     */
    private val voiceId: String = "",
    private val model: String = DEFAULT_MODEL
) : TtsEngine {

    companion object {
        const val ENDPOINT = "https://api.fish.audio/v1/tts"

        /** Their current default. Overridable, so a newer one doesn't need a release. */
        const val DEFAULT_MODEL = "s2.1-pro"

        /** Identifies the voice in the cache when the user hasn't picked one. */
        const val DEFAULT_VOICE_KEY = "default"
    }

    private val cache = VoiceCache(context)
    private val voiceKey = voiceId.ifBlank { DEFAULT_VOICE_KEY }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null

    override suspend fun speak(text: String) {
        val spoken = com.lain.assistant.agent.LainName.forSpeech(text)
        if (spoken.isBlank()) return

        val cached = cache.fileFor(voiceKey, spoken)
        if (cached.isFile && cached.length() > 0) {
            playFile(cached, deleteAfter = false)
            return
        }

        val audio = synthesise(spoken) ?: throw VoiceUnavailable()
        cache.put(voiceKey, spoken, audio)
        val file = cache.fileFor(voiceKey, spoken)
        if (file.isFile) playFile(file, deleteAfter = false) else playBytes(audio)
    }

    /**
     * One request, returning the mp3 or null.
     *
     * Null covers every reason it did not arrive — no signal, a rejected key, a
     * voice id that no longer resolves — because the caller does the same thing for
     * all of them: fall back to a voice that works. What it must not do is throw
     * into the speech path and leave her mute.
     */
    suspend fun synthesise(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("text", text)
            if (voiceId.isNotBlank()) put("reference_id", voiceId)
            put("format", "mp3")
            put("mp3_bitrate", 128)
            // Their "balanced" sits between first-word latency and prosody. Lain
            // speaks in short lines, where a late start is more noticeable than a
            // slightly flatter sentence.
            put("latency", "balanced")
            put("normalize", true)
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("model", model)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** Raised when there is neither cached audio nor a way to fetch any. */
    class VoiceUnavailable : Exception("No cached audio for this line and no connection to Fish Audio")

    private suspend fun playBytes(bytes: ByteArray) {
        val temp = File.createTempFile("lain_fish", ".mp3", context.cacheDir)
        temp.writeBytes(bytes)
        playFile(temp, deleteAfter = true)
    }

    private suspend fun playFile(file: File, deleteAfter: Boolean) =
        suspendCancellableCoroutine<Unit> { cont ->
            val mp = MediaPlayer().apply {
                runCatching { setDataSource(file.absolutePath) }.onFailure {
                    if (deleteAfter) file.delete()
                    if (cont.isActive) cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                setOnCompletionListener {
                    release()
                    if (deleteAfter) file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    if (deleteAfter) file.delete()
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                setOnPreparedListener { start() }
                prepareAsync()
            }
            player = mp
            cont.invokeOnCancellation {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                if (deleteAfter) file.delete()
            }
        }

    override fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
