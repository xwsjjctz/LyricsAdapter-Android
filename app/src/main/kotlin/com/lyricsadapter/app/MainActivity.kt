package com.lyricsadapter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lyricsadapter.app.ui.AppScreen
import com.lyricsadapter.app.ui.LyricsAdapterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = container
        lifecycleScope.launch { container.player.connect() }
        setContent {
            val colorMode by container.settings.colorMode.value.collectAsStateWithLifecycle()
            LyricsAdapterTheme(colorMode) {
                AppScreen(container)
            }
        }
    }
}
