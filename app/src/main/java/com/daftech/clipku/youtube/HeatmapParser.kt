package com.daftech.clipku.youtube

import com.daftech.clipku.model.HeatPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser heatmap "Most Replayed" — BEST-EFFORT, tidak dijamin ada.
 *
 * YouTube tidak mendokumentasikan letak data ini di respons player.
 * Parser ini memindai JSON secara rekursif untuk objek penanda heatmap
 * (kunci mengandung "heat") dan array intensitas. Kalau tidak ditemukan,
 * hasilnya list kosong — itu KONDISI NORMAL untuk video baru / sepi
 * penonton, bukan error. Scoring kemudian mengandalkan transkrip + audio.
 *
 * Jangan pernah mengarang heatmap: tidak ada data = skor heatmap 0 untuk
 * semua detik, dan bobotnya didistribusikan ulang ke sinyal yang ada.
 */
object HeatmapParser {
    /**
     * v1.0.8 P0-3: parse butuh durasi video untuk mengubah
     * timeRangeStartMillis (ms absolut) menjadi fraksi 0..1. Tanpa
     * durasi, marker objek tidak bisa dipakai dan hanya jalur array
     * angka yang hidup.
     */
    fun parse(root: JSONObject, durationMs: Long = 0L): List<HeatPoint> {
        val found = mutableListOf<HeatPoint>()
        scan(root, found, durationMs)
        // Normalisasi + urutkan + buang duplikat fraksi.
        return found
            .filter { it.fraction in 0.0..1.0 && it.intensity in 0.0..1.0 }
            .sortedBy { it.fraction }
            .distinctBy { (it.fraction * 1000).toInt() }
    }

    private fun scan(value: Any?, out: MutableList<HeatPoint>, durationMs: Long) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.contains("heat", ignoreCase = true) && child is JSONArray) {
                        parseHeatArray(child, out)
                    }
                    // Penanda klasik: heatMarkers / markers dengan decoration / intensity.
                    if ((key == "heatMarkers" || key == "markers") && child is JSONArray) {
                        parseMarkers(child, out, durationMs)
                    }
                    scan(child, out, durationMs)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) scan(value.opt(i), out, durationMs)
            }
            else -> Unit
        }
    }

    private fun parseHeatArray(array: JSONArray, out: MutableList<HeatPoint>) {
        // Bentuk yang diharapkan: array angka intensitas 0..1, indeks = fraksi.
        if (array.length() < 10) return
        var allNumbers = true
        for (i in 0 until array.length()) {
            if (array.opt(i) !is Number) { allNumbers = false; break }
        }
        if (!allNumbers) return
        for (i in 0 until array.length()) {
            val intensity = array.optDouble(i)
            if (intensity in 0.0..1.0) {
                out.add(HeatPoint(i.toDouble() / array.length().toDouble(), intensity))
            }
        }
    }

    /**
     * v1.0.8 P0-3 — format ASLI YouTube: array OBJEK heatMarkers,
     * tiap objek membawa timeRangeStartMillis (ms absolut) +
     * heatMarkerIntensity (0..1). Bentuk yang ditangani, semuanya
     * best-effort:
     *  - {"timeRangeStartMillis": 12345, "heatMarkerIntensity": 0.8}
     *  - {"heatMarkerRenderer": {"timeRangeStartMillis": ..., ...}}
     *  - timeRangeStartMillis sebagai angka, string angka, atau objek
     *    {"value": ...} / {"simpleText": "..."}; intensitas sebagai
     *    angka, string, atau objek {"value": ...}.
     * Dulu parseMarkers menyimpan fraksi -1.0 yang lalu dibuang
     * parse() — jalur markers SELALU mati. Sekarang fraksi dihitung
     * dari durasi video; tanpa durasi (>0) marker dilewati.
     */
    private fun parseMarkers(
        array: JSONArray,
        out: MutableList<HeatPoint>,
        durationMs: Long
    ) {
        if (durationMs <= 0L) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val node = item.optJSONObject("heatMarkerRenderer") ?: item
            val startMs = readMillis(node, "timeRangeStartMillis")
            val intensity = readDouble(node, "heatMarkerIntensity")
            if (startMs >= 0 && intensity in 0.0..1.0) {
                val fraction = startMs.toDouble() / durationMs.toDouble()
                if (fraction in 0.0..1.0) {
                    out.add(HeatPoint(fraction, intensity))
                }
            }
        }
    }

    /** Baca ms dari angka / string angka / objek {"value"|"simpleText"}. -1 kalau tidak ada. */
    private fun readMillis(obj: JSONObject, key: String): Long {
        return when (val v = obj.opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: -1L
            is JSONObject -> v.optLong("value", -1L).takeIf { it >= 0 }
                ?: v.optString("simpleText", "").trim().toLongOrNull() ?: -1L
            else -> -1L
        }
    }

    /** Baca double dari angka / string / objek {"value"}. -1.0 kalau tidak ada. */
    private fun readDouble(obj: JSONObject, key: String): Double {
        return when (val v = obj.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: -1.0
            is JSONObject -> v.optDouble("value", -1.0)
            else -> -1.0
        }
    }
}
