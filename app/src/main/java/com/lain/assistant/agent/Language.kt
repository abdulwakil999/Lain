package com.lain.assistant.agent

import java.util.Locale

/**
 * Which language a piece of text is in, well enough to speak it correctly.
 *
 * The model handles languages by itself — it will answer Yoruba in Yoruba without
 * being taught how. What it cannot do is fix the two places the *app* assumed
 * English: the synthesiser was pinned to Locale.US, so a French reply came out
 * read as English phonetics, and the local router only ever matched English
 * commands, so "abre WhatsApp" paid for a network round trip that "open WhatsApp"
 * did not.
 *
 * Deliberately conservative. Guessing wrong picks the wrong voice, which is worse
 * than the English default it replaces — so anything without clear evidence stays
 * English.
 */
object Language {

    enum class Tag(val code: String, val display: String) {
        ENGLISH("en", "English"),
        SPANISH("es", "Spanish"),
        FRENCH("fr", "French"),
        PORTUGUESE("pt", "Portuguese"),
        GERMAN("de", "German"),
        JAPANESE("ja", "Japanese"),
        ARABIC("ar", "Arabic"),
        HINDI("hi", "Hindi"),
        YORUBA("yo", "Yoruba"),
        HAUSA("ha", "Hausa"),
        IGBO("ig", "Igbo"),
        SWAHILI("sw", "Swahili");

        fun locale(): Locale = Locale.forLanguageTag(code)
    }

    /**
     * Words that identify a language when several appear together.
     *
     * Single hits are not enough — "no" and "la" are Spanish and also English and
     * French — so [of] requires two, which is what stops an English sentence
     * containing one borrowed word being spoken in the wrong accent.
     */
    private val MARKERS: Map<Tag, List<String>> = mapOf(
        Tag.SPANISH to listOf(
            "hola", "gracias", "por favor", "qué", "cómo", "dónde", "cuándo", "quién",
            "puedes", "quiero", "necesito", "abre", "llama", "envía", "mensaje",
            "el", "la", "los", "las", "una", "está", "estoy", "muy", "también", "pero"
        ),
        Tag.FRENCH to listOf(
            "bonjour", "merci", "s'il", "comment", "pourquoi", "où", "quand", "qui",
            "peux", "veux", "besoin", "ouvre", "appelle", "envoie", "message",
            "je", "vous", "nous", "est", "sont", "pas", "avec", "pour", "mais", "aussi"
        ),
        Tag.PORTUGUESE to listOf(
            "olá", "obrigado", "obrigada", "por favor", "como", "onde", "quando",
            "abre", "abrir", "liga", "manda", "mensagem", "você", "não", "está",
            "muito", "também", "mas", "isso", "faz"
        ),
        Tag.GERMAN to listOf(
            "hallo", "danke", "bitte", "wie", "warum", "wo", "wann", "wer",
            "öffne", "ruf", "schick", "nachricht", "ich", "nicht", "und", "das",
            "ist", "mit", "auch", "aber"
        ),
        Tag.YORUBA to listOf(
            "báwo", "bawo", "ṣé", "jọ̀wọ́", "jowo", "ẹ", "ọ", "mo", "kí", "ki",
            "ni", "wọn", "dáadáa", "daadaa", "ẹ ṣé", "ese", "pẹ̀lẹ́", "pele",
            "ṣí", "pè", "ránṣẹ́", "orúkọ", "oruko", "níbo", "nibo", "ọjọ́"
        ),
        Tag.HAUSA to listOf(
            "sannu", "yaya", "ina", "nagode", "na gode", "don allah", "ka", "ki",
            "kai", "kina", "yaushe", "wanne", "buɗe", "bude", "kira", "aika",
            "saƙo", "sako", "zan", "muna", "kuma", "amma", "lafiya"
        ),
        Tag.IGBO to listOf(
            "ndewo", "kedu", "biko", "daalụ", "daalu", "nna", "nne", "ọ", "ị",
            "gịnị", "gini", "mepee", "kpọọ", "kpoo", "zipu", "ozi", "ebee",
            "kedu ka", "ka ọ", "nke", "mma"
        ),
        Tag.SWAHILI to listOf(
            "habari", "asante", "tafadhali", "jambo", "nini", "wapi", "lini",
            "fungua", "piga", "tuma", "ujumbe", "ninataka", "sana", "lakini", "pia"
        ),
        Tag.HINDI to listOf(
            "namaste", "dhanyavaad", "kripya", "kya", "kahan", "kaise", "kaun",
            "kholo", "call karo", "bhejo", "mujhe", "aap", "hai", "nahi", "aur"
        )
    )

    /**
     * The language of [text].
     *
     * Script is checked first because it is decisive — Japanese kana, Arabic and
     * Devanagari cannot be anything else. Word markers only decide when at least two
     * land, and English is the answer whenever nothing does.
     */
    fun of(text: String): Tag {
        if (text.isBlank()) return Tag.ENGLISH

        scriptOf(text)?.let { return it }

        val words = text.lowercase()
            .split(Regex("[^\\p{L}'’]+"))
            .filter { it.isNotBlank() }
            .toSet()
        if (words.isEmpty()) return Tag.ENGLISH

        val lower = text.lowercase()
        var best: Tag? = null
        var bestScore = 0

        for ((tag, markers) in MARKERS) {
            val score = markers.count { marker ->
                if (marker.contains(' ')) lower.contains(marker) else marker in words
            }
            if (score > bestScore) {
                bestScore = score
                best = tag
            }
        }

        // Two markers, or one on a very short message where two cannot fit.
        val enough = bestScore >= 2 || (bestScore == 1 && words.size <= 3)
        return if (enough && best != null) best else Tag.ENGLISH
    }

    /** Languages whose script settles the question outright. */
    private fun scriptOf(text: String): Tag? {
        var japanese = 0
        var arabic = 0
        var devanagari = 0
        for (c in text) {
            when (c.code) {
                // Hiragana, katakana, and CJK ideographs.
                in 0x3040..0x30FF, in 0x4E00..0x9FFF -> japanese++
                in 0x0600..0x06FF -> arabic++
                in 0x0900..0x097F -> devanagari++
            }
        }
        return when {
            japanese >= 2 -> Tag.JAPANESE
            arabic >= 2 -> Tag.ARABIC
            devanagari >= 2 -> Tag.HINDI
            else -> null
        }
    }
}
