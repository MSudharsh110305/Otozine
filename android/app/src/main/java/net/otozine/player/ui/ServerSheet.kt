package net.otozine.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.Prefs
import net.otozine.player.ui.components.NeuWell
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Oto

/**
 * Streaming server setup.
 *
 * Speaks Subsonic, which Navidrome implements -- so the same screen works
 * against Navidrome, Gonic, Airsonic or anything else that supports it. The
 * password is stored on the device and only ever sent as a salted hash.
 */
@Composable
fun ServerSheet(
    prefs: Prefs,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var user by remember { mutableStateOf(prefs.serverUser) }
    var password by remember { mutableStateOf(prefs.serverPassword) }

    Sheet(
        title = "Streaming server",
        subtitle = "Point this at your Navidrome server to stream the whole " +
            "library without keeping it on the phone.",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("SERVER URL", url, { url = it }, "https://music.example.com")
            Field("USERNAME", user, { user = it }, "otozine")
            Field("PASSWORD", password, { password = it }, "", secret = true)

            state.serverStatus?.let { status ->
                VSpace(2.dp)
                Text(status, style = Oto.type.data, color = Oto.colors.teal)
            }

            VSpace(4.dp)
            SheetButton(
                if (state.serverBusy) "CHECKING…" else "SAVE & CONNECT",
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                viewModel.saveServer(url, user, password)
            }

            VSpace(6.dp)
            Text(
                "Setup script for a free Oracle Cloud server is in the project at " +
                    "server/setup-navidrome.sh. It installs Navidrome behind HTTPS " +
                    "and points it at a music folder you rsync to.",
                style = Oto.type.body,
                color = Oto.colors.ink3,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    Column {
        Text(label, style = Oto.type.micro, color = Oto.colors.ink3)
        VSpace(5.dp)
        NeuWell(Modifier.fillMaxWidth(), radius = 12.dp) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = Oto.type.sub, color = Oto.colors.ink3)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = Oto.type.item.copy(color = Oto.colors.ink),
                    cursorBrush = SolidColor(Oto.colors.teal),
                    visualTransformation = if (secret) PasswordVisualTransformation()
                                           else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
