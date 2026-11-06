package com.daftech.clipku.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.daftech.clipku.Prefs
import com.daftech.clipku.analysis.AudioEnergy
import com.daftech.clipku.analysis.GroqClient
import com.daftech.clipku.analysis.Narration
import com.daftech.clipku.analysis.Scoring
import com.daftech.clipku.cutting.ClipDownload
import com.daftech.clipku.groq.WhisperClient
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.VideoInfo
import com.daftech.clipku.model.VideoStream
import com.daftech.clipku.youtube.InnertubeClient
import com.daftech.clipku.youtube.NewPipeStreamExtractor
import com.daftech.clipku.youtube.VideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel ClipKu.
 *
 * ATURAN PENAMAAN (pelajaran SuaraKu 2026-10-21, Platform declaration clash):
 * JANGAN PERNAH menamai fungsi dengan pola setter yang sama dengan nama
 * var Compose — setter JVM yang digenerate akan bertabrakan. Semua fungsi
 * di sini memakai updateX / startX / requestX / generateX, bukan pola setter.
 */
class ClipKuViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = Prefs(application)
    private val innertube = InnertubeClient()
    private val groq = GroqClient()
    private val whisper = WhisperClient()

    var link by mutableStateOf("")
        private set
    var isAnalysing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var diagnostic by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var videoTitle by mutableStateOf("")
        private set
    var videoAuthor by mutableStateOf("")
        private set
    var clips by mutableStateOf<List<Clip>>(emptyList())
        private set
    var groqKey by mutableStateOf(prefs.groqApiKey)
        private set
    var lastCopied by mutableStateOf(false)
        private set

    private var currentInfo: VideoInfo? = null
    private var player: ExoPlayer? = null

    fun updateLink(value: String) {
        link = value
        lastCopied = false
    }

    fun updateGroqKey(value: String) {
        groqKey = value
    }

    fun saveGroqKey() {
        prefs.groqApiKey = groqKey.trim()
        groqKey = prefs.groqApiKey
        status = if (groqKey.isBlank()) {
            "Groq API key dihapus. Narasi dan skor LLM tidak bisa jalan tanpa key."
        } else {
            "Groq API key tersimpan di HP ini saja."
        }
    }

    fun clearGroqKey() {
        prefs.clearGroqKey()
        groqKey = ""
        status = "Groq API key dihapus."
    }

    fun clearError() {
        error = null
    }

    /** Player preview per clip (stream langsung, tanpa mengunduh file). */
    fun previewPlayer(context: Context, clip: Clip): ExoPlayer {
        val info = currentInfo ?: throw IllegalStateException("Analisa dulu sebelum preview.")
        val url = info.stream.progressiveUrl
            ?: info.stream.videoOnlyUrl
            ?: throw IllegalStateException("Tidak ada stream untuk preview.")
        val existing = player
        if (existing != null) {
            existing.stop()
            existing.release()
        }
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.startMs)
            .setEndPositionMs(clip.endMs)
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setClippingConfiguration(clipping)
            .build()
        val newPlayer = ExoPlayer.Builder(context).build()
        newPlayer.setMediaItem(item)
        newPlayer.prepare()
        newPlayer.play()
        player = newPlayer
        return newPlayer
    }

    fun stopPreview() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            // abaikan
        }
        player = null
    }

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }

    fun startAnalysis() {
        val videoId = VideoId.extract(link)
        if (videoId == null) {
            error = "Link tidak dikenali. Tempel link YouTube (watch, youtu.be, atau Shorts)."
            return
        }
        if (isAnalysing) return
        isAnalysing = true
        error = null
        diagnostic = ""
        clips = emptyList()
        currentInfo = null
        status = "Menghubungi YouTube…"
        viewModelScope.launch {
            try {
                var info = innertube.fetchVideoInfo(videoId)
                // v1.0.2: NewPipe DULUAN untuk stream (decipher signature).
                // Innertube tetap dipakai untuk judul/durasi/caption/heatmap.
                // NewPipe menang hanya untuk yang diberikannya: audioUrl
                // dan videoUrl (sebagai videoOnlyUrl, BUKAN progressive —
                // stream video NewPipe umumnya video-only, dan menandainya
                // progressive akan membuat download memotong tanpa audio).
                // Subtitle NewPipe hanya dipakai kalau Innertube tidak
                // punya caption sama sekali. Gagal NewPipe = hasil kosong,
                // Innertube tetap dipakai, bukan crash.
                var streamSource = innertube.lastStreamSource
                var subtitlesFromNewPipe = false
                // v1.0.3: daftar URL audio untuk fallback berurutan.
                // v1.0.8 P0-4: simpan audioUrl Innertube ASLI sebelum
                // merge NewPipe — begitu NewPipe memberi audio, URL
                // Innertube tertimpa di info.stream dan tidak pernah
                // dicoba lagi (host-nya bisa berbeda dari host NewPipe).
                val innertubeAudioUrl = info.stream.audioUrl
                var audioUrls: List<String> = listOfNotNull(info.stream.audioUrl)
                status = "Mencari stream via NewPipe…"
                try {
                    // v1.0.5: hint bahasa video untuk ranking audio
                    // NewPipe — "id" kalau ada caption id, kalau tidak
                    // bahasa caption pertama (Innertube). Null kalau
                    // tidak ada caption sama sekali (NewPipe menentukan
                    // sendiri, lalu default "id").
                    val preferredLanguage = if (info.captionTracks.any {
                            it.languageCode.startsWith("id")
                        }
                    ) {
                        "id"
                    } else {
                        info.captionTracks.firstOrNull()?.languageCode
                            ?.take(2)?.lowercase()
                    }
                    val newPipe = NewPipeStreamExtractor.extract(
                        videoId, preferredLanguage
                    )
                    if (newPipe.audioUrl != null || newPipe.videoUrl != null) {
                        info = info.copy(
                            stream = VideoStream(
                                progressiveUrl = info.stream.progressiveUrl,
                                videoOnlyUrl = newPipe.videoUrl
                                    ?: info.stream.videoOnlyUrl,
                                audioUrl = newPipe.audioUrl ?: info.stream.audioUrl,
                                mimeProgressive = info.stream.mimeProgressive,
                                // v1.0.5: deskripsi track mengikuti SUMBER
                                // audioUrl yang menang (NewPipe kalau
                                // NewPipe yang memberi audio).
                                audioTrackDescription = if (newPipe.audioUrl != null) {
                                    newPipe.audioTrackDescription.ifEmpty {
                                        info.stream.audioTrackDescription
                                    }
                                } else {
                                    info.stream.audioTrackDescription
                                }
                            )
                        )
                        if (newPipe.audioUrl != null) {
                            streamSource = "NEWPIPE"
                            // v1.0.8 P0-4: URL Innertube TETAP masuk
                            // daftar coba (di belakang URL NewPipe,
                            // tanpa duplikat) — AudioEnergy mencoba
                            // satu URL per host dulu, jadi host
                            // Innertube yang berbeda tidak lagi
                            // terkubur oleh 35 URL satu host NewPipe.
                            audioUrls = (
                                newPipe.audioUrls.ifEmpty {
                                    listOfNotNull(newPipe.audioUrl)
                                } + listOfNotNull(innertubeAudioUrl)
                                ).distinct()
                        }
                        if (info.captionTracks.isEmpty() && newPipe.subtitles.isNotEmpty()) {
                            info = info.copy(captionTracks = newPipe.subtitles)
                            subtitlesFromNewPipe = true
                        }
                    }
                } catch (e: Exception) {
                    // NewPipe gagal = lanjut dengan Innertube apa adanya.
                }
                if (audioUrls.isEmpty()) {
                    audioUrls = listOfNotNull(info.stream.audioUrl)
                }
                currentInfo = info
                videoTitle = info.title
                videoAuthor = info.author
                status = "Mengambil transkrip…"
                // v1.0.1: rantai fallback WEB -> TIMEDTEXT -> ANDROID.
                // Sumbernya dicatat untuk diagnostik, bukan disembunyikan.
                // v1.0.2: kalau track yang dipakai langkah pertama ternyata
                // subtitle NewPipe, labelnya dikoreksi menjadi NEWPIPE.
                val transcriptResult = innertube.fetchTranscriptWithFallback(info)
                val sentences = transcriptResult.sentences
                var transcriptSource = transcriptResult.source
                if (subtitlesFromNewPipe && transcriptSource == "WEB") {
                    transcriptSource = "NEWPIPE"
                }
                status = "Menganalisa audio…"
                // v1.0.3: coba stream audio berurutan + bawa jejak tahapnya.
                // v1.0.8 P0-4: kalau kegagalan terakhir adalah DNS
                // (UnknownHostException), lakukan SATU re-extract fresh
                // NewPipe + SATU percobaan ulang dengan URL baru — URL
                // googlevideo hasil extract lama bisa menunjuk host yang
                // sudah tidak bisa di-resolve, sementara extract baru
                // memberi host lain. Satu retry saja, bukan loop.
                var audioResult = AudioEnergy.analyseMultiple(
                    audioUrls,
                    getApplication<Application>().cacheDir
                )
                var audioDnsRetried = false
                if (audioResult.dnsFailure && audioResult.bySecond.isEmpty()) {
                    try {
                        val retryPreferred = if (info.captionTracks.any {
                                it.languageCode.startsWith("id")
                            }
                        ) {
                            "id"
                        } else {
                            info.captionTracks.firstOrNull()?.languageCode
                                ?.take(2)?.lowercase()
                        }
                        val fresh = NewPipeStreamExtractor.extract(
                            videoId, retryPreferred
                        )
                        val retryUrls = (
                            fresh.audioUrls.ifEmpty {
                                listOfNotNull(fresh.audioUrl)
                            } + listOfNotNull(innertubeAudioUrl)
                            ).distinct()
                        if (retryUrls.isNotEmpty()) {
                            audioDnsRetried = true
                            audioResult = AudioEnergy.analyseMultiple(
                                retryUrls,
                                getApplication<Application>().cacheDir
                            )
                        }
                    } catch (e: Exception) {
                        // Re-extract gagal = pakai hasil pertama, bukan crash.
                    }
                }
                val audio = audioResult.bySecond
                val tracksTried = innertube.lastTracksTried
                val segments = Scoring.buildSegments(sentences)

                var llmScores: Map<Int, GroqClient.SegmentScore> = emptyMap()
                // v1.0.4: pesan error Groq asli disimpan untuk diagnostik.
                var llmError: String? = null
                // v1.0.7: jejak jendela scoring (chunked) untuk diagnostik.
                var llmWindowsText = ""
                val key = prefs.groqApiKey.trim()
                if (key.isNotBlank() && segments.isNotEmpty()) {
                    status = "Menilai highlight dengan Groq…"
                    try {
                        val inputs = segments.map { segment ->
                            GroqClient.SegmentInput(
                                id = segment.id,
                                startLabel = VideoId.formatMs(segment.startMs),
                                endLabel = VideoId.formatMs(segment.endMs),
                                text = segment.text.take(1200)
                            )
                        }
                        // v1.0.7: scoring ber-chunk — video 91 menit
                        // (ALI, v1.0.6) mengirim ~61 segmen dalam SATU
                        // call dan kena HTTP 413 di qwen3.8-27b. Kini
                        // tiap call maks ~8 segmen / 12 menit; satu
                        // jendela gagal tidak menolkan video.
                        val chunked = groq.scoreSegmentsChunked(key, info.title, inputs)
                        llmScores = chunked.scores
                        llmWindowsText = " • jendela LLM: " +
                            chunked.windowsSucceeded + "/" +
                            chunked.windowsTotal + " berhasil"
                        if (chunked.windowsFailed > 0) {
                            // Error terakhir dicatat, skor yang berhasil
                            // dari jendela lain tetap dipakai.
                            llmError = chunked.lastError
                            status = "Skor LLM sebagian gagal " +
                                "(${chunked.windowsFailed} jendela), " +
                                "lanjut dengan yang berhasil."
                        }
                    } catch (e: Exception) {
                        // Skor LLM gagal = lanjut dengan heatmap + audio saja.
                        // Pesan error TIDAK pernah memuat API key (GroqClient
                        // hanya mencatat HTTP + pesan error Groq).
                        llmError = e.message?.take(200)
                        status = "Skor LLM gagal, lanjut dengan heatmap + audio."
                        llmScores = emptyMap()
                    }
                }

                val foundRaw = Scoring.selectClips(
                    Scoring.AnalysisInput(
                        durationMs = info.durationMs,
                        sentences = sentences,
                        heatPoints = info.heatPoints,
                        audioBySecond = audio,
                        llmScores = llmScores,
                        segments = segments
                    ),
                    info.title
                )
                // v1.0.7: dedupe kandidat overlap >50% (skor lebih
                // rendah dibuang), lalu SATU rerank komparatif atas
                // kandidat final. Rerank gagal = urutan semula dipakai.
                var found = Scoring.dedupeClips(foundRaw)
                if (key.isNotBlank() && found.size >= 2 && llmScores.isNotEmpty()) {
                    try {
                        val candidates = found.map { clip ->
                            GroqClient.RerankInput(
                                id = clip.index,
                                startLabel = VideoId.formatMs(clip.startMs),
                                endLabel = VideoId.formatMs(clip.endMs),
                                title = clip.title,
                                reason = clip.reason,
                                score = (clip.score * 100).toInt().coerceIn(0, 100),
                                text = clip.transcript.take(1200)
                            )
                        }
                        val ranking = groq.rerankClips(key, info.title, candidates)
                        if (ranking != null) {
                            val rankById = ranking.associate { it.id to it.rank }
                            val reranked = found.sortedBy { rankById[it.index] ?: 99 }
                            // Re-index 1..N sesuai urutan rerank; isi
                            // clip (waktu, judul, alasan, transkrip)
                            // tidak diubah — rerank hanya mengurutkan.
                            found = Scoring.dedupeClips(
                                reranked.mapIndexed { i, clip ->
                                    clip.copy(index = i + 1)
                                }
                            ).mapIndexed { i, clip -> clip.copy(index = i + 1) }
                        }
                    } catch (e: Exception) {
                        // Rerank gagal = urutan semula, bukan error analisa.
                    }
                }
                // v1.0.1 DIAGNOSTIK: angka nyata per sinyal, sukses maupun
                // gagal, supaya kegagalan berikutnya langsung ketahuan
                // sinyal mana yang nol dan kenapa (bukan pesan generik).
                val durationMinutes = info.durationMs / 60_000
                val zeroSignals = mutableListOf<String>()
                if (info.heatPoints.isEmpty()) zeroSignals.add("heatmap")
                if (sentences.isEmpty()) zeroSignals.add("transkrip")
                if (audio.isEmpty()) zeroSignals.add("audio")
                if (key.isBlank()) zeroSignals.add("Groq key")
                val audioUrlText = if (info.stream.audioUrl != null) "ada" else "tidak ada"
                val groqKeyText = if (key.isNotBlank()) "ada" else "tidak ada"
                val zeroText = if (zeroSignals.isNotEmpty()) {
                    " • sinyal nol: " + zeroSignals.joinToString(", ")
                } else {
                    ""
                }
                // v1.0.3: jejak tahap audio + transkrip, bukan cuma angka akhir.
                val audioErrorText = if (audioResult.error != null) {
                    " • audio error: " + audioResult.error
                } else {
                    ""
                }
                // v1.0.8 P0-4: tandai kegagalan DNS secara eksplisit di
                // diagnostik (termasuk apakah sudah dicoba ulang sekali).
                val audioDnsText = if (audioResult.dnsFailure) {
                    " • audio DNS: host " + (audioResult.failedHost ?: "?") +
                        " gagal resolve (DNS)" +
                        if (audioDnsRetried) " — sudah dicoba ulang 1x" else ""
                } else {
                    ""
                }
                diagnostic = "Diagnostik: judul terambil • durasi " +
                    durationMinutes + " menit • track caption: " +
                    info.captionTracks.size + " • transkrip: " +
                    sentences.size + " kalimat (sumber: " + transcriptSource +
                    ", track dicoba: " + tracksTried + ") • " +
                    // v1.0.6: sumber analisa — caption kalau transkrip
                    // ada (jalur transcript-first, v1.0.1), audio-saja
                    // kalau tidak ada caption (Whisper di sini HANYA
                    // dipakai per clip untuk narasi, bukan analisa).
                    "analisa: " + if (sentences.isNotEmpty()) {
                        "caption"
                    } else {
                        "audio-saja"
                    } + " • " +
                    "heatmap: " + info.heatPoints.size + " titik • audio: URL " +
                    audioUrlText + ", " + audio.size + " detik ter-decode • " +
                    // v1.0.5: track audio yang dipilih (bahasa + tipe +
                    // nama), mis. "audio track: id, original".
                    "audio track: " +
                    info.stream.audioTrackDescription.ifEmpty { "-" } + " • " +
                    "sumber stream: " + streamSource + " • audio HTTP: " +
                    audioResult.httpCode + " • audio byte: " +
                    audioResult.downloadedBytes + " • audio mime: " +
                    audioResult.mime.ifEmpty { "-" } + " • stream dicoba: " +
                    audioResult.streamsTried + audioErrorText + audioDnsText +
                    " • Groq key: " +
                    groqKeyText + " • skor LLM: " + llmScores.size + " segmen" +
                    (if (llmError != null) " (gagal: " + llmError + ")" else "") +
                    // v1.0.7: berapa jendela scoring yang berhasil/gagal.
                    llmWindowsText + zeroText
                if (found.isEmpty()) {
                    // v1.0.8 P0-1: pesan penolakan dibangun dari sinyal
                    // yang BENAR-BENAR nol — bukan blanket "tidak punya
                    // heatmap, transkrip, maupun audio" yang berbohong
                    // saat transkrip 1103 kalimat ADA (kasus ALI).
                    val refusalParts = mutableListOf<String>()
                    if (sentences.isEmpty()) {
                        refusalParts.add("transkrip tidak ada")
                    } else {
                        refusalParts.add(
                            "transkrip ada ${sentences.size} kalimat"
                        )
                    }
                    if (key.isBlank()) {
                        refusalParts.add("Groq key kosong")
                    } else if (llmError != null) {
                        refusalParts.add("skor LLM gagal ($llmError)")
                    } else if (llmScores.isEmpty()) {
                        refusalParts.add("skor LLM 0 segmen")
                    }
                    if (audio.isEmpty()) {
                        refusalParts.add("audio 0 detik ter-decode")
                    }
                    if (info.heatPoints.isEmpty()) {
                        refusalParts.add("heatmap 0 titik")
                    }
                    error = "Tidak ada sinyal highlight yang cukup: " +
                        refusalParts.joinToString(", ") +
                        " — tidak ada sinyal yang cukup untuk membuat " +
                        "clip. Clip tidak dibuat-buat dari tebakan."
                    status = ""
                } else {
                    clips = found
                    val heatNote = if (info.heatPoints.isEmpty()) {
                        " (heatmap tidak tersedia — video ini mungkin belum cukup ditonton)"
                    } else ""
                    status = "Ditemukan ${found.size} clip highlight$heatNote."
                    if (sentences.isEmpty()) {
                        // v1.0.2: tanpa transkrip video, narasi MASIH bisa
                        // dibuat per clip lewat Whisper (bukan dari judul).
                        status += " Transkrip video tidak tersedia — tap " +
                            "Buat Narasi untuk transkrip clip via Whisper."
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Analisa gagal. Coba lagi."
                status = ""
                if (diagnostic.isBlank()) {
                    diagnostic = "Diagnostik: metadata gagal diambil " +
                        "(${e.message ?: "tanpa pesan"}). Track caption, " +
                        "transkrip, heatmap, dan audio tidak diketahui."
                }
            } finally {
                isAnalysing = false
            }
        }
    }

    /** Download SATU clip, atas tap user. Tidak ada auto-download semua. */
    fun requestDownload(clip: Clip) {
        val info = currentInfo ?: run {
            error = "Analisa dulu sebelum download."
            return
        }
        viewModelScope.launch {
            updateClip(clip.index) { it.copy(isDownloading = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    ClipDownload.downloadClipDetailed(getApplication(), info, clip)
                }
                updateClip(clip.index) {
                    it.copy(isDownloading = false, savedPath = result.path)
                }
                // v1.0.6: jalur ekspor yang dipakai dicatat di
                // diagnostik (segment vs full-download) — bukti
                // jalurnya terlihat, bukan cuma dirasakan waktunya.
                // v1.0.9: kalau lapis segmen gagal lalu full-download
                // yang menyelamatkan, alasan gagal segmen ikut
                // dicatat di diagnostik yang sama (format " • ").
                val exportNote = " • clip ${clip.index}: ${result.method}" +
                    if (result.segmentFailureReason != null) {
                        " (segmen gagal: ${result.segmentFailureReason})"
                    } else {
                        ""
                    }
                diagnostic = if (diagnostic.isBlank()) {
                    "clip ${clip.index}: ${result.method}"
                } else {
                    diagnostic + exportNote
                }
                status = "Clip ${clip.index} tersimpan: ${result.path} (${result.method})"
            } catch (e: Exception) {
                updateClip(clip.index) { it.copy(isDownloading = false) }
                error = e.message ?: "Download clip gagal."
                // v1.0.9: alasan download yang gagal ikut masuk
                // diagnostik (format " • " yang sama), supaya kegagalan
                // berikutnya terbaca tanpa menebak-nebak dari banner.
                val reason = e.message ?: "tanpa pesan"
                val failNote = " • download clip ${clip.index} gagal: $reason"
                diagnostic = if (diagnostic.isBlank()) {
                    "download clip ${clip.index} gagal: $reason"
                } else {
                    diagnostic + failNote
                }
            }
        }
    }

    /**
     * Buat narasi final per clip, on-demand.
     * Menolak kalau: tidak ada Groq key, atau tidak ada teks apa pun
     * yang bisa dipakai sebagai dasar narasi.
     * Dua jalur teks dasar (keduanya BUKAN judul saja):
     *  1. Transkrip video yang sudah ada di clip (jalur v1).
     *  2. v1.0.2 Whisper: kalau clip tidak punya transkrip, potong
     *     clip itu saja (~60 detik) ke file temp, kirim file temp itu
     *     ke Groq Whisper, HAPUS file temp, lalu pakai transkrip
     *     Whisper sebagai dasar narasi LLM. Video utuh TIDAK PERNAH
     *     dikirim ke Whisper.
     * Kalau Whisper gagal / tidak menemukan ucapan, narasi ditolak
     * jujur, tapi clip-nya tetap tersedia untuk download dan edit.
     * Teks yang disimpan adalah teks BERSIH hasil Narration.clean —
     * tombol Salin menyalin teks itu saja, bukan metadata; label
     * sumber ("Transkrip"/"Whisper") hanya metadata UI di luar blok.
     */
    fun generateNarrationFor(clip: Clip) {
        val key = prefs.groqApiKey.trim()
        if (key.isBlank()) {
            updateClip(clip.index) {
                it.copy(narrationError = "Isi Groq API key di Settings dulu.")
            }
            return
        }
        viewModelScope.launch {
            updateClip(clip.index) {
                it.copy(isGeneratingNarration = true, narrationError = null)
            }
            try {
                val transcriptText: String
                val transcriptSource: String
                if (clip.transcript.isNotBlank()) {
                    transcriptText = clip.transcript
                    transcriptSource = "Transkrip"
                } else {
                    val info = currentInfo
                        ?: throw IllegalStateException(
                            "Analisa dulu sebelum membuat narasi."
                        )
                    val temp = withContext(Dispatchers.IO) {
                        ClipDownload.cutClipToTemp(getApplication(), info, clip)
                    } ?: throw IllegalStateException(
                        "Gagal memotong clip untuk transkrip Whisper. " +
                            "Coba download clip dulu, atau coba lagi."
                    )
                    try {
                        val whisperText = whisper.transcribe(key, temp)
                        if (whisperText.isBlank()) {
                            throw IllegalStateException(
                                "Whisper tidak menemukan ucapan di clip " +
                                    "ini, jadi narasi tidak dibuat dari tebakan."
                            )
                        }
                        transcriptText = whisperText
                        transcriptSource = "Whisper"
                    } finally {
                        try { temp.delete() } catch (e: Exception) { /* abaikan */ }
                    }
                }
                var raw = groq.generateNarration(key, videoTitle, transcriptText)
                var cleaned = Narration.clean(raw)
                // Satu kali percobaan ulang kalau di luar 110-140 kata.
                if (!Narration.isInTargetRange(cleaned)) {
                    try {
                        raw = groq.generateNarration(key, videoTitle, transcriptText)
                        val second = Narration.clean(raw)
                        if (Narration.isInTargetRange(second)) cleaned = second
                    } catch (e: Exception) {
                        // Pertahankan hasil pertama, tampilkan jumlah kata jujur.
                    }
                }
                val count = Narration.wordCount(cleaned)
                updateClip(clip.index) {
                    it.copy(
                        isGeneratingNarration = false,
                        narration = cleaned,
                        narrationWordCount = count,
                        narrationSource = transcriptSource
                    )
                }
            } catch (e: Exception) {
                updateClip(clip.index) {
                    it.copy(
                        isGeneratingNarration = false,
                        narrationError = e.message ?: "Gagal membuat narasi."
                    )
                }
            }
        }
    }

    /**
     * Salin HANYA blok narasi final ke clipboard.
     * Judul, timestamp, alasan, jumlah kata, dan estimasi durasi adalah
     * UI terpisah dan TIDAK PERNAH masuk clipboard.
     */
    fun copyNarration(clip: Clip) {
        val text = clip.narration ?: return
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Narasi ClipKu", text))
        lastCopied = true
        status = "Narasi clip ${clip.index} tersalin. Paste di SuaraKu untuk generate suara."
    }

    private fun updateClip(index: Int, transform: (Clip) -> Clip) {
        clips = clips.map { if (it.index == index) transform(it) else it }
    }
}
