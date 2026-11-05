package com.daftech.clipku.cutting

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.VideoInfo
import com.daftech.clipku.model.VideoStream
import com.daftech.clipku.youtube.NewPipeStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Download per clip — HANYA atas tap user, bukan auto-download semua.
 *
 * v1.0.6 (upgrade #2): alur per tap Download sekarang DUA lapis.
 *  1. JALUR SEGMEN (baru): resolve URL stream FRESH tepat sebelum
 *     ekspor (URL googlevideo bisa kedaluwarsa), lalu potong lewat
 *     SegmentExport — Transformer dari URL remote + clipping, tanpa
 *     mengunduh video utuh. Output divalidasi; kalau gagal / tidak
 *     valid / timeout 180 detik, jatuh ke lapis 2, BUKAN crash.
 *  2. JALUR DOWNLOAD-PENUH (v1.0.5, tidak diubah): unduh video
 *     sumber SEKALI ke cache (dipakai ulang untuk clip lain dari
 *     video yang sama), atau unduh video-only + audio kalau hanya
 *     adaptive yang tersedia, lalu potong via CuttingEngine.
 *  Jalur yang dipakai dicatat di DownloadResult.method ("segment"
 *  atau "full-download") dan ditampilkan ViewModel di diagnostik,
 *  supaya klaim "segment" bisa dicek dari waktu download di HP.
 *  Salin hasil potong ke Downloads/ClipKu via MediaStore (API 29+),
 *  fallback ke folder file eksternal app kalau MediaStore gagal.
 *  Hapus file cache hasil potong (file sumber cache dipertahankan
 *  selama sesi supaya clip berikutnya tidak mengunduh ulang).
 *
 * Hasil akhir adalah MP4 rasio asli, default 720p (atau resolusi
 * progressive terbaik <=720p yang tersedia). File ini bahan edit
 * user di CapCut — APK tidak mengeditnya lebih lanjut.
 */
object ClipDownload {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Batas waktu satu percobaan jalur segmen sebelum fallback. */
    private const val SEGMENT_TIMEOUT_MS = 180_000L
    /** Batas waktu percobaan segmen audio-only untuk Whisper. */
    private const val SEGMENT_AUDIO_TIMEOUT_MS = 120_000L

    /** Hasil download clip + jalur ekspor yang benar-benar dipakai. */
    data class DownloadResult(
        val path: String,
        /** "segment" atau "full-download". */
        val method: String
    )

    /** Resolve URL stream FRESH tepat sebelum ekspor (v1.0.6).
     * NewPipe di-extract ulang (metadata saja, tanpa media) supaya
     * URL yang dipakai bukan URL analisa yang bisa kedaluwarsa
     * (SABR / PO-token / expiry googlevideo). Kalau extract gagal
     * atau tidak memberi URL, stream analisa dipakai apa adanya —
     * jalur segmen boleh gagal, fallback download-penuh yang
     * menanggungnya, bukan crash di sini. Ranking audio sadar-bahasa
     * v1.0.5 TIDAK diubah: extract yang sama dipakai, dengan hint
     * bahasa yang sama dari caption.
     */
    private suspend fun resolveFreshStreams(info: VideoInfo): VideoStream {
        return try {
            val preferred = if (info.captionTracks.any {
                    it.languageCode.startsWith("id")
                }
            ) {
                "id"
            } else {
                info.captionTracks.firstOrNull()?.languageCode
                    ?.take(2)?.lowercase()
            }
            val fresh = NewPipeStreamExtractor.extract(info.videoId, preferred)
            if (fresh.audioUrl != null || fresh.videoUrl != null) {
                info.stream.copy(
                    videoOnlyUrl = fresh.videoUrl ?: info.stream.videoOnlyUrl,
                    audioUrl = fresh.audioUrl ?: info.stream.audioUrl,
                    audioTrackDescription = if (fresh.audioUrl != null) {
                        fresh.audioTrackDescription.ifEmpty {
                            info.stream.audioTrackDescription
                        }
                    } else {
                        info.stream.audioTrackDescription
                    }
                )
            } else {
                info.stream
            }
        } catch (e: Exception) {
            info.stream
        }
    }

    /** Unduh stream ke [target]. Mengembalikan true kalau file valid. */
    suspend fun downloadToFile(url: String, target: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (target.exists()) target.delete()
                val request = Request.Builder().url(url).build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false
                target.outputStream().use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }
                response.close()
                target.exists() && target.length() > 0
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Download + potong satu clip, simpan ke storage, sertakan jalur
     * ekspor yang dipakai. Mengembalikan path tampil (mis.
     * "Downloads/ClipKu/...") atau null kalau gagal, dengan pesan
     * error dilempar sebagai IllegalStateException.
     */
    suspend fun downloadClipDetailed(
        context: Context,
        info: VideoInfo,
        clip: Clip
    ): DownloadResult = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val safeTitle = info.title
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .take(40)
            .ifEmpty { "clip" }
        val fileName = "%02d_%s.mp4".format(clip.index, safeTitle.replace(' ', '-'))

        val cutFile = File(context.cacheDir, "clipku-cut-$fileName")
        if (cutFile.exists()) cutFile.delete()

        // v1.0.6 LAPIS 1 — jalur segmen: URL fresh + Transformer
        // dari URL remote, tanpa download video utuh. Gagal apa pun
        // (timeout, 403, URL kedaluwarsa, output tidak valid) = jatuh
        // ke lapis 2 di bawah, BUKAN error ke user.
        val fresh = resolveFreshStreams(info)
        val segmentOk = try {
            withTimeoutOrNull(SEGMENT_TIMEOUT_MS) {
                SegmentExport.trySegmentClip(
                    context,
                    fresh.progressiveUrl,
                    fresh.videoOnlyUrl,
                    fresh.audioUrl,
                    clip.startMs,
                    clip.endMs,
                    cutFile
                )
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (segmentOk && cutFile.exists() && cutFile.length() > 0L) {
            return@withContext DownloadResult(
                saveToDownloads(context, cutFile, fileName), "segment"
            )
        }
        try { cutFile.delete() } catch (e: Exception) { /* abaikan */ }

        // v1.0.6 LAPIS 2 — jalur download-penuh v1.0.5, TIDAK DIUBAH.
        // Satu-satunya jalan keluar yang tersisa; kalau ini pun gagal,
        // error dilempar seperti dulu.
        val ok = if (info.stream.progressiveUrl != null) {
            val source = File(cacheDir, "source-${info.videoId}.mp4")
            if (!source.exists() || source.length() == 0L) {
                if (!downloadToFile(info.stream.progressiveUrl, source)) {
                    throw IllegalStateException("Gagal mengunduh video sumber.")
                }
            }
            CuttingEngine.cutProgressive(context, source, clip.startMs, clip.endMs, cutFile)
        } else if (info.stream.videoOnlyUrl != null && info.stream.audioUrl != null) {
            val videoFile = File(cacheDir, "source-${info.videoId}-video.mp4")
            val audioFile = File(cacheDir, "source-${info.videoId}-audio")
            if (!videoFile.exists() || videoFile.length() == 0L) {
                if (!downloadToFile(info.stream.videoOnlyUrl, videoFile)) {
                    throw IllegalStateException("Gagal mengunduh video sumber.")
                }
            }
            if (!audioFile.exists() || audioFile.length() == 0L) {
                if (!downloadToFile(info.stream.audioUrl, audioFile)) {
                    throw IllegalStateException("Gagal mengunduh audio sumber.")
                }
            }
            CuttingEngine.cutAdaptive(videoFile, audioFile, clip.startMs, clip.endMs, cutFile)
        } else {
            throw IllegalStateException(
                "Tidak ada stream yang bisa diunduh untuk video ini."
            )
        }
        if (!ok || !cutFile.exists() || cutFile.length() == 0L) {
            throw IllegalStateException("Gagal memotong clip. Coba lagi.")
        }
        DownloadResult(saveToDownloads(context, cutFile, fileName), "full-download")
    }

    /**
     * Download + potong satu clip, simpan ke storage.
     * Mengembalikan path tampil (mis. "Downloads/ClipKu/...") atau null
     * kalau gagal, dengan pesan error dilempar sebagai IllegalStateException.
     */
    suspend fun downloadClip(
        context: Context,
        info: VideoInfo,
        clip: Clip
    ): String = downloadClipDetailed(context, info, clip).path

    /**
     * Potong SATU clip ke file temp di cache (v1.0.2, untuk Whisper).
     * Sama seperti downloadClip, tapi TIDAK menyalin hasil ke
     * Downloads/ClipKu dan TIDAK mengembalikan path tampil — yang
     * dikembalikan adalah file temp itu sendiri, dan PEMANGGIL wajib
     * menghapusnya di blok finally setelah dipakai (Whisper selesai).
     * Mengembalikan null kalau tidak ada stream yang bisa dipotong
     * atau pemotongan gagal.
     * v1.0.6: Whisper hanya butuh AUDIO, jadi dicoba dulu jalur
     * segmen audio-only (URL audio fresh + Transformer dari URL,
     * tanpa video utuh). Kalau jalur itu gagal, baru jatuh ke
     * potong-penuh dari sumber cache seperti dulu (Whisper pertama
     * untuk satu video BISA mengunduh sumber penuh di jalur fallback
     * itu — biaya yang sama seperti satu tap Download — tapi yang
     * dikirim ke Whisper tetap hanya file clip ~60 detik ini, bukan
     * video utuh).
     */
    suspend fun cutClipToTemp(
        context: Context,
        info: VideoInfo,
        clip: Clip
    ): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val temp = File(context.cacheDir, "clipku-whisper-${info.videoId}-${clip.index}.mp4")
        if (temp.exists()) temp.delete()
        // v1.0.6: Whisper hanya butuh AUDIO clip (~60 detik), bukan
        // video. Coba dulu segmen audio-only dari URL fresh (tanpa
        // mengunduh video utuh sama sekali). Gagal = jatuh ke jalur
        // potong-penuh di bawah, seperti dulu.
        val freshAudio = try {
            resolveFreshStreams(info).audioUrl ?: info.stream.audioUrl
        } catch (e: Exception) {
            info.stream.audioUrl
        }
        val audioSegmentOk = try {
            withTimeoutOrNull(SEGMENT_AUDIO_TIMEOUT_MS) {
                SegmentExport.trySegmentAudio(
                    context, freshAudio, clip.startMs, clip.endMs, temp
                )
            } ?: false
        } catch (e: Exception) {
            false
        }
        if (audioSegmentOk && temp.exists() && temp.length() > 0) {
            return@withContext temp
        }
        try { temp.delete() } catch (e: Exception) { /* abaikan */ }
        val ok = if (info.stream.progressiveUrl != null) {
            val source = File(cacheDir, "source-${info.videoId}.mp4")
            if (!source.exists() || source.length() == 0L) {
                if (!downloadToFile(info.stream.progressiveUrl, source)) {
                    return@withContext null
                }
            }
            CuttingEngine.cutProgressive(context, source, clip.startMs, clip.endMs, temp)
        } else if (info.stream.videoOnlyUrl != null && info.stream.audioUrl != null) {
            val videoFile = File(cacheDir, "source-${info.videoId}-video.mp4")
            val audioFile = File(cacheDir, "source-${info.videoId}-audio")
            if (!videoFile.exists() || videoFile.length() == 0L) {
                if (!downloadToFile(info.stream.videoOnlyUrl, videoFile)) {
                    return@withContext null
                }
            }
            if (!audioFile.exists() || audioFile.length() == 0L) {
                if (!downloadToFile(info.stream.audioUrl, audioFile)) {
                    return@withContext null
                }
            }
            CuttingEngine.cutAdaptive(videoFile, audioFile, clip.startMs, clip.endMs, temp)
        } else {
            false
        }
        if (ok && temp.exists() && temp.length() > 0) temp else {
            try { temp.delete() } catch (e: Exception) { /* abaikan */ }
            null
        }
    }

    private fun saveToDownloads(context: Context, cutFile: File, fileName: String): String {
        // Jalur utama: MediaStore Downloads/ClipKu (API 29+, tanpa izin storage).
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ClipKu")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    cutFile.inputStream().copyTo(out)
                }
                cutFile.delete()
                return "Download/ClipKu/$fileName"
            }
        } catch (e: Exception) {
            // Jatuh ke fallback di bawah, bukan crash.
        }
        // Fallback: folder file eksternal app (selalu bisa ditulis).
        val fallbackDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ClipKu"
        ).apply { mkdirs() }
        val fallback = File(fallbackDir, fileName)
        cutFile.copyTo(fallback, overwrite = true)
        cutFile.delete()
        return fallback.absolutePath
    }
}
