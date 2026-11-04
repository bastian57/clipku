package com.daftech.clipku.cutting

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Download per clip — HANYA atas tap user, bukan auto-download semua.
 *
 * Alur per tap Download:
 *  1. Unduh video sumber SEKALI ke cache (dipakai ulang untuk clip lain
 *     dari video yang sama), atau unduh video-only + audio kalau hanya
 *     adaptive yang tersedia.
 *  2. Potong rentang clip via CuttingEngine (ekstraksi, bukan edit).
 *  3. Salin hasil potong ke Downloads/ClipKu via MediaStore (API 29+),
 *     fallback ke folder file eksternal app kalau MediaStore gagal.
 *  4. Hapus file cache hasil potong (file sumber cache dipertahankan
 *     selama sesi supaya clip berikutnya tidak mengunduh ulang).
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
     * Download + potong satu clip, simpan ke storage.
     * Mengembalikan path tampil (mis. "Downloads/ClipKu/...") atau null
     * kalau gagal, dengan pesan error dilempar sebagai IllegalStateException.
     */
    suspend fun downloadClip(
        context: Context,
        info: VideoInfo,
        clip: Clip
    ): String = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val safeTitle = info.title
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .take(40)
            .ifEmpty { "clip" }
        val fileName = "%02d_%s.mp4".format(clip.index, safeTitle.replace(' ', '-'))

        val cutFile = File(context.cacheDir, "clipku-cut-$fileName")
        if (cutFile.exists()) cutFile.delete()

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
        saveToDownloads(context, cutFile, fileName)
    }

    /**
     * Potong SATU clip ke file temp di cache (v1.0.2, untuk Whisper).
     * Sama seperti downloadClip, tapi TIDAK menyalin hasil ke
     * Downloads/ClipKu dan TIDAK mengembalikan path tampil — yang
     * dikembalikan adalah file temp itu sendiri, dan PEMANGGIL wajib
     * menghapusnya di blok finally setelah dipakai (Whisper selesai).
     * Mengembalikan null kalau tidak ada stream yang bisa dipotong
     * atau pemotongan gagal. File sumber cache dipakai ulang dari
     * sesi yang sama persis seperti downloadClip, jadi Whisper untuk
     * clip kedua dari video yang sama tidak mengunduh ulang sumber.
     * Trade-off yang dicatat di FIX-NOTES: Whisper pertama untuk satu
     * video bisa mengunduh sumber penuh (biaya yang sama seperti satu
     * tap Download), tapi yang dikirim ke Whisper tetap hanya file
     * clip ~60 detik ini, bukan video utuh.
     */
    suspend fun cutClipToTemp(
        context: Context,
        info: VideoInfo,
        clip: Clip
    ): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val temp = File(context.cacheDir, "clipku-whisper-${info.videoId}-${clip.index}.mp4")
        if (temp.exists()) temp.delete()
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
