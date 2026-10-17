package app.retriever

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.ContentHasher
import app.retriever.core.DeviceMonitor
import app.retriever.core.EmbedderFactory
import app.retriever.core.Platform
import app.retriever.di.DemoParts
import app.retriever.di.appModule
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import org.junit.Test
import java.net.URI

class KoinModuleTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test fun everyDefinitionResolves() {
        // Not definitions: Context is androidContext(), SavedStateHandle comes with each ViewModel, and verify reads the
        // constructors of what a definition returns (File, DeviceProbe, Store, Retriever), whose arguments the module builds inline.
        appModule.verify(
            extraTypes = listOf(
                Context::class, SavedStateHandle::class, Function1::class, Function2::class, Boolean::class, URI::class,
                Platform::class, SqlDriver::class, EmbedderFactory::class, DeviceMonitor::class, ContentHasher::class, DemoParts::class,
            ),
        )
    }
}
