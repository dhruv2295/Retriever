package app.retriever.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkerParameters
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.Platform
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify
import java.net.URI

class KoinModuleTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionResolves() {
        // Not definitions: Context is androidContext(), SavedStateHandle comes with each ViewModel, WorkerParameters with each worker, and verify reads the
        // constructors of what a definition returns (File, DeviceProbe, Store, Retriever), whose arguments the module builds inline.
        appModule.verify(
            extraTypes = listOf(
                Context::class, SavedStateHandle::class, Function1::class, Function2::class, Boolean::class, URI::class,
                Platform::class, SqlDriver::class, DemoParts::class, WorkerParameters::class,
            ),
        )
    }
}
