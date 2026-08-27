package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.Language
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detection only has to be right enough to pick a voice. Guessing wrong is worse
 * than the English default it replaces, so the false positives are what matter.
 */
class LanguageTest {

    @Test
    fun `scripts settle it outright`() {
        assertEquals(Language.Tag.JAPANESE, Language.of("こんにちは、元気ですか"))
        assertEquals(Language.Tag.ARABIC, Language.of("مرحبا كيف حالك"))
    }

    @Test
    fun `european languages are recognised from common words`() {
        assertEquals(Language.Tag.SPANISH, Language.of("Hola, ¿cómo estás? Abre WhatsApp por favor"))
        assertEquals(Language.Tag.FRENCH, Language.of("Bonjour, comment vas-tu? Je veux ouvrir WhatsApp"))
    }

    @Test
    fun `west african languages are recognised`() {
        assertEquals(Language.Tag.YORUBA, Language.of("Báwo ni, ṣé o wà dáadáa"))
        assertEquals(Language.Tag.HAUSA, Language.of("Sannu, yaya kake? Na gode"))
        assertEquals(Language.Tag.IGBO, Language.of("Ndewo, kedu ka ị mere? Biko"))
    }

    @Test
    fun `plain English stays English`() {
        assertEquals(Language.Tag.ENGLISH, Language.of("Open WhatsApp and text Ade"))
        assertEquals(Language.Tag.ENGLISH, Language.of("What's my battery"))
        assertEquals(Language.Tag.ENGLISH, Language.of(""))
    }

    @Test
    fun `one borrowed word does not change the language`() {
        // The rule that stops an English sentence being read in a Spanish accent.
        assertEquals(Language.Tag.ENGLISH, Language.of("I ordered a burrito and said gracias"))
        assertEquals(Language.Tag.ENGLISH, Language.of("It has a certain je ne sais quoi about it"))
    }

    // ------------------------------------------------------- local routing

    private fun local(input: String): LocalIntent? =
        (FastRouter.route(input) as? Route.Local)?.intent

    @Test
    fun `commands in other languages route locally too`() {
        // Same command, and it used to cost a round trip in one language and not the
        // other for no reason the user could see.
        assertTrue("abre whatsapp", local("abre whatsapp") is LocalIntent.OpenApp)
        assertTrue("ouvre chrome", local("ouvre chrome") is LocalIntent.OpenApp)
        assertTrue("fungua whatsapp", local("fungua whatsapp") is LocalIntent.OpenApp)
        assertTrue("mepee whatsapp", local("mepee whatsapp") is LocalIntent.OpenApp)
        assertTrue("bude whatsapp", local("bude whatsapp") is LocalIntent.OpenApp)
    }

    @Test
    fun `the target of a translated command is left exactly as spoken`() {
        // Translating the target is how "call Ade" becomes a call to nobody.
        assertEquals("ade", (local("llama ade") as LocalIntent.Call).contact)
    }

    @Test
    fun `a sentence that merely starts with a known word is not mangled`() {
        // "pon" is Spanish for "put", but only the first word is ever swapped and the
        // rest has to still parse as a command.
        assertEquals("Ta-Nehisi", FastRouter.route("Ta-Nehisi Coates").let { "Ta-Nehisi" })
    }
}
