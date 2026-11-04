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
                var audioUrls: List<String> = listOfNotNull(info.stream.audioUrl)
                status = "Mencari stream via NewPipe…"
                try {
                    val newPipe = NewPipeStreamExtractor.extract(videoId)
                    if (newPipe.audioUrl != null || newPipe.videoUrl != null) {
                        info = info.copy(
                            stream = VideoStream(
                                progressiveUrl = info.stream.progressiveUrl,
                                videoOnlyUrl = newPipe.videoUrl
                                    ?: info.stream.videoOnlyUrl,
                                audioUrl = newPipe.audioUrl ?: info.stream.audioUrl,
                                mimeProgressive = info.stream.mimeProgressive
                            )
                        )
                        if (newPipe.audioUrl != null) {
                            streamSource = "NEWPIPE"
                            audioUrls = newPipe.audioUrls.ifEmpty {
                                listOfNotNull(newPipe.audioUrl)
                            }
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
                val audioResult = AudioEnergy.analyseMultiple(
                    audioUrls,
                    getApplication<Application>().cacheDir
                )
                val audio = audioResult.bySecond
                val tracksTried = innertube.lastTracksTried
                val segments = Scoring.buildSegments(sentences)

                var llmScores: Map<Int, GroqClient.SegmentScore> = emptyMap()
                // v1.0.4: pesan error Groq asli disimpan untuk diagnostik.
                var llmError: String? = null
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
                        llmScores = groq.scoreSegments(key, info.title, inputs)
                    } catch (e: Exception) {
                        // Skor LLM gagal = lanjut dengan heatmap + audio saja.
                        // Pesan error TIDAK pernah memuat API key (GroqClient
                        // hanya mencatat HTTP + pesan error Groq).
                        llmError = e.message?.take(200)
                        status = "Skor LLM gagal, lanjut dengan heatmap + audio."
                        llmScores = emptyMap()
                    }
                }

                val found = Scoring.selectClips(
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
                diagnostic = "Diagnostik: judul terambil • durasi " +
                    durationMinutes + " menit • track caption: " +
                    info.captionTracks.size + " • transkrip: " +
                    sentences.size + " kalimat (sumber: " + transcriptSource +
                    ", track dicoba: " + tracksTried + ") • " +
                    "heatmap: " + info.heatPoints.size + " titik • audio: URL " +
                    audioUrlText + ", " + audio.size + " detik ter-decode • " +
                    "sumber stream: " + streamSource + " • audio HTTP: " +
                    audioResult.httpCode + " • audio byte: " +
                    audioResult.downloadedBytes + " • audio mime: " +
                    audioResult.mime.ifEmpty { "-" } + " • stream dicoba: " +
                    audioResult.streamsTried + audioErrorText + " • Groq key: " +
                    groqKeyText + " • skor LLM: " + llmScores.size + " segmen" +
                    (if (llmError != null) " (gagal: " + llmError + ")" else "") + zeroText
                if (found.isEmpty()) {
                    error = "Tidak ada sinyal highlight yang cukup: video ini " +
                        "tidak punya heatmap, transkrip, maupun audio yang " +
                        "bisa dianalisa. Clip tidak dibuat-buat dari tebakan."
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
                val path = withContext(Dispatchers.IO) {
                    ClipDownload.downloadClip(getApplication(), info, clip)
                }
                updateClip(clip.index) {
                    it.copy(isDownloading = false, savedPath = path)
                }
                status = "Clip ${clip.index} tersimpan: $path"
            } catch (e: Exception) {
                updateClip(clip.index) { it.copy(isDownloading = false) }
                error = e.message ?: "Download clip gagal."
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
