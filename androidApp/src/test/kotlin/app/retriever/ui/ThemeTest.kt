package app.retriever.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.retriever.core.Retriever
import app.retriever.testRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() = stopKoin()

    /** The scheme is a static local, so a new one recomposes every screen; prefs change on every no-match answer. */
    @Test fun onlyTheWallpaperSwitchRebuildsTheColours() {
        val retriever = testRetriever()
        startKoin { modules(module { single<Retriever> { retriever } }) }
        var runs = 0
        rule.setContent {
            RetrieverTheme(dark = false) {
                MaterialTheme.colorScheme
                SideEffect { runs++ }
            }
        }
        rule.waitForIdle()
        assertEquals(1, runs)
        runBlocking { retriever.updatePrefs { it.copy(noMatchSeen = it.noMatchSeen + 1) } }
        rule.waitForIdle()
        assertEquals("an unrelated pref rebuilt the colours", 1, runs)
        runBlocking { retriever.updatePrefs { it.copy(dynamicColor = !it.dynamicColor) } }
        rule.waitForIdle()
        assertEquals(2, runs)
    }
}
