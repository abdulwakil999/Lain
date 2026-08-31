package com.lain.assistant.tts

import android.content.Context
import com.lain.assistant.agent.Replies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Downloads Lain's voice, once, so it works with no signal afterwards.
 *
 * "Download the voice" cannot mean fetching the model: Fish Audio runs on their
 * machines, there is no on-device build of it, and shipping any neural TTS into the
 * APK is the heavyweight thing that was ruled out. So this downloads the *output*
 * instead — every line in [Replies.fixedLines], synthesised once and kept in
 * [VoiceCache].
 *
 * That is not a workaround, it is the right shape for this app. The lines Lain says
 * without a network are exactly the lines this renders, because both come from the
 * same place: the fast path answers from a fixed bank of strings. After the download
 * every one of them speaks in her voice on a phone in flight mode.
 *
 * A few hundred short lines, a few megabytes, and the user sees the number before
 * they start it.
 */
class VoicePack(context: Context) {

    private val cache = VoiceCache(context)

    /** How far a download has got. */
    data class Progress(
        val done: Int,
        val total: Int,
        val failed: Int,
        val finished: Boolean = false
    )

    /** Lines that would be fetched, i.e. everything not already held for this voice. */
    fun missing(voiceKey: String): List<String> =
        lines().filterNot { cache.has(voiceKey, it) }

    fun total(): Int = lines().size

    fun heldCount(): Int = cache.count()
    fun heldBytes(): Long = cache.sizeBytes()
    fun clear() = cache.clear()

    /**
     * Renders everything missing, emitting progress as it goes.
     *
     * Sequential rather than parallel, and paced. A voice download is a background
     * convenience competing with whatever the user is actually doing, and firing a
     * hundred concurrent requests at a metered API to save a few seconds would risk
     * a rate limit that fails the whole thing — for a job nobody is watching finish.
     *
     * Failures are counted, not thrown. One line that will not render is one line
     * that falls back to the device voice; abandoning the other ninety-nine over it
     * would be the wrong trade, and the count is reported so the user can see it
     * happened rather than being told everything worked.
     */
    fun download(engine: FishAudioTtsEngine, voiceKey: String): Flow<Progress> = flow {
        val work = missing(voiceKey)
        val total = work.size
        if (total == 0) {
            emit(Progress(done = 0, total = 0, failed = 0, finished = true))
            return@flow
        }

        var done = 0
        var failed = 0
        emit(Progress(done, total, failed))

        for (line in work) {
            currentCoroutineContext().ensureActive()
            val audio = withContext(Dispatchers.IO) { engine.synthesise(line) }
            if (audio != null && cache.put(voiceKey, line, audio)) done++ else failed++
            emit(Progress(done, total, failed))
            // Paced so a long download stays a background job rather than saturating
            // the connection the user is trying to use for something else.
            delay(PACE_MS)
        }

        emit(Progress(done, total, failed, finished = true))
    }

    /**
     * Deduplicated, because the same words appear in more than one bank and the cache
     * is keyed by text — rendering a line twice would spend a request to overwrite a
     * file with itself.
     */
    private fun lines(): List<String> =
        Replies.fixedLines
            .map { com.lain.assistant.agent.LainName.forSpeech(it.text) }
            .filter { it.isNotBlank() }
            .distinct()

    private companion object {
        const val PACE_MS = 120L
    }
}
