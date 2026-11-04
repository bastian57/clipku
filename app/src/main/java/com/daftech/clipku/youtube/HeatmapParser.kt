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
    fun parse(root: JSONObject): List<HeatPoint> {
        val found = mutableListOf<HeatPoint>()
        scan(root, found)
        // Normalisasi + urutkan + buang duplikat fraksi.
        return found
            .filter { it.fraction in 0.0..1.0 && it.intensity in 0.0..1.0 }
            .sortedBy { it.fraction }
            .distinctBy { (it.fraction * 1000).toInt() }
    }

    private fun scan(value: Any?, out: MutableList<HeatPoint>) {
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
                        parseMarkers(child, out)
                    }
                    scan(child, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) scan(value.opt(i), out)
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

    private fun parseMarkers(array: JSONArray, out: MutableList<HeatPoint>) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val timeRange = item.optJSONObject("timeRangeStartMillis")
            val startMs = timeRange?.optLong("value", -1L) ?: -1L
            val intensity = item.optDouble("heatMarkerIntensity", -1.0)
            // Tanpa durasi total di node ini, fraksi tidak bisa dihitung di sini;
            // node seperti ini diabaikan — parseHeatArray adalah jalur utama.
            if (startMs >= 0 && intensity in 0.0..1.0) {
                // Disimpan sebagai intensitas mentah dengan fraksi -1 agar
                // tidak dipakai diam-diam; scoring mengabaikan fraksi < 0.
                out.add(HeatPoint(-1.0, intensity))
            }
        }
    }
}
