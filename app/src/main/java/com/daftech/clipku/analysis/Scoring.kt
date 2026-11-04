package com.daftech.clipku.analysis

import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.HeatPoint
import com.daftech.clipku.model.Sentence
import com.daftech.clipku.youtube.VideoId
import kotlin.math.max

/**
 * Scoring highlight + pemilihan clip.
 *
 * Sinyal (semua dinormalisasi 0.0-1.0 per detik):
 *  - heatmap Most Replayed (best-effort, bisa kosong)
 *  - skor LLM per segmen transkrip (best-effort, butuh Groq key)
 *  - energi audio RMS per detik (best-effort, bisa kosong)
 *
 * Bobot dasar: heatmap 0.45, LLM 0.35, audio 0.20. Kalau satu sinyal
 * kosong, bobotnya didistribusikan ulang secara proporsional ke sinyal
 * yang ada — tidak ada sinyal yang diarang nilainya.
 *
 * Pemilihan: skor per detik dihaluskan (rata-rata 15 detik), puncak
 * diurutkan menurun, diambil secara serakah dengan jarak minimal 75 detik
 * antar puncak supaya clip tidak tumpang tindih. Maksimal TOP_N clip.
 *
 * Snap kalimat: dari puncak, cari kalimat yang memuat puncak, lalu
 * kembangkan ke kalimat sebelum/sesudah sampai durasi 50-70 detik
 * (target 60 detik). Kalau video lebih pendek dari itu, clip = video utuh.
 */
object Scoring {
    const val TOP_N = 6
    const val TARGET_MS = 60_000L
    const val MIN_MS = 50_000L
    const val MAX_MS = 70_000L
    const val MIN_PEAK_GAP_MS = 75_000L

    data class AnalysisInput(
        val durationMs: Long,
        val sentences: List<Sentence>,
        val heatPoints: List<HeatPoint>,
        val audioBySecond: Map<Int, Double>,
        /** Skor LLM per segmen, segmen = indeks kalimat-kalimat yang tercakup. */
        val llmScores: Map<Int, GroqClient.SegmentScore>,
        /** Daftar segmen transkrip (teks per segmen, untuk judul/alasan). */
        val segments: List<Segment>
    )

    data class Segment(
        val id: Int,
        val sentenceIndices: List<Int>,
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    /** Bagi transkrip menjadi segmen ~90 detik untuk penilaian LLM. */
    fun buildSegments(sentences: List<Sentence>): List<Segment> {
        val out = mutableListOf<Segment>()
        var current = mutableListOf<Int>()
        var startMs = 0L
        for ((index, sentence) in sentences.withIndex()) {
            if (current.isEmpty()) startMs = sentence.startMs
            current.add(index)
            val span = sentence.endMs - startMs
            if (span >= 90_000L || index == sentences.lastIndex) {
                val text = current.joinToString(" ") { sentences[it].text }
                out.add(
                    Segment(
                        id = out.size,
                        sentenceIndices = current.toList(),
                        startMs = startMs,
                        endMs = sentence.endMs,
                        text = text
                    )
                )
                current = mutableListOf()
            }
        }
        return out
    }

    /**
     * v1.0.2 JALUR AUDIO-ONLY (aturan, bukan perubahan logika baru):
     * Kalau transkrip kosong DAN heatmap kosong TAPI energi audio ada,
     * clip TETAP dibuat dari puncak audio saja. Ini sudah cara kerja
     * kode di bawah dan sengaja TIDAK diubah di v1.0.2: bobot yang
     * kosong menjadi 0, bobot yang tersisa dinormalisasi ulang
     * (audio 0.20 -> 1.0), puncak diambil dari skor audio yang
     * dihaluskan 15 detik, dan snapToSentences punya fallback untuk
     * kalimat kosong (puncak - 30 detik sampai + 60 detik, dijepit
     * durasi video). Yang BARU di v1.0.2 adalah audio itu sendiri
     * kini bisa didapat lewat NewPipe untuk video yang stream
     * Innertube-nya cipher-only — sebelumnya jalur ini mati karena
     * audioUrl-nya null. Kalau audio JUGA kosong, tetap kembali
     * emptyList (tolak jujur, bukan clip dari detik 0 atau dari sunyi).
     */
    fun selectClips(input: AnalysisInput, videoTitle: String): List<Clip> {
        val durationSec = max(1, (input.durationMs / 1000).toInt())
        val heatPerSecond = heatPerSecond(input.heatPoints, input.durationMs)
        val llmPerSecond = llmPerSecond(input, durationSec)
        val hasHeat = heatPerSecond.any { it > 0.0 }
        val hasLlm = llmPerSecond.any { it > 0.0 }
        val hasAudio = input.audioBySecond.any { it.value > 0.0 }

        var heatWeight = if (hasHeat) 0.45 else 0.0
        var llmWeight = if (hasLlm) 0.35 else 0.0
        var audioWeight = if (hasAudio) 0.20 else 0.0
        val totalWeight = heatWeight + llmWeight + audioWeight
        if (totalWeight <= 0.0) {
            // Tidak ada sinyal sama sekali: kembalikan clip kosong dengan
            // alasan jelas di ViewModel (bukan clip ngarang dari detik 0).
            return emptyList()
        }
        heatWeight /= totalWeight
        llmWeight /= totalWeight
        audioWeight /= totalWeight

        val perSecond = DoubleArray(durationSec) { second ->
            val heat = heatPerSecond.getOrElse(second) { 0.0 }
            val llm = llmPerSecond.getOrElse(second) { 0.0 }
            val audio = input.audioBySecond[second] ?: 0.0
            heatWeight * heat + llmWeight * llm + audioWeight * audio
        }
        // Haluskan 15 detik (rata-rata bergerak) supaya puncak tidak berisik.
        val smoothed = DoubleArray(durationSec) { second ->
            val from = max(0, second - 7)
            val to = minOf(durationSec - 1, second + 7)
            var sum = 0.0
            for (i in from..to) sum += perSecond[i]
            sum / (to - from + 1)
        }

        // Puncak lokal, urut menurun.
        val peaks = mutableListOf<Int>()
        for (second in 0 until durationSec) {
            val left = if (second > 0) smoothed[second - 1] else 0.0
            val right = if (second < durationSec - 1) smoothed[second + 1] else 0.0
            if (smoothed[second] >= left && smoothed[second] >= right && smoothed[second] > 0.0) {
                peaks.add(second)
            }
        }
        peaks.sortByDescending { smoothed[it] }

        val chosenPeaks = mutableListOf<Int>()
        for (peak in peaks) {
            val peakMs = peak * 1000L
            val tooClose = chosenPeaks.any { kotlin.math.abs(it * 1000L - peakMs) < MIN_PEAK_GAP_MS }
            if (!tooClose) chosenPeaks.add(peak)
            if (chosenPeaks.size >= TOP_N) break
        }
        // Kalau puncak lokal terlalu sedikit (video pendek / skor datar),
        // isi dari detik skor tertinggi yang masih berjarak cukup.
        if (chosenPeaks.size < TOP_N) {
            val byScore = (0 until durationSec).sortedByDescending { smoothed[it] }
            for (second in byScore) {
                val secondMs = second * 1000L
                val tooClose = chosenPeaks.any { kotlin.math.abs(it * 1000L - secondMs) < MIN_PEAK_GAP_MS }
                if (!tooClose) chosenPeaks.add(second)
                if (chosenPeaks.size >= TOP_N) break
            }
        }

        return chosenPeaks.sorted().mapIndexed { index, peakSecond ->
            val clipRange = snapToSentences(input.sentences, peakSecond * 1000L, input.durationMs)
            val segment = findSegmentFor(input.segments, peakSecond * 1000L)
            val llm = segment?.let { input.llmScores[it.id] }
            val title = llm?.title?.takeIf { it.isNotBlank() }
                ?: segment?.text?.take(60)?.trim().orEmpty().ifEmpty { videoTitle }
            val reasonParts = mutableListOf<String>()
            if (hasHeat) reasonParts.add("paling di-rewatch")
            if (llm != null && llm.reason.isNotBlank()) reasonParts.add(llm.reason)
            if (hasAudio) reasonParts.add("energi audio tinggi")
            Clip(
                index = index + 1,
                startMs = clipRange.first,
                endMs = clipRange.second,
                title = title,
                reason = reasonParts.joinToString(" • ").ifEmpty { "highlight auto" },
                score = smoothed[peakSecond],
                transcript = transcriptInRange(input.sentences, clipRange.first, clipRange.second)
            )
        }
    }

    private fun heatPerSecond(points: List<HeatPoint>, durationMs: Long): DoubleArray {
        val seconds = max(1, (durationMs / 1000).toInt())
        val out = DoubleArray(seconds) { 0.0 }
        val valid = points.filter { it.fraction in 0.0..1.0 }
        if (valid.isEmpty() || durationMs <= 0) return out
        for (point in valid) {
            val second = (point.fraction * seconds).toInt().coerceIn(0, seconds - 1)
            if (point.intensity > out[second]) out[second] = point.intensity
        }
        // Isi celah dengan interpolasi linear sederhana antar titik terisi.
        var lastFilled = -1
        var lastValue = 0.0
        for (second in 0 until seconds) {
            if (out[second] > 0.0) {
                lastFilled = second
                lastValue = out[second]
            } else if (lastFilled >= 0) {
                out[second] = lastValue * 0.5
            }
        }
        return out
    }

    private fun llmPerSecond(input: AnalysisInput, durationSec: Int): DoubleArray {
        val out = DoubleArray(durationSec) { 0.0 }
        for (segment in input.segments) {
            val score = input.llmScores[segment.id]?.score ?: continue
            val normalized = score / 100.0
            val from = (segment.startMs / 1000).toInt().coerceIn(0, durationSec - 1)
            val to = (segment.endMs / 1000).toInt().coerceIn(0, durationSec - 1)
            for (second in from..to) out[second] = max(out[second], normalized)
        }
        return out
    }

    private fun findSegmentFor(segments: List<Segment>, peakMs: Long): Segment? {
        return segments.firstOrNull { peakMs in it.startMs..it.endMs }
            ?: segments.minByOrNull { kotlin.math.abs(it.startMs - peakMs) }
    }

    private fun transcriptInRange(sentences: List<Sentence>, startMs: Long, endMs: Long): String {
        return sentences
            .filter { it.startMs >= startMs - 500 && it.startMs < endMs }
            .joinToString(" ") { it.text }
    }

    /** Kembangkan dari kalimat puncak sampai 50-70 detik, sedekat mungkin ke 60 detik. */
    fun snapToSentences(
        sentences: List<Sentence>,
        peakMs: Long,
        durationMs: Long
    ): Pair<Long, Long> {
        if (sentences.isEmpty()) {
            val start = max(0L, peakMs - 30_000L)
            val end = minOf(durationMs, start + TARGET_MS)
            return Pair(start, end)
        }
        var centerIndex = sentences.indexOfFirst { peakMs in it.startMs..it.endMs }
        if (centerIndex < 0) {
            centerIndex = sentences.indices.minByOrNull {
                kotlin.math.abs(sentences[it].startMs - peakMs)
            } ?: 0
        }
        var startIndex = centerIndex
        var endIndex = centerIndex
        var startMs = sentences[startIndex].startMs
        var endMs = sentences[endIndex].endMs
        // Kembangkan bergantian ke kiri/kanan sampai mencapai target,
        // jangan melewati MAX_MS.
        while (endMs - startMs < TARGET_MS) {
            val canLeft = startIndex > 0
            val canRight = endIndex < sentences.lastIndex
            if (!canLeft && !canRight) break
            if (canLeft && (!canRight || (endMs - startMs) % 2L == 0L)) {
                startIndex--
                startMs = sentences[startIndex].startMs
            } else if (canRight) {
                endIndex++
                endMs = sentences[endIndex].endMs
            }
            if (endMs - startMs > MAX_MS) break
        }
        // Kalau hasil di bawah MIN_MS karena kalimat habis, terima apa adanya
        // (video pendek) — jangan paksa dengan memotong tengah kalimat.
        startMs = startMs.coerceIn(0L, durationMs)
        endMs = endMs.coerceIn(startMs + 1_000L, durationMs)
        return Pair(startMs, endMs)
    }

    fun label(startMs: Long, endMs: Long): String {
        return "${VideoId.formatMs(startMs)} - ${VideoId.formatMs(endMs)}"
    }
}
