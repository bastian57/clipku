package com.daftech.clipku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
