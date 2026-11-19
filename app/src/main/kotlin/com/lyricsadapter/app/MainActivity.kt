package com.lyricsadapter.app

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.lyricsadapter.app.ui.AppScreen
import com.lyricsadapter.app.ui.LyricsAdapterTheme
import com.lyricsadapter.app.ui.StartupScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest's theme is only for the launch window, which shows the logo; see themes.xml.
        setTheme(R.style.Theme_LyricsAdapter)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The startup screen is in the first frame with the same picture, so the system's goes at once, without its own fade.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        val container = container
        lifecycleScope.launch { container.player.connect() }
        container.updater.checkAtStartup()
        setContent {
            val colorMode by container.settings.colorMode.value.collectAsStateWithLifecycle()
            LyricsAdapterTheme(colorMode) {
                var settled by remember { mutableStateOf(false) }
                var startingUp by remember { mutableStateOf(!container.startupShown) }
                Box(Modifier.fillMaxSize()) {
                    AppScreen(container, onSettled = { settled = true })
                    if (startingUp) {
                        StartupScreen(ready = { settled }) {
                            container.startupShown = true
                            startingUp = false
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A downloaded update that could not be installed while the app was away waits in settings.
        container.updater.takeNotice()?.let { version ->
            Toast.makeText(this, getString(R.string.update_ready_notice, version), Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        // Put away, not turned around: the moment a downloaded update can go in without interrupting.
        if (!isChangingConfigurations) container.updater.onBackground(playing = container.player.state.value.isPlaying)
    }
}
