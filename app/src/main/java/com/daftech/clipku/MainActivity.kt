package com.daftech.clipku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daftech.clipku.ui.ClipKuTheme
import com.daftech.clipku.ui.ClipKuViewModel
import com.daftech.clipku.ui.MainScreen
import com.daftech.clipku.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipKuTheme {
                // v1.0.1 FIX (kontras, pelajaran tema gelap LoopLive):
                // Tanpa Surface, Text Compose default ke hitam, padahal
                // tema ClipKu gelap (background 0xFF0B1220). Surface
                // menetapkan LocalContentColor ke onBackground (putih),
                // sehingga semua Text di dalamnya terbaca. Surface
                // saja sudah cukup; tidak ada Text yang diubah warnanya
                // satu per satu di fix ini.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Satu ViewModel dipakai bersama Main + Settings supaya
                    // Groq key dan hasil analisa tidak hilang saat pindah layar.
                    val viewModel: ClipKuViewModel = viewModel()
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            viewModel = viewModel
                        )
                    } else {
                        MainScreen(
                            onOpenSettings = { showSettings = true },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}
