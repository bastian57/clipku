package com.daftech.clipku.youtube

/**
 * Ekstrak videoId YouTube dari berbagai bentuk link.
 * Bentuk yang didukung: youtube.com/watch?v=, youtu.be/, youtube.com/shorts/,
 * youtube.com/embed/, youtube.com/live/, youtube.com/v/, music.youtube.com,
 * attribution_link (parameter u= yang ter-encode), dan videoId mentah
 * 11 karakter.
 * Mengembalikan null kalau tidak ada videoId yang valid.
 */
object VideoId {
    fun extract(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        // videoId mentah
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(text)) return text
        // v1.4.0 (P2-10): attribution_link — videoId tersembunyi di
        // parameter u= yang ter-URL-encode, mis.
        // youtube.com/attribution_link?a=...&u=/watch%3Fv%3DVIDEOID%26...
        // Decode dulu, lalu ekstrak dari hasilnya (rekursif, satu level).
        if ("attribution_link" in text) {
            val uParam = Regex("[?&]u=([^&]*)").find(text)?.groupValues?.get(1)
            if (uParam != null) {
                val decoded = try {
                    java.net.URLDecoder.decode(uParam, "UTF-8")
                } catch (e: Exception) {
                    null
                }
                if (decoded != null) {
                    val fromDecoded = extract(decoded)
                    if (fromDecoded != null) return fromDecoded
                }
            }
        }
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
            Regex("/live/([A-Za-z0-9_-]{11})"),
            // v1.4.0 (P2-10): format lama /v/ dan YouTube Music.
            // (music.youtube.com/watch?v= sebenarnya sudah kena pola
            // [?&]v= di atas — pola eksplisit ini untuk kejelasan dan
            // kalau suatu hari formatnya berubah.)
            Regex("/v/([A-Za-z0-9_-]{11})"),
            Regex("music\\.youtube\\.com/watch\\?[^#]*[?&]v=([A-Za-z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
