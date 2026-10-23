package app.retriever.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FormatTest {
    private val locale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(locale)

    /** A figure that rounds to 1000.0 is the next unit's 1.0, at every unit. */
    @Test fun aSizeNeverReadsAThousandOfAUnit() {
        Locale.setDefault(Locale.US)
        val sizes = listOf(999L, 1_000L, 999_949L, 999_950L, 999_999L, 999_999_999L, 1_000_000_000L, 41_000_000_000L)
        assertEquals(
            listOf("999 B", "1.0 kB", "999.9 kB", "1.0 MB", "1.0 MB", "1.0 GB", "1.0 GB", "41.0 GB"),
            sizes.map { Format.bytes(it).replace('\u00A0', ' ') },
        )
    }

    /** The cached formatter says a day as the platform's medium date did, and follows a change of language. */
    @Test fun aDateReadsAsTheLocalesMediumDate() {
        val ms = 1_760_000_000_000
        for (l in listOf(Locale.US, Locale.GERMANY, Locale.JAPAN, Locale.US)) {
            Locale.setDefault(l)
            assertEquals(l.toString(), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)), Format.date(ms))
        }
    }
}
