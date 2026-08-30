package dev.herdroid.feature.keys

import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible

private const val MAX_KEY_DOCUMENT_BYTES = 256 * 1024

fun readKeyDocument(input: InputStream): ByteArray {
    val buffer = ByteArray(MAX_KEY_DOCUMENT_BYTES + 1)
    var size = 0
    try {
        while (true) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) return buffer.copyOf(size)
            size += count
            require(size <= MAX_KEY_DOCUMENT_BYTES) { "Key document is too large" }
        }
    } finally {
        buffer.fill(0)
    }
}

suspend fun readKeyDocument(
    dispatcher: CoroutineDispatcher,
    openInputStream: () -> InputStream?,
): ByteArray = runInterruptible(dispatcher) {
    openInputStream()?.use(::readKeyDocument) ?: error("Unable to read key document")
}
