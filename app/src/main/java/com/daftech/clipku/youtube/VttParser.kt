package com.daftech.clipku.youtube

import com.daftech.clipku.model.Sentence

/**
 * Parser WebVTT minimal untuk caption YouTube.
 * Format cue: "HH:MM:SS.mmm --> HH:MM:SS.mmm" lalu 1+ baris teks.
 * Tag HTML (<c>, <b>, dll) dan penanda posisi dibuang.
 * Kalimat dipecah di . ? ! supaya batas clip bisa snap ke ujung kalimat.
 */
object VttParser {
    private val timeRegex = Regex(
        """(\d+):(\d+):(\d+)\.(\d+)\s+-->\s+(\d+):(\d+):(\d+)\.(\d+)"""
    )

    fun parse(vtt: String): List<Sentence> {
        val cues = mutableListOf<Sentence>()
        var pendingStart = -1L
        var pendingEnd = -1L
        val pendingText = StringBuilder()
        fun flush() {
            if (pendingStart >= 0 && pendingText.isNotBlank()) {
                val clean = pendingText.toString()
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (clean.isNotEmpty()) {
                    cues.add(Sentence(pendingStart, pendingEnd, clean))
                }
            }
            pendingText.clear()
            pendingStart = -1L
            pendingEnd = -1L
        }
        for (rawLine in vtt.lines()) {
            val line = rawLine.trim()
            val match = timeRegex.find(line)
            if (match != null) {
                flush()
                val groups = match.groupValues
                pendingStart = toMs(groups[1], groups[2], groups[3], groups[4])
                pendingEnd = toMs(groups[5], groups[6], groups[7], groups[8])
            } else if (line.isEmpty()) {
                flush()
            } else if (line.startsWith("WEBVTT") || line.startsWith("NOTE") ||
                line.startsWith("Kind:") || line.startsWith("Language:")
            ) {
                // Header, abaikan.
            } else if (pendingStart >= 0) {
                if (pendingText.isNotEmpty()) pendingText.append(' ')
                pendingText.append(line)
            }
        }
        flush()

        // Pecah cue panjang menjadi kalimat, proporsional terhadap durasi cue.
        val sentences = mutableListOf<Sentence>()
        for (cue in cues) {
            val parts = cue.text.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size <= 1) {
                sentences.add(cue)
            } else {
                val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
                var cursor = cue.startMs
                for ((index, part) in parts.withIndex()) {
                    val share = part.length.toDouble() /
                        parts.sumOf { it.length }.coerceAtLeast(1).toDouble()
                    val partEnd = if (index == parts.lastIndex) cue.endMs
                        else cursor + (duration * share).toLong()
                    sentences.add(Sentence(cursor, partEnd, part))
                    cursor = partEnd
                }
            }
        }
        return sentences
    }

    private fun toMs(hour: String, minute: String, second: String, millis: String): Long {
        val msPart = millis.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hour.toLongOrNull() ?: 0L) * 3600 +
            (minute.toLongOrNull() ?: 0L) * 60 +
            (second.toLongOrNull() ?: 0L)) * 1000 + msPart
    }
}
