package com.daftech.clipku

import com.daftech.clipku.youtube.NewPipeStreamExtractor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType

/**
 * v1.4.0 (P2-9): unit test ranking audio NewPipe — tanpa network/key.
 * Aturan (angka kecil menang): 0 = ORIGINAL + bahasa cocok,
 * 1 = bahasa cocok, 2 = ORIGINAL saja, 3 = nama mengandung
 * "original", 4 = tipe lain / tidak diketahui, 5 = DUBBED /
 * DESCRIPTIVE yang bahasanya tidak cocok.
 */
class AudioRankTest {

    private fun stream(
        locale: String?,
        type: AudioTrackType?,
        name: String? = null,
        bitrate: Int = 128_000
    ): AudioStream {
        val builder = AudioStream.Builder()
            .setId("test")
            .setContent("https://example.com/audio.m4a", true)
            .setAverageBitrate(bitrate)
        if (locale != null) builder.setAudioLocale(Locale(locale))
        if (type != null) builder.setAudioTrackType(type)
        if (name != null) builder.setAudioTrackName(name)
        return builder.build()
    }

    @Test
    fun audioRank_originalAndMatchingLanguage_isZero() {
        val s = stream("id", AudioTrackType.ORIGINAL)
        assertEquals(0, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_indonesianCodeIn_normalisesToId() {
        // v1.0.5: kode "in" (Java lama untuk Indonesia) harus tetap
        // dihitung cocok dengan "id".
        val s = stream("in", AudioTrackType.ORIGINAL)
        assertEquals(0, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_matchingLanguageNonOriginal_isOne() {
        val s = stream("id", AudioTrackType.DUBBED)
        assertEquals(1, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_originalWrongLanguage_isTwo() {
        val s = stream("en", AudioTrackType.ORIGINAL)
        assertEquals(2, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_dubbedWrongLanguage_isFive() {
        val s = stream("en", AudioTrackType.DUBBED)
        assertEquals(5, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_unknownLanguageAndType_isFour() {
        val s = stream(null, null)
        assertEquals(4, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun audioRank_nameContainsOriginal_isThree() {
        // Tipe tidak diketahui tapi nama track mengandung "original".
        val s = stream("en", null, name = "Original soundtrack")
        assertEquals(3, NewPipeStreamExtractor.audioRank(s, "id"))
    }

    @Test
    fun pickAudioStreams_originalIndonesianWinsOverDubbedEnglish() {
        val dubbedEn = stream("en", AudioTrackType.DUBBED, bitrate = 192_000)
        val originalId = stream("id", AudioTrackType.ORIGINAL, bitrate = 96_000)
        val picked = NewPipeStreamExtractor.pickAudioStreams(
            listOf(dubbedEn, originalId), "id"
        )
        assertEquals(2, picked.size)
        // Rank menang atas bitrate: original-id (rank 0) dulu walau
        // bitrate-nya lebih rendah dari dubbed-en (rank 5).
        assertEquals(
            "id",
            picked.first().audioLocale?.language
        )
        assertEquals(AudioTrackType.ORIGINAL, picked.first().audioTrackType)
    }

    @Test
    fun pickAudioStreams_emptyOrNull_returnsEmpty() {
        assertTrue(NewPipeStreamExtractor.pickAudioStreams(emptyList(), "id").isEmpty())
        assertTrue(NewPipeStreamExtractor.pickAudioStreams(null, "id").isEmpty())
    }
}
