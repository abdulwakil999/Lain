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
 * Talks to a self-hosted Kokoro TTS server (e.g. the Kokoro-FastAPI project),
 * which exposes an OpenAI-compatible `/v1/audio/speech` endpoint. Kokoro is a
 * model, not a mobile SDK, so it needs to run somewhere reachable — a small
 * server, a home box, or a cloud instance — and [baseUrl] points at it.
 *
 * [voiceId] is fixed to one voice; Lain doesn't expose voice selection to
 * the user, it just needs to sound consistently like her.
 */
class KokoroTtsEngine(
    private val context: Context,
    private val baseUrl: String,
    private val voiceId: String = "af_heart"
) : TtsEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", "kokoro")
            // Respelled for the synthesiser only; the transcript keeps "Lain".
            put("input", com.lain.assistant.agent.LainName.forSpeech(text))
            put("voice", voiceId)
            put("response_format", "mp3")
        }

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/audio/speech")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val audioFile = File.createTempFile("lain_tts", ".mp3", context.cacheDir)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            response.body?.byteStream()?.use { input ->
                audioFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        playFile(audioFile)
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                release()
                file.delete()
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { _, _, _ ->
                release()
                file.delete()
                if (cont.isActive) cont.resume(Unit)
                true
            }
            prepareAsync()
            setOnPreparedListener { start() }
        }
        player = mp
        cont.invokeOnCancellation { mp.release() }
    }

    override fun stop() {
        player?.let { if (it.isPlaying) it.stop() }
        player?.release()
        player = null
    }
}
