package app.retriever.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import org.koin.compose.koinInject

// Android 12+ follows the wallpaper. Android 11 (minSdk 30) gets these, picked from the icon: golden amber
// on cream, and the same amber on warm charcoal. Contrast (WCAG): light onPrimary/primary 6.9, onSurface/surface
// 15.7, onSurfaceVariant/surface 8.5; dark onPrimary/primary 7.7, onSurface/surface 13.8, onSurfaceVariant/surface 10.4.
private val WarmLight = lightColorScheme(
    primary = Color(0xFF7A5200),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDEA6),
    onPrimaryContainer = Color(0xFF2A1800),
    secondaryContainer = Color(0xFFF1E3C2),
    onSecondaryContainer = Color(0xFF2A2412),
    background = Color(0xFFFAF4E6),
    onBackground = Color(0xFF1E1B13),
    surface = Color(0xFFFAF4E6),
    onSurface = Color(0xFF1E1B13),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4C4639),
    surfaceBright = Color(0xFFFAF4E6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6EFDF),
    surfaceContainer = Color(0xFFF1EAD9),
    surfaceContainerHigh = Color(0xFFEBE4D3),
    surfaceContainerHighest = Color(0xFFE5DECD),
    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFD0C5B4),
)

private val WarmDark = darkColorScheme(
    primary = Color(0xFFF2BE5C),
    onPrimary = Color(0xFF442C00),
    primaryContainer = Color(0xFF5F4100),
    onPrimaryContainer = Color(0xFFFFDEA6),
    secondaryContainer = Color(0xFF473D26),
    onSecondaryContainer = Color(0xFFEBDFC0),
    background = Color(0xFF1B1712),
    onBackground = Color(0xFFEAE1D4),
    surface = Color(0xFF1B1712),
    onSurface = Color(0xFFEAE1D4),
    surfaceVariant = Color(0xFF4C4639),
    onSurfaceVariant = Color(0xFFCFC5B4),
    surfaceBright = Color(0xFF423B34),
    surfaceContainerLowest = Color(0xFF15120D),
    surfaceContainerLow = Color(0xFF211D17),
    surfaceContainer = Color(0xFF26211B),
    surfaceContainerHigh = Color(0xFF312B25),
    surfaceContainerHighest = Color(0xFF3C362F),
    outline = Color(0xFF999080),
    outlineVariant = Color(0xFF4C4639),
)

/**
 * The Wallpaper colours switch in Settings, and nothing else of the prefs: the scheme is a static composition local, so a
 * new one recomposes the whole app, and prefs change on every no-match answer. Shots pass `dynamic` themselves, since
 * they run without Koin.
 */
@Composable
private fun wallpaperColorsOn(retriever: Retriever = koinInject()): Boolean {
    val prefs = retriever.prefs.collectAsStateWithLifecycle()
    // Derived, so the theme is told of a change to this one field only.
    val on by remember(prefs) { derivedStateOf { prefs.value.dynamicColor } }
    return on
}

@Composable
fun RetrieverTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = wallpaperColorsOn(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // A wallpaper change recreates the activity, so a remembered dynamic scheme is never stale.
    val colors: ColorScheme = remember(dark, dynamic, context) {
        when {
            dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            dark -> WarmDark

            else -> WarmLight
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
