package app.retriever

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import java.io.File

/** Saves the rendered content as androidApp/build/shots/[name].png (Gradle runs unit tests from the module directory). */
fun ComposeContentTestRule.shot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/shots/$name.png").also { it.parentFile?.mkdirs() }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** The stock Material colours in light or dark with text scaled by [fontScale], so a shot shows how a piece reads at large text. */
@Composable
fun ShotTheme(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
