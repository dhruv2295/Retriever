package app.retriever.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.retriever.ShotTheme
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.shot
import app.retriever.tallWindowForBigText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Documents have no system thumbnailer on Android: each shows a type card, sized to every surface, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DocCardTest {
    @get:Rule val rule = createComposeRule()

    private val names = listOf(
        "Letter.docx", "Budget.xlsx", "Deck.pptx", "Expenses.csv", "Notes.txt", "Settings.json",
        "Feed.xml", "Config.yaml", "Page.html", "Archive.zip", "README",
    )
    private val files = names.map { SourceFile("demo:doc/$it", it, "Documents", "application/octet-stream", Kind.DOC, 100, 1_760_000_000_000) }
    private fun badge(name: String) = name.substringAfterLast('.', "").uppercase()

    private fun tiles(size: Dp, dark: Boolean = false, fontScale: Float = 1f, tall: Dp = size) {
        tallWindowForBigText(fontScale)
        rule.setContent {
            ShotTheme(dark, fontScale) {
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // FileTile is the pile's, Try's and Recently read's tile; ThumbnailImage alone is Home's taller card.
                    for (f in files) if (tall == size) FileTile(f, size) else ThumbnailImage(f, tall, Modifier.size(size, tall))
                }
            }
        }
    }

    @Test fun aGridTileShowsItsTypeAndNothingElse() {
        tiles(84.dp)
        for (name in names.dropLast(1)) rule.onNodeWithTag("badge:${badge(name)}", useUnmergedTree = true).assertIsDisplayed()
        // No file name and no content, and the badge is drawn, not read: the tile's label already says the kind.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
        assertTrue(names.none { rule.onAllNodesWithText(it.substringBefore('.'), substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
    }

    @Test fun noExtensionShowsTheKindIcon() {
        tiles(84.dp)
        rule.onNodeWithTag("badge:", useUnmergedTree = true).assertIsNotDisplayed()
        rule.onAllNodesWithTag("kind-icon", useUnmergedTree = true).fetchSemanticsNodes().let { icons ->
            assertTrue(icons.count { it.layoutInfo.isPlaced } == 1)
        }
    }

    @Test fun aBadgeThatDoesNotFitGivesWayToTheIcon() {
        tiles(48.dp, fontScale = 2f)
        for (name in names.dropLast(1)) rule.onNodeWithTag("badge:${badge(name)}", useUnmergedTree = true).assertIsNotDisplayed()
        assertTrue(rule.onAllNodesWithTag("kind-icon", useUnmergedTree = true).fetchSemanticsNodes().all { it.layoutInfo.isPlaced })
    }

    @Test fun theBadgeReadsOnItsPageInBothThemes() {
        val ratios = mutableMapOf<Boolean, Double>()
        rule.setContent {
            for (dark in listOf(false, true)) {
                ShotTheme(dark) {
                    val c = MaterialTheme.colorScheme
                    ratios[dark] = ColorUtils.calculateContrast(c.onSurfaceVariant.toArgb(), c.surfaceBright.toArgb())
                }
            }
        }
        rule.waitForIdle()
        assertTrue("contrast $ratios", ratios.size == 2 && ratios.values.all { it >= 4.5 })
    }

    @Test fun pileLight() = render("pile", 168.dp, false)

    @Test fun pileDark() = render("pile", 168.dp, true)

    @Test fun gridLight() = render("grid", 84.dp, false)

    @Test fun gridDark() = render("grid", 84.dp, true)

    @Test fun listLight() = render("list", 48.dp, false)

    @Test fun listDark() = render("list", 48.dp, true)

    @Test fun recentLight() = render("recent", 56.dp, false)

    @Test fun recentDark() = render("recent", 56.dp, true)

    @Test fun homeCardLight() = render("home", 112.dp, false, tall = 150.dp)

    @Test fun homeCardDark() = render("home", 112.dp, true, tall = 150.dp)

    @Test fun gridBigText() {
        tiles(84.dp, fontScale = 2f)
        rule.shot("doc_tiles_grid_text2")
    }

    private fun render(surface: String, size: Dp, dark: Boolean, tall: Dp = size) {
        tiles(size, dark, tall = tall)
        rule.shot("doc_tiles_${surface}_${if (dark) "dark" else "light"}")
    }
}
