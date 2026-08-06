package net.otozine.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import net.otozine.player.ui.components.Icon
import net.otozine.player.ui.components.NeuWell
import net.otozine.player.ui.components.OtoIcon
import net.otozine.player.ui.theme.Oto

/**
 * Inline search field.
 *
 * Lives inside Play and Library rather than owning a tab. Search is something
 * you do *to* a list, not a place you go -- putting it in the bottom bar meant
 * leaving the list you were looking at in order to filter it.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Title, artist, composer, film…",
) {
    val colors = Oto.colors

    NeuWell(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), radius = 16.dp) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OtoIcon(Icon.SEARCH, tint = colors.ink3, size = 17.dp)
            Box(Modifier.padding(start = 10.dp).weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, style = Oto.type.sub, color = colors.ink3)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = Oto.type.item.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.teal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Text(
                    "CLEAR",
                    style = Oto.type.micro,
                    color = colors.teal,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onQueryChange("") }
                        .padding(4.dp),
                )
            }
        }
    }
}
