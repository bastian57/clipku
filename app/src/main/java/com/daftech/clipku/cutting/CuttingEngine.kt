package com.daftech.clipku.cutting

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
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
 * Pemotong clip — EKSTRAKSI, bukan edit kreatif.
 *
 * Yang dilakukan di sini HANYA: ambil rentang start-end dari video sumber,
 * simpan sebagai MP4 baru dengan rasio asli. TIDAK ada transisi, teks,
 * musik, filter, reframe vertikal, voiceover, atau thumbnail yang dibakar.
 *
 * Jalur utama: Media3 Transformer 1.4.1 (EditedMediaItem + ClippingConfiguration).
 * Jalur fallback: MediaExtractor + MediaMuxer murni Android, dipakai kalau
 * sumbernya adaptive (video-only + audio terpisah) atau Transformer gagal.
 * Fallback ini memotong per sampel berdasarkan timestamp, tanpa re-encode
 * penuh — kualitas asli dipertahankan, batas potong mengikuti keyframe
 * terdekat untuk video dan sampel audio terdekat.
 *
 * FFmpeg TIDAK dibundel di v1 (lihat BUILD-NOTES.md): FFmpegKit arthenica
 * sudah pensiun dan binary-nya dicabut, sedangkan fork maintained
 * membutuhkan build dari source + dukungan 16KB page size yang belum
 * terverifikasi di CI ini. Kalau suatu hari Transformer + MediaMuxer
 * terbukti tidak cukup presisi untuk satu format, itulah satu-satunya
 * alasan menambah FFmpeg — bukan untuk fitur edit.
 */
object CuttingEngine {

    /**
     * Potong file sumber (progressive, video+audio) menjadi [output].
     * Mengembalikan true kalau berhasil.
     */
    suspend fun cutProgressive(
        context: Context,
        input: File,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        return try {
            cutWithTransformer(context, input, startMs, endMs, output)
        } catch (e: Exception) {
            // Transformer gagal -> fallback MediaMuxer, bukan crash.
            try {
                cutWithMuxer(input, startMs, endMs, output)
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Gabung + potong sumber adaptive: video-only + audio terpisah
     * menjadi satu MP4. Dijalankan lewat MediaMuxer (bukan Transformer,
     * karena Transformer v1 di sini dipetakan untuk satu input).
     */
    fun cutAdaptive(
        videoFile: File,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        return try {
            muxTrim(videoFile, audioFile, startMs, endMs, output)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun cutWithTransformer(
        context: Context,
        input: File,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (output.exists()) output.delete()
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(input.absolutePath)
            .setClippingConfiguration(clipping)
            .build()
        val composition = Composition.Builder(
            listOf(
                // fix2 (2026-11-07): ctor EditedMediaItemSequence(...) jadi
                // private di media3 1.9.0 — pakai factory withAudioAndVideoFrom.
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    listOf(EditedMediaItem.Builder(mediaItem).build())
                )
            )
        ).build()
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

    private fun cutWithMuxer(
        input: File,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        return muxTrim(input, null, startMs, endMs, output)
    }

    /**
     * Salin track video (+ audio kalau [audioFile] diberikan, atau dari
     * file yang sama kalau null) yang timestamp-nya di dalam rentang,
     * ke MP4 baru via MediaMuxer.
     */
    private fun muxTrim(
        videoFile: File,
        audioFile: File?,
        startMs: Long,
        endMs: Long,
        output: File
    ): Boolean {
        if (output.exists()) output.delete()
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = selectTrack(videoExtractor, "video/")
                ?: throw IllegalStateException("Track video tidak ditemukan")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val outVideoTrack = muxer.addTrack(videoFormat)

            var audioExtractor: MediaExtractor? = null
            var audioTrack = -1
            var outAudioTrack = -1
            var audioFormat: MediaFormat? = null
            val audioSource = audioFile ?: videoFile
            if (audioFile != null || true) {
                try {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(audioSource.absolutePath)
                    val found = selectTrack(extractor, "audio/")
                    if (found != null) {
                        audioExtractor = extractor
                        audioTrack = found
                        audioFormat = extractor.getTrackFormat(found)
                        outAudioTrack = muxer.addTrack(audioFormat)
                    }
                } catch (e: Exception) {
                    audioExtractor = null
                }
            }

            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, outVideoTrack, startUs, endUs, true)
            if (audioExtractor != null && audioTrack >= 0 && outAudioTrack >= 0) {
                copyTrack(audioExtractor, audioTrack, muxer, outAudioTrack, startUs, endUs, false)
            }
            muxer.stop()
            videoExtractor.release()
            audioExtractor?.release()
            return output.exists() && output.length() > 0
        } finally {
            try { muxer.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        outTrack: Int,
        startUs: Long,
        endUs: Long,
        isVideo: Boolean
    ) {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        var safety = 0
        while (safety < 500_000) {
            safety++
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val sampleTime = extractor.sampleTime
            if (sampleTime > endUs) break
            if (sampleTime >= startUs) {
                info.set(0, sampleSize, sampleTime - startUs, extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
            }
            if (!extractor.advance()) break
        }
        // isVideo dipertahankan di signature untuk kejelasan pemanggil;
        // perilaku video vs audio di sini sama (salin sampel dalam rentang).
        @Suppress("UNUSED_PARAMETER")
        val unused = isVideo
    }
}
