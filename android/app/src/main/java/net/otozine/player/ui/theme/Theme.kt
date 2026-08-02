package net.otozine.player.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalOtoColors = staticCompositionLocalOf { PaperColors }
val LocalOtoType = staticCompositionLocalOf { Typography }

/** Shorthand: `Oto.colors.ink`, `Oto.type.label`. */
object Oto {
    val colors: OtoColors
        @Composable get() = LocalOtoColors.current
    val type: OtoTypography
        @Composable get() = LocalOtoType.current
}

/**
 * @param palette an explicit theme, or null to follow the system's light/dark.
 */
@Composable
fun OtoZineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: OtoPalette? = null,
    content: @Composable () -> Unit,
) {
    val target = palette?.colors ?: if (darkTheme) InkColors else PaperColors

    // Cross-fade the palette rather than cutting to it.
    //
    // Every surface, shadow and glyph changes at once when a theme is picked,
    // and an instant swap of the whole screen reads as a glitch -- the eye
    // cannot tell a deliberate change from a redraw. 260 ms is long enough to
    // register as one intentional movement and short enough not to be a wait.
    val spec = tween<Color>(durationMillis = 260, easing = FastOutSlowInEasing)
    val colors = OtoColors(
        page = animateColorAsState(target.page, spec, label = "page").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        sunken = animateColorAsState(target.sunken, spec, label = "sunken").value,
        highlight = animateColorAsState(target.highlight, spec, label = "highlight").value,
        shadow = animateColorAsState(target.shadow, spec, label = "shadow").value,
        ink = animateColorAsState(target.ink, spec, label = "ink").value,
        ink2 = animateColorAsState(target.ink2, spec, label = "ink2").value,
        ink3 = animateColorAsState(target.ink3, spec, label = "ink3").value,
        teal = animateColorAsState(target.teal, spec, label = "teal").value,
        sky = animateColorAsState(target.sky, spec, label = "sky").value,
        line = animateColorAsState(target.line, spec, label = "line").value,
        isDark = target.isDark,
        // Not animated: these choose *which* drawing routine runs, and a value
        // half way between two routines is not a half-way appearance, it is a
        // frame of neither.
        finish = target.finish,
        glow = animateColorAsState(target.glow, spec, label = "glow").value,
    )
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            // Window bar colours come from enableEdgeToEdge(); only the icon
            // tint is ours to set. `statusBarColor` is deprecated and a no-op
            // on recent Android.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !colors.isDark
                isAppearanceLightNavigationBars = !colors.isDark
            }
        }
    }

    // Material3 is still present underneath for ripples, text selection and the
    // handful of M3 components we use, so give it a scheme that matches rather
    // than letting its defaults bleed through.
    val material = if (colors.isDark) {
        darkColorScheme(
            primary = colors.teal, background = colors.page,
            surface = colors.surface, onSurface = colors.ink,
            onBackground = colors.ink,
        )
    } else {
        lightColorScheme(
            primary = colors.teal, background = colors.page,
            surface = colors.surface, onSurface = colors.ink,
            onBackground = colors.ink,
        )
    }

    CompositionLocalProvider(
        LocalOtoColors provides colors,
        LocalOtoType provides Typography,
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
