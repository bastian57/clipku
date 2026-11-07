package com.daftech.clipku.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * v1.0.3: hasil bukan lagi sekadar map kosong/berisi. AudioAnalysisResult
 * membawa jejak tahapnya (HTTP, byte, mime, error decode, stream dicoba)
 * supaya diagnostik UI bisa menyebut KENAPA audio 0 detik, bukan cuma
 * "0 detik". Fungsi ini TIDAK pernah melempar ke UI.
 */
data class AudioAnalysisResult(
    val bySecond: Map<Int, Double>,
    val httpCode: Int,
    val downloadedBytes: Long,
    val mime: String,
    val error: String?,
    val streamsTried: Int,
    /**
     * v1.0.8 P0-4: true kalau kegagalan terakhir adalah DNS
     * (UnknownHostException) — bedakan dari HTTP/expired/decode.
     * failedHost = host googlevideo yang gagal di-resolve.
     */
    val dnsFailure: Boolean = false,
    val failedHost: String? = null,
    /**
     * v1.3.0 (#4): "streaming" kalau RMS didapat lewat
     * MediaExtractor dari URL langsung, "download-penuh" kalau
     * lewat jalur unduh file seperti dulu.
     */
    val audioMethod: String = "download-penuh"
)

private data class DecodeOutcome(
    val bySecond: Map<Int, Double>,
    val mime: String,
    val error: String?
)

object AudioEnergy {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    /** v1.3.0 (#4): batas percobaan streaming sebelum fallback download. */
    private const val STREAMING_TIMEOUT_MS = 90_000L

    suspend fun analyse(
        audioUrl: String?,
        cacheDir: File,
        http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    ): AudioAnalysisResult =
        analyseMultiple(listOfNotNull(audioUrl), cacheDir, http)

    /**
     * Coba stream audio berurutan: yang pertama decode-nya kosong
     * langsung dilanjutkan ke stream berikutnya, sampai satu berhasil
     * atau daftar habis. Yang pertama berhasil langsung dipakai.
     *
     * v1.0.8 P0-4 — diversifikasi host DULU: semua URL audio NewPipe
     * untuk satu video biasanya menunjuk ke SATU host googlevideo
     * yang sama, jadi satu UnknownHostException menggagalkan ke-35
     * percobaan dengan error yang sama. Urutan coba sekarang: satu
     * URL per host yang berbeda dulu (NewPipe vs Innertube), baru
     * sisanya berurutan. Satu host yang mati tidak lagi membunuh
     * seluruh daftar sebelum host lain dicoba.
     */
    suspend fun analyseMultiple(
        audioUrls: List<String>,
        cacheDir: File,
        http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    ): AudioAnalysisResult = withContext(Dispatchers.IO) {
        val urls = diversifyByHost(
            audioUrls.filter { it.isNotEmpty() }.distinct()
        )
        if (urls.isEmpty()) {
            return@withContext AudioAnalysisResult(
                emptyMap(), 0, 0, "", "tanpa URL audio", 0
            )
        }
        var tried = 0
        var last = AudioAnalysisResult(emptyMap(), 0, 0, "", "tanpa URL audio", 0)
        for (url in urls) {
            tried++
            val result = analyseOne(url, cacheDir, http)
            if (result.bySecond.isNotEmpty()) {
                return@withContext result.copy(streamsTried = tried)
            }
            last = result
        }
        last.copy(streamsTried = tried)
    }

    /**
     * v1.0.8 P0-4: urutkan URL supaya satu URL per host yang berbeda
     * dicoba dulu (giliran pertama), baru URL sisa dari host yang
     * sudah dicoba (giliran kedua). Host diambil dari URL; kalau
     * tidak ter-parse, URL itu dianggap host-nya sendiri.
     */
    private fun diversifyByHost(urls: List<String>): List<String> {
        val seenHosts = linkedSetOf<String>()
        val firstPerHost = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (url in urls) {
            val host = hostOf(url)
            if (seenHosts.add(host)) {
                firstPerHost.add(url)
            } else {
                rest.add(url)
            }
        }
        return firstPerHost + rest
    }

    private fun hostOf(url: String): String {
        return try {
            java.net.URL(url).host.lowercase()
        } catch (e: Exception) {
            url
        }
    }

    /** Cari UnknownHostException di rantai penyebab exception. */
    private fun findUnknownHost(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is java.net.UnknownHostException) return true
            t = t.cause
        }
        return false
    }

    /**
     * v1.3.0 (#4): coba STREAMING dulu — MediaExtractor membuka URL
     * audio langsung (dengan UA + Referer yang sama seperti download)
     * dan RMS dihitung dari PCM yang mengalir, sambil jaringan masih
     * mengunduh — decode + download tumpang-tindih, tanpa tulis/baca
     * file cache. Timeout 90 detik; gagal/timeout/kosong = jatuh ke
     * jalur download-penuh di bawah (analisa TIDAK digagalkan oleh
     * streaming). JUJUR: apakah extractor benar-benar tidak menarik
     * file penuh belum terbukti (container/moov) — ukur waktu di HP;
     * yang pasti hemat adalah tidak adanya tulis/baca disk.
     */
    private suspend fun analyseOne(
        audioUrl: String,
        cacheDir: File,
        http: OkHttpClient
    ): AudioAnalysisResult = withContext(Dispatchers.IO) {
        val streamed = withTimeoutOrNull(STREAMING_TIMEOUT_MS) {
            decodeStreaming(audioUrl)
        }
        if (streamed != null && streamed.bySecond.isNotEmpty()) {
            return@withContext AudioAnalysisResult(
                streamed.bySecond, 0, 0, streamed.mime, null, 0,
                audioMethod = "streaming"
            )
        }
        var tmp: File? = null
        try {
            val request = Request.Builder()
                .url(audioUrl)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://www.youtube.com/")
                .build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext AudioAnalysisResult(
                    emptyMap(), code, 0, "", "HTTP $code", 0
                )
            }
            // Ekstensi dari Content-Type, sebelum body ditulis, supaya
            // MediaExtractor lebih mudah mengenali formatnya.
            val contentType = response.header("Content-Type", "") ?: ""
            val ext = when {
                contentType.contains("mp4") -> ".m4a"
                contentType.contains("webm") -> ".webm"
                contentType.contains("mpeg") || contentType.contains("mp3") -> ".mp3"
                contentType.contains("ogg") || contentType.contains("opus") -> ".ogg"
                else -> ""
            }
            tmp = File(cacheDir, "clipku-audio-temp$ext")
            var bytes = 0L
            tmp.outputStream().use { out ->
                val stream = response.body?.byteStream()
                if (stream != null) {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytes += read
                    }
                }
            }
            val code = response.code
            response.close()
            if (bytes <= 0L) {
                return@withContext AudioAnalysisResult(
                    emptyMap(), code, 0, contentType, "0 byte terunduh", 0
                )
            }
            val outcome = decodeRmsPerSecond(tmp)
            AudioAnalysisResult(
                outcome.bySecond, code, bytes, outcome.mime,
                outcome.error, 0
            )
        } catch (e: Exception) {
            // v1.0.8 P0-4: bedakan DNS dari kegagalan lain — host yang
            // gagal di-resolve dicatat supaya diagnostik bisa menulis
            // "host X gagal resolve (DNS)", bukan pesan exception mentah.
            if (findUnknownHost(e)) {
                val host = hostOf(audioUrl)
                AudioAnalysisResult(
                    emptyMap(), 0, 0, "",
                    "host $host gagal resolve (DNS)", 0,
                    dnsFailure = true, failedHost = host
                )
            } else {
                AudioAnalysisResult(
                    emptyMap(), 0, 0, "",
                    (e.message ?: "gagal unduh/decode").take(80), 0
                )
            }
        } finally {
            try { tmp?.delete() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /** v1.3.0 (#4): decode streaming dari URL langsung (tanpa file). */
    private fun decodeStreaming(audioUrl: String): DecodeOutcome {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(
                audioUrl,
                mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to "https://www.youtube.com/"
                )
            )
            decodeFromExtractor(extractor)
        } catch (e: Exception) {
            DecodeOutcome(
                emptyMap(), "", (e.message ?: "gagal streaming").take(80)
            )
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun decodeRmsPerSecond(file: File): DecodeOutcome {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            decodeFromExtractor(extractor)
        } catch (e: Exception) {
            DecodeOutcome(
                emptyMap(), "", (e.message ?: "gagal decode").take(80)
            )
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /**
     * v1.3.0 (#4): inti decode RMS — dipakai file lokal DAN streaming.
     * Extractor sudah di-setDataSource oleh pemanggil; fungsi ini hanya
     * memilih track audio, decode ke PCM, dan menghitung RMS per detik.
     * TIDAK me-release extractor (pemiliknya yang me-release).
     */
    private fun decodeFromExtractor(extractor: MediaExtractor): DecodeOutcome {
        try {
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
            if (trackIndex < 0 || format == null) {
                return DecodeOutcome(emptyMap(), "", "tanpa track audio")
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            extractor.selectTrack(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
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
            if (sumSquares.isEmpty()) {
                return DecodeOutcome(emptyMap(), mime, "decode 0 sampel")
            }
            var maxRms = 0.0
            val rmsBySecond = mutableMapOf<Int, Double>()
            for ((second, sum) in sumSquares) {
                val count = counts[second] ?: 1L
                val rms = sqrt(sum / count.toDouble())
                rmsBySecond[second] = rms
                if (rms > maxRms) maxRms = rms
            }
            if (maxRms <= 0.0) {
                return DecodeOutcome(emptyMap(), mime, "audio senyap (RMS 0)")
            }
            return DecodeOutcome(
                rmsBySecond.mapValues { (_, rms) -> (rms / maxRms).coerceIn(0.0, 1.0) },
                mime, null
            )
        } catch (e: Exception) {
            return DecodeOutcome(
                emptyMap(), "", (e.message ?: "gagal decode").take(80)
            )
        }
        // TANPA finally release: extractor milik pemanggil
        // (decodeStreaming / decodeRmsPerSecond) yang me-release.
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
