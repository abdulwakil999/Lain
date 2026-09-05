package com.lain.assistant.agent

import java.security.MessageDigest
import java.util.Locale

/**
 * The few constants that should not read as plain text in the source.
 *
 * Two different jobs, handled two different ways.
 *
 * Things that have to be *shown* — a word she says out loud — cannot be one-way
 * hashed, so they are stored as bytes and assembled at runtime. That is obfuscation
 * and nothing more: it keeps them out of a `strings` dump and out of a casual read
 * of the file, and it would not survive ten minutes of deliberate work with a
 * decompiler. Any APK can be taken apart. This raises the cost; it does not make it
 * impossible, and treating it as though it did would be the more dangerous mistake.
 *
 * Things that only have to be *checked* — a phrase someone types — are never stored
 * at all. Only a salted digest of each is here, so the phrases genuinely do not
 * exist in the binary in any form, and comparing is a hash of the input against a
 * hash of nothing anyone can read back. That half is properly out of reach.
 *
 * Nothing here is a security boundary. It changes how Lain speaks to someone and
 * grants no capability: every confirmation before an irreversible action applies
 * identically whoever is holding the phone.
 */
internal object Vault {

    private const val K = 0x5C

    /** Rebuilt each call rather than held, so it is not sitting in a field to be read. */
    private fun reveal(bytes: IntArray): String =
        String(bytes.map { (it xor K).toByte() }.toByteArray())

    private val A = intArrayOf(0x0f, 0x3d, 0x30, 0x35, 0x31, 0x3d)
    private val B = intArrayOf(0x1d, 0x3e, 0x38, 0x29, 0x30, 0x2b, 0x3d, 0x37, 0x35, 0x30)

    /** The subject of the question only he can answer. */
    fun subject(): String = reveal(A)

    /**
     * His actual name.
     *
     * Held so she is not ignorant of it, and never spoken — see the prompt, which
     * tells her plainly not to say it. There is no reply bank containing it.
     */
    fun maker(): String = reveal(B)

    private const val SALT = "necio::"

    /** The right answer to the question. Stored as a digest; the word is not here. */
    private const val PASS =
        "5b0420636ff9c783ad095601fd367303d82a2d0b5377440725b64623bd2591c4"

    /** The phrase that stands the role down. Also only a digest. */
    private const val STAND_DOWN =
        "c34b44d6cefa5e286b19911c8de19b30a59c2151458aff581d0c38ae235597dc"

    /**
     * Whether [text] carries the answer.
     *
     * Checks the whole normalised message and each word in it, so the answer counts
     * inside a sentence as well as alone. Comparison is digest against digest, which
     * means a wrong guess reveals nothing and the right one is never written down.
     */
    fun isPass(text: String): Boolean = matches(text, PASS, wordwise = true)

    /**
     * Checked against every message, so it has to be cheap before it is thorough.
     *
     * The word limit is not a rule about the phrase, it is a guard: hashing is the
     * expensive part and the router runs this on the hot path for everything typed.
     * Without the guard, ordinary sentences were paying for a digest per word and
     * routing went from tens of microseconds to over a hundred.
     */
    fun isStandDown(text: String): Boolean {
        val whole = normalise(text)
        // Length first, because it is free and a digest is not. This is the difference
        // between hashing every short command someone types and hashing almost none:
        // the router runs on the hot path and "torch on" should not pay for SHA-256.
        //
        // It does concede the phrase's length to anyone reading this, which is a far
        // smaller thing to give away than the phrase, and the alternative was a
        // measurable slowdown on every message.
        if (whole.length != STAND_DOWN_LENGTH) return false
        return hash(whole) == STAND_DOWN
    }

    private const val STAND_DOWN_LENGTH = 12

    /**
     * @param wordwise also hash each word, so an answer counts inside a sentence.
     *   Off for anything on the hot path — it is a digest per word.
     */
    private fun matches(text: String, digest: String, wordwise: Boolean): Boolean {
        val whole = normalise(text)
        if (whole.isEmpty()) return false
        if (hash(whole) == digest) return true
        if (!wordwise) return false
        return whole.split(" ").any { it.isNotBlank() && hash(it) == digest }
    }

    private fun normalise(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT + value).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
