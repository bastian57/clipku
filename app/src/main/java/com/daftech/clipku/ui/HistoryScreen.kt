package com.daftech.clipku.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftech.clipku.history.AnalysisHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Layar History Analisa (v1.3.0, Room).
 *
 * Tap item = buka hasil lama TANPA analisa ulang dan TANPA network
 * (semua dari Room; hanya Preview/Download yang butuh stream fresh).
 * Hapus per item + hapus semua. Maks 100 item, eviksi LRU otomatis
 * (yang terlama terhapus saat item ke-101 masuk).
 * Hapus analisa TIDAK menghapus file clip di Download/ClipKu.
 */
@Composable
fun HistoryScreen(
    items: List<AnalysisHistoryEntity>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AnalysisHistoryEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "History Analisa",
                style = MaterialTheme.typography.headlineMedium
            )
            TextButton(onClick = onBack) { Text("Kembali") }
        }
        Text(
            text = "Tap untuk membuka hasil tanpa analisa ulang " +
                "(bisa mode pesawat). Maks 100 analisa — yang terlama " +
                "terhapus otomatis.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (items.isEmpty()) {
            Text(
                text = "Belum ada analisa tersimpan. Analisa satu video, " +
                    "hasilnya otomatis masuk sini.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${items.size} / 100 analisa",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { confirmDeleteAll = true }) {
                    Text("Hapus Semua")
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.videoId }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(item.videoId) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.title.ifBlank { item.videoId },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { confirmDelete = item }) {
                                    Text("✕")
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.channelName.ifBlank { "?" } +
                                    " • " + formatDurationMs(item.durationMs) +
                                    " • " + formatDate(item.analyzedAt),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${item.clipCount} clip" +
                                    if (item.downloadedCount > 0) {
                                        " • ${item.downloadedCount} tersimpan"
                                    } else {
                                        ""
                                    } +
                                    if (item.audioTrackDescription.isNotBlank()) {
                                        " • ${item.audioTrackDescription}"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Hapus semua History?") },
            text = {
                Text(
                    "Semua ${items.size} analisa tersimpan akan dihapus. " +
                        "File clip yang sudah di-download di Download/ClipKu " +
                        "TIDAK ikut terhapus."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onDeleteAll()
                }) { Text("Hapus Semua") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text("Batal")
                }
            }
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus analisa ini?") },
            text = {
                Text(
                    "\"${item.title.ifBlank { item.videoId }}\" akan " +
                        "dihapus dari History. File clip yang sudah " +
                        "di-download TIDAK ikut terhapus."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(item.videoId)
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Batal")
                }
            }
        )
    }
}

private fun formatDate(epochMs: Long): String {
    return try {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id"))
            .format(Date(epochMs))
    } catch (e: Exception) {
        ""
    }
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
