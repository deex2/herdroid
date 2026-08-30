package dev.herdroid.feature.keys

import android.net.Uri
import dev.herdroid.core.testing.FakeKeyVault
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysViewModelCancellationTest {
    @Test
    fun document_name_lookup_propagates_cancellation() = runBlocking {
        val cancellingDispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean =
                throw CancellationException("document lookup cancelled")

            override fun dispatch(context: CoroutineContext, block: Runnable) =
                error("Cancellation must happen before dispatch")
        }
        val viewModel = KeysViewModel(
            FakeKeyVault(),
            cancellingDispatcher,
            CoroutineScope(Dispatchers.Unconfined),
        )

        val lookup = viewModel.selectDocument(Uri.parse("content://keys/private"))
        lookup.join()

        assertTrue(lookup.isCancelled)
    }
}
