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
 * The audio comes from public recitation archives that publish complete surahs
 * under stable URLs. Nothing about the user is sent — the request is a surah number
 * and nothing else. The text of the Qur'an is not bundled or modified; this plays a
 * published recording, unaltered, in full.
 */
class QuranPlayer(private val context: Context) {

    companion object {

        /**
         * One reciter, and every place their recitation can be fetched from.
         *
         * The list is a list because the first source was wrong. Recitation used to
         * be built from `cdn.islamic.network/quran/audio-surah/...` with ten reciter
         * identifiers taken from that service's own catalogue — and seven of the ten
         * answer 403 on the surah path, because the catalogue lists editions that
         * exist per *ayah* and not per surah. So "recite Al-Kahf with Sudais" failed
         * every time, and only Alafasy and Abdul Basit ever worked.
         *
         * quranicaudio.com publishes complete surahs for all of them and is checked
         * first; islamic.network stays as a second source for the two it does serve,
         * because two hosts failing at once is rarer than one.
         */
        private data class Reciter(
            val id: String,
            val name: String,
            /** Spoken forms that should reach this reciter. */
            val aliases: List<String>,
            /** quranicaudio path segment, no leading or trailing slash. */
            val quranicAudioPath: String,
            /** islamic.network edition identifier, where that host serves whole surahs. */
            val islamicNetworkEdition: String? = null
        ) {
            /** Sources in the order they should be tried. */
            fun urls(surah: Int): List<String> = buildList {
                add("https://download.quranicaudio.com/quran/$quranicAudioPath/%03d.mp3".format(surah))
                islamicNetworkEdition?.let {
                    add("https://cdn.islamic.network/quran/audio-surah/128/$it/$surah.mp3")
                }
            }
        }

        private val RECITERS = listOf(
            Reciter(
                "alafasy", "Mishary Rashid Alafasy",
                listOf("alafasy", "afasy", "mishary", "meshary"),
                "mishaari_raashid_al_3afaasee", "ar.alafasy"
            ),
            Reciter(
                "sudais", "Abdur-Rahman as-Sudais",
                listOf("sudais", "sudays", "sadees"),
                "abdurrahmaan_as-sudays"
            ),
            Reciter(
                "abdulbasit", "Abdul Basit (Murattal)",
                listOf("abdul basit", "abdulbasit", "abdel basit", "basit"),
                "abdul_basit_murattal", "ar.abdulbasitmurattal"
            ),
            Reciter(
                "minshawi", "Muhammad Siddiq al-Minshawi",
                listOf("minshawi", "minshawy", "menshawi"),
                "muhammad_siddeeq_al-minshaawee"
            ),
            Reciter(
                "husary", "Mahmoud Khalil al-Husary",
                listOf("husary", "husari", "hosary"),
                "mahmood_khaleel_al-husaree_iza3a"
            ),
            Reciter(
                "shatri", "Abu Bakr ash-Shatri",
                listOf("shatri", "shaatri", "shaatree", "abu bakr"),
                "abu_bakr_ash-shaatree"
            ),
            Reciter(
                "ajmy", "Ahmed ibn Ali al-Ajmy",
                listOf("ajmy", "ajami", "ajamy"),
                "ahmed_ibn_3ali_al-3ajamy"
            ),
            Reciter(
                "shuraym", "Saud ash-Shuraym",
                listOf("shuraym", "shuraim", "shuraem"),
                "sa3ood_al-shuraym"
            ),
            Reciter(
                "ghamdi", "Saad al-Ghamdi",
                listOf("ghamdi", "ghamidi", "ghaamidi"),
                "sa3d_al-ghaamidi/complete"
            ),
            Reciter(
                "muaiqly", "Maher al-Muaiqly",
                listOf("muaiqly", "muaiqli", "maher", "moaikly"),
                "maher_256"
            ),
            Reciter(
                "dussary", "Yasser ad-Dussary",
                listOf("dussary", "dossary", "yasser"),
                "yasser_ad-dussary"
            ),
            Reciter(
                "juhani", "Abdullah Awad al-Juhani",
                listOf("juhani", "juhany", "juhaynee"),
                "abdullaah_3awwaad_al-juhaynee"
            ),
            Reciter(
                "hudhaify", "Ali al-Hudhaify",
                listOf("hudhaify", "huthaify", "hudhaifi"),
                "huthayfi"
            ),
            Reciter(
                "abkar", "Idrees Abkar",
                listOf("abkar", "idrees", "idris"),
                "idrees_abkar"
            ),
            Reciter(
                "qatami", "Nasser al-Qatami",
                listOf("qatami", "qatamy", "nasser"),
                "nasser_bin_ali_alqatami"
            )
        )

        private val DEFAULT_RECITER = RECITERS.first()

        /** The reciter a spoken name asks for, or the default. */
        private fun reciterFor(spoken: String): Reciter {
            val needle = spoken.lowercase()
            if (needle.isBlank()) return DEFAULT_RECITER
            return RECITERS.firstOrNull { r -> r.aliases.any { needle.contains(it) } }
                ?: DEFAULT_RECITER
        }

        /** The reciters offered, so "who can you recite as" has a real answer. */
        fun reciterNames(): List<String> = RECITERS.map { it.name }

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
     * Each source is tried in turn and success is reported only once a stream has
     * actually prepared, so "reciting Al-Kahf" is never said over a connection that
     * never opened. When every source fails the reply says so and names the reciter,
     * rather than falling back silently to a different voice than the one asked for.
     */
    suspend fun recite(spoken: String, reciterName: String = ""): ToolResult {
        val surah = QuranIndex.find(spoken)
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "I don't know a surah called \"$spoken\". Give me the name or the number, 1 to 114."
            )

        val reciter = reciterFor(reciterName)
        stop()

        for (url in reciter.urls(surah.number)) {
            if (open(url, surah)) {
                return ToolResult.ok(
                    "Reciting ${surah.name} — ${surah.meaning}, ${surah.ayahCount} ayat, " +
                        "recited by ${reciter.name}."
                )
            }
            // A half-opened player has to go before the next attempt, or the second
            // source prepares into a MediaPlayer that is already in an error state.
            stop()
        }

        return ToolResult.fail(
            FailureKind.TOOL_FAILURE,
            "Couldn't start ${surah.name} with ${reciter.name} — neither audio source answered. " +
                "Recitation streams, so it needs a connection."
        )
    }

    /**
     * Opens one stream, returning whether it is genuinely playing.
     *
     * [MediaPlayer.prepareAsync] reports a bad URL through the error listener rather
     * than by throwing, which is why this waits for one of the two callbacks instead
     * of treating "no exception" as success.
     */
    private suspend fun open(url: String, surah: QuranIndex.Surah): Boolean = runCatching {
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(url)
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
