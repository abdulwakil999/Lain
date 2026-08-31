package com.lain.assistant.tts

import android.content.Context

/**
 * Picks the voice, and arranges for there to always be one.
 *
 * Order of preference: Fish Audio when a key is set, then a self-hosted Kokoro
 * endpoint, then the device's own voice. The last of those is the reason the other
 * two can be cloud services at all — whatever happens to a network or a key,
 * something still speaks.
 */
object TtsEngineProvider {

    fun create(
        context: Context,
        kokoroEndpoint: String?,
        fishKey: String? = null,
        fishVoiceId: String = ""
    ): TtsEngine = when {
        !fishKey.isNullOrBlank() ->
            FallbackTtsEngine(
                preferred = FishAudioTtsEngine(context, fishKey, fishVoiceId),
                fallback = AndroidTtsEngine(context)
            )

        !kokoroEndpoint.isNullOrBlank() -> KokoroTtsEngine(context, kokoroEndpoint)

        else -> AndroidTtsEngine(context)
    }
}

/**
 * Speaks with [preferred], and with [fallback] when it cannot.
 *
 * The offline story in one class. A cached line plays from disk with no network; an
 * uncached one on a phone with no signal throws, and rather than leaving her mute
 * the device voice finishes the sentence. The user hears a different voice for that
 * line, which is a visible, understandable degradation — unlike silence, which is
 * indistinguishable from the app being broken.
 *
 * [stop] reaches both, because whichever one is mid-sentence has to be the one that
 * stops when the user presses stop.
 */
class FallbackTtsEngine(
    private val preferred: TtsEngine,
    private val fallback: TtsEngine
) : TtsEngine {

    override suspend fun speak(text: String) {
        val spoke = runCatching { preferred.speak(text) }.isSuccess
        if (!spoke) fallback.speak(text)
    }

    override fun stop() {
        runCatching { preferred.stop() }
        runCatching { fallback.stop() }
    }

    override suspend fun warmUp() {
        runCatching { preferred.warmUp() }
        runCatching { fallback.warmUp() }
    }
}
