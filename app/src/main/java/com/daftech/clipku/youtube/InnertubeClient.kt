package com.daftech.clipku.youtube

import com.daftech.clipku.model.CaptionTrack
import com.daftech.clipku.model.HeatPoint
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
 * Ini adalah jalur UTAMA v1, menggantikan NewPipeExtractor yang sengaja
 * tidak dibundel (lihat BUILD-NOTES.md: JitPack + GPL + risiko build yang
 * tidak bisa diverifikasi di VM ini). Satu respons player memberi:
 * metadata, streamingData (URL stream), captionTracks, dan — best-effort —
 * data heatmap.
 *
 * Kunci "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8" adalah kunci WEB PUBLIK
 * YouTube yang dipakai klien web resmi, BUKAN kredensial pribadi Putra dan
 * BUKAN Groq key. Tidak ada secret Putra di file ini.
 *
 * Keterbatasan jujur: YouTube tidak mendokumentasikan endpoint ini dan bisa
 * mengubah responsnya sewaktu-waktu. Kalau respons berubah, analisa gagal
 * dengan pesan jelas (bukan hasil ngarang) sampai parser diperbarui.
 */
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
    }

    suspend fun fetchVideoInfo(videoId: String): VideoInfo = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20240111.09.00")
                    put("hl", "id")
                    put("gl", "ID")
                })
            })
        }
        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
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
        parseVideoInfo(videoId, JSONObject(text))
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

    /** Unduh transkrip VTT dari track terbaik (id > en > apa pun, manual > auto). */
    suspend fun fetchTranscript(info: VideoInfo): List<com.daftech.clipku.model.Sentence> =
        withContext(Dispatchers.IO) {
            val track = info.captionTracks.firstOrNull { it.languageCode.startsWith("id") && !it.isAutoGenerated }
                ?: info.captionTracks.firstOrNull { it.languageCode.startsWith("id") }
                ?: info.captionTracks.firstOrNull { it.languageCode.startsWith("en") && !it.isAutoGenerated }
                ?: info.captionTracks.firstOrNull { it.languageCode.startsWith("en") }
                ?: info.captionTracks.firstOrNull()
                ?: return@withContext emptyList()
            val url = track.baseUrl + "&fmt=vtt"
            val request = Request.Builder().url(url).build()
            val response = http.newCall(request).execute()
            val text = response.body?.string() ?: ""
            response.close()
            if (!response.isSuccessful) return@withContext emptyList()
            VttParser.parse(text)
        }
}
