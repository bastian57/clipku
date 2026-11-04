package com.daftech.clipku.youtube

/**
 * Ekstrak videoId YouTube dari berbagai bentuk link.
 * Bentuk yang didukung: youtube.com/watch?v=, youtu.be/, youtube.com/shorts/,
 * youtube.com/embed/, youtube.com/live/, dan videoId mentah 11 karakter.
 * Mengembalikan null kalau tidak ada videoId yang valid.
 */
object VideoId {
    fun extract(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        // videoId mentah
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(text)) return text
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
            Regex("/live/([A-Za-z0-9_-]{11})")
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
