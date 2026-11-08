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
 *  2. JALUR DOWNLOAD-PENUH (v1.0.5; v1.0.9: kini memakai URL stream
 *     FRESH yang sama dengan lapis 1, bukan URL analisa — cacat
 *     v1.0.8 yang membuat download gagal 13 menit setelah analisa).
 *     Unduh video sumber SEKALI ke cache (dipakai ulang untuk clip
 *     lain dari video yang sama), atau unduh video-only + audio kalau
 *     hanya adaptive yang tersedia, lalu potong via CuttingEngine.
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

    /** UA browser yang sama dengan AudioEnergy (v1.0.3) + GroqClient. */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    private const val YOUTUBE_REFERER = "https://www.youtube.com/"

    /** Batas waktu satu percobaan jalur segmen sebelum fallback. */
    private const val SEGMENT_TIMEOUT_MS = 180_000L
    /** Batas waktu percobaan segmen audio-only untuk Whisper. */
    private const val SEGMENT_AUDIO_TIMEOUT_MS = 120_000L
    /**
     * v1.4.0 (P1-3): batas TOTAL cache sumber (clipku-source).
     * File sumber penuh per video menumpuk tanpa batas sejak v1.0.5;
     * kini totalnya dibatasi 500 MB dengan eviksi LRU (file sumber
     * tertua dihapus dulu) + tombol "Bersihkan Cache" di Settings.
     * History Room TIDAK ikut dihapus — hanya file sumber video.
     */
    const val MAX_SOURCE_CACHE_BYTES = 500L * 1024L * 1024L

    /** Hasil download clip + jalur ekspor yang benar-benar dipakai. */
    data class DownloadResult(
        val path: String,
        /** "segment" atau "full-download". */
        val method: String,
        /**
         * v1.0.9 — alasan singkat kenapa lapis segmen gagal, kalau
         * akhirnya jatuh ke full-download. Null kalau segmen berhasil
         * atau tidak sempat dicoba.
         */
        val segmentFailureReason: String? = null,
        /**
         * v1.4.0 (P1-1): durasi AKTUAL file clip hasil potong (ms,
         * dari MediaExtractor) — ukuran jujur pengganti klaim byte
         * yang tidak bisa diukur Transformer. Null kalau tidak terbaca.
         */
        val actualDurationMs: Long? = null
    )

    /**
     * v1.0.9 — hasil satu percobaan downloadToFile dengan alasan
     * aslinya (HTTP code / DNS / timeout / exception), supaya error
     * ke user bukan lagi pesan generik. Alasan TIDAK PERNAH memuat
     * URL lengkap atau Groq key.
     */
    data class DownloadOutcome(val ok: Boolean, val reason: String? = null)

    /** Cari UnknownHostException di rantai penyebab exception. */
    private fun findUnknownHost(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is java.net.UnknownHostException) return true
            t = t.cause
        }
        return false
    }

    /** Klasifikasi kegagalan download jadi alasan singkat. */
    private fun classifyDownloadFailure(url: String, e: Exception): String {
        if (findUnknownHost(e)) {
            val host = try {
                java.net.URL(url).host.lowercase()
            } catch (e2: Exception) {
                "?"
            }
            return "host $host gagal resolve (DNS)"
        }
        if (e is java.net.SocketTimeoutException ||
            e is java.util.concurrent.TimeoutException
        ) {
            return "timeout"
        }
        return (e.message ?: e.javaClass.simpleName).take(80)
    }

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

    /**
     * v1.0.8 P0-5 — validasi file sumber cache sebelum dipakai ulang.
     * Cek nama/length saja TIDAK cukup: file parsial dari download
     * yang gagal di tengah (exception saat copy) panjangnya >0 dan
     * lolos sebagai "sumber valid", lalu CuttingEngine memotong file
     * rusak itu dan percobaan ulang tetap gagal. Valid = panjang >0
     * DAN MediaExtractor bisa membukanya dengan >=1 track. File yang
     * tidak valid DIHAPUS supaya download berikutnya mengunduh ulang
     * dari nol — gagal download tidak boleh meracuni percobaan
     * berikutnya.
     */
    private fun isValidCachedSource(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.trackCount > 0
        } catch (e: Exception) {
            false
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    private fun deleteIfInvalidCachedSource(file: File) {
        if (!isValidCachedSource(file)) {
            try { file.delete() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /**
     * v1.4.0 (P1-3): total ukuran cache sumber (clipku-source).
     * Dipakai Settings untuk menampilkan ukuran + tombol bersih-bersih.
     */
    fun sourceCacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, "clipku-source")
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * v1.4.0 (P1-3): hapus SEMUA file sumber cache, kembalikan byte
     * yang dibebaskan. Dipakai tombol "Bersihkan Cache" di Settings.
     * File hasil potong (clipku-cut-*) dan temp Whisper TIDAK ikut —
     * hanya sumber video di clipku-source.
     */
    fun clearSourceCache(context: Context): Long {
        val dir = File(context.cacheDir, "clipku-source")
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            freed += file.length()
            try { file.delete() } catch (e: Exception) { /* abaikan */ }
        }
        return freed
    }

    /**
     * v1.4.0 (P1-3): eviksi LRU cache sumber sampai total <= batas.
     * File tertua (lastModified) dihapus dulu. Dipanggil di awal
     * downloadClipDetailed/cutClipToTemp — SEBELUM file sumber baru
     * ditambahkan — supaya cache tidak tumbuh tanpa batas.
     */
    private fun enforceSourceCacheLimit(context: Context) {
        val dir = File(context.cacheDir, "clipku-source")
        if (!dir.exists()) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_SOURCE_CACHE_BYTES) break
            total -= file.length()
            try { file.delete() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /**
     * v1.4.0 (P1-1): durasi aktual file media (ms) via MediaExtractor,
     * null kalau tidak terbaca. Dipakai DownloadResult supaya
     * diagnostik + kartu clip menampilkan durasi YANG BENAR-BENAR
     * dihasilkan, bukan durasi yang diminta.
     */
    private fun actualDurationMs(file: File): Long? {
        if (!file.exists() || file.length() <= 0L) return null
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = -1L
            for (i in 0 until extractor.trackCount) {
                try {
                    val d = extractor.getTrackFormat(i)
                        .getLong(android.media.MediaFormat.KEY_DURATION)
                    if (d > 0) {
                        durationUs = d
                        break
                    }
                } catch (e: Exception) { /* format tanpa durasi */ }
            }
            if (durationUs > 0) durationUs / 1000L else null
        } catch (e: Exception) {
            null
        } finally {
            try { extractor.release() } catch (e: Exception) { /* abaikan */ }
        }
    }

    /**
     * v1.4.0 (P2-4): nama file unik di Downloads/ClipKu — kalau nama
     * sudah ada (download clip yang sama berulang), tambahkan suffix
     * angka ("..._2.mp4", "..._3.mp4") supaya tidak timpa diam-diam
     * dan tidak duplikat diam-diam. Cek via MediaStore (nama tampil)
     * + file fallback.
     */
    private fun uniqueDownloadName(context: Context, fileName: String): String {
        if (!downloadNameExists(context, fileName)) return fileName
        val base = fileName.removeSuffix(".mp4")
        var n = 2
        while (n <= 99) {
            val candidate = "${base}_$n.mp4"
            if (!downloadNameExists(context, candidate)) return candidate
            n++
        }
        return "${base}_${System.currentTimeMillis()}.mp4"
    }

    private fun downloadNameExists(context: Context, name: String): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )
            cursor?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Unduh stream ke [target]. Mengembalikan hasil + alasan gagal.
     * v1.0.9: request KINI mengirim User-Agent browser + Referer
     * YouTube (sama seperti AudioEnergy) — request polos tanpa header
     * sebelumnya adalah cacat P2-7 audit dan tersangka 403 di
     * googlevideo. Timeout tidak diubah.
     */
    suspend fun downloadToFile(url: String, target: File): DownloadOutcome =
        withContext(Dispatchers.IO) {
            try {
                if (target.exists()) target.delete()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", YOUTUBE_REFERER)
                    .build()
                val response = http.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    // v1.0.8 P0-5: gagal HTTP = target dihapus, jangan
                    // tinggalkan file kosong/parsial untuk dipakai ulang.
                    try { target.delete() } catch (e: Exception) { /* abaikan */ }
                    return@withContext DownloadOutcome(false, "HTTP $code")
                }
                target.outputStream().use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }
                response.close()
                // v1.0.8 P0-5: sukses tapi 0 byte = file tidak valid,
                // hapus supaya tidak dianggap sumber cache yang sah.
                if (!target.exists() || target.length() <= 0L) {
                    try { target.delete() } catch (e: Exception) { /* abaikan */ }
                    return@withContext DownloadOutcome(false, "0 byte terunduh")
                }
                DownloadOutcome(true)
            } catch (e: Exception) {
                // v1.0.8 P0-5: exception di tengah copy (jaringan
                // putus, disk penuh) = file parsial DIHAPUS, bukan
                // dibiarkan untuk dipakai ulang sebagai "sumber".
                try { target.delete() } catch (e2: Exception) { /* abaikan */ }
                DownloadOutcome(false, classifyDownloadFailure(url, e))
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
        // v1.4.0 (P1-3): eviksi LRU dulu — cache tidak boleh tumbuh
        // tanpa batas sebelum sumber baru ditambahkan.
        enforceSourceCacheLimit(context)
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val safeTitle = info.title
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .take(40)
            .ifEmpty { "clip" }
        // v1.4.0 (P2-4): nama final unik — download ulang clip yang
        // sama tidak menimpa / menduplikasi diam-diam di Downloads.
        val fileName = uniqueDownloadName(
            context,
            "%02d_%s.mp4".format(clip.index, safeTitle.replace(' ', '-'))
        )

        val cutFile = File(context.cacheDir, "clipku-cut-$fileName")
        if (cutFile.exists()) cutFile.delete()

        // v1.0.6 LAPIS 1 — jalur segmen: URL fresh + Transformer
        // dari URL remote, tanpa download video utuh. Gagal apa pun
        // (timeout, 403, URL kedaluwarsa, output tidak valid) = jatuh
        // ke lapis 2 di bawah, BUKAN error ke user.
        // v1.0.9: alasan gagal lapis 1 DICATAT (bukan hilang diam-diam)
        // — timeout 180 detik dibedakan dari gagal ekspor/validasi.
        val fresh = resolveFreshStreams(info)
        var segmentReason: String? = null
        val segmentOk = try {
            val result = withTimeoutOrNull(SEGMENT_TIMEOUT_MS) {
                SegmentExport.trySegmentClipDetailed(
                    context,
                    fresh.progressiveUrl,
                    fresh.videoOnlyUrl,
                    fresh.audioUrl,
                    clip.startMs,
                    clip.endMs,
                    cutFile
                )
            }
            if (result == null) {
                segmentReason = "timeout 180 detik"
                false
            } else {
                segmentReason = result.second
                result.first
            }
        } catch (e: Exception) {
            segmentReason = (e.message ?: e.javaClass.simpleName).take(120)
            false
        }
        if (segmentOk && cutFile.exists() && cutFile.length() > 0L) {
            // Durasi dibaca SEBELUM saveToDownloads (yang menghapus
            // cutFile setelah menyalin) — kalau dibalik, selalu null.
            val actualMs = actualDurationMs(cutFile)
            return@withContext DownloadResult(
                saveToDownloads(context, cutFile, fileName), "segment",
                actualDurationMs = actualMs
            )
        }
        try { cutFile.delete() } catch (e: Exception) { /* abaikan */ }

        // v1.0.6 LAPIS 2 — jalur download-penuh v1.0.5.
        // v1.0.9 CACAT 1 DIPERBAIKI: lapis 2 KINI memakai stream
        // `fresh` (hasil resolveFreshStreams yang sama dengan lapis
        // 1), BUKAN info.stream dari waktu analisa. Sebelumnya lapis
        // 2 mengunduh dengan URL analisa yang bisa kedaluwarsa
        // (kasus Mahfud MD 2026-11-06: tap Download 13 menit setelah
        // analisa -> "Gagal mengunduh audio sumber."). Kalau re-extract
        // fresh gagal, resolveFreshStreams sudah mengembalikan
        // info.stream sebagai fallback — jadi `fresh` selalu terisi.
        // v1.0.8 P0-5: cache sumber divalidasi ISINYA (MediaExtractor
        // >=1 track), bukan cuma exists()+length — file parsial dari
        // download gagal tidak lagi dipakai ulang.
        // v1.0.9: kegagalan download membawa alasan aslinya (HTTP /
        // DNS / timeout) di pesan error, bukan pesan generik.
        val ok = if (fresh.progressiveUrl != null) {
            val source = File(cacheDir, "source-${info.videoId}.mp4")
            if (!isValidCachedSource(source)) {
                val attempt = downloadToFile(fresh.progressiveUrl, source)
                if (!attempt.ok) {
                    throw IllegalStateException(
                        "Gagal mengunduh video sumber. " +
                            "(${attempt.reason ?: "tanpa alasan"})"
                    )
                }
            }
            CuttingEngine.cutProgressive(context, source, clip.startMs, clip.endMs, cutFile)
        } else if (fresh.videoOnlyUrl != null && fresh.audioUrl != null) {
            val videoFile = File(cacheDir, "source-${info.videoId}-video.mp4")
            val audioFile = File(cacheDir, "source-${info.videoId}-audio")
            if (!isValidCachedSource(videoFile)) {
                val attempt = downloadToFile(fresh.videoOnlyUrl, videoFile)
                if (!attempt.ok) {
                    throw IllegalStateException(
                        "Gagal mengunduh video sumber. " +
                            "(${attempt.reason ?: "tanpa alasan"})"
                    )
                }
            }
            if (!isValidCachedSource(audioFile)) {
                val attempt = downloadToFile(fresh.audioUrl, audioFile)
                if (!attempt.ok) {
                    throw IllegalStateException(
                        "Gagal mengunduh audio sumber. " +
                            "(${attempt.reason ?: "tanpa alasan"})"
                    )
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
        // Durasi dibaca SEBELUM saveToDownloads (lihat lapis segmen).
        val actualMs = actualDurationMs(cutFile)
        DownloadResult(
            saveToDownloads(context, cutFile, fileName),
            "full-download",
            segmentFailureReason = segmentReason,
            actualDurationMs = actualMs
        )
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
     * v1.4.0 (P1-4): [audioUrlOverride] — URL audio hasil re-pick
     * (saat Whisper mendeteksi bahasa berbeda). Kalau diisi, jalur
     * segmen audio DAN fallback potong-penuh memakai URL itu, bukan
     * URL analisa/fresh. v1.4.0 (P1-11): [onProgress] dipanggil
     * dengan tahap yang sedang berjalan ("memotong audio clip…" /
     * "mengunduh audio clip…") supaya UI narasi tidak diam.
     */
    suspend fun cutClipToTemp(
        context: Context,
        info: VideoInfo,
        clip: Clip,
        audioUrlOverride: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        // v1.4.0 (P1-3): eviksi LRU dulu, seperti downloadClip.
        enforceSourceCacheLimit(context)
        val cacheDir = File(context.cacheDir, "clipku-source").apply { mkdirs() }
        val temp = File(context.cacheDir, "clipku-whisper-${info.videoId}-${clip.index}.mp4")
        if (temp.exists()) temp.delete()
        // v1.0.9: URL fresh di-resolve SEKALI di awal fungsi dan dipakai
        // untuk segmen audio DAN fallback potong-penuh — sebelumnya
        // fallback memakai info.stream (URL analisa yang bisa
        // kedaluwarsa), cacat yang sama seperti lapis 2 downloadClip.
        val fresh = try {
            resolveFreshStreams(info)
        } catch (e: Exception) {
            info.stream
        }
        // v1.0.6: Whisper hanya butuh AUDIO clip (~60 detik), bukan
        // video. Coba dulu segmen audio-only dari URL fresh (tanpa
        // mengunduh video utuh sama sekali). Gagal = jatuh ke jalur
        // potong-penuh di bawah, seperti dulu.
        // v1.4.0 (P1-4): override re-pick menang atas URL fresh.
        val freshAudio = audioUrlOverride
            ?: fresh.audioUrl
            ?: info.stream.audioUrl
        onProgress?.invoke("memotong audio clip…")
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
        // v1.4.0 (P1-11): tahap ini baru terlihat — fallback
        // potong-penuh mengunduh sumber dulu (bisa bermenit-menit
        // untuk video panjang), jangan diam tanpa status.
        onProgress?.invoke("mengunduh audio clip…")
        // v1.0.8 P0-5: cache sumber divalidasi isinya (lihat lapis 2).
        val ok = if (fresh.progressiveUrl != null) {
            val source = File(cacheDir, "source-${info.videoId}.mp4")
            if (!isValidCachedSource(source)) {
                if (!downloadToFile(fresh.progressiveUrl, source).ok) {
                    return@withContext null
                }
            }
            CuttingEngine.cutProgressive(context, source, clip.startMs, clip.endMs, temp)
        } else if (fresh.videoOnlyUrl != null && freshAudio != null) {
            val videoFile = File(cacheDir, "source-${info.videoId}-video.mp4")
            val audioFile = File(cacheDir, "source-${info.videoId}-audio")
            if (!isValidCachedSource(videoFile)) {
                if (!downloadToFile(fresh.videoOnlyUrl, videoFile).ok) {
                    return@withContext null
                }
            }
            if (!isValidCachedSource(audioFile)) {
                if (!downloadToFile(freshAudio, audioFile).ok) {
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
        // v1.0.8 P0-6: sukses HANYA setelah byte benar-benar tertulis.
        // Kalau openOutputStream(uri) mengembalikan null, atau 0 byte
        // tertulis, entri MediaStore kosong itu DIHAPUS dan alur jatuh
        // ke fallback — cutFile TIDAK dihapus dan sukses TIDAK
        // dikembalikan. Dulu code mengembalikan path sukses walau
        // filenya kosong/tidak ada.
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
                val out = context.contentResolver.openOutputStream(uri)
                if (out != null) {
                    var bytesWritten = 0L
                    out.use { o ->
                        bytesWritten = cutFile.inputStream().copyTo(o)
                    }
                    if (bytesWritten > 0L) {
                        cutFile.delete()
                        return "Download/ClipKu/$fileName"
                    }
                }
                // OutputStream null ATAU 0 byte tertulis: hapus entri
                // kosong, JANGAN hapus cutFile, jatuh ke fallback.
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) { /* abaikan */ }
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
