package com.daftech.clipku.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Klien Groq OpenAI-compatible (https://api.groq.com/openai/v1/chat/completions).
 *
 * API key HANYA dari Settings yang diisi user di HP, disimpan di
 * SharedPreferences privat (Prefs), TIDAK pernah di-hardcode, TIDAK pernah
 * di-log, dan TIDAK pernah ditulis ke file lain. Kalau key kosong, semua
 * pemanggilan ditolak di ViewModel sebelum sampai ke sini — fungsi di sini
 * tetap memeriksa ulang dan melempar pesan yang bisa ditampilkan ke user.
 *
 * Model: gpt-oss-120b utama (sama keluarga dengan pipeline Nalar),
 * fallback gpt-oss-20b kalau model utama gagal.
 */
class GroqClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val PRIMARY_MODEL = "openai/gpt-oss-120b"
        const val FALLBACK_MODEL = "openai/gpt-oss-20b"
        private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    /** Skor semantik per segmen: map segmenId -> (skor 0-100, judul, alasan). */
    data class SegmentScore(val score: Int, val title: String, val reason: String)

    suspend fun scoreSegments(
        apiKey: String,
        videoTitle: String,
        segments: List<SegmentInput>
    ): Map<Int, SegmentScore> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
        if (segments.isEmpty()) return@withContext emptyMap()
        val segmentText = segments.joinToString("\n\n") { segment ->
            "[${segment.id}] ${segment.startLabel}-${segment.endLabel}\n${segment.text}"
        }
        val systemPrompt = """
            Kamu penilai highlight video. Nilai tiap segmen 0-100 berdasarkan:
            hook yang kuat, informasi penting atau mengejutkan, emosi atau
            konflik, dan momen yang layak ditonton ulang. Jangan menilai
            dari panjang teks, jangan mengarang isi di luar teks segmen.
            Balas HANYA JSON: {"scores":[{"id":0,"score":0,"title":"...","reason":"..."}]}
            Judul maksimal 8 kata, bahasa sama dengan teks segmen.
            Alasan maksimal 12 kata.
        """.trimIndent()
        val userPrompt = "Judul video: $videoTitle\n\nSegmen:\n$segmentText"
        val raw = callChat(apiKey, systemPrompt, userPrompt)
        parseScores(raw)
    }

    data class SegmentInput(
        val id: Int,
        val startLabel: String,
        val endLabel: String,
        val text: String
    )

    /** Narasi final siap copas ke SuaraKu. Validasi panjang dilakukan di Narration. */
    suspend fun generateNarration(
        apiKey: String,
        videoTitle: String,
        clipTranscript: String
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
        val systemPrompt = """
            Kamu penulis narasi gaya Nalar: kritis, independen, cerdas,
            antimainstream, dan mencerdaskan. Tulis narasi Bahasa Indonesia
            untuk voiceover video, 110-140 kata, dalam 3 paragraf pendek
            tanpa judul dan tanpa label apa pun.
            Aturan keras:
            - Hanya berdasarkan transkrip di bawah. Jangan mengarang nama,
              angka, tanggal, atau peristiwa yang tidak ada di transkrip.
              Kalau sesuatu tidak jelas di transkrip, jangan sebutkan
              sebagai fakta.
            - Paragraf pertama: hook, kenapa momen ini penting.
            - Paragraf kedua: fakta inti dari transkrip.
            - Paragraf ketiga: sudut kritis — apa yang tidak dibilang, apa
              implikasinya, atau pertanyaan yang layak ditanyakan penonton.
              Sampaikan sudut ini sebagai kalimat opini atau pertanyaan,
              bukan sebagai label.
            - Teks final saja: tanpa markdown, tanpa tanda bintang atau pagar,
              tanpa label, tanpa timestamp, tanpa emoji, tanpa URL, tanpa
              arahan panggung, tanpa daftar. Kalimat pendek, tanda baca
              alami untuk jeda bicara. Jangan pakai simbol mentah seperti
              %, $, &, /, +, =. Tulis persen, dan, dan sebagainya dengan kata.
        """.trimIndent()
        val userPrompt = "Judul video: $videoTitle\n\nTranskrip clip:\n$clipTranscript"
        callChat(apiKey, systemPrompt, userPrompt).trim()
    }

    private fun callChat(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val body = JSONObject().apply {
            put("model", PRIMARY_MODEL)
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", userPrompt)
                })
            })
        }
        val result = postOnce(apiKey, body, PRIMARY_MODEL)
        if (result != null) return result
        val fallbackBody = JSONObject(body.toString()).apply { put("model", FALLBACK_MODEL) }
        return postOnce(apiKey, fallbackBody, FALLBACK_MODEL)
            ?: throw IllegalStateException(
                "Groq gagal menjawab. Periksa Groq API key di Settings dan koneksi internet."
            )
    }

    private fun postOnce(apiKey: String, body: JSONObject, model: String): String? {
        return try {
            val request = Request.Builder()
                .url(URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val text = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) return null
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: ""
            if (content.isBlank()) null else content
        } catch (e: Exception) {
            null
        }
    }

    private fun parseScores(raw: String): Map<Int, SegmentScore> {
        val out = mutableMapOf<Int, SegmentScore>()
        try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) return out
            val json = JSONObject(raw.substring(start, end + 1))
            val scores = json.optJSONArray("scores") ?: return out
            for (i in 0 until scores.length()) {
                val item = scores.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id < 0) continue
                out[id] = SegmentScore(
                    score = item.optInt("score", 0).coerceIn(0, 100),
                    title = item.optString("title", "").take(80),
                    reason = item.optString("reason", "").take(120)
                )
            }
        } catch (e: Exception) {
            // JSON LLM tidak sempurna = skor LLM diabaikan, bukan crash.
            return out
        }
        return out
    }
}
