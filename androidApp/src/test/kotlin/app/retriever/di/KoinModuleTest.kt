package app.retriever.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkerParameters
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.Platform
import app.retriever.nav.BrowseRoute
import app.retriever.nav.DocRoute
import app.retriever.nav.PlayerRoute
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.test.verify.verify
import java.net.URI
import kotlin.test.assertTrue

class KoinModuleTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionResolves() {
        // Not definitions: Context is androidContext(), SavedStateHandle comes with each ViewModel, a route is a viewer's parameter, WorkerParameters with each worker, and verify reads the
        // constructors of what a definition returns (File, DeviceProbe, Store, Retriever), whose arguments the module builds inline.
        appModule.verify(
            extraTypes = listOf(
                Context::class, SavedStateHandle::class, Function1::class, Function2::class, Boolean::class, URI::class,
                Platform::class, SqlDriver::class, DemoParts::class, WorkerParameters::class, BrowseRoute::class, DocRoute::class,
                PlayerRoute::class,
            ),
        )
    }

    /** verify() can't see a qualifier that was still null when the module registered; the debug app crashed on launch. */
    @OptIn(KoinInternalApi::class)
    @Test
    fun qualifiedDefinitionsRegisterUnderTheirQualifier() {
        assertTrue(appModule.mappings.keys.any { PlatformReady.value in it }, "PlatformReady missing from ${appModule.mappings.keys}")
    }
}
