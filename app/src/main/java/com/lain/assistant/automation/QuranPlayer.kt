package com.lain.assistant.automation

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Recitation, played by Lain rather than handed to another app.
 *
 * Handing it off would mean opening a Qur'an app, driving its search, and hoping
 * it is installed — six taps and a dependency, for something that is a single
 * audio stream. Playing it here means "recite Al-Kahf" works on a phone with no
 * Qur'an app on it, and stops when Lain is told to stop.
 *
 * The audio comes from the Islamic Network CDN, which publishes complete recitations
 * per surah under a stable URL scheme. Nothing about the user is sent — the request
 * is a surah number and nothing else. The text of the Qur'an is not bundled or
 * modified; this plays a published recording, unaltered, in full.
 */
class QuranPlayer(private val context: Context) {

    companion object {
        /**
         * Reciters offered, keyed by the name people say.
         *
         * The identifiers are the CDN's, and the default is Alafasy because it is the
         * most widely recognised and the most complete edition on the service.
         */
        private val RECITERS = mapOf(
            "alafasy" to "ar.alafasy",
            "mishary" to "ar.alafasy",
            "sudais" to "ar.abdurrahmaansudais",
            "abdul basit" to "ar.abdulbasitmurattal",
            "abdulbasit" to "ar.abdulbasitmurattal",
            "minshawi" to "ar.minshawi",
            "husary" to "ar.husary",
            "shatri" to "ar.shaatree",
            "ajamy" to "ar.ahmedajamy",
            "ghamdi" to "ar.saoodshuraym"
        )

        private const val DEFAULT_RECITER = "ar.alafasy"

        /** Complete surah audio, 128kbps, by surah number. */
        private fun surahUrl(reciter: String, surah: Int): String =
            "https://cdn.islamic.network/quran/audio-surah/128/$reciter/$surah.mp3"

        @Volatile
        private var player: MediaPlayer? = null

        @Volatile
        private var playing: QuranIndex.Surah? = null

        /** What is being recited right now, if anything. */
        val nowPlaying: QuranIndex.Surah? get() = playing
    }

    /**
     * Starts a recitation.
     *
     * Prepared asynchronously and reported only once the stream is actually ready,
     * so "playing Al-Kahf" is not said over a connection that never opened.
     */
    suspend fun recite(spoken: String, reciterName: String = ""): ToolResult {
        val surah = QuranIndex.find(spoken)
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "I don't know a surah called \"$spoken\". Give me the name or the number, 1 to 114."
            )

        val reciter = RECITERS.entries
            .firstOrNull { reciterName.lowercase().contains(it.key) }
            ?.value ?: DEFAULT_RECITER

        stop()

        val started = runCatching {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(surahUrl(reciter, surah.number))
            }
            player = mp
            val ready = suspendCancellableCoroutine { cont ->
                mp.setOnPreparedListener { if (cont.isActive) cont.resume(true) }
                mp.setOnErrorListener { _, _, _ ->
                    if (cont.isActive) cont.resume(false)
                    true
                }
                cont.invokeOnCancellation { runCatching { mp.release() } }
                mp.prepareAsync()
            }
            if (ready) {
                // Cleared when it finishes on its own, so "what's playing" stays true.
                mp.setOnCompletionListener { playing = null }
                mp.start()
                playing = surah
            }
            ready
        }.getOrDefault(false)

        return if (started) {
            ToolResult.ok("Reciting ${surah.name} — ${surah.meaning}, ${surah.ayahCount} ayat.")
        } else {
            stop()
            ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Couldn't start ${surah.name}. Recitation streams, so it needs a connection."
            )
        }
    }

    fun stop(): Boolean {
        val mp = player ?: return false
        runCatching { if (mp.isPlaying) mp.stop() }
        runCatching { mp.release() }
        player = null
        playing = null
        return true
    }

    fun pause(): Boolean = runCatching {
        player?.takeIf { it.isPlaying }?.let { it.pause(); true } ?: false
    }.getOrDefault(false)

    fun resume(): Boolean = runCatching {
        player?.takeIf { !it.isPlaying }?.let { it.start(); true } ?: false
    }.getOrDefault(false)

    /** Describes a surah without playing it. */
    fun describe(spoken: String): ToolResult {
        val surah = QuranIndex.find(spoken)
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "I don't know a surah called \"$spoken\"."
            )
        return ToolResult.ok(surah.describe())
    }
}
