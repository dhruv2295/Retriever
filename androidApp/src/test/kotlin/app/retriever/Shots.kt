package app.retriever

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.retriever.ui.RetrieverTheme
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Call before setContent: text at 1.5x needs a taller window than the phone's so every fixture row is visible. */
fun tallWindowForBigText(fontScale: Float) {
    if (fontScale != 1f) RuntimeEnvironment.setQualifiers("+h1600dp")
}

/** Saves the rendered content as androidApp/build/shots/[name].png (Gradle runs unit tests from the module directory). */
fun ComposeContentTestRule.shot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/shots/$name.png").also { it.parentFile?.mkdirs() }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** The app's own warm palette (not wallpaper colours) in light or dark with text scaled by [fontScale], so a shot shows how a piece reads at large text. */
@Composable
internal fun ShotTheme(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    RetrieverTheme(dark = dark, dynamic = false) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
