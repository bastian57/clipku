package com.daftech.clipku.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.daftech.clipku.Prefs
import com.daftech.clipku.analysis.AudioEnergy
import com.daftech.clipku.analysis.GroqClient
import com.daftech.clipku.analysis.Narration
import com.daftech.clipku.analysis.Scoring
import com.daftech.clipku.cutting.ClipDownload
import com.daftech.clipku.groq.WhisperClient
import com.daftech.clipku.history.AnalysisHistoryEntity
import com.daftech.clipku.history.HistoryClipEntity
import com.daftech.clipku.history.HistoryRepository
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.Sentence
import com.daftech.clipku.model.VideoInfo
import com.daftech.clipku.model.VideoStream
import com.daftech.clipku.youtube.InnertubeClient
import com.daftech.clipku.youtube.NewPipeStreamExtractor
import com.daftech.clipku.youtube.VideoId
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    // v1.3.0 History (Room): daftar analisa tersimpan, diamati reaktif.
    private val historyRepository = HistoryRepository(application)

    var historyItems by mutableStateOf<List<AnalysisHistoryEntity>>(emptyList())
        private set
    /** Banner "sudah dianalisa" saat link yang ditempel ada di History. */
    var historyNotice by mutableStateOf<String?>(null)
        private set
    private var historyNoticeVideoId: String? = null

    init {
        viewModelScope.launch {
            historyRepository.observeAll().collect { historyItems = it }
        }
        // v1.4.0 (P1-11): bersihkan file temp Whisper yang tertinggal
        // kalau app mati di tengah jalan (finally ViewModel tidak
        // sempat jalan saat proses mati). Hanya file clipku-whisper-*
        // di cacheDir — bukan History, bukan cache sumber.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = getApplication<Application>().cacheDir
                cacheDir.listFiles()
                    ?.filter { it.name.startsWith("clipku-whisper-") }
                    ?.forEach { file ->
                        try { file.delete() } catch (e: Exception) { /* abaikan */ }
                    }
            } catch (e: Exception) {
                // abaikan — pembersihan startup best-effort
            }
        }
        refreshCacheInfo()
    }

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
    /**
     * v1.4.0 (P2-3): key yang TERAKHIR DISIMPAN (prefs). Settings
     * menampilkan "belum disimpan" kalau groqKey != groqKeySaved.
     */
    var groqKeySaved by mutableStateOf(prefs.groqApiKey)
        private set
    var lastCopied by mutableStateOf(false)
        private set
    /**
     * v1.4.0 (P1-2): error playback preview terakhir (dari
     * Player.Listener) — MainScreen menampilkannya di bawah player,
     * supaya gagal putar tidak diam.
     */
    var previewError by mutableStateOf<String?>(null)
        private set
    /**
     * v1.4.0 (P1-3): info ukuran cache sumber untuk Settings
     * ("Cache sumber: 123 MB — maks 500 MB").
     */
    var cacheInfo by mutableStateOf("")
        private set

    private var currentInfo: VideoInfo? = null
    private var player: ExoPlayer? = null
    /**
     * v1.4.0 (P1-10): Job yang bisa dibatalkan tombol Batal —
     * analisa (satu Job), download + narasi (per clip).
     */
    private var analysisJob: Job? = null
    private val downloadJobs = mutableMapOf<Int, Job>()
    private val narrationJobs = mutableMapOf<Int, Job>()

    fun updateLink(value: String) {
        link = value
        lastCopied = false
        historyNotice = null
        historyNoticeVideoId = null
        // v1.3.0 History: link yang ditempel sudah pernah dianalisa?
        // Cek lokal (Room) — mencegah buang kuota Groq untuk video
        // yang sama; banner menawarkan buka hasil lama / analisa ulang.
        val videoId = VideoId.extract(value)
        if (videoId != null) {
            viewModelScope.launch {
                val existing = try {
                    historyRepository.getByVideoId(videoId)
                } catch (e: Exception) {
                    null
                }
                if (existing != null) {
                    historyNotice = "Video ini sudah dianalisa — " +
                        "buka hasil lama tanpa analisa ulang, atau " +
                        "analisa ulang untuk menimpa hasil lama."
                    historyNoticeVideoId = videoId
                }
            }
        }
    }

    fun clearHistoryNotice() {
        historyNotice = null
        historyNoticeVideoId = null
    }

    fun openHistoryFromNotice() {
        val videoId = historyNoticeVideoId ?: return
        openHistory(videoId)
    }

    fun updateGroqKey(value: String) {
        groqKey = value
    }

    fun saveGroqKey() {
        prefs.groqApiKey = groqKey.trim()
        groqKey = prefs.groqApiKey
        groqKeySaved = prefs.groqApiKey
        status = if (groqKey.isBlank()) {
            "Groq API key dihapus. Narasi dan skor LLM tidak bisa jalan tanpa key."
        } else {
            "Groq API key tersimpan di HP ini saja."
        }
    }

    fun clearGroqKey() {
        prefs.clearGroqKey()
        groqKey = ""
        groqKeySaved = ""
        status = "Groq API key dihapus."
    }

    fun clearError() {
        error = null
    }

    /**
     * v1.4.0 (P1-2): hasil preview + apakah preview itu ber-audio.
     * MainScreen menampilkan "preview tanpa audio" kalau hasAudio
     * false, supaya clip adaptive yang bisu tidak dikira bisu.
     */
    data class PreviewResult(val player: ExoPlayer, val hasAudio: Boolean)

    /**
     * Player preview per clip (stream langsung, tanpa mengunduh file).
     * v1.4.0 (P1-2): video adaptive (video-only + audio terpisah)
     * kini diputar sebagai MergingMediaSource video+audio — dulu
     * hanya track video yang diputar dan clip terdengar bisu.
     * Kalau HANYA video-only yang tersedia (tanpa audio sama sekali),
     * preview tetap jalan tapi hasAudio=false + label di UI. Error
     * playback async kini masuk previewError (dulu diam).
     */
    fun previewPlayer(context: Context, clip: Clip): PreviewResult {
        val info = currentInfo ?: throw IllegalStateException("Analisa dulu sebelum preview.")
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(clip.startMs)
            .setEndPositionMs(clip.endMs)
            .build()
        val existing = player
        if (existing != null) {
            existing.stop()
            existing.release()
        }
        previewError = null
        val progressiveUrl = info.stream.progressiveUrl
        val videoOnlyUrl = info.stream.videoOnlyUrl
        val audioUrl = info.stream.audioUrl
        val newPlayer = ExoPlayer.Builder(context).build()
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                previewError = "Preview gagal diputar: " +
                    (error.message?.take(120) ?: "tanpa pesan")
            }
        })
        var hasAudio = false
        when {
            progressiveUrl != null -> {
                // Progressive = video+audio dalam satu stream.
                val item = MediaItem.Builder()
                    .setUri(progressiveUrl)
                    .setClippingConfiguration(clipping)
                    .build()
                newPlayer.setMediaItem(item)
                hasAudio = true
            }
            videoOnlyUrl != null && audioUrl != null -> {
                // Adaptive: gabung video + audio (MergingMediaSource),
                // keduanya di-clip ke rentang yang sama.
                val factory = DefaultDataSource.Factory(context)
                val videoItem = MediaItem.Builder()
                    .setUri(videoOnlyUrl)
                    .setClippingConfiguration(clipping)
                    .build()
                val audioItem = MediaItem.Builder()
                    .setUri(audioUrl)
                    .setClippingConfiguration(clipping)
                    .build()
                val merged = MergingMediaSource(
                    ProgressiveMediaSource.Factory(factory).createMediaSource(videoItem),
                    ProgressiveMediaSource.Factory(factory).createMediaSource(audioItem)
                )
                newPlayer.setMediaSource(merged)
                hasAudio = true
            }
            videoOnlyUrl != null -> {
                val item = MediaItem.Builder()
                    .setUri(videoOnlyUrl)
                    .setClippingConfiguration(clipping)
                    .build()
                newPlayer.setMediaItem(item)
                hasAudio = false
            }
            else -> throw IllegalStateException("Tidak ada stream untuk preview.")
        }
        newPlayer.prepare()
        newPlayer.play()
        player = newPlayer
        return PreviewResult(newPlayer, hasAudio)
    }

    fun stopPreview() {
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            // abaikan
        }
        player = null
        previewError = null
    }

    /**
     * v1.4.0 (P1-10): batalkan analisa yang sedang berjalan.
     * Coroutine dibatalkan (finally di startAnalysis mereset
     * isAnalysing); status kembali normal, bukan menggantung.
     * Catatan jujur: pembatalan tidak memotong request HTTP yang
     * sedang blokir di thread IO — request itu selesai sendiri
     * (timeout OkHttp), tapi hasilnya dibuang dan UI langsung bebas.
     */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        isAnalysing = false
        status = "Analisa dibatalkan."
    }

    /** v1.4.0 (P1-10): batalkan download satu clip. */
    fun cancelDownload(clip: Clip) {
        downloadJobs[clip.index]?.cancel()
        downloadJobs.remove(clip.index)
        updateClip(clip.index) { it.copy(isDownloading = false) }
        status = "Download clip ${clip.index} dibatalkan."
    }

    /** v1.4.0 (P1-10): batalkan pembuatan narasi satu clip. */
    fun cancelNarration(clip: Clip) {
        narrationJobs[clip.index]?.cancel()
        narrationJobs.remove(clip.index)
        updateClip(clip.index) {
            it.copy(isGeneratingNarration = false, narrationStage = null)
        }
        status = "Pembuatan narasi clip ${clip.index} dibatalkan."
    }

    /**
     * v1.4.0 (P2-8): salin teks diagnostik lengkap ke clipboard —
     * user tidak perlu screenshot untuk melapor.
     */
    fun copyDiagnostic() {
        val text = diagnostic.ifBlank { return }
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostik ClipKu", text))
        status = "Diagnostik tersalin ke clipboard."
    }

    /**
     * v1.4.0 (P1-3): hitung ulang info ukuran cache sumber untuk
     * Settings. Dipanggil di init + setelah bersih-bersih.
     */
    fun refreshCacheInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            // v1.4.0-fix1: hitung di IO, tapi TULIS state di Main.
            // Menulis Compose mutableStateOf dari thread IO melempar
            // IllegalStateException (snapshot) — ini penyebab crash
            // "ClipKu terus berhenti" di setiap launch (init memanggil
            // fungsi ini), terbukti dari logcat 11-08 11:45 WIB.
            val text = try {
                val bytes = ClipDownload.sourceCacheSizeBytes(getApplication())
                val mb = bytes / (1024 * 1024)
                val limitMb = ClipDownload.MAX_SOURCE_CACHE_BYTES / (1024 * 1024)
                "Cache sumber: $mb MB (maks $limitMb MB, file tertua " +
                    "dihapus otomatis kalau melebihi batas)"
            } catch (e: Exception) {
                ""
            }
            withContext(Dispatchers.Main) { cacheInfo = text }
        }
    }

    /** v1.4.0 (P1-3): hapus semua file sumber cache (bukan History). */
    fun clearSourceCache() {
        viewModelScope.launch(Dispatchers.IO) {
            // v1.4.0-fix1: sama seperti refreshCacheInfo — tulis
            // status di Main, bukan dari thread IO.
            val text = try {
                val freed = ClipDownload.clearSourceCache(getApplication())
                val mb = freed / (1024 * 1024)
                "Cache sumber dibersihkan ($mb MB dibebaskan). " +
                    "History analisa tidak ikut terhapus."
            } catch (e: Exception) {
                "Gagal membersihkan cache: ${e.message ?: "tanpa pesan"}"
            }
            withContext(Dispatchers.Main) { status = text }
            refreshCacheInfo()
        }
    }

    /**
     * Bahasa yang diharapkan dari caption (dipakai P1-4 cross-check
     * Whisper + P1-6): "id" kalau ada caption id, kalau tidak bahasa
     * caption pertama, null kalau tidak ada caption sama sekali.
     */
    private fun expectedCaptionLanguage(info: VideoInfo): String? {
        if (info.captionTracks.any { it.languageCode.startsWith("id") }) return "id"
        return info.captionTracks.firstOrNull()?.languageCode
            ?.take(2)?.lowercase()?.takeIf { it.isNotBlank() }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        downloadJobs.values.forEach { it.cancel() }
        narrationJobs.values.forEach { it.cancel() }
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
        // v1.4.0 (P1-10): Job disimpan supaya tombol Batal bisa
        // membatalkannya (lihat cancelAnalysis).
        analysisJob = viewModelScope.launch {
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
                // v1.4.0 (P1-6): apakah bahasa track audio yang menang
                // diketahui (locale null semua = tidak diketahui).
                var newPipeAudioUnknown = false
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
                    // v1.4.0 (P1-6): flag dari NewPipe — true kalau
                    // track audio yang menang locale-nya null.
                    newPipeAudioUnknown = newPipe.audioLanguageUnknown
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
                            // v1.3.0 (#4): analisa audio memakai opus
                            // bitrate TERENDAH dulu (hemat byte) —
                            // audioUrl utama (bitrate tertinggi) tetap
                            // dipakai untuk download clip, bukan analisa.
                            audioUrls = (
                                listOfNotNull(newPipe.analysisAudioUrl) +
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
                // v1.4.0 (P1-6): bahasa audio tidak diketahui kalau
                // NewPipe melaporkan locale null, ATAU deskripsi
                // Innertube diawali "?" (bahasa null). Ranking audio
                // dalam kondisi ini memakai bahasa caption — ditulis
                // eksplisit di diagnostik, bukan diam-diam.
                val audioLangUnknown = newPipeAudioUnknown ||
                    info.stream.audioTrackDescription.startsWith("?")
                val captionLangForWarning = expectedCaptionLanguage(info)
                status = "Mengambil transkrip…"
                // v1.0.1: rantai fallback WEB -> TIMEDTEXT -> ANDROID.
                // Sumbernya dicatat untuk diagnostik, bukan disembunyikan.
                // v1.0.2: kalau track yang dipakai langkah pertama ternyata
                // subtitle NewPipe, labelnya dikoreksi menjadi NEWPIPE.
                val transcriptResult = innertube.fetchTranscriptWithFallback(info)
                val sentences = transcriptResult.sentences
                // v1.4.0 (P1-8): timeout total tahap transkrip (120 dtk)
                // — dicatat di diagnostik, bukan diam-diam "tidak ada
                // caption" saat sebagian track belum sempat dicoba.
                val transcriptTimedOut = innertube.lastTranscriptTimedOut
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
                            listOfNotNull(fresh.analysisAudioUrl) +
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
                // v1.4.0 (P2-2): jejak rerank (id tak dikenal / tie-breaker).
                var rerankNote = ""
                // v1.3.0 History: angka jendela disimpan juga ke Room.
                var llmWindowsSucceeded = 0
                var llmWindowsTotal = 0
                // v1.2.0: jejak pre-filter + paralel per model.
                var prefilterText = ""
                var parallelText = ""
                val key = prefs.groqApiKey.trim()
                if (key.isNotBlank() && segments.isNotEmpty()) {
                    status = "Menilai highlight dengan Groq…"
                    try {
                        // v1.2.0 — pre-filter transcript-only (upgrade
                        // #1 riset super-cepat): LLM hanya men-skor
                        // Top-12 segmen (Scoring.TOP_N_LLM). Segmen di
                        // luar Top-12 tetap masuk selectClips di bawah
                        // (tanpa skor LLM) — bukan dibuang.
                        val prefiltered = Scoring.prefilterSegmentsForLlm(
                            segments, sentences
                        )
                        prefilterText = " • pre-filter: " + segments.size +
                            " segmen → Top " + prefiltered.size
                        val inputs = prefiltered.map { segment ->
                            GroqClient.SegmentInput(
                                id = segment.id,
                                startLabel = VideoId.formatMs(segment.startMs),
                                endLabel = VideoId.formatMs(segment.endMs),
                                text = segment.text.take(1200),
                                // v1.4.0 (P2-1): jendela scoring kini
                                // berbasis WAKTU (startMs), bukan indeks.
                                startMs = segment.startMs
                            )
                        }
                        // v1.0.7: scoring ber-chunk — video 91 menit
                        // (ALI, v1.0.6) mengirim ~61 segmen dalam SATU
                        // call dan kena HTTP 413 di qwen3.8-27b. Kini
                        // tiap call maks ~8 segmen / 12 menit; satu
                        // jendela gagal tidak menolkan video.
                        // v1.2.0: jendela dieksekusi paralel ke 3 model
                        // (lihat GroqClient.scoreSegmentsChunked).
                        val chunked = groq.scoreSegmentsChunked(key, info.title, inputs)
                        llmScores = chunked.scores
                        llmWindowsSucceeded = chunked.windowsSucceeded
                        llmWindowsTotal = chunked.windowsTotal
                        llmWindowsText = " • jendela LLM: " +
                            chunked.windowsSucceeded + "/" +
                            chunked.windowsTotal + " berhasil"
                        if (chunked.modelWins.isNotEmpty()) {
                            parallelText = " • LLM paralel: " +
                                chunked.modelWins.entries.joinToString(", ") { (model, count) ->
                                    "$model:$count"
                                }
                        }
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
                                // v1.4.0 (P2-2): kandidat audio-only
                                // (transkrip kosong) dinilai dari
                                // judul+alasan, bukan teks kosong.
                                text = if (clip.transcript.isNotBlank()) {
                                    clip.transcript.take(1200)
                                } else {
                                    "Judul: ${clip.title}. " +
                                        "Alasan: ${clip.reason}"
                                }
                            )
                        }
                        val ranking = groq.rerankClips(key, info.title, candidates)
                        if (ranking != null) {
                            // v1.4.0 (P2-2): id rerank yang tidak dikenal
                            // (bukan kandidat) dicatat di diagnostik —
                            // jangan diam-diam dibuang ke akhir.
                            val knownIds = found.map { it.index }.toSet()
                            val unknownIds = ranking
                                .map { it.id }
                                .filter { it !in knownIds }
                                .distinct()
                            if (unknownIds.isNotEmpty()) {
                                rerankNote = " • rerank: id tak dikenal " +
                                    unknownIds.joinToString(", ") +
                                    " (diabaikan, bukan kandidat)"
                            }
                            val rankById = ranking.associate { it.id to it.rank }
                            val rerankScoreById =
                                ranking.associate { it.id to it.score }
                            // v1.4.0 (P2-2): skor rerank jadi tie-breaker
                            // — rank sama (atau tanpa rank) diurutkan
                            // dari skor rerank tertinggi, bukan asal
                            // urutan list.
                            val reranked = found.sortedWith(
                                compareBy<Clip> { rankById[it.index] ?: 99 }
                                    .thenByDescending {
                                        rerankScoreById[it.index] ?: 0
                                    }
                            )
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
                    // v1.4.0 (P1-6): peringatan bahasa audio tidak
                    // diketahui (ranking memakai bahasa caption).
                    (if (audioLangUnknown) {
                        "PERINGATAN: bahasa audio tidak diketahui — " +
                            "memakai bahasa caption" +
                            (if (captionLangForWarning != null) {
                                " ($captionLangForWarning)"
                            } else {
                                ""
                            }) + " sebagai penentu ranking audio. • "
                    } else {
                        ""
                    }) +
                    // v1.4.0 (P1-8): timeout total tahap transkrip.
                    (if (transcriptTimedOut) {
                        "Transkrip: waktu habis (batas total 120 detik) — " +
                            "sebagian track belum sempat dicoba. • "
                    } else {
                        ""
                    }) +
                    "sumber stream: " + streamSource + " • audio HTTP: " +
                    // v1.3.0 (#4): streaming tidak punya HTTP code
                    // (MediaExtractor) — tampilkan "-" bukan 0.
                    (if (audioResult.httpCode > 0) {
                        audioResult.httpCode.toString()
                    } else {
                        "-"
                    }) + " • audio byte: " +
                    audioResult.downloadedBytes + " • audio mime: " +
                    audioResult.mime.ifEmpty { "-" } +
                    // v1.3.0 (#4): streaming vs download-penuh.
                    " • audio cara: " + audioResult.audioMethod +
                    " • stream dicoba: " +
                    audioResult.streamsTried + audioErrorText + audioDnsText +
                    " • Groq key: " +
                    groqKeyText + " • skor LLM: " + llmScores.size + " segmen" +
                    (if (llmError != null) " (gagal: " + llmError + ")" else "") +
                    // v1.0.7: berapa jendela scoring yang berhasil/gagal.
                    // v1.2.0: jejak pre-filter + jendela per model.
                    // v1.4.0 (P2-2): jejak rerank (id tak dikenal).
                    llmWindowsText + prefilterText + parallelText + rerankNote + zeroText
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
                    // v1.3.0 History: simpan hasil analisa lengkap ke
                    // Room (upsert by videoId, maks 100 item + LRU).
                    // Gagal simpan = analisa tetap jalan, bukan error.
                    saveCurrentAnalysisToHistory(
                        info = info,
                        sentences = sentences,
                        foundClips = found,
                        diagnosticText = diagnostic,
                        windowsSucceeded = llmWindowsSucceeded,
                        windowsTotal = llmWindowsTotal,
                        llmSegmentCount = llmScores.size
                    )
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
            } catch (e: CancellationException) {
                // v1.4.0 (P1-10): tombol Batal — reset state, status
                // normal. BUKAN kegagalan analisa; hasil parsial
                // (diagnostik sejauh ini) tetap disimpan.
                status = "Analisa dibatalkan."
                analysisJob = null
                throw e
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
                analysisJob = null
            }
        }
    }

    /** Download SATU clip, atas tap user. Tidak ada auto-download semua. */
    fun requestDownload(clip: Clip) {
        val info = currentInfo ?: run {
            error = "Analisa dulu sebelum download."
            return
        }
        // v1.4.0 (P1-10): Job per clip supaya tombol Batal bisa
        // membatalkannya (lihat cancelDownload).
        downloadJobs[clip.index] = viewModelScope.launch {
            updateClip(clip.index) { it.copy(isDownloading = true) }
            // v1.4.0 (P1-1): waktu download diukur dari tap sampai
            // tersimpan (detik) — ukuran jujur, bukan klaim byte.
            val startedAtMs = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.IO) {
                    ClipDownload.downloadClipDetailed(getApplication(), info, clip)
                }
                val seconds = (System.currentTimeMillis() - startedAtMs) / 1000.0
                // v1.4.0 (P1-1 + P2-5): durasi AKTUAL file hasil potong
                // disimpan di clip — kartu clip menampilkannya ("58 dtk"),
                // bukan durasi yang diminta.
                updateClip(clip.index) {
                    it.copy(
                        isDownloading = false,
                        savedPath = result.path,
                        actualDurationMs = result.actualDurationMs
                    )
                }
                // v1.0.6: jalur ekspor yang dipakai dicatat di
                // diagnostik (segment vs full-download) — bukti
                // jalurnya terlihat, bukan cuma dirasakan waktunya.
                // v1.0.9: kalau lapis segmen gagal lalu full-download
                // yang menyelamatkan, alasan gagal segmen ikut
                // dicatat di diagnostik yang sama (format " • ").
                // v1.4.0 (P1-1): durasi aktual + waktu download ikut
                // dicatat — Transformer tidak mengekspos byte counter,
                // jadi byte TIDAK diklaim di sini.
                val durationText = result.actualDurationMs?.let { ms ->
                    ", durasi ${ms / 1000} dtk"
                } ?: ""
                val exportNote = " • clip ${clip.index}: ${result.method}" +
                    durationText +
                    // Locale.US supaya desimal selalu titik (",1" di
                    // locale Indonesia membingungkan di diagnostik).
                    ", waktu " + String.format(Locale.US, "%.1f", seconds) + " dtk" +
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
                // v1.3.0 History: catat path + metode download ke
                // entri History (badge "tersimpan" di daftar).
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        historyRepository.updateClipDownload(
                            info.videoId, clip.index, result.path, result.method
                        )
                    } catch (e: Exception) {
                        // abaikan
                    }
                }
            } catch (e: CancellationException) {
                // v1.4.0 (P1-10): download dibatalkan — reset flag,
                // status normal. BUKAN error.
                updateClip(clip.index) { it.copy(isDownloading = false) }
                status = "Download clip ${clip.index} dibatalkan."
                throw e
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
            } finally {
                downloadJobs.remove(clip.index)
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
        // v1.4.0 (P1-10): Job per clip supaya tombol Batal bisa
        // membatalkannya (lihat cancelNarration).
        narrationJobs[clip.index] = viewModelScope.launch {
            updateClip(clip.index) {
                it.copy(
                    isGeneratingNarration = true,
                    narrationError = null,
                    // v1.4.0 (P1-11): tahap awal — status bertahap
                    // supaya layar tidak diam saat Whisper berjalan.
                    narrationStage = "menyiapkan…"
                )
            }
            try {
                val transcriptText: String
                val transcriptSource: String
                var narrationNote: String? = null
                if (clip.transcript.isNotBlank()) {
                    transcriptText = clip.transcript
                    transcriptSource = "Transkrip"
                } else {
                    val info = currentInfo
                        ?: throw IllegalStateException(
                            "Analisa dulu sebelum membuat narasi."
                        )
                    // v1.4.0 (P1-11): tahap 1 — potong audio clip.
                    // onProgress di cutClipToTemp menulis tahap
                    // "memotong audio clip…" / "mengunduh audio clip…"
                    // ke narrationStage via updateClip.
                    var temp: File? = null
                    try {
                        temp = withContext(Dispatchers.IO) {
                            ClipDownload.cutClipToTemp(
                                getApplication(), info, clip,
                                onProgress = { stage ->
                                    // v1.4.0-fix1: callback ini dipanggil
                                    // dari thread IO (di dalam withContext
                                    // di atas) — updateClip menulis Compose
                                    // state, jadi harus lewat Main.
                                    viewModelScope.launch {
                                        updateClip(clip.index) {
                                            it.copy(narrationStage = stage)
                                        }
                                    }
                                }
                            )
                        } ?: throw IllegalStateException(
                            "Gagal memotong clip untuk transkrip Whisper. " +
                                "Coba download clip dulu, atau coba lagi."
                        )
                        // v1.4.0 (P1-11): tahap 2 — transkrip.
                        updateClip(clip.index) {
                            it.copy(narrationStage = "mentranskrip…")
                        }
                        val detailed = whisper.transcribeDetailed(key, temp)
                        var whisperText = detailed.text
                        if (whisperText.isBlank()) {
                            throw IllegalStateException(
                                "Whisper tidak menemukan ucapan di clip " +
                                    "ini, jadi narasi tidak dibuat dari tebakan."
                            )
                        }
                        // v1.4.0 (P1-4): cross-check bahasa Whisper
                        // terhadap bahasa yang diharapkan (caption /
                        // track yang dipilih). Kalau berbeda, audio
                        // yang dipotong mungkin track dubbing yang
                        // salah — pilih ulang track audio SEKALI dari
                        // bahasa yang diharapkan, transkrip ulang,
                        // dan tampilkan peringatan di kartu narasi.
                        val expectedLang = expectedCaptionLanguage(info)
                        val detectedLang = detailed.language
                        if (expectedLang != null && detectedLang != null &&
                            detectedLang != expectedLang
                        ) {
                            narrationNote = "Bahasa terdeteksi $detectedLang, " +
                                "berbeda dari $expectedLang — track audio " +
                                "dipilih ulang sekali."
                            try {
                                val repickUrl =
                                    NewPipeStreamExtractor.extractAudioUrlForLanguage(
                                        info.videoId, expectedLang
                                    )
                                if (repickUrl != null) {
                                    val temp2 = withContext(Dispatchers.IO) {
                                        ClipDownload.cutClipToTemp(
                                            getApplication(), info, clip,
                                            audioUrlOverride = repickUrl,
                                            onProgress = { stage ->
                                                // v1.4.0-fix1: sama seperti
                                                // di atas — callback dari
                                                // thread IO, update lewat Main.
                                                viewModelScope.launch {
                                                    updateClip(clip.index) {
                                                        it.copy(narrationStage = stage)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    if (temp2 != null) {
                                        try {
                                            updateClip(clip.index) {
                                                it.copy(narrationStage = "mentranskrip…")
                                            }
                                            val detailed2 = whisper.transcribeDetailed(
                                                key, temp2
                                            )
                                            if (detailed2.text.isNotBlank()) {
                                                whisperText = detailed2.text
                                                // Bahasa hasil re-pick
                                                // dicatat apa adanya.
                                                narrationNote =
                                                    "Bahasa terdeteksi $detectedLang, " +
                                                        "berbeda dari $expectedLang — " +
                                                        "track audio dipilih ulang sekali" +
                                                        (if (detailed2.language != null) {
                                                            " (hasil: ${detailed2.language})"
                                                        } else {
                                                            ""
                                                        }) + "."
                                            }
                                        } finally {
                                            try { temp2.delete() } catch (e: Exception) { /* abaikan */ }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Re-pick gagal = pakai transkrip
                                // pertama + peringatan tetap tampil.
                            }
                        }
                        transcriptText = whisperText
                        transcriptSource = "Whisper"
                    } finally {
                        try { temp?.delete() } catch (e: Exception) { /* abaikan */ }
                    }
                }
                // v1.4.0 (P1-11): tahap 3 — buat narasi.
                updateClip(clip.index) {
                    it.copy(narrationStage = "membuat narasi…")
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
                        narrationSource = transcriptSource,
                        narrationStage = null,
                        narrationNote = narrationNote
                    )
                }
                // v1.3.0 History: simpan narasi ke entri History
                // (buka ulang dari History tidak generate ulang —
                // hemat kuota Groq).
                val savedInfo = currentInfo
                if (savedInfo != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            historyRepository.updateClipNarration(
                                savedInfo.videoId, clip.index,
                                cleaned, transcriptSource
                            )
                        } catch (e: Exception) {
                            // abaikan
                        }
                    }
                }
            } catch (e: CancellationException) {
                // v1.4.0 (P1-10): pembuatan narasi dibatalkan — reset
                // flag + tahap, status normal. BUKAN error.
                updateClip(clip.index) {
                    it.copy(isGeneratingNarration = false, narrationStage = null)
                }
                status = "Pembuatan narasi clip ${clip.index} dibatalkan."
                throw e
            } catch (e: Exception) {
                updateClip(clip.index) {
                    it.copy(
                        isGeneratingNarration = false,
                        narrationStage = null,
                        narrationError = e.message ?: "Gagal membuat narasi."
                    )
                }
            } finally {
                narrationJobs.remove(clip.index)
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

    /**
     * v1.3.0 History — simpan hasil analisa lengkap ke Room.
     * Stream URL TIDAK disimpan (kedaluwarsa); thumbnail hanya URL.
     * Analisa ulang menimpa baris lama (upsert by videoId).
     */
    private fun saveCurrentAnalysisToHistory(
        info: VideoInfo,
        sentences: List<Sentence>,
        foundClips: List<Clip>,
        diagnosticText: String,
        windowsSucceeded: Int,
        windowsTotal: Int,
        llmSegmentCount: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entity = AnalysisHistoryEntity(
                    videoId = info.videoId,
                    title = info.title,
                    channelName = info.author,
                    durationMs = info.durationMs,
                    thumbnailUrl = "https://i.ytimg.com/vi/${info.videoId}/hqdefault.jpg",
                    analyzedAt = System.currentTimeMillis(),
                    clipCount = foundClips.size,
                    transcriptSentenceCount = sentences.size,
                    audioTrackDescription = info.stream.audioTrackDescription,
                    heatmapPointCount = info.heatPoints.size,
                    diagnosticSummary = diagnosticText,
                    transcriptJson = HistoryRepository.sentencesToJson(sentences),
                    llmWindowSucceeded = windowsSucceeded,
                    llmWindowTotal = windowsTotal,
                    llmSegmentCount = llmSegmentCount,
                    downloadedCount = foundClips.count { it.savedPath != null }
                )
                val clipEntities = foundClips.map { clip ->
                    HistoryClipEntity(
                        videoId = info.videoId,
                        clipIndex = clip.index,
                        startMs = clip.startMs,
                        endMs = clip.endMs,
                        score = (clip.score * 100).toInt().coerceIn(0, 100),
                        title = clip.title,
                        reason = clip.reason,
                        transcriptText = clip.transcript,
                        narrationText = clip.narration,
                        narrationSource = clip.narrationSource,
                        downloadedPath = clip.savedPath,
                        downloadMethod = null
                    )
                }
                historyRepository.saveAnalysis(entity, clipEntities)
            } catch (e: Exception) {
                // History gagal simpan = analisa tetap jalan, bukan error.
            }
        }
    }

    /**
     * v1.3.0 History — buka hasil lama TANPA analisa ulang dan TANPA
     * network (state di-restore dari Room). Stream URL tidak disimpan,
     * jadi refreshStreamsForHistory me-resolve stream fresh di
     * belakang supaya Preview/Download tetap jalan; kalau refresh
     * gagal, hasil tetap tampil dan Preview/Download gagal jujur.
     */
    fun openHistory(videoId: String) {
        viewModelScope.launch {
            try {
                val entity = historyRepository.getByVideoId(videoId) ?: run {
                    error = "Hasil analisa itu sudah tidak ada di History."
                    return@launch
                }
                val clipEntities = historyRepository.getClips(videoId)
                stopPreview()
                link = "https://youtu.be/$videoId"
                historyNotice = null
                historyNoticeVideoId = null
                error = null
                videoTitle = entity.title
                videoAuthor = entity.channelName
                diagnostic = entity.diagnosticSummary
                clips = clipEntities.map { clipEntity ->
                    Clip(
                        index = clipEntity.clipIndex,
                        startMs = clipEntity.startMs,
                        endMs = clipEntity.endMs,
                        title = clipEntity.title,
                        reason = clipEntity.reason,
                        score = (clipEntity.score / 100.0).coerceIn(0.0, 1.0),
                        transcript = clipEntity.transcriptText,
                        savedPath = resolveSavedPath(clipEntity.downloadedPath),
                        narration = clipEntity.narrationText,
                        narrationSource = clipEntity.narrationSource,
                        narrationWordCount = clipEntity.narrationText?.let {
                            Narration.wordCount(it)
                        } ?: 0
                    )
                }
                currentInfo = VideoInfo(
                    videoId = videoId,
                    title = entity.title,
                    author = entity.channelName,
                    durationMs = entity.durationMs,
                    stream = VideoStream(
                        progressiveUrl = null,
                        videoOnlyUrl = null,
                        audioUrl = null,
                        mimeProgressive = null,
                        audioTrackDescription = entity.audioTrackDescription
                    ),
                    captionTracks = emptyList(),
                    heatPoints = emptyList(),
                    chapters = emptyList()
                )
                status = "Dibuka dari History — tanpa analisa ulang."
                refreshStreamsForHistory(videoId)
            } catch (e: Exception) {
                error = e.message ?: "Gagal membuka History."
            }
        }
    }

    /** Resolve stream fresh untuk hasil History (best-effort, tanpa throw). */
    private suspend fun refreshStreamsForHistory(videoId: String) {
        try {
            var freshInfo = innertube.fetchVideoInfo(videoId)
            val preferredLanguage = if (freshInfo.captionTracks.any {
                    it.languageCode.startsWith("id")
                }
            ) {
                "id"
            } else {
                freshInfo.captionTracks.firstOrNull()?.languageCode
                    ?.take(2)?.lowercase()
            }
            val newPipe = NewPipeStreamExtractor.extract(videoId, preferredLanguage)
            if (newPipe.audioUrl != null || newPipe.videoUrl != null) {
                freshInfo = freshInfo.copy(
                    stream = VideoStream(
                        progressiveUrl = freshInfo.stream.progressiveUrl,
                        videoOnlyUrl = newPipe.videoUrl
                            ?: freshInfo.stream.videoOnlyUrl,
                        audioUrl = newPipe.audioUrl ?: freshInfo.stream.audioUrl,
                        mimeProgressive = freshInfo.stream.mimeProgressive,
                        audioTrackDescription = if (newPipe.audioUrl != null) {
                            newPipe.audioTrackDescription.ifEmpty {
                                freshInfo.stream.audioTrackDescription
                            }
                        } else {
                            freshInfo.stream.audioTrackDescription
                        }
                    )
                )
            }
            val current = currentInfo
            if (current != null) {
                currentInfo = current.copy(stream = freshInfo.stream)
            }
        } catch (e: Exception) {
            // Refresh gagal = hasil History tetap tampil; Preview /
            // Download tanpa stream gagal jujur saat ditekan.
        }
    }

    /**
     * Path clip tersimpan yang dicatat History bisa basi (user hapus
     * file dari luar app) — kembalikan null kalau file tidak ada,
     * supaya UI tidak mengklaim "Tersimpan" padahal file hilang.
     */
    private fun resolveSavedPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = if (path.startsWith("/")) {
            File(path)
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ),
                path.removePrefix("Download/").removePrefix("/")
            )
        }
        return if (file.exists()) path else null
    }

    fun deleteHistoryItem(videoId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.deleteByVideoId(videoId)
            } catch (e: Exception) {
                // abaikan — daftar di-refresh dari Flow
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyRepository.deleteAll()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private fun updateClip(index: Int, transform: (Clip) -> Clip) {
        clips = clips.map { if (it.index == index) transform(it) else it }
    }
}
