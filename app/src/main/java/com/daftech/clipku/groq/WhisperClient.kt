package com.daftech.clipku.groq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Klien transkripsi Groq Whisper (v1.0.2, bagian "full").
 *
 * ATURAN SCOPE YANG KERAS — dibaca sebelum mengubah file ini:
 *  - Yang dikirim ke Whisper HANYA file clip hasil potong (~60 detik),
 *    TIDAK PERNAH video utuh. Transkrip video 91 menit utuh di luar
 *    scope: lambat, mahal, dan tidak perlu, karena narasi dibuat per
 *    clip, bukan per video.
 *  - API key HANYA dari Settings yang diisi user di HP (Prefs), sama
 *    seperti GroqClient. TIDAK pernah di-hardcode, TIDAK pernah di-log.
 *  - Parameter bahasa TIDAK dikirim dengan sengaja: v1.0.2 menargetkan
 *    berbagai macam video (Indonesia, Inggris, dan lainnya), dan
 *    Whisper melakukan deteksi bahasa otomatis kalau parameter bahasa
 *    dihilangkan. Mengunci "id" akan merusak transkrip video berbahasa
 *    lain.
 *  - File yang dikirim adalah MP4 hasil CuttingEngine (video+audio),
 *    karena format itu dijamin ada dari jalur potong yang sudah ada.
 *    Groq Whisper menerima mp4 sebagai salah satu format inputnya.
 *    File temp dihapus pemanggil (ViewModel) di blok finally — klien
 *    ini TIDAK menghapus file inputnya sendiri, supaya pemanggil yang
 *    mengendalikan lifecycle temp.
 *  - Gagal apa pun (key kosong/kuota/jaringan/transkrip kosong)
 *    dilempar sebagai IllegalStateException dengan pesan yang bisa
 *    ditampilkan ke user; pemanggil mengubahnya menjadi penolakan
 *    narasi yang jujur, sementara clip-nya TETAP tersedia untuk
 *    download dan edit di CapCut.
 *
 * Ini TRANSCRIPSI, bukan voice generation: tidak ada suara yang
 * dibuat di APK ini. Suara tetap dibuat user di SuaraKu dari teks
 * narasi yang disalin.
 */
class WhisperClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val MODEL = "whisper-large-v3-turbo"
        private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        // v1.0.4: User-Agent browser, sama seperti GroqClient — jalur
        // Whisper tidak boleh gagal hanya karena User-Agent default.
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        /**
         * v1.4.0 (P1-11): batas upload Groq Whisper — file clip dicek
         * SEBELUM dikirim, bukan sesudah ditolak 413. Clip ~60 detik
         * 720p biasanya 5-15 MB; 25 MB adalah batas keras Groq.
         */
        const val MAX_UPLOAD_BYTES = 25L * 1024L * 1024L
    }

    /**
     * v1.4.0 (P1-4): hasil transkrip + bahasa yang terdeteksi
     * Whisper (kode 2 huruf, mis. "id"/"en", null kalau tidak
     * terbaca). Dipakai ViewModel untuk cross-check: kalau bahasa
     * terdeteksi berbeda dari bahasa yang diharapkan (caption),
     * track audio dipilih ulang sekali + peringatan ditampilkan.
     */
    data class WhisperResult(val text: String, val language: String?)

    /** Transkrip teks polos dari [audioFile] (file clip ~60 detik). */
    suspend fun transcribe(apiKey: String, audioFile: File): String =
        transcribeDetailed(apiKey, audioFile).text

    /**
     * Transkrip + bahasa terdeteksi. response_format verbose_json
     * mengembalikan {"text": "...", "language": "indonesian"} —
     * nama bahasa lengkap (bukan kode), jadi dinormalisasi ke kode
     * 2 huruf di sini. File >25 MB ditolak SEBELUM upload dengan
     * pesan jelas (P1-11), bukan sesudah Groq menjawab 413.
     */
    suspend fun transcribeDetailed(apiKey: String, audioFile: File): WhisperResult =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Isi Groq API key di Settings dulu." }
            require(audioFile.exists() && audioFile.length() > 0) {
                "File clip untuk transkrip tidak ditemukan."
            }
            if (audioFile.length() > MAX_UPLOAD_BYTES) {
                val mb = audioFile.length() / (1024 * 1024)
                throw IllegalStateException(
                    "File clip $mb MB melebihi batas 25 MB Whisper — " +
                        "narasi tidak dibuat. Coba clip lain."
                )
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart(
                    "file",
                    "clip.mp4",
                    audioFile.asRequestBody("video/mp4".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url(URL)
                .header("User-Agent", BROWSER_UA)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            val response = http.newCall(request).execute()
            val code = response.code
            val text = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) {
                // v1.0.4: sertakan pesan error Groq (tanpa API key),
                // bukan cuma kode HTTP, supaya penyebabnya terbaca.
                val detail = try {
                    JSONObject(text).optJSONObject("error")
                        ?.optString("message", "")?.take(150)
                        ?.replace(Regex("\\s+"), " ")?.trim()
                        ?: ""
                } catch (e: Exception) {
                    ""
                }
                throw IllegalStateException(
                    "Whisper gagal (HTTP $code" +
                        (if (detail.isNotBlank()) ": $detail" else "") +
                        "). Periksa Groq API key di Settings, kuota, dan koneksi internet."
                )
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            WhisperResult(
                text = json.optString("text", "").trim(),
                language = normaliseDetectedLanguage(json.optString("language", ""))
            )
        }

    /**
     * "indonesian"/"english"/kode 2 huruf -> "id"/"en"/... Null
     * kalau tidak dikenal / kosong. Whisper verbose_json memakai
     * nama lengkap bahasa Inggris; kode Java lama "in" ikut
     * dinormalisasi ke "id".
     */
    private fun normaliseDetectedLanguage(raw: String): String? {
        val lower = raw.trim().lowercase()
        if (lower.isBlank()) return null
        return when (lower) {
            "indonesian", "indonesia", "in", "id" -> "id"
            "english", "en" -> "en"
            "javanese", "jv" -> "jv"
            "sundanese", "su" -> "su"
            "malay", "melayu", "ms" -> "ms"
            else -> lower.take(2).takeIf { it.length == 2 }
        }
    }
}
