package com.daftech.clipku

import com.daftech.clipku.youtube.VttParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 (P2-9): unit test VttParser — tanpa network/key.
 */
class VttParserTest {

    @Test
    fun parse_basicTwoCues_returnsTwoSentences() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: id

            00:00:01.000 --> 00:00:04.000
            Halo dunia.

            00:00:05.000 --> 00:00:08.000
            Apa kabar?
        """.trimIndent()
        val sentences = VttParser.parse(vtt)
        assertEquals(2, sentences.size)
        assertEquals(1_000L, sentences[0].startMs)
        assertEquals(4_000L, sentences[0].endMs)
        assertEquals("Halo dunia.", sentences[0].text)
        assertEquals(5_000L, sentences[1].startMs)
        assertEquals("Apa kabar?", sentences[1].text)
    }

    @Test
    fun parse_htmlTags_stripped() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:04.000
            <c.colorCCCCCC>teks penting</c> dan <b>tebal</b>
        """.trimIndent()
        val sentences = VttParser.parse(vtt)
        assertEquals(1, sentences.size)
        assertEquals("teks penting dan tebal", sentences[0].text)
    }

    @Test
    fun parse_multiSentenceCue_splitsProportionally() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            Satu kalimat. Dua kalimat!
        """.trimIndent()
        val sentences = VttParser.parse(vtt)
        assertEquals(2, sentences.size)
        assertEquals("Satu kalimat.", sentences[0].text)
        assertEquals("Dua kalimat!", sentences[1].text)
        assertEquals(0L, sentences[0].startMs)
        assertEquals(10_000L, sentences[1].endMs)
        assertEquals(sentences[0].endMs, sentences[1].startMs)
    }

    @Test
    fun parse_emptyInput_returnsEmpty() {
        assertTrue(VttParser.parse("").isEmpty())
        assertTrue(VttParser.parse("WEBVTT\n").isEmpty())
    }

    @Test
    fun parse_hourTimestamp_parsedCorrectly() {
        val vtt = """
            WEBVTT

            01:02:03.500 --> 01:02:06.000
            Satu jam lebih.
        """.trimIndent()
        val sentences = VttParser.parse(vtt)
        assertEquals(1, sentences.size)
        assertEquals(((1 * 3600 + 2 * 60 + 3) * 1000 + 500).toLong(), sentences[0].startMs)
    }
}
