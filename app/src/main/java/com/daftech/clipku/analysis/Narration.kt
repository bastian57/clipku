package com.daftech.clipku.analysis

/**
 * Validasi + pembersihan teks narasi final untuk copas ke SuaraKu.
 *
 * ATURAN FINAL (locked 2026-11-04 14:16 WIB):
 *  - Teks yang disalin HARUS plain text final saja: tanpa markdown,
 *    tanpa label, tanpa timestamp, tanpa emoji, tanpa URL, tanpa
 *    arahan panggung. Metadata (judul, timestamp, jumlah kata, estimasi
 *    durasi) adalah UI terpisah dan TIDAK PERNAH ikut tersalin.
 *  - Panjang target 110-140 kata (~50-60 detik dibaca normal).
 *  - Kalau clip tidak ada transkrip, narasi DITOLAK — tidak dibuat
 *    dari judul saja, supaya tidak mengarang.
 *
 * Pembersihan di sini adalah jaring pengaman, bukan alasan untuk
 * membiarkan LLM mengeluarkan markdown: prompt Groq sudah melarangnya.
 * Kalau hasil bersih masih di luar 110-140 kata, teks tetap ditampilkan
 * apa adanya dengan jumlah kata yang jujur di UI (di luar teks),
 * bukan dipotong diam-diam sampai rusak maknanya.
 */
object Narration {
    const val MIN_WORDS = 110
    const val MAX_WORDS = 140

    fun wordCount(text: String): Int {
        return text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    }

    /** Estimasi durasi baca: ~2.3 kata/detik (nada bicara Indonesia normal). */
    fun estimatedSeconds(text: String): Int {
        val words = wordCount(text)
        return (words / 2.3).toInt().coerceAtLeast(1)
    }

    /** Bersihkan markdown / label / timestamp / URL / emoji dari teks LLM. */
    fun clean(raw: String): String {
        var text = raw.trim()
        // Hapus blok kode markdown kalau LLM membungkus jawaban.
        text = text.replace("```", "")
        // Hapus label di awal baris seperti "Narasi:", "Hook:", "Fakta:".
        text = text.lines().joinToString("\n") { line ->
            line.replace(Regex("^(Narasi|Hook|Fakta|Analisis|Penutup|Judul)\\s*:\\s*"), "")
        }
        // Hapus timestamp [mm:ss] / (mm:ss) dan URL.
        text = text.replace(Regex("\\[?\\d{1,2}:\\d{2}(:\\d{2})?\\]?"), "")
        text = text.replace(Regex("https?://\\S+"), "")
        // Hapus karakter markdown dan simbol mentah yang akan dibaca TTS aneh.
        text = text.replace("*", "").replace("#", "").replace("_", "")
        text = text.replace("%", " persen ")
        text = text.replace("&", " dan ")
        // Emoji: buang karakter di luar rentang teks Latin umum + tanda baca.
        text = text.filter { char ->
            char.code < 0x1F000 || char == '\n'
        }
        // Rapikan spasi dan baris kosong berlebih, pertahankan maks 3 paragraf.
        val paragraphs = text.split(Regex("\n\\s*\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
        return paragraphs.joinToString("\n\n")
    }

    fun isInTargetRange(text: String): Boolean {
        val count = wordCount(text)
        return count in MIN_WORDS..MAX_WORDS
    }
}
