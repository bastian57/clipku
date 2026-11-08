package com.daftech.clipku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.daftech.clipku.analysis.Narration
import com.daftech.clipku.analysis.Scoring
import com.daftech.clipku.model.Clip

/**
 * Layar utama ClipKu — satu layar, tanpa tab tambahan di v1.
 *
 * Alur: tempel link -> Analisa -> daftar kartu clip -> Preview ->
 * Download (per clip, atas tap user) -> Buat Narasi -> Salin Narasi.
 *
 * Salin memakai ViewModel.copyNarration (clipboard sistem) agar yang
 * tersalin PASTI hanya blok narasi final, bukan metadata.
 */
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit = {},
    viewModel: ClipKuViewModel = viewModel()
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "ClipKu", style = MaterialTheme.typography.headlineMedium)
            Row {
                // v1.3.0 History: daftar analisa tersimpan (Room).
                TextButton(onClick = onOpenHistory) { Text("History") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
        Text(
            text = "Tempel link YouTube. APK menonton dan menganalisa " +
                "video panjang, lalu menyuguhkan beberapa clip highlight " +
                "±1 menit. Download clip yang kamu mau, edit di CapCut.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = viewModel.link,
            onValueChange = { viewModel.updateLink(it) },
            label = { Text("Link YouTube") },
            placeholder = { Text("https://www.youtube.com/watch?v=…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            onClick = { viewModel.startAnalysis() },
            enabled = !viewModel.isAnalysing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.isAnalysing) "Menganalisa…" else "Analisa")
        }
        // v1.4.0 (P1-10): tombol Batal selama analisa berjalan.
        if (viewModel.isAnalysing) {
            OutlinedButton(
                onClick = { viewModel.cancelAnalysis() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batal")
            }
        }
        // v1.3.0 History: link yang ditempel sudah pernah dianalisa —
        // tawarkan buka hasil lama (tanpa analisa ulang / tanpa
        // network) atau analisa ulang (menimpa entri lama).
        viewModel.historyNotice?.let { notice ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = notice, style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { viewModel.openHistoryFromNotice() }) {
                            Text("Buka Hasil Lama")
                        }
                        TextButton(onClick = { viewModel.clearHistoryNotice() }) {
                            Text("Abaikan")
                        }
                    }
                }
            }
        }
        if (viewModel.status.isNotBlank()) {
            Text(text = viewModel.status, style = MaterialTheme.typography.bodyMedium)
        }
        viewModel.error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { viewModel.clearError() }) { Text("Tutup") }
                }
            }
        }
        if (viewModel.diagnostic.isNotBlank()) {
            Text(text = viewModel.diagnostic, style = MaterialTheme.typography.bodySmall)
            // v1.4.0 (P2-8): salin diagnostik lengkap ke clipboard —
            // tidak perlu screenshot untuk melapor.
            OutlinedButton(onClick = { viewModel.copyDiagnostic() }) {
                Text("Salin Diagnostik")
            }
        }
        if (viewModel.videoTitle.isNotBlank()) {
            Text(text = viewModel.videoTitle, style = MaterialTheme.typography.titleMedium)
            if (viewModel.videoAuthor.isNotBlank()) {
                Text(text = viewModel.videoAuthor, style = MaterialTheme.typography.bodySmall)
            }
        }
        for (clip in viewModel.clips) {
            ClipCard(
                clip = clip,
                onPreview = { viewModel.previewPlayer(context, clip) },
                onStopPreview = { viewModel.stopPreview() },
                onDownload = { viewModel.requestDownload(clip) },
                onCancelDownload = { viewModel.cancelDownload(clip) },
                onGenerateNarration = { viewModel.generateNarrationFor(clip) },
                onCancelNarration = { viewModel.cancelNarration(clip) },
                onCopyNarration = { viewModel.copyNarration(clip) },
                previewError = viewModel.previewError
            )
        }
        if (viewModel.clips.isNotEmpty()) {
            Text(
                text = "Catatan: APK ini tidak mengedit kreatif. Tidak ada " +
                    "transisi, teks, musik, filter, reframe vertikal, " +
                    "voiceover, atau thumbnail yang dibakar ke clip. " +
                    "Potong di batas highlight adalah ekstraksi. " +
                    "Edit kreatif dilakukan kamu di CapCut.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ClipCard(
    clip: Clip,
    onPreview: () -> ClipKuViewModel.PreviewResult,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onGenerateNarration: () -> Unit,
    onCancelNarration: () -> Unit,
    onCopyNarration: () -> Unit,
    previewError: String?
) {
    var showPlayer by remember { mutableStateOf(false) }
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }
    // v1.4.0 (P1-2): apakah preview yang sedang tampil ber-audio.
    var previewHasAudio by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Clip ${clip.index} — ${clip.title}",
                style = MaterialTheme.typography.titleMedium
            )
            // Metadata di luar teks narasi: tidak pernah ikut tersalin.
            // v1.4.0 (P2-5): durasi AKTUAL file hasil potong (kalau
            // sudah di-download), kalau belum — durasi yang diminta.
            val shownDurationSec = (clip.actualDurationMs ?: clip.durationMs) / 1000
            Text(
                text = Scoring.label(clip.startMs, clip.endMs) +
                    " • $shownDurationSec dtk" +
                    (if (clip.actualDurationMs != null) " (aktual)" else "") +
                    " • skor ${(clip.score * 100).toInt()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(text = clip.reason, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try {
                        val result = onPreview()
                        playerRef = result.player
                        previewHasAudio = result.hasAudio
                        showPlayer = true
                    } catch (e: Exception) {
                        // Preview gagal (tidak ada stream): tombol Download
                        // tetap jalan, karena download memakai jalur file.
                        showPlayer = false
                    }
                }) {
                    Text("Preview")
                }
                if (showPlayer) {
                    OutlinedButton(onClick = {
                        showPlayer = false
                        playerRef = null
                        onStopPreview()
                    }) {
                        Text("Stop")
                    }
                }
            }
            if (showPlayer) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = true } },
                    update = { view -> view.player = playerRef },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
                // v1.4.0 (P1-2): jangan diam-diam bisu — kalau hanya
                // video-only yang tersedia, labelnya eksplisit.
                if (!previewHasAudio) {
                    Text(
                        text = "Preview tanpa audio — hanya video yang " +
                            "tersedia untuk clip ini. File download tetap " +
                            "ber-audio kalau track audio tersedia.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                previewError?.let { message ->
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = onDownload,
                enabled = !clip.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (clip.isDownloading) "Menyimpan…" else "Download")
            }
            // v1.4.0 (P1-10): batalkan download clip yang sedang berjalan.
            if (clip.isDownloading) {
                OutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Batal Download")
                }
            }
            clip.savedPath?.let { path ->
                Text(text = "Tersimpan: $path", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onGenerateNarration,
                enabled = !clip.isGeneratingNarration,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (clip.isGeneratingNarration) "Membuat narasi…" else "Buat Narasi"
                )
            }
            // v1.4.0 (P1-10): batalkan pembuatan narasi yang sedang berjalan.
            if (clip.isGeneratingNarration) {
                OutlinedButton(
                    onClick = onCancelNarration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Batal Narasi")
                }
                // v1.4.0 (P1-11): tahap yang sedang berjalan — layar
                // tidak diam saat Whisper bekerja.
                clip.narrationStage?.let { stage ->
                    Text(text = stage, style = MaterialTheme.typography.bodySmall)
                }
            }
            clip.narrationError?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
            clip.narration?.let { narration ->
                // BLOK NARASI FINAL — satu-satunya yang disalin tombol Salin.
                Text(text = narration, style = MaterialTheme.typography.bodyMedium)
                // v1.4.0 (P1-4): peringatan cross-check bahasa Whisper
                // — tampil di bawah blok narasi (bukan bagian teks).
                clip.narrationNote?.let { note ->
                    Text(text = note, style = MaterialTheme.typography.bodySmall)
                }
                // Metadata narasi di luar blok: tidak ikut tersalin.
                // Termasuk sumber transkrip ("Transkrip"/"Whisper") —
                // label ini hanya info UI, bukan bagian teks yang disalin.
                val sourceText = clip.narrationSource?.let { "sumber: $it • " } ?: ""
                Text(
                    text = sourceText + "${clip.narrationWordCount} kata • " +
                        "±${Narration.estimatedSeconds(narration)} detik • " +
                        "untuk generate di SuaraKu",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onCopyNarration,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salin Narasi")
                }
                Text(
                    text = "Tombol Salin hanya menyalin teks narasi di atas. " +
                        "Judul, timestamp, dan jumlah kata tidak ikut tersalin.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
