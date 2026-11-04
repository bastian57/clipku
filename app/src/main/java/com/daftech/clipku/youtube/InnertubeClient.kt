package com.daftech.clipku.youtube

import com.daftech.clipku.model.CaptionTrack
import com.daftech.clipku.model.HeatPoint
import com.daftech.clipku.model.Sentence
import com.daftech.clipku.model.VideoInfo
import com.daftech.clipku.model.VideoStream
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
 * Klien Innertube YouTube (player API) via OkHttp.
 *
 * Jalur UTAMA v1: WEB. Jalur fallback v1.0.1: timedtext direct, lalu
 * klien ANDROID — keduanya hanya jalan kalau jalur sebelumnya kosong.
 *
 * Kunci "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8" adalah kunci WEB PUBLIK
 * YouTube yang dipakai klien web resmi, BUKAN kredensial pribadi Putra dan
 * BUKAN Groq key. Tidak ada secret Putra di file ini.
 *
 * Keterbatasan jujur: YouTube tidak mendokumentasikan endpoint ini dan bisa
 * mengubah responsnya sewaktu-waktu. Kalau respons berubah, analisa gagal
 * dengan pesan jelas (bukan hasil ngarang) sampai parser diperbarui.
 * signatureCipher TIDAK di-decipher di fix ini (butuh decipher JS player,
 * di luar scope v1.0.1) — lihat FIX-NOTES-v1.0.1.md untuk fallback
 * masa depan (NewPipeExtractor / yt-dlp) kalau ANDROID juga cipher-only.
 */

/** Hasil transkrip beserta label sumbernya, untuk diagnostik UI. */
data class TranscriptResult(
    val sentences: List<Sentence>,
    val source: String
)

class InnertubeClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        // Kunci web publik YouTube (lihat komentar kelas). Bukan secret.
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?key=$WEB_API_KEY"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        // Klien ANDROID 19.x — lini klien Android yang dipakai luas
        // sepanjang 2025. Kalau YouTube menolak versi ini, fallback ini
        // mengembalikan kosong (tidak crash) dan sumber sebelumnya tetap
        // dipakai; versi ini bukan jaminan, melainkan best-effort.
        private const val ANDROID_CLIENT_VERSION = "19.47.53"
    }

    /** Cache respons ANDROID dari merge stream, dipakai ulang oleh fallback transkrip. */
    private var cachedAndroidInfo: VideoInfo? = null

    /**
     * Sumber stream Innertube terakhir yang berhasil dipakai fetchVideoInfo:
     * "WEB", "WEB+ANDROID" (ANDROID mengisi yang kosong dari WEB), atau
     * "NONE". Dibaca ViewModel untuk diagnostik. NewPipe TIDAK mengubah
     * nilai ini — sumber NEWPIPE dicatat terpisah di ViewModel, karena
     * NewPipe di-merge SETELAH fetchVideoInfo selesai.
     */
    var lastStreamSource: String = "NONE"
        private set

    suspend fun fetchVideoInfo(videoId: String): VideoInfo = withContext(Dispatchers.IO) {
        val webRoot = fetchPlayer(videoId, "WEB", "2.20240111.09.00")
        var info = parseVideoInfo(videoId, webRoot)
        val webAudio = info.stream.audioUrl
        val webProgressive = info.stream.progressiveUrl
        lastStreamSource = if (webAudio != null || webProgressive != null) "WEB" else "NONE"
        // v1.0.1: fallback stream ANDROID, hanya kalau WEB kekurangan stream.
        // Format ANDROID sering berupa URL langsung, sedangkan format WEB
        // sering hanya signatureCipher (yang tidak dibaca / tidak di-decipher
        // di fix ini), sehingga audioUrl / progressiveUrl WEB bisa null.
        if (info.stream.audioUrl == null || info.stream.progressiveUrl == null) {
            try {
                val androidInfo = fetchAndroidVideoInfo(videoId)
                if (androidInfo != null) {
                    cachedAndroidInfo = androidInfo
                    if ((webAudio == null && androidInfo.stream.audioUrl != null) ||
                        (webProgressive == null && androidInfo.stream.progressiveUrl != null)
                    ) {
                        lastStreamSource = "WEB+ANDROID"
                    }
                    val mergedStream = VideoStream(
                        progressiveUrl = info.stream.progressiveUrl
                            ?: androidInfo.stream.progressiveUrl,
                        videoOnlyUrl = info.stream.videoOnlyUrl
                            ?: androidInfo.stream.videoOnlyUrl,
                        audioUrl = info.stream.audioUrl
                            ?: androidInfo.stream.audioUrl,
                        mimeProgressive = info.stream.mimeProgressive
                    )
                    info = info.copy(
                        stream = mergedStream,
                        captionTracks = if (info.captionTracks.isNotEmpty()) {
                            info.captionTracks
                        } else {
                            androidInfo.captionTracks
                        },
                        heatPoints = if (info.heatPoints.isNotEmpty()) {
                            info.heatPoints
                        } else {
                            androidInfo.heatPoints
                        }
                    )
                }
            } catch (e: Exception) {
                // Fallback gagal = pakai hasil WEB apa adanya, bukan crash.
            }
        }
        info
    }

    /** Satu POST player, dengan clientName / clientVersion yang diminta. */
    private suspend fun fetchPlayer(
        videoId: String,
        clientName: String,
        clientVersion: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", clientName)
                    put("clientVersion", clientVersion)
                    put("hl", "id")
                    put("gl", "ID")
                })
            })
        }
        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", BROWSER_UA)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = http.newCall(request).execute()
        val text = response.body?.string() ?: ""
        response.close()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "Innertube gagal (HTTP ${response.code}). " +
                    "YouTube mungkin mengubah player-nya atau memblokir permintaan ini."
            )
        }
        JSONObject(text)
    }

    /** Player ANDROID, best-effort: null kalau gagal, tidak pernah melempar. */
    private suspend fun fetchAndroidVideoInfo(videoId: String): VideoInfo? {
        return try {
            val root = fetchPlayer(videoId, "ANDROID", ANDROID_CLIENT_VERSION)
            parseVideoInfo(videoId, root)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVideoInfo(videoId: String, root: JSONObject): VideoInfo {
        val details = root.optJSONObject("videoDetails") ?: JSONObject()
        val title = details.optString("title", "Video tanpa judul")
        val author = details.optString("author", "")
        val durationMs = (details.optString("lengthSeconds", "0").toLongOrNull() ?: 0L) * 1000L
        if (durationMs <= 0L) {
            // playabilityStatus bisa menjelaskan: private / age-gated / tidak ada.
            val status = root.optJSONObject("playabilityStatus")?.optString("status", "")
            val reason = root.optJSONObject("playabilityStatus")?.optString("reason", "")
            throw IllegalStateException(
                "Video tidak bisa dianalisa (status: $status $reason). " +
                    "Video privat, dibatasi usia, atau tidak ditemukan tidak didukung v1."
            )
        }

        val streaming = root.optJSONObject("streamingData") ?: JSONObject()
        val progressive = pickProgressive(streaming.optJSONArray("formats"))
        val adaptiveVideo = pickAdaptiveVideo(streaming.optJSONArray("adaptiveFormats"))
        val adaptiveAudio = pickAdaptiveAudio(streaming.optJSONArray("adaptiveFormats"))
        val stream = VideoStream(
            progressiveUrl = progressive,
            videoOnlyUrl = adaptiveVideo,
            audioUrl = adaptiveAudio,
            mimeProgressive = null
        )

        val captions = parseCaptions(root)
        val heat = HeatmapParser.parse(root)
        return VideoInfo(
            videoId = videoId,
            title = title,
            author = author,
            durationMs = durationMs,
            stream = stream,
            captionTracks = captions,
            heatPoints = heat,
            chapters = emptyList()
        )
    }

    /** Format gabungan mp4 <=720p, diutamakan yang tertinggi di bawah 720p. */
    private fun pickProgressive(formats: JSONArray?): String? {
        if (formats == null) return null
        var best: String? = null
        var bestHeight = -1
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mime = item.optString("mimeType", "")
            if (!mime.contains("video/mp4")) continue
            val height = item.optInt("height", 0)
            if (height <= 0 || height > 720) continue
            val url = item.optString("url", "").ifEmpty { null } ?: continue
            if (height > bestHeight) {
                bestHeight = height
                best = url
            }
        }
        // Fallback: format gabungan apa pun (mis. 360p webm) kalau mp4 tidak ada.
        if (best == null) {
            for (i in 0 until formats.length()) {
                val item = formats.optJSONObject(i) ?: continue
                val url = item.optString("url", "").ifEmpty { null } ?: continue
                return url
            }
        }
        return best
    }

    private fun pickAdaptiveVideo(formats: JSONArray?): String? {
        if (formats == null) return null
        var best: String? = null
        var bestHeight = -1
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mime = item.optString("mimeType", "")
            if (!mime.startsWith("video/mp4")) continue
            val height = item.optInt("height", 0)
            if (height <= 0 || height > 720) continue
            val url = item.optString("url", "").ifEmpty { null } ?: continue
            if (height > bestHeight) {
                bestHeight = height
                best = url
            }
        }
        return best
    }

    private fun pickAdaptiveAudio(formats: JSONArray?): String? {
        if (formats == null) return null
        var best: String? = null
        var bestBitrate = -1
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val mime = item.optString("mimeType", "")
            if (!mime.startsWith("audio/")) continue
            val bitrate = item.optInt("bitrate", 0)
            val url = item.optString("url", "").ifEmpty { null } ?: continue
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                best = url
            }
        }
        return best
    }

    private fun parseCaptions(root: JSONObject): List<CaptionTrack> {
        val out = mutableListOf<CaptionTrack>()
        val tracks = root.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklist")
            ?.optJSONArray("captionTracks") ?: return out
        for (i in 0 until tracks.length()) {
            val item = tracks.optJSONObject(i) ?: continue
            val baseUrl = item.optString("baseUrl", "").ifEmpty { null } ?: continue
            out.add(
                CaptionTrack(
                    baseUrl = baseUrl,
                    languageCode = item.optString("languageCode", ""),
                    name = item.optJSONObject("name")?.optString("simpleText", "")
                        ?: item.optString("languageCode", ""),
                    isAutoGenerated = item.optString("kind", "") == "asr"
                )
            )
        }
        return out
    }

    /** Kompatibilitas v1.0.0: hanya kalimatnya, tanpa label sumber. */
    suspend fun fetchTranscript(info: VideoInfo): List<Sentence> =
        fetchTranscriptWithFallback(info).sentences

    /**
     * Rantai fallback transkrip v1.0.1, berurutan, pertama yang berhasil menang:
     * 1. WEB — captionTracks dari fetchVideoInfo, format VTT.
     * 2. TIMEDTEXT — endpoint timedtext langsung (id, lalu en), format json3.
     * 3. ANDROID — captionTracks dari player ANDROID, format VTT.
     * Tiap langkah yang gagal / kosong tidak melempar; hasil kosong
     * mengembalikan sumber "NONE" supaya diagnostik bisa menyebutnya.
     */
    suspend fun fetchTranscriptWithFallback(info: VideoInfo): TranscriptResult =
        withContext(Dispatchers.IO) {
            val web = fetchTranscriptFromTracks(info.captionTracks)
            if (web.isNotEmpty()) {
                return@withContext TranscriptResult(web, "WEB")
            }
            val timedtextId = fetchTimedtext(info.videoId, "id")
            if (timedtextId.isNotEmpty()) {
                return@withContext TranscriptResult(timedtextId, "TIMEDTEXT")
            }
            val timedtextEn = fetchTimedtext(info.videoId, "en")
            if (timedtextEn.isNotEmpty()) {
                return@withContext TranscriptResult(timedtextEn, "TIMEDTEXT")
            }
            val androidInfo = cachedAndroidInfo ?: fetchAndroidVideoInfo(info.videoId)
            if (androidInfo != null) {
                cachedAndroidInfo = androidInfo
                val android = fetchTranscriptFromTracks(androidInfo.captionTracks)
                if (android.isNotEmpty()) {
                    return@withContext TranscriptResult(android, "ANDROID")
                }
            }
            TranscriptResult(emptyList(), "NONE")
        }

    /** Unduh transkrip VTT dari track terbaik (id > en > apa pun, manual > auto). */
    private suspend fun fetchTranscriptFromTracks(
        tracks: List<CaptionTrack>
    ): List<Sentence> = withContext(Dispatchers.IO) {
        val track = tracks.firstOrNull { it.languageCode.startsWith("id") && !it.isAutoGenerated }
            ?: tracks.firstOrNull { it.languageCode.startsWith("id") }
            ?: tracks.firstOrNull { it.languageCode.startsWith("en") && !it.isAutoGenerated }
            ?: tracks.firstOrNull { it.languageCode.startsWith("en") }
            ?: tracks.firstOrNull()
            ?: return@withContext emptyList()
        try {
            // v1.0.2: track NewPipe sudah membawa fmt sendiri di URL-nya
            // (mis. fmt=vtt atau format lain) — jangan menumpuk "&fmt=vtt"
            // di belakangnya. Track Innertube yang belum ber-fmt tetap
            // ditambah "&fmt=vtt" seperti sebelumnya.
            val url = if (track.baseUrl.contains("fmt=")) {
                track.baseUrl
            } else {
                track.baseUrl + "&fmt=vtt"
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .build()
            val response = http.newCall(request).execute()
            val text = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) return@withContext emptyList()
            VttParser.parse(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Timedtext langsung: GET /api/timedtext?v={id}&lang={lang}&fmt=json3.
     * Format json3: { "events": [ { "tStartMs": N, "dDurationMs": N,
     * "segs": [ { "utf8": "..." } ] } ] }. Tag HTML dibuang.
     * HTTP non-200 (termasuk 403 untuk caption auto di beberapa video)
     * diperlakukan sebagai kosong, bukan error.
     */
    private suspend fun fetchTimedtext(videoId: String, lang: String): List<Sentence> =
        withContext(Dispatchers.IO) {
            try {
                val url =
                    "https://www.youtube.com/api/timedtext?v=$videoId&lang=$lang&fmt=json3"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .build()
                val response = http.newCall(request).execute()
                val text = response.body?.string() ?: ""
                response.close()
                if (!response.isSuccessful || text.isBlank()) return@withContext emptyList()
                val root = JSONObject(text)
                val events = root.optJSONArray("events") ?: return@withContext emptyList()
                val out = mutableListOf<Sentence>()
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val start = event.optLong("tStartMs", -1L)
                    if (start < 0) continue
                    val duration = event.optLong("dDurationMs", 0L)
                    val segs = event.optJSONArray("segs") ?: continue
                    val builder = StringBuilder()
                    for (j in 0 until segs.length()) {
                        val seg = segs.optJSONObject(j) ?: continue
                        builder.append(seg.optString("utf8", ""))
                    }
                    val clean = builder.toString()
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (clean.isNotEmpty()) {
                        out.add(Sentence(start, start + duration, clean))
                    }
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
}
