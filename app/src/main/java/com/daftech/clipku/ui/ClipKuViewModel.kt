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
import com.daftech.clipku.model.Clip
import com.daftech.clipku.model.VideoInfo
import com.daftech.clipku.youtube.InnertubeClient
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

    var link by mutableStateOf("")
        private set
    var isAnalysing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
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
        clips = emptyList()
        currentInfo = null
        status = "Menghubungi YouTube…"
        viewModelScope.launch {
            try {
                val info = innertube.fetchVideoInfo(videoId)
                currentInfo = info
                videoTitle = info.title
                videoAuthor = info.author
                status = "Mengambil transkrip…"
                val sentences = innertube.fetchTranscript(info)
                status = "Menganalisa audio…"
                val audio = AudioEnergy.analyse(
                    info.stream.audioUrl,
                    getApplication<Application>().cacheDir
                )
                val segments = Scoring.buildSegments(sentences)

                var llmScores: Map<Int, GroqClient.SegmentScore> = emptyMap()
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
                        status += " Transkrip tidak tersedia, jadi narasi per clip tidak bisa dibuat."
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Analisa gagal. Coba lagi."
                status = ""
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
     * Menolak kalau: tidak ada Groq key, atau clip tidak ada transkrip.
     * Teks yang disimpan adalah teks BERSIH hasil Narration.clean —
     * tombol Salin menyalin teks itu saja, bukan metadata.
     */
    fun generateNarrationFor(clip: Clip) {
        val key = prefs.groqApiKey.trim()
        if (key.isBlank()) {
            updateClip(clip.index) {
                it.copy(narrationError = "Isi Groq API key di Settings dulu.")
            }
            return
        }
        if (clip.transcript.isBlank()) {
            updateClip(clip.index) {
                it.copy(
                    narrationError = "Narasi tidak bisa dibuat: clip ini " +
                        "tidak ada transkrip. Narasi tidak dibuat dari judul saja."
                )
            }
            return
        }
        viewModelScope.launch {
            updateClip(clip.index) {
                it.copy(isGeneratingNarration = true, narrationError = null)
            }
            try {
                var raw = groq.generateNarration(key, videoTitle, clip.transcript)
                var cleaned = Narration.clean(raw)
                // Satu kali percobaan ulang kalau di luar 110-140 kata.
                if (!Narration.isInTargetRange(cleaned)) {
                    try {
                        raw = groq.generateNarration(key, videoTitle, clip.transcript)
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
                        narrationWordCount = count
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
