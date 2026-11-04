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
            TextButton(onClick = onOpenSettings) { Text("Settings") }
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
                onGenerateNarration = { viewModel.generateNarrationFor(clip) },
                onCopyNarration = { viewModel.copyNarration(clip) }
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
    onPreview: () -> ExoPlayer,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit,
    onGenerateNarration: () -> Unit,
    onCopyNarration: () -> Unit
) {
    var showPlayer by remember { mutableStateOf(false) }
    var playerRef by remember { mutableStateOf<ExoPlayer?>(null) }

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
            Text(
                text = Scoring.label(clip.startMs, clip.endMs) +
                    " • ${clip.durationMs / 1000} detik" +
                    " • skor ${(clip.score * 100).toInt()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(text = clip.reason, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try {
                        playerRef = onPreview()
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
            }
            Button(
                onClick = onDownload,
                enabled = !clip.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (clip.isDownloading) "Menyimpan…" else "Download")
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
            clip.narrationError?.let { message ->
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
            clip.narration?.let { narration ->
                // BLOK NARASI FINAL — satu-satunya yang disalin tombol Salin.
                Text(text = narration, style = MaterialTheme.typography.bodyMedium)
                // Metadata narasi di luar blok: tidak ikut tersalin.
                Text(
                    text = "${clip.narrationWordCount} kata • " +
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
