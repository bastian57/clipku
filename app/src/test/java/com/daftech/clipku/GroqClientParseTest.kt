package com.daftech.clipku

import com.daftech.clipku.analysis.GroqClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 (P2-9): unit test GroqClient.parseScores — tanpa network/key.
 * Parser harus tahan terhadap teks di luar JSON, skor di luar 0-100,
 * dan item tanpa id (dilewati, bukan crash).
 */
class GroqClientParseTest {

    private val client = GroqClient()

    @Test
    fun parseScores_validJsonWithPreamble_parsesAll() {
        val raw = "Berikut hasilnya:\n" +
            "{\"scores\":[" +
            "{\"id\":0,\"score\":90,\"title\":\"Hook kuat\",\"reason\":\"Momen mengejutkan\"}," +
            "{\"id\":1,\"score\":45,\"title\":\"Biasa\",\"reason\":\"\"}" +
            "]}"
        val scores = client.parseScores(raw)
        assertEquals(2, scores.size)
        assertEquals(90, scores[0]?.score)
        assertEquals("Hook kuat", scores[0]?.title)
        assertEquals("Momen mengejutkan", scores[0]?.reason)
        assertEquals(45, scores[1]?.score)
    }

    @Test
    fun parseScores_malformed_returnsEmpty() {
        assertTrue(client.parseScores("bukan json sama sekali").isEmpty())
        assertTrue(client.parseScores("").isEmpty())
        assertTrue(client.parseScores("{\"scores\": \"rusak\"}").isEmpty())
    }

    @Test
    fun parseScores_scoreClampedTo0_100() {
        val raw = "{\"scores\":[" +
            "{\"id\":0,\"score\":150,\"title\":\"T\",\"reason\":\"R\"}," +
            "{\"id\":1,\"score\":-5,\"title\":\"T\",\"reason\":\"R\"}" +
            "]}"
        val scores = client.parseScores(raw)
        assertEquals(100, scores[0]?.score)
        assertEquals(0, scores[1]?.score)
    }

    @Test
    fun parseScores_itemWithoutId_isSkipped() {
        val raw = "{\"scores\":[" +
            "{\"score\":80,\"title\":\"Tanpa id\",\"reason\":\"R\"}," +
            "{\"id\":2,\"score\":70,\"title\":\"Ada id\",\"reason\":\"R\"}" +
            "]}"
        val scores = client.parseScores(raw)
        assertEquals(1, scores.size)
        assertEquals(70, scores[2]?.score)
    }

    @Test
    fun parseScores_emptyScoresArray_returnsEmpty() {
        assertTrue(client.parseScores("{\"scores\":[]}").isEmpty())
    }

    @Test
    fun parseScores_titleAndReasonTruncated() {
        val longTitle = "T".repeat(200)
        val longReason = "R".repeat(300)
        val raw = "{\"scores\":[{\"id\":0,\"score\":50," +
            "\"title\":\"$longTitle\",\"reason\":\"$longReason\"}]}"
        val scores = client.parseScores(raw)
        assertEquals(80, scores[0]?.title?.length)
        assertEquals(120, scores[0]?.reason?.length)
    }
}
