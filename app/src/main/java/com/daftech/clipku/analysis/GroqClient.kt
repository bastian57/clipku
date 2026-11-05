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
 * fallback gpt-oss-20b, lalu qwen/qwen3.8-27b (model default Phoenix/Nalar
 * yang terbukti jalan dengan key yang sama).
 *
 * v1.0.4: DUA perbaikan atas kegagalan narasi + skor LLM di v1.0.3
 * (keduanya gagal sistematis padahal key ada dan key yang sama jalan
 * di pipeline Nalar):
 *  1. User-Agent browser di SEMUA request Groq. Sebelum v1.0.4 klien ini
 *     TIDAK mengirim User-Agent sama sekali (default OkHttp). Pelajaran
 *     Nalar/Phoenix: request tanpa User-Agent browser pernah kena
 *     Cloudflare error 1010 dari API Groq — kandidat utama gagalnya di HP.
 *     Kandidat, bukan jaminan: butir 2 yang akan membuktikannya di HP.
 *  2. Error asli TIDAK lagi ditelan. postOnce dulu mengembalikan null
 *     untuk semua kegagalan (non-200, konten kosong, exception) dan
 *     callChat melempar pesan generik yang sama. Sekarang kegagalan
 *     terakhir dicatat (HTTP + pesan/kode error Groq, dipotong) dan
 *     dilempar bersama pesan generik, TANPA pernah memuat API key.
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
        // v1.0.4: fallback ketiga, model default Phoenix/Nalar.
        const val QWEN_MODEL = "qwen/qwen3.8-27b"
        private val MODELS = listOf(PRIMARY_MODEL, FALLBACK_MODEL, QWEN_MODEL)
        private const val URL = "https://api.groq.com/openai/v1/chat/completions"
        // Sama persis dengan BROWSER_UA Innertube/AudioEnergy: pola yang
        // dipakai Nalar untuk lolos dari penolakan berbasis User-Agent.
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        // v1.0.7 — chunking scoring (riset bagian A/F upgrade #3:
        // AI-Shorts 20 mnt/60 dtk overlap, SupoClip 12 mnt/2 mnt
        // overlap). Dipilih 12 menit / 90 detik: satu jendela = ~8
        // segmen 90 detik x maks 1200 karakter = maks ~9.600 karakter
        // ≈ 2.400 token + prompt — jauh di bawah batas input-token per
        // menit yang membunuh tes ALI 91 menit di v1.0.6 (satu call
        // ~61 segmen ≈ 73.000 karakter → HTTP 413 di qwen3.8-27b).
        // Video pendek (<12 menit) tetap satu jendela, sama seperti dulu.
        // SATU companion object saja per class (Kotlin) — konstanta ini
        // WAJIB di sini, bukan di companion object kedua (itu yang
        // bikin v1.0.7 merah: "Only one companion object is allowed").
        const val WINDOW_MS = 12 * 60_000L
        const val WINDOW_OVERLAP_MS = 90_000L
        /** Batas keras teks per call scoring (segmen sudah dipotong 1200 char di pemanggil). */
        const val MAX_CHARS_PER_CALL = 12_000
    }

    // Kegagalan terakhir postOnce, untuk pesan error callChat.
    // TIDAK PERNAH berisi API key: hanya HTTP + pesan error Groq.
    private var lastError: String? = null

    /** Skor semantik per segmen: map segmenId -> (skor 0-100, judul, alasan). */
    data class SegmentScore(val score: Int, val title: String, val reason: String)

    /**
     * v1.0.7 — hasil scoring ber-chunk: skor per segmen + jejak jendela,
     * untuk diagnostik ("jendela LLM: X/Y berhasil") dan isolasi gagal.
     */
    data class ChunkedScoreResult(
        val scores: Map<Int, SegmentScore>,
        val windowsTotal: Int,
        val windowsSucceeded: Int,
        val windowsFailed: Int,
        /** Error terakhir (HTTP + pesan Groq, dipotong) — TIDAK PERNAH berisi key. */
        val lastError: String?
    )

    /** Satu kandidat clip untuk rerank komparatif (satu call, semua kandidat). */
    data class RerankInput(
        val id: Int,
        val startLabel: String,
        val endLabel: String,
        val title: String,
        val reason: String,
        val score: Int,
        val text: String
    )

    data class RerankResult(val id: Int, val rank: Int, val score: Int)

    /**
     * v1.0.7 — scoring ber-chunk dengan isolasi gagal per jendela.
     * Segmen dibagi jendela waktu [WINDOW_MS] dengan overlap
     * [WINDOW_OVERLAP_MS]; tiap jendela satu call scoreSegments.
     * Jendela yang gagal (413/429/timeout/parse) di-skip dan dicatat,
     * TIDAK menggagalkan video — satu jendela gagal tidak boleh
     * menolkan seluruh video seperti yang terjadi di ALI v1.0.6.
     * Segmen yang masuk dua jendela (overlap) di-dedupe: skor
     * tertinggi yang menang.
     */
    suspend fun scoreSegmentsChunked(
        apiKey: String,
        videoTitle: String,
        segments: List<SegmentInput>
    ): ChunkedScoreResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
        if (segments.isEmpty()) {
            return@withContext ChunkedScoreResult(emptyMap(), 0, 0, 0, null)
        }
        val merged = mutableMapOf<Int, SegmentScore>()
        var windowsTotal = 0
        var windowsSucceeded = 0
        var windowsFailed = 0
        var lastError: String? = null
        // Jendela dibangun dari indeks segmen, bukan label waktu teks:
        // segmen berdurasi ~90 detik (Scoring.buildSegments), jadi
        // 8 segmen ≈ 12 menit dan langkah 7 segmen ≈ overlap 90 detik.
        val segmentsPerWindow = 8
        val step = 7 // 8 segmen per jendela, maju 7 = overlap ~1 segmen (~90 dtk)
        var fromIndex = 0
        while (fromIndex < segments.size) {
            val windowSegments = segments.subList(
                fromIndex, minOf(segments.size, fromIndex + segmentsPerWindow)
            )
            windowsTotal++
            try {
                val windowScores = scoreSegments(apiKey, videoTitle, windowSegments)
                for ((id, score) in windowScores) {
                    val existing = merged[id]
                    if (existing == null || score.score > existing.score) {
                        merged[id] = score
                    }
                }
                windowsSucceeded++
            } catch (e: Exception) {
                windowsFailed++
                lastError = e.message?.take(200)
            }
            if (fromIndex + segmentsPerWindow >= segments.size) break
            fromIndex += step
        }
        ChunkedScoreResult(merged, windowsTotal, windowsSucceeded, windowsFailed, lastError)
    }

    /**
     * v1.0.7 — rerank komparatif: SATU call LLM atas kandidat final
     * (maks TOP 6), minta urutan terbaik-dulu. Gagal = null (pemanggil
     * memakai urutan semula, bukan crash). Input kecil: tiap kandidat
     * maks 1200 karakter transkrip, jadi call ini tidak bisa kena 413.
     */
    suspend fun rerankClips(
        apiKey: String,
        videoTitle: String,
        candidates: List<RerankInput>
    ): List<RerankResult>? = withContext(Dispatchers.IO) {
        if (candidates.size < 2) return@withContext null
        val candidateText = candidates.joinToString("\n\n") { c ->
            "[${c.id}] ${c.startLabel}-${c.endLabel} skor awal ${c.score} " +
                "judul: ${c.title} alasan: ${c.reason}\n${c.text}"
        }
        val systemPrompt = """
            Kamu editor highlight. Urutkan kandidat clip di bawah dari
            yang PALING layak ditonton dulu sampai yang paling lemah,
            dengan kriteria yang sama: hook paling kuat di 5 detik
            pertama, momen paling mengejutkan atau paling bisa dikutip,
            emosi atau konflik paling jelas, dan nilai praktis paling
            nyata. Jangan mengarang isi di luar teks kandidat.
            Balas HANYA JSON: {"ranking":[{"id":0,"rank":1,"score":0}]}
            rank 1 = paling layak. score 0-100.
        """.trimIndent()
        val userPrompt = "Judul video: $videoTitle\n\nKandidat:\n$candidateText"
        return@withContext try {
            val raw = callChat(apiKey, systemPrompt, userPrompt)
            parseRanking(raw)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRanking(raw: String): List<RerankResult>? {
        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val json = JSONObject(raw.substring(start, end + 1))
            val ranking = json.optJSONArray("ranking") ?: return null
            val out = mutableListOf<RerankResult>()
            for (i in 0 until ranking.length()) {
                val item = ranking.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id < 0) continue
                out.add(
                    RerankResult(
                        id = id,
                        rank = item.optInt("rank", 99),
                        score = item.optInt("score", 0).coerceIn(0, 100)
                    )
                )
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }

    suspend fun scoreSegments(
        apiKey: String,
        videoTitle: String,
        segments: List<SegmentInput>
    ): Map<Int, SegmentScore> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
        if (segments.isEmpty()) return@withContext emptyMap()
        val segmentText = segments.joinToString("\n\n") { segment ->
            "[${segment.id}] ${segment.startLabel}-${segment.endLabel}\n${segment.text}"
        }.take(MAX_CHARS_PER_CALL)
        // v1.0.7 — framework viralitas (desain dipinjam dari
        // AI-Youtube-Shorts-Generator highlights.py / SupoClip, prompt
        // ditulis sendiri untuk ClipKu, BUKAN copy code mereka):
        // hook, emotional peak, opinion bomb, revelation, conflict,
        // quotable line, story peak, practical value. Skor 0-100 +
        // hook sentence (judul) + reason, format JSON sama seperti dulu.
        val systemPrompt = """
            Kamu penilai highlight video. Nilai tiap segmen 0-100 dengan
            kriteria eksplisit ini, dan JANGAN menilai dari panjang teks:
            - hook: kalimat pembuka yang membuat penonton berhenti scroll
            - emotional peak: emosi paling terasa (kaget, marah, haru, tawa)
            - opinion bomb: opini tajam atau kontroversial yang memancing reaksi
            - revelation: fakta atau pengakuan yang mengejutkan / baru terungkap
            - conflict: pertentangan, perdebatan, atau ketegangan antar pihak
            - quotable line: kalimat yang layak dikutip dan disebar
            - story peak: titik balik atau klimaks cerita di segmen itu
            - practical value: pelajaran atau langkah nyata yang bisa dipakai
            Segmen tanpa satu pun kriteria di atas = skor rendah (di bawah 40).
            Jangan mengarang isi di luar teks segmen.
            Balas HANYA JSON: {"scores":[{"id":0,"score":0,"title":"...","reason":"..."}]}
            Judul = hook sentence segmen itu, maksimal 8 kata, bahasa sama
            dengan teks segmen. Alasan maksimal 12 kata, sebut kriteria
            yang paling menonjol.
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
        // v1.0.4: coba model berurutan, pertama yang berhasil menang.
        // Signature fungsi ini TIDAK berubah: pemanggil lama (scoring dan
        // narasi) tetap menerima String atau IllegalStateException.
        lastError = null
        for (model in MODELS) {
            val body = JSONObject().apply {
                put("model", model)
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
            val result = postOnce(apiKey, body, model)
            if (result != null) return result
        }
        val detail = lastError?.take(200) ?: "tanpa detail"
        throw IllegalStateException(
            "Groq gagal menjawab ($detail). Periksa Groq API key di Settings dan koneksi internet."
        )
    }

    private fun postOnce(apiKey: String, body: JSONObject, model: String): String? {
        return try {
            val request = Request.Builder()
                .url(URL)
                // v1.0.4: User-Agent browser — tanpa ini request pernah
                // ditolak Cloudflare (pelajaran 1010 Nalar/Phoenix).
                .header("User-Agent", BROWSER_UA)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
            val code = response.code
            val text = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) {
                lastError = "HTTP $code " + groqErrorText(text)
                return null
            }
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: ""
            if (content.isBlank()) {
                lastError = "HTTP $code jawaban kosong"
                null
            } else {
                content
            }
        } catch (e: Exception) {
            // Jangan menimpa detail HTTP yang sudah tercatat.
            if (lastError == null) {
                lastError = "jaringan/timeout: " +
                    (e.message?.take(120) ?: "tanpa pesan")
            }
            null
        }
    }

    /**
     * Ambil pesan/kode error Groq dari body error, tanpa API key.
     * Bentuk Groq: {"error": {"message": "...", "code": "..."}}.
     * Kalau tidak ter-parse, pakai potongan body mentah (maks 120 char).
     */
    private fun groqErrorText(bodyText: String): String {
        return try {
            val err = JSONObject(bodyText).optJSONObject("error")
            if (err != null) {
                val message = err.optString("message", "").take(150)
                val code = err.optString("code", "")
                when {
                    message.isNotBlank() && code.isNotBlank() -> "$code: $message"
                    message.isNotBlank() -> message
                    code.isNotBlank() -> code
                    else -> bodyText.take(120)
                }
            } else {
                bodyText.take(120)
            }
        } catch (e: Exception) {
            bodyText.take(120)
        }.replace(Regex("\\s+"), " ").trim().ifEmpty { "tanpa pesan error" }
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
