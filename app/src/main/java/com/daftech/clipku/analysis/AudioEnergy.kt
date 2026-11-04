package com.daftech.clipku.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Energi audio per detik (RMS ternormalisasi 0.0-1.0) dari track audio.
 *
 * Cara kerja: unduh audio-only ke cache SEMENTARA (bukan storage hasil),
 * decode ke PCM via MediaExtractor + MediaCodec, hitung RMS per detik,
 * lalu HAPUS file cache. Audio TIDAK disimpan sebagai hasil akhir.
 *
 * Best-effort: kalau decode gagal (codec tidak didukung, stream rusak),
 * kembalikan map kosong — scoring tetap jalan dengan heatmap + transkrip.
 * Fungsi ini TIDAK pernah melempar ke UI.
 */
object AudioEnergy {
    suspend fun analyse(
        audioUrl: String?,
        cacheDir: File,
        http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    ): Map<Int, Double> = withContext(Dispatchers.IO) {
        if (audioUrl.isNullOrEmpty()) return@withContext emptyMap()
        val tmp = File(cacheDir, "clipku-audio-temp")
        try {
            val request = Request.Builder().url(audioUrl).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyMap()
            tmp.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
            }
            response.close()
            decodeRmsPerSecond(tmp)
        } catch (e: Exception) {
            emptyMap()
        } finally {
            try { tmp.delete() } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun decodeRmsPerSecond(file: File): Map<Int, Double> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return emptyMap()
            extractor.selectTrack(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME) ?: return emptyMap()
            )
            codec.configure(format, null, null, 0)
            codec.start()
            val sumSquares = mutableMapOf<Int, Double>()
            val counts = mutableMapOf<Int, Long>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var safety = 0
            while (!outputDone && safety < 200_000) {
                safety++
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (buffer != null) {
                            extractor.readSampleData(buffer, 0)
                        } else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null) {
                            accumulate(buffer, bufferInfo, sampleRate, channelCount, sumSquares, counts)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            if (sumSquares.isEmpty()) return emptyMap()
            var maxRms = 0.0
            val rmsBySecond = mutableMapOf<Int, Double>()
            for ((second, sum) in sumSquares) {
                val count = counts[second] ?: 1L
                val rms = sqrt(sum / count.toDouble())
                rmsBySecond[second] = rms
                if (rms > maxRms) maxRms = rms
            }
            if (maxRms <= 0.0) return emptyMap()
            return rmsBySecond.mapValues { (_, rms) -> (rms / maxRms).coerceIn(0.0, 1.0) }
        } catch (e: Exception) {
            return emptyMap()
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun accumulate(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        sampleRate: Int,
        channelCount: Int,
        sumSquares: MutableMap<Int, Double>,
        counts: MutableMap<Int, Long>
    ) {
        // PCM s16le diasumsikan (decoder audio Android umumnya mengeluarkan ini).
        // Buffer diposisikan di offset info; baca sebagai short.
        val start = info.offset
        val end = info.offset + info.size
        val second = (info.presentationTimeUs / 1_000_000L).toInt()
        var sum = 0.0
        var count = 0L
        var pos = start
        // ByteBuffer dari MediaCodec biasanya sudah diposisikan; pakai getShort absolut
        // supaya tidak merusak posisi buffer untuk pemakaian lain.
        while (pos + 1 < end && pos + 1 < buffer.capacity()) {
            val sample = buffer.getShort(pos).toDouble() / 32768.0
            sum += sample * sample
            count++
            pos += 2
        }
        if (count > 0) {
            sumSquares[second] = (sumSquares[second] ?: 0.0) + sum
            counts[second] = (counts[second] ?: 0L) + count
        }
        // sampleRate/channelCount dipakai implisit lewat posisi waktu (presentationTimeUs);
        // parameter dipertahankan agar signature jelas dan mudah diuji.
        @Suppress("UNUSED_PARAMETER")
        val unusedRate = sampleRate
        @Suppress("UNUSED_PARAMETER")
        val unusedChannels = channelCount
    }
}
