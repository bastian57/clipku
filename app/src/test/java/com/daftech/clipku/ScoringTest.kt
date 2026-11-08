package com.daftech.clipku

import com.daftech.clipku.analysis.Scoring
import com.daftech.clipku.analysis.Scoring.AnalysisInput
import com.daftech.clipku.analysis.Scoring.Segment
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0 (P2-9): unit test Scoring — tanpa network/key.
 * Menjaga aturan inti: buildSegments ~90 detik, selectClips
 * (audio-only, transcript-only, semua-kosong), pre-filter Top-12,
 * dan dedupe overlap.
 */
class ScoringTest {

    private fun sentences(count: Int, secondsEach: Long = 10L): List<Sentence> {
        return (0 until count).map { i ->
            val start = i * secondsEach * 1000L
            Sentence(
                startMs = start,
                endMs = start + secondsEach * 1000L,
                text = "Kalimat nomor $i menjelaskan hal penting dengan jelas?"
            )
        }
    }

    @Test
    fun buildSegments_splitsRoughlyEvery90Seconds() {
        val segments = Scoring.buildSegments(sentences(30))
        // 30 kalimat x 10 dtk = 300 dtk -> segmen ~90 dtk (9 kalimat).
        assertEquals(4, segments.size)
        assertEquals(0L, segments.first().startMs)
        assertTrue(segments.all { it.text.isNotBlank() })
    }

    @Test
    fun buildSegments_emptyTranscript_returnsEmpty() {
        assertTrue(Scoring.buildSegments(emptyList()).isEmpty())
    }

    @Test
    fun selectClips_audioOnly_returnsClipsAroundPeak() {
        val durationMs = 600_000L
        // Baseline rendah di mana-mana, puncak di detik 280-320.
        val audio = (0 until 600).associateWith { second ->
            if (second in 280..320) 0.9 else 0.05
        }
        val clips = Scoring.selectClips(
            AnalysisInput(
                durationMs = durationMs,
                sentences = emptyList(),
                heatPoints = emptyList(),
                audioBySecond = audio,
                llmScores = emptyMap(),
                segments = emptyList()
            ),
            "Video Tanpa Transkrip"
        )
        assertTrue("audio-only harus menghasilkan clip", clips.isNotEmpty())
        assertTrue("maks Top 6", clips.size <= 6)
        assertTrue(
            "semua clip di dalam durasi video",
            clips.all { it.startMs >= 0 && it.endMs <= durationMs && it.startMs < it.endMs }
        )
        assertTrue(
            "salah satu clip mencakup detik puncak (300)",
            clips.any { it.startMs <= 300_000L && it.endMs >= 300_000L }
        )
    }

    @Test
    fun selectClips_allSignalsEmpty_returnsEmpty() {
        val clips = Scoring.selectClips(
            AnalysisInput(
                durationMs = 600_000L,
                sentences = emptyList(),
                heatPoints = emptyList(),
                audioBySecond = emptyMap(),
                llmScores = emptyMap(),
                segments = emptyList()
            ),
            "Video Kosong"
        )
        assertTrue(clips.isEmpty())
    }

    @Test
    fun selectClips_transcriptOnly_isMarkedHonestly() {
        val sents = sentences(60)
        val segments = Scoring.buildSegments(sents)
        val clips = Scoring.selectClips(
            AnalysisInput(
                durationMs = 600_000L,
                sentences = sents,
                heatPoints = emptyList(),
                audioBySecond = emptyMap(),
                llmScores = emptyMap(),
                segments = segments
            ),
            "Video Transkrip Saja"
        )
        assertTrue("transkrip saja harus menghasilkan clip", clips.isNotEmpty())
        assertTrue(
            "clip transcript-only harus ditandai di alasan",
            clips.all { "transkrip saja" in it.reason }
        )
    }

    @Test
    fun prefilter_capsAtTop12() {
        val sents = sentences(200)
        val segments = Scoring.buildSegments(sents)
        assertTrue("butuh >12 segmen untuk menguji cap", segments.size > 12)
        val picked = Scoring.prefilterSegmentsForLlm(segments, sents)
        assertEquals(Scoring.TOP_N_LLM, picked.size)
        assertEquals(12, picked.size)
    }

    @Test
    fun prefilter_smallInput_returnsAll() {
        val sents = sentences(10)
        val segments = Scoring.buildSegments(sents)
        assertEquals(segments, Scoring.prefilterSegmentsForLlm(segments, sents))
    }

    @Test
    fun dedupeClips_dropsLowerScoreOverlap() {
        val high = Clip(
            index = 1, startMs = 0L, endMs = 60_000L,
            title = "A", reason = "r", score = 0.9, transcript = ""
        )
        val lowOverlap = Clip(
            index = 2, startMs = 10_000L, endMs = 70_000L,
            title = "B", reason = "r", score = 0.5, transcript = ""
        )
        val far = Clip(
            index = 3, startMs = 200_000L, endMs = 260_000L,
            title = "C", reason = "r", score = 0.4, transcript = ""
        )
        val kept = Scoring.dedupeClips(listOf(lowOverlap, high, far))
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.title == "A" })
        assertTrue(kept.any { it.title == "C" })
        assertTrue(kept.none { it.title == "B" })
    }

    @Test
    fun snapToSentences_emptyTranscript_centersOnPeak() {
        val (start, end) = Scoring.snapToSentences(emptyList(), 300_000L, 600_000L)
        assertEquals(270_000L, start)
        assertEquals(330_000L, end)
    }

    @Test
    fun segment_hasStartMsForTimeBasedWindows() {
        // v1.4.0 (P2-1): jendela scoring berbasis startMs — segmen
        // WAJIB membawa startMs yang benar.
        val segments = Scoring.buildSegments(sentences(30))
        assertEquals(0L, segments[0].startMs)
        assertTrue(segments[1].startMs > segments[0].startMs)
        assertTrue(segments.all { it.startMs < it.endMs })
    }
}
