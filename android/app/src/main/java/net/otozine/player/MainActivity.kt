package net.otozine.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.otozine.player.ui.Shell
import net.otozine.player.ui.rememberPermissionGate
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.OtoPalette
import net.otozine.player.ui.theme.OtoZineTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Root() }
    }
}

@Composable
private fun Root(viewModel: PlayerViewModel = viewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    // Asks for notifications and audio access on first composition. Granting
    // audio populates the library immediately, so opening the app for the first
    // time shows the music already on the phone rather than an empty screen.
    val requestAudio = rememberPermissionGate(viewModel::onAudioPermissionGranted)

    val dark = when (prefs.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    OtoZineTheme(darkTheme = dark, palette = OtoPalette.of(prefs.palette)) {
        Box(Modifier.fillMaxSize().background(Oto.colors.page)) {
            Shell(viewModel, requestAudio)
        }
    }
}
