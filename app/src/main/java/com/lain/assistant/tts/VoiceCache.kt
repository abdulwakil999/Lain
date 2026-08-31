package com.lain.assistant.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Audio Lain has already been given, kept so she can say it again with no network.
 *
 * This is what makes a cloud voice usable offline, and it works because of a
 * property of this app specifically: everything Lain answers without a model — every
 * greeting, every jab, every identity answer, the whole of [com.lain.assistant.agent.Replies]
 * — is a *fixed, finite* set of strings. Synthesise them once and the offline path
 * has her real voice for good. Only the replies that need a model need a network,
 * and those needed one anyway to exist.
 *
 * In `filesDir`, not `cacheDir`. A cache directory is the system's to empty whenever
 * storage runs short, and a voice that silently disappears the week after it was
 * downloaded is worse than one that was never offered.
 */
class VoiceCache(context: Context) {

    private val root = File(context.filesDir, "voice").apply { mkdirs() }

    /** The file that would hold this line, whether or not it exists yet. */
    fun fileFor(voiceId: String, text: String): File = File(root, name(voiceId, text))

    fun has(voiceId: String, text: String): Boolean =
        fileFor(voiceId, text).let { it.isFile && it.length() > 0 }

    /** Bytes currently held, for showing the user what the voice is costing them. */
    fun sizeBytes(): Long = runCatching {
        root.listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    fun count(): Int = runCatching { root.listFiles()?.size ?: 0 }.getOrDefault(0)

    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Writes audio for a line, atomically.
     *
     * Through a temporary file and a rename, because a synthesis interrupted halfway
     * would otherwise leave a truncated mp3 that [has] reports as present. The user
     * then gets a voice that cuts off mid-word on one particular line, forever, with
     * nothing to indicate why.
     */
    fun put(voiceId: String, text: String, bytes: ByteArray): Boolean = runCatching {
        if (bytes.isEmpty()) return false
        val target = fileFor(voiceId, text)
        val temp = File(root, target.name + ".part")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }.getOrDefault(false)

    /**
     * Keyed by voice and text together, so changing the voice does not replay the old
     * one from cache. Hashed because a line is a sentence and a filename is not.
     */
    private fun name(voiceId: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$voiceId|${text.trim()}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".mp3"
    }
}
