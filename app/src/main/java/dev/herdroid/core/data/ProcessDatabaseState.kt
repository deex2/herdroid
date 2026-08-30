package dev.herdroid.core.data

import android.content.Context
import dev.herdroid.core.data.db.HerdroidDatabase
import dev.herdroid.core.data.db.LocalDataReady
import dev.herdroid.core.data.db.LocalDataUnavailable
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Singleton
class ProcessDatabaseState private constructor(
    val availability: StateFlow<LocalDataAvailability>,
    private val initialized: CompletableDeferred<Unit>,
) {
    @Volatile internal var database: HerdroidDatabase? = null
        private set

    internal suspend fun awaitInitialized() = initialized.await()

    companion object {
        fun open(context: Context, ioDispatcher: CoroutineDispatcher): ProcessDatabaseState =
            start(ioDispatcher) { onClosed -> HerdroidDatabase.open(context, onClosed) }

        internal fun start(
            ioDispatcher: CoroutineDispatcher,
            open: (() -> Unit) -> dev.herdroid.core.data.db.DatabaseOpenResult,
        ): ProcessDatabaseState {
            val availability = MutableStateFlow<LocalDataAvailability>(LocalDataAvailability.Initializing)
            val initialized = CompletableDeferred<Unit>()
            val state = ProcessDatabaseState(availability, initialized)
            CoroutineScope(SupervisorJob() + ioDispatcher).launch {
                try {
                    when (val result = open { availability.value = LocalDataAvailability.Unavailable }) {
                        is LocalDataReady -> {
                            state.database = result.database
                            availability.value = LocalDataAvailability.Available
                        }
                        LocalDataUnavailable -> availability.value = LocalDataAvailability.Unavailable
                    }
                } catch (_: Exception) {
                    availability.value = LocalDataAvailability.Unavailable
                } catch (_: LinkageError) {
                    availability.value = LocalDataAvailability.Unavailable
                } finally {
                    initialized.complete(Unit)
                }
            }
            return state
        }
    }
}
