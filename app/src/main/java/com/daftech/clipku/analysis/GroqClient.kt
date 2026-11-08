package com.daftech.clipku.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
        // v1.0.8 P0-2 — pacing anggaran Groq free tier (8.000 token
        // input/menit): 9 window x ~2.600 token ≈ 23.400 token.
        // Tanpa jeda, 23.400 token terkirim dalam ±30-45 detik dan
        // window akhir kena 429. Dengan jeda 12 detik antar window
        // (8 jeda = 96 detik) + waktu call sendiri (~5-10 detik x 9),
        // total analisa ≈ 2,5-3 menit → laju rata-rata ≈ 23.400 /
        // 180 detik ≈ 7.800 token/menit, di bawah batas 8.000.
        // Ini lantai, bukan jaminan: kalau call-nya sangat cepat,
        // jeda inilah yang menahan laju; kalau Groq lambat, total
        // waktu bertambah sendiri. Analisa video panjang JADI LEBIH
        // LAMBAT karena ini — disengaja, bukan bug.
        const val WINDOW_DELAY_MS = 12_000L
        // v1.0.8 P0-2 — backoff 429 antar model di callChat: 2 detik
        // sebelum model ke-2, 4 detik sebelum model ke-3.
        const val RETRY_429_BASE_MS = 2_000L
    }

    /**
     * v1.0.8 P0-7 — pengecualian khusus: LLM menjawab tapi tidak
     * men-skor satu pun segmen (jawaban tidak ter-parse sebagai
     * JSON skor). Beda dari gagal HTTP: ini "sukses palsu" yang
     * dulu dihitung sebagai jendela berhasil.
     */
    class EmptyLlmScoreException(message: String) : IllegalStateException(message)

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
        val lastError: String?,
        /** v1.2.0 — jendela yang berhasil per model ("120b"/"20b"/"qwen"). */
        val modelWins: Map<String, Int> = emptyMap()
    )

    /** Nama pendek model untuk diagnostik ("120b"/"20b"/"qwen"). */
    fun shortModelName(model: String): String = when (model) {
        PRIMARY_MODEL -> "120b"
        FALLBACK_MODEL -> "20b"
        QWEN_MODEL -> "qwen"
        else -> model.substringAfterLast("/").take(12)
    }

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

    /** Hasil satu jendela: skor + model yang berhasil, atau error. */
    private data class WindowOutcome(
        val scores: Map<Int, SegmentScore>,
        val model: String?,
        val error: String?
    )

    /**
     * v1.2.0 — scoring ber-chunk PARALEL 3 model (upgrade #1 riset
     * super-cepat). Jendela dibangun dari startMs segmen memakai
     * WINDOW_MS (12 menit) + overlap WINDOW_OVERLAP_MS (90 detik)
     * — v1.4.0 (P2-1): dulu jendela dibangun dari INDEKS (8 segmen,
     * langkah 7) sehingga konstanta WINDOW_MS/WINDOW_OVERLAP_MS
     * tidak pernah dipakai dan "12 menit" hanya benar kalau tiap
     * segmen persis ~90 detik. Kini jendela = segmen yang startMs-nya
     * jatuh di [awal, awal + 12 menit), maks 8 segmen per jendela
     * (pengaman kalau kalimat sangat rapat); jendela berikutnya
     * mulai 10,5 menit setelah jendela sebelumnya (overlap 90 dtk
     * = ~1 segmen, sama seperti dulu). Segmen di dua jendela
     * di-dedupe (skor tertinggi menang). Jendela dihitung BERHASIL
     * hanya kalau LLM benar-benar men-skor >=1 segmen (v1.0.8 P0-7).
     * Eksekusi paralel: jendela dibagikan round-robin ke 3 model
     * Groq gratis. Limit TPM Groq berlaku PER MODEL, bukan per
     * akun — jadi paralel lintas model tidak melanggar batas 8.000
     * TPM per model. Pacing antar call ke model YANG SAMA
     * dipertahankan: jendela ke model yang sama di-stagger
     * WINDOW_DELAY_MS (12 detik).
     * Isolasi gagal per jendela tetap berlaku (v1.0.7): satu jendela
     * gagal tidak menolkan video. Gagal di model yang ditugaskan —
     * kecuali 413 (prompt terlalu besar akan gagal di semua model
     * dengan prompt yang sama) — dicoba SEKALI ke model lain; kalau
     * itu pun gagal, jendela dicatat gagal dan segmennya tetap bisa
     * jadi clip lewat jalur transcript-only di Scoring.
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
        // v1.4.0 (P2-1): jendela BERBASIS WAKTU, bukan indeks.
        val sorted = segments.sortedBy { it.startMs }
        val windows = mutableListOf<List<SegmentInput>>()
        var windowStartMs = sorted.first().startMs
        val lastStartMs = sorted.maxOf { it.startMs }
        while (windowStartMs <= lastStartMs) {
            val windowEndMs = windowStartMs + WINDOW_MS
            val window = sorted.filter { it.startMs in windowStartMs until windowEndMs }
                .take(8)
            if (window.isNotEmpty()) windows.add(window)
            windowStartMs += WINDOW_MS - WINDOW_OVERLAP_MS
        }
        if (windows.isEmpty()) windows.add(sorted)
        // v1.2.0 — eksekusi paralel: satu async per jendela.
        // postOnce memblokir thread (OkHttp execute), jadi tiap
        // async eksplisit di Dispatchers.IO juga.
        val outcomes = coroutineScope {
            windows.mapIndexed { index, windowSegments ->
                async(Dispatchers.IO) {
                    val assigned = index % MODELS.size
                    // Pacing per model: jendela ke model yang sama
                    // mulai berselisih WINDOW_DELAY_MS.
                    val stagger = (index / MODELS.size) * WINDOW_DELAY_MS
                    if (stagger > 0) delay(stagger)
                    scoreWindowWithFallback(apiKey, videoTitle, windowSegments, assigned)
                }
            }.awaitAll()
        }
        val merged = mutableMapOf<Int, SegmentScore>()
        var windowsSucceeded = 0
        var windowsFailed = 0
        var lastError: String? = null
        val modelWins = mutableMapOf<String, Int>()
        for (outcome in outcomes) {
            if (outcome.scores.isNotEmpty() && outcome.model != null) {
                for ((id, score) in outcome.scores) {
                    val existing = merged[id]
                    if (existing == null || score.score > existing.score) {
                        merged[id] = score
                    }
                }
                windowsSucceeded++
                val short = shortModelName(outcome.model)
                modelWins[short] = (modelWins[short] ?: 0) + 1
            } else {
                windowsFailed++
                lastError = outcome.error
            }
        }
        ChunkedScoreResult(
            merged, windows.size, windowsSucceeded, windowsFailed, lastError, modelWins
        )
    }

    /**
     * v1.2.0 — skor SATU jendela: coba model yang ditugaskan, lalu
     * SEKALI model lain kalau gagal. 413 tidak di-retry (prompt yang
     * sama akan gagal di semua model); 429 diberi jeda singkat
     * (RETRY_429_BASE_MS) sebelum fallback.
     */
    private suspend fun scoreWindowWithFallback(
        apiKey: String,
        videoTitle: String,
        windowSegments: List<SegmentInput>,
        assignedModelIndex: Int
    ): WindowOutcome {
        val first = runCatching {
            scoreSegmentsWithModel(
                apiKey, videoTitle, windowSegments, MODELS[assignedModelIndex]
            )
        }
        if (first.isSuccess) {
            return WindowOutcome(first.getOrThrow(), MODELS[assignedModelIndex], null)
        }
        val firstError = first.exceptionOrNull()?.message?.take(200) ?: "tanpa pesan"
        // 413 = prompt terlalu besar: akan gagal di SEMUA model dengan
        // prompt yang sama — jangan buang token dengan retry.
        if (firstError.startsWith("HTTP 413")) {
            return WindowOutcome(emptyMap(), null, firstError)
        }
        val fallbackIndex = (assignedModelIndex + 1) % MODELS.size
        if (firstError.startsWith("HTTP 429")) {
            delay(RETRY_429_BASE_MS)
        }
        val second = runCatching {
            scoreSegmentsWithModel(
                apiKey, videoTitle, windowSegments, MODELS[fallbackIndex]
            )
        }
        return if (second.isSuccess) {
            WindowOutcome(second.getOrThrow(), MODELS[fallbackIndex], null)
        } else {
            val secondError = second.exceptionOrNull()?.message?.take(200)
                ?: "tanpa pesan"
            WindowOutcome(emptyMap(), null, secondError)
        }
    }

    /**
     * v1.2.0 — skor SATU jendela dengan SATU model tertentu (tanpa
     * fallback antar model di dalam call — fallback ditangani di
     * scoreWindowWithFallback). Dipakai jalur paralel; scoreSegments
     * (rantai fallback berurutan) dipertahankan untuk pemakaian lama.
     * Melempar IllegalStateException berisi detail HTTP (mis.
     * "HTTP 429 ...") atau EmptyLlmScoreException kalau jawaban
     * tidak ter-parse — TIDAK PERNAH memuat API key.
     */
    private suspend fun scoreSegmentsWithModel(
        apiKey: String,
        videoTitle: String,
        segments: List<SegmentInput>,
        model: String
    ): Map<Int, SegmentScore> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
        if (segments.isEmpty()) return@withContext emptyMap()
        val (systemPrompt, userPrompt) = buildScoringPrompts(videoTitle, segments)
        // v1.3.0 (#2 strict JSON): coba structured output dulu —
        // Groq menjamin output sesuai skema, kelas kegagalan P0-7
        // (jawaban tidak ter-parse) hilang. Kalau model/server
        // menolak strict mode (400/unsupported), SEKALI fallback ke
        // body biasa — parser lama tetap dipakai, bukan crash.
        val strictOutcome = postOnceDetailed(
            apiKey, scoringBody(model, systemPrompt, userPrompt, strict = true), model
        )
        val raw = strictOutcome.content ?: run {
            if (isStrictUnsupported(strictOutcome.error)) {
                val plainOutcome = postOnceDetailed(
                    apiKey,
                    scoringBody(model, systemPrompt, userPrompt, strict = false),
                    model
                )
                plainOutcome.content
                    ?: throw IllegalStateException(
                        plainOutcome.error?.take(200)
                            ?: "Groq gagal menjawab (tanpa detail)."
                    )
            } else {
                throw IllegalStateException(
                    strictOutcome.error?.take(200)
                        ?: "Groq gagal menjawab (tanpa detail)."
                )
            }
        }
        val parsed = parseScores(raw)
        if (parsed.isEmpty()) {
            throw EmptyLlmScoreException(
                "LLM tidak men-skor satu pun segmen (jawaban tidak ter-parse sebagai JSON skor)."
            )
        }
        parsed
    }

    /**
     * v1.3.0 (#2): body scoring. strict=true menambah
     * response_format json_schema (strict) + reasoning_effort=low
     * (hanya gpt-oss — qwen menolak field ini) + max_completion_tokens
     * ketat 800 (Groq menagih TPM dari yang dideklarasikan, bukan
     * yang dipakai — 800 cukup untuk ~8 segmen, tanpa membakar budget).
     * strict=false = body lama v1.2.0 (fallback).
     * Prompt, temperatur 0.4, dan format isi JSON TIDAK berubah.
     */
    private fun scoringBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        strict: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            if (strict) {
                put("response_format", JSONObject().apply {
                    put("type", "json_schema")
                    put("json_schema", JSONObject().apply {
                        put("name", "clip_scores")
                        put("strict", true)
                        put("schema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("scores", JSONObject().apply {
                                    put("type", "array")
                                    put("items", JSONObject().apply {
                                        put("type", "object")
                                        put("properties", JSONObject().apply {
                                            put("id", JSONObject().apply {
                                                put("type", "integer")
                                            })
                                            put("score", JSONObject().apply {
                                                put("type", "integer")
                                            })
                                            put("title", JSONObject().apply {
                                                put("type", "string")
                                            })
                                            put("reason", JSONObject().apply {
                                                put("type", "string")
                                            })
                                        })
                                        put("required", JSONArray().apply {
                                            put("id"); put("score")
                                            put("title"); put("reason")
                                        })
                                        put("additionalProperties", false)
                                    })
                                })
                            })
                            put("required", JSONArray().apply { put("scores") })
                            put("additionalProperties", false)
                        })
                    })
                })
                if (model.startsWith("openai/gpt-oss")) {
                    put("reasoning_effort", "low")
                }
                put("max_completion_tokens", 800)
            }
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", userPrompt)
                })
            })
        }
    }

    /** Error ini berarti strict mode ditolak server — fallback ke body biasa. */
    private fun isStrictUnsupported(error: String?): Boolean {
        if (error == null) return false
        val lower = error.lowercase()
        return "400" in lower || "response_format" in lower ||
            "json_schema" in lower || "unsupported" in lower ||
            "strict" in lower
    }

    /**
     * v1.3.0 (#2): body rerank dengan skema strict yang sama
     * polanya (ranking array). reasoning_effort=low hanya gpt-oss,
     * max_completion_tokens 600 (6 kandidat, output pendek).
     */
    private fun rankingBody(
        model: String,
        systemPrompt: String,
        userPrompt: String
    ): JSONObject {
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("response_format", JSONObject().apply {
                put("type", "json_schema")
                put("json_schema", JSONObject().apply {
                    put("name", "clip_ranking")
                    put("strict", true)
                    put("schema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("ranking", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject().apply {
                                        put("id", JSONObject().apply {
                                            put("type", "integer")
                                        })
                                        put("rank", JSONObject().apply {
                                            put("type", "integer")
                                        })
                                        put("score", JSONObject().apply {
                                            put("type", "integer")
                                        })
                                    })
                                    put("required", JSONArray().apply {
                                        put("id"); put("rank"); put("score")
                                    })
                                    put("additionalProperties", false)
                                })
                            })
                        })
                        put("required", JSONArray().apply { put("ranking") })
                        put("additionalProperties", false)
                    })
                })
            })
            if (model.startsWith("openai/gpt-oss")) {
                put("reasoning_effort", "low")
            }
            put("max_completion_tokens", 600)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", userPrompt)
                })
            })
        }
    }

    /**
     * v1.0.7 — rerank komparatif: SATU call LLM atas kandidat final
     * (maks TOP 6), minta urutan terbaik-dulu. Gagal = null (pemanggil
     * memakai urutan semula, bukan crash). Input kecil: tiap kandidat
     * maks 1200 karakter transkrip, jadi call ini tidak bisa kena 413.
     * v1.4.0 (P1-7): rantai model di sini DULU tanpa hygiene 413/429
     * (callChat sudah punya sejak v1.0.8, loop ini belum): 413 kini
     * fail-fast (prompt yang sama gagal di semua model — jangan buang
     * token), 429 diberi jeda eksponensial singkat (2 dtk, lalu 4 dtk)
     * sebelum model berikutnya.
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
        // v1.3.0 (#2): strict JSON dulu per model; kalau semua model
        // menolak strict, jatuh ke callChat lama (parser lama tetap).
        for ((index, model) in MODELS.withIndex()) {
            val outcome = postOnceDetailed(
                apiKey, rankingBody(model, systemPrompt, userPrompt), model
            )
            val content = outcome.content
            if (content != null) {
                val parsed = parseRanking(content)
                if (parsed != null) return@withContext parsed
            }
            val err = outcome.error ?: ""
            // 413 = prompt terlalu besar di semua model — berhenti.
            if (err.startsWith("HTTP 413")) break
            // 429 = jeda eksponensial singkat sebelum model berikut.
            if (err.startsWith("HTTP 429") && index < MODELS.lastIndex) {
                delay(RETRY_429_BASE_MS * (index + 1))
            }
        }
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
        val (systemPrompt, userPrompt) = buildScoringPrompts(videoTitle, segments)
        val raw = callChat(apiKey, systemPrompt, userPrompt)
        val parsed = parseScores(raw)
        // v1.0.8 P0-7: jawaban LLM yang tidak ter-parse menjadi skor
        // apa pun adalah KEGAGALAN window ini, bukan "sukses 0 skor".
        // Pemanggil (scoreSegmentsChunked) menghitungnya gagal dan
        // lanjut ke window lain — bukan diam-diam menghitung sukses.
        if (parsed.isEmpty()) {
            throw EmptyLlmScoreException(
                "LLM tidak men-skor satu pun segmen (jawaban tidak ter-parse sebagai JSON skor)."
            )
        }
        parsed
    }

    /**
     * Prompt scoring (framework viralitas v1.0.7 — desain dipinjam
     * dari AI-Youtube-Shorts-Generator highlights.py / SupoClip,
     * prompt ditulis sendiri untuk ClipKu, BUKAN copy code mereka).
     * Dipakai scoreSegments (rantai fallback) dan
     * scoreSegmentsWithModel (jalur paralel) — prompt, temperatur,
     * dan format JSON TIDAK berubah di v1.2.0.
     */
    private fun buildScoringPrompts(
        videoTitle: String,
        segments: List<SegmentInput>
    ): Pair<String, String> {
        val segmentText = segments.joinToString("\n\n") { segment ->
            "[${segment.id}] ${segment.startLabel}-${segment.endLabel}\n${segment.text}"
        }.take(MAX_CHARS_PER_CALL)
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
        return systemPrompt to userPrompt
    }

    data class SegmentInput(
        val id: Int,
        val startLabel: String,
        val endLabel: String,
        val text: String,
        /** v1.4.0 (P2-1): awal segmen (ms) — pembangun jendela berbasis waktu. */
        val startMs: Long = 0L
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
        // v1.0.8 P0-2:
        //  - Fail-fast 413: prompt terlalu besar akan gagal di KETIGA
        //    model dengan prompt yang sama — buang waktu dan memperparah
        //    rate limit. Begitu satu model menjawab 413, berhenti.
        //  - Backoff 429: jeda eksponensial singkat sebelum mencoba
        //    model berikutnya (2 detik, lalu 4 detik), bukan retry
        //    langsung yang memperburuk limit.
        lastError = null
        for ((index, model) in MODELS.withIndex()) {
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
            val err = lastError ?: ""
            if (err.startsWith("HTTP 413")) break
            if (err.startsWith("HTTP 429") && index < MODELS.lastIndex) {
                try {
                    Thread.sleep(RETRY_429_BASE_MS * (index + 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val detail = lastError?.take(200) ?: "tanpa detail"
        throw IllegalStateException(
            "Groq gagal menjawab ($detail). Periksa Groq API key di Settings dan koneksi internet."
        )
    }

    /** Hasil satu POST Groq: konten jawaban atau pesan error (tanpa key). */
    private data class PostOutcome(val content: String?, val error: String?)

    /**
     * v1.2.0 — varian postOnce TANPA shared state: aman dipakai dari
     * coroutine paralel (lastError lama adalah var bersama yang akan
     * balapan kalau ditulis 3 thread sekaligus). postOnce lama
     * dipertahankan sebagai wrapper untuk callChat (jalur berurutan:
     * narasi, rerank, scoreSegments).
     */
    private fun postOnceDetailed(
        apiKey: String,
        body: JSONObject,
        model: String
    ): PostOutcome {
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
                return PostOutcome(null, "HTTP $code " + groqErrorText(text))
            }
            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?: ""
            if (content.isBlank()) {
                PostOutcome(null, "HTTP $code jawaban kosong")
            } else {
                PostOutcome(content, null)
            }
        } catch (e: Exception) {
            PostOutcome(
                null,
                "jaringan/timeout: " + (e.message?.take(120) ?: "tanpa pesan")
            )
        }
    }

    private fun postOnce(apiKey: String, body: JSONObject, model: String): String? {
        val outcome = postOnceDetailed(apiKey, body, model)
        lastError = outcome.error
        return outcome.content
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

    /**
     * v1.4.0 (P2-9): internal supaya unit test bisa menguji parsing
     * skor LLM tanpa network/key. Logika TIDAK berubah.
     */
    internal fun parseScores(raw: String): Map<Int, SegmentScore> {
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
