package com.daftech.clipku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Settings v1: hanya Groq API key.
 *
 * Key diisi user sendiri, disimpan di SharedPreferences privat HP,
 * dikecualikan dari backup, tidak pernah di-hardcode, tidak pernah
 * dikirim ke mana pun kecuali endpoint Groq saat scoring / narasi.
 * Tanpa key: analisa tetap jalan dengan heatmap + audio saja, tapi
 * skor LLM dan narasi ditolak dengan pesan jelas.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: ClipKuViewModel = viewModel()
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RowHeader(onBack = onBack)
        Text(
            text = "Groq API key",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Key ini milik kamu sendiri. Disimpan di HP ini saja, " +
                "tidak ikut backup, tidak pernah ditulis ke source atau chat.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = viewModel.groqKey,
            onValueChange = { viewModel.updateGroqKey(it) },
            label = { Text("Groq API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Button(
            onClick = { viewModel.saveGroqKey() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Simpan Key")
        }
        OutlinedButton(
            onClick = { viewModel.clearGroqKey() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Hapus Key")
        }
        if (viewModel.status.isNotBlank()) {
            Text(text = viewModel.status, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Tanpa key, ClipKu tetap bisa menganalisa dengan " +
                "heatmap + energi audio, tapi tidak bisa menilai segmen " +
                "dengan LLM dan tidak bisa membuat narasi.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RowHeader(onBack: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
        TextButton(onClick = onBack) { Text("Kembali") }
    }
}
