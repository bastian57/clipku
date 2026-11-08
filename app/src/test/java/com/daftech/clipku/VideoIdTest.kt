package com.daftech.clipku

import com.daftech.clipku.youtube.VideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.4.0 (P2-9 + P2-10): unit test VideoId.extract — tanpa network/key.
 * Bentuk baru v1.4.0: music.youtube.com, path /v/, dan
 * attribution_link (parameter u= ter-encode).
 */
class VideoIdTest {

    @Test
    fun extract_watchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun extract_watchUrlWithExtraParams() {
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL123")
        )
    }

    @Test
    fun extract_shortUrl() {
        assertEquals("dQw4w9WgXcQ", VideoId.extract("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://youtu.be/dQw4w9WgXcQ?t=30")
        )
    }

    @Test
    fun extract_shortsEmbedLive() {
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/embed/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/live/dQw4w9WgXcQ")
        )
    }

    @Test
    fun extract_legacyVPath() {
        // v1.4.0 (P2-10): format lama /v/.
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://www.youtube.com/v/dQw4w9WgXcQ")
        )
    }

    @Test
    fun extract_musicYoutube() {
        // v1.4.0 (P2-10): YouTube Music.
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123")
        )
    }

    @Test
    fun extract_attributionLink_decodesUParam() {
        // v1.4.0 (P2-10): videoId tersembunyi di parameter u=
        // yang ter-URL-encode.
        assertEquals(
            "dQw4w9WgXcQ",
            VideoId.extract(
                "https://www.youtube.com/attribution_link?a=abc123" +
                    "&u=/watch%3Fv%3DdQw4w9WgXcQ%26feature%3Dshare"
            )
        )
    }

    @Test
    fun extract_rawId() {
        assertEquals("dQw4w9WgXcQ", VideoId.extract("dQw4w9WgXcQ"))
    }

    @Test
    fun extract_invalid_returnsNull() {
        assertNull(VideoId.extract(""))
        assertNull(VideoId.extract("   "))
        assertNull(VideoId.extract("https://www.google.com/"))
        assertNull(VideoId.extract("https://www.youtube.com/watch"))
        assertNull(VideoId.extract("terlalu-pendek"))
    }
}
