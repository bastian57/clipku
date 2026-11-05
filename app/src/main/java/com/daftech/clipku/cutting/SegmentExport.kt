package com.daftech.clipku.cutting

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ekspor segmen TANPA mengunduh video utuh (v1.0.6, upgrade #2).
 *
 * CARA KERJA (jujur, bukan klaim yang belum teruji):
 *  - Inputnya URL stream LANGSUNG (fresh, di-resolve tepat sebelum
 *    ekspor oleh ClipDownload) + ClippingConfiguration [start, end].
 *    Media3 (Transformer) memakai tumpukan DataSource yang sama
 *    dengan ExoPlayer preview — yang SUDAH terbukti di HP bisa
 *    memutar rentang tengah video tanpa mengunduh utuh: ia membaca
 *    moov dulu, lalu seek (HTTP Range) ke sampel clip. Jadi yang
 *    ditransfer idealnya hanya moov + byte segmen clip, bukan video
 *    utuh. BERAPA byte persisnya yang ditransfer TIDAK bisa diukur
 *    dari sini (Transformer tidak mengekspos counter byte) — bukti
 *    nyatanya adalah WAKTU download di HP pada acceptance, bukan
 *    angka dari code ini. Jangan klaim "hanya X MB" tanpa tes HP.
 *  - Ini BUKAN fetch Range byte-per-byte + remux MP4 tulisan tangan:
 *    merakit ulang moov (stco/stsz/stts/stss) sendiri adalah ~300
 *    baris rawan salah dan tidak bisa dites di sandbox ini (tanpa
 *    JDK/Gradle). Transformer/MediaMuxer yang sudah terbukti di
 *    v1.0.3 dipakai untuk muxing-nya; yang baru di sini hanya
 *    INPUT-nya (URL remote, bukan file lokal penuh).
 *  - Transformer DASH (.mpd) / HLS (.m3u8) sebagai input TIDAK
 *    dipakai di sini dan TIDAK diklaim jalan (riset: tidak
 *    terverifikasi). Yang dipakai adalah URL stream langsung
 *    (progressive, atau video-only + audio terpisah) dari NewPipe /
 *    Innertube — persis URL yang sama dipakai preview + download
 *    penuh v1.0.5.
 *  - Batas potong mengikuti keyframe terdekat (snap): hasil akhir
 *    boleh meleset sampai ~2 detik dari batas yang diminta. Itu
 *    batas yang diterima di scope ini, bukan bug — UI tidak boleh
 *    mengklaim potongan akurat-frame.
 *
 * KEAMANAN: fungsi di sini TIDAK PERNAH melempar ke pemanggil dan
 * TIDAK PERNAH mengembalikan file yang tidak tervalidasi. Output
 * dicek dengan MediaExtractor (track video/audio ada + durasi
 * masuk akal); kalau gagal / tidak valid, file output dihapus dan
 * hasilnya false — pemanggil (ClipDownload) WAJIB jatuh ke jalur
 * download-penuh v1.0.5. Clip rusak tidak pernah sampai ke user.
 */
object SegmentExport {

    /**
     * Coba ekspor satu clip dari URL remote, tanpa download penuh.
     * Progressive (satu item) diutamakan, sama seperti ClipDownload;
     * adaptive memakai Composition dua sequence (video + audio,
     * keduanya di-clip ke rentang yang sama).
     * Mengembalikan true HANYA kalau [output] tervalidasi.
     */
    suspend fun trySegmentClip(
        context: Context,
        progressiveUrl: String?,
        videoOnlyUrl: String?,
        audioUrl: String?,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        if (endMs <= startMs) return false
        val items: List<MediaItem> = when {
            !progressiveUrl.isNullOrBlank() -> {
                listOf(clippedItem(progressiveUrl, startMs, endMs))
            }
            !videoOnlyUrl.isNullOrBlank() && !audioUrl.isNullOrBlank() -> {
                listOf(
                    clippedItem(videoOnlyUrl, startMs, endMs),
                    clippedItem(audioUrl, startMs, endMs)
                )
            }
            else -> return false
        }
        return try {
            val ok = transformRemote(context, items, output)
            if (!ok) return false
            if (!validateClip(output, endMs - startMs, needVideo = true)) {
                try { output.delete() } catch (e: Exception) { /* abaikan */ }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            try { output.delete() } catch (e2: Exception) { /* abaikan */ }
            false
        }
    }

    /**
     * Coba potong HANYA audio clip dari URL audio remote (untuk
     * Whisper, v1.0.2: yang dikirim ke Whisper hanya clip ~60 detik).
     * Output-nya MP4 berisi AAC — WhisperClient mengirim file ini
     * sebagai clip.mp4 (Groq membaca isi, bukan ekstensi).
     * Mengembalikan true HANYA kalau [output] tervalidasi.
     */
    suspend fun trySegmentAudio(
        context: Context,
        audioUrl: String?,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        if (audioUrl.isNullOrBlank() || endMs <= startMs) return false
        return try {
            val ok = transformRemote(
                context,
                listOf(clippedItem(audioUrl, startMs, endMs)),
                output
            )
            if (!ok) return false
            if (!validateClip(output, endMs - startMs, needVideo = false)) {
                try { output.delete() } catch (e: Exception) { /* abaikan */ }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            try { output.delete() } catch (e2: Exception) { /* abaikan */ }
            false
        }
    }

    private fun clippedItem(url: String, startMs: Long, endMs: Long): MediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setClippingConfiguration(clipping)
            .build()
    }

    /**
     * Transformer dari URL remote (bukan file lokal). Mirip
     * CuttingEngine.cutWithTransformer, sengaja diduplikasi di sini
     * supaya jalur fallback v1.0.5 di CuttingEngine TIDAK disentuh
     * sama sekali oleh upgrade ini.
     */
    private suspend fun transformRemote(
        context: Context,
        items: List<MediaItem>,
        output: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (output.exists()) output.delete()
        val sequences = items.map { item ->
            EditedMediaItemSequence(EditedMediaItem.Builder(item).build())
        }
        val composition = Composition.Builder(sequences).build()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(exportException)
                    }
                }
            })
            .build()
        transformer.start(composition, output.absolutePath)
        continuation.invokeOnCancellation {
            try { transformer.cancel() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /**
     * Validasi output: file ada + berisi, track yang dibutuhkan ada,
     * durasi masuk akal (0.4x–1.6x dari yang diminta; longgar karena
     * keyframe snap ±2 detik + edit list Transformer). Durasi yang
     * tidak terbaca (format tanpa KEY_DURATION) tetap diterima kalau
     * track-nya ada — yang ditolak adalah file tanpa track / kosong.
     */
    fun validateClip(
        file: File,
        expectedDurationMs: Long,
        needVideo: Boolean
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        if (expectedDurationMs <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var durationUs = -1L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
                if (durationUs < 0) {
                    try {
                        val d = format.getLong(MediaFormat.KEY_DURATION)
                        if (d > 0) durationUs = d
                    } catch (e: Exception) { /* format tanpa durasi */ }
                }
            }
            if (needVideo && !hasVideo) return false
            if (!needVideo && !hasAudio) return false
            if (durationUs > 0) {
                val durationMs = durationUs / 1000L
                val minMs = (expectedDurationMs * 0.4).toLong()
                val maxMs = (expectedDurationMs * 1.6).toLong()
                if (durationMs < minMs || durationMs > maxMs) return false
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }
}
