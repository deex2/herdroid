package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.BridgeSessionDescriptor
import dev.herdroid.core.herdr.wire.ClientRequest
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.herdr.wire.toWire
import dev.herdroid.core.model.RemoteOperatingSystem
import dev.herdroid.core.ssh.RemoteProcess
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class BridgeExpectation(
    val targetOs: RemoteOperatingSystem,
    val targetArch: String,
    val bridgeVersion: String,
    val minHerdrVersion: String,
)

open class BridgeProtocolException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class BridgeTransportException(message: String, cause: Throwable? = null) : BridgeProtocolException(message, cause)

class BridgeRemoteException(
    val code: String,
    val detail: String,
    val session: String?,
) : IllegalStateException(detail)

class BridgeClient private constructor(
    private val process: RemoteProcess,
    private val expectation: BridgeExpectation,
    private val input: InputStream,
    private val ioDispatcher: CoroutineDispatcher,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writer = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ServerMessage.Response>>()
    private val readerStarted = AtomicBoolean()
    private val terminalFailure = AtomicReference<BridgeProtocolException?>()
    private val mutableFailure = MutableStateFlow<BridgeProtocolException?>(null)
    val failure: StateFlow<BridgeProtocolException?> = mutableFailure.asStateFlow()
    private val mutableMessages = MutableSharedFlow<ServerMessage>()
    internal val messages: SharedFlow<ServerMessage> = mutableMessages.onSubscription { startReader() }
    internal lateinit var initialHello: ServerMessage.Hello
        private set
    internal lateinit var initialSessions: List<BridgeSessionDescriptor>
        private set

    internal suspend fun request(request: ClientRequest): ServerMessage.Response {
        require(BridgeIdentifiers.validSession(request.session)) { "Invalid session id" }
        check(mutableMessages.subscriptionCount.value > 0) { "Collect bridge messages before requests" }
        val line = json.encodeToString(ClientRequest.serializer(), request).encodeToByteArray()
        if (line.size > MAX_LINE_BYTES) throw BridgeProtocolException("Bridge request exceeds limit")
        val bytes = line + '\n'.code.toByte()
        val response = CompletableDeferred<ServerMessage.Response>()
        check(pending.putIfAbsent(request.id, response) == null) { "Duplicate bridge request id" }
        try {
            writer.withLock {
                terminalFailure.get()?.let { throw it }
                try {
                    process.stdin.write(bytes)
                    process.stdin.flush()
                } catch (failure: Throwable) {
                    throw fail(
                        if (failure is IOException) BridgeTransportException("Bridge write failed", failure)
                        else BridgeProtocolException("Bridge write failed", failure),
                    )
                }
            }
            return response.await()
        } finally {
            pending.remove(request.id, response)
        }
    }

    override fun close() {
        fail(null)
    }

    private suspend fun initialize() {
        val hello = decode(readLine() ?: throw BridgeTransportException("Bridge ended before Hello")) as? ServerMessage.Hello
            ?: throw BridgeProtocolException("Bridge must start with Hello")
        validateInbound(hello)
        require(hello.protocol == PROTOCOL_VERSION) { "Unsupported bridge protocol" }
        require(hello.targetOs == expectation.targetOs.toWire() && hello.targetArch == expectation.targetArch) {
            "Bridge target mismatch"
        }
        require(hello.bridgeVersion == expectation.bridgeVersion) { "Bridge version mismatch" }
        require(versionAtLeast(hello.herdrVersion, expectation.minHerdrVersion)) { "Herdr version is too old" }
        val sessions = decode(readLine() ?: throw BridgeTransportException("Bridge ended before Sessions")) as? ServerMessage.Sessions
            ?: throw BridgeProtocolException("Bridge must emit Sessions after Hello")
        validateInbound(sessions)
        initialHello = hello
        initialSessions = sessions.sessions
        mutableMessages.emit(hello)
        mutableMessages.emit(sessions)
    }

    private fun startReader() {
        if (terminalFailure.get() != null || !readerStarted.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (terminalFailure.get() == null) {
                    dispatch(decode(readLine() ?: throw BridgeTransportException("Bridge stdout closed")))
                }
            } catch (failure: Throwable) {
                fail(normalize(failure, "Bridge protocol failed"))
            }
        }
    }

    private suspend fun dispatch(message: ServerMessage) {
        validateInbound(message)
        if (message is ServerMessage.Response) pending[message.id]?.complete(message)
        if (message is ServerMessage.Error && message.id != null) {
            pending.remove(message.id)?.completeExceptionally(
                BridgeRemoteException(message.code, message.message, message.session),
            )
        }
        mutableMessages.emit(message)
    }

    private suspend fun readLine(): String? = withContext<String?>(ioDispatcher) {
        val bytes = ByteArrayOutputStream(256)
        while (true) {
            val next = input.read()
            if (next < 0) break
            if (next == '\n'.code) return@withContext decodeUtf8(bytes.toByteArray())
            if (bytes.size() == MAX_LINE_BYTES) throw BridgeProtocolException("Bridge line exceeds limit")
            bytes.write(next)
        }
        if (bytes.size() == 0) null else throw BridgeProtocolException("Bridge stdout ended mid-line")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: Exception) {
        throw BridgeProtocolException("Invalid bridge UTF-8", failure)
    }

    private fun decode(line: String): ServerMessage = try {
        json.decodeFromString(ServerMessage.serializer(), line)
    } catch (failure: Throwable) {
        throw BridgeProtocolException("Invalid bridge message")
    }

    private fun validateInbound(message: ServerMessage) {
        when (message) {
            is ServerMessage.Sessions -> require(message.sessions.all { BridgeIdentifiers.validSession(it.name) }) {
                "Invalid bridge session id"
            }
            is ServerMessage.Response -> {
                require(BridgeIdentifiers.validSession(message.session)) { "Invalid bridge session id" }
                validatePaneIds(message.result)
            }
            is ServerMessage.Error -> message.session?.let {
                require(BridgeIdentifiers.validSession(it)) { "Invalid bridge session id" }
            }
            is ServerMessage.Snapshot -> {
                require(BridgeIdentifiers.validSession(message.session)) { "Invalid bridge session id" }
                validatePaneIds(message.snapshot)
            }
            is ServerMessage.AgentStatus -> {
                require(BridgeIdentifiers.validSession(message.session)) { "Invalid bridge session id" }
                require(BridgeIdentifiers.validPane(message.paneId)) { "Invalid bridge pane id" }
            }
            is ServerMessage.Degraded -> {
                require(BridgeIdentifiers.validSession(message.session)) { "Invalid bridge session id" }
                require(message.uncoveredPaneIds.all(BridgeIdentifiers::validPane)) { "Invalid bridge pane id" }
            }
            else -> Unit
        }
    }

    private fun validatePaneIds(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                when {
                    key == "pane_id" || key.endsWith("_pane_id") -> requirePaneId(value)
                    key == "pane_ids" || key.endsWith("_pane_ids") -> {
                        require(value is JsonArray) { "Invalid bridge pane ids" }
                        value.forEach(::requirePaneId)
                    }
                    else -> validatePaneIds(value)
                }
            }
            is JsonArray -> element.forEach(::validatePaneIds)
            else -> Unit
        }
    }

    private fun requirePaneId(value: JsonElement) {
        require(value is JsonPrimitive && value.isString && BridgeIdentifiers.validPane(value.content)) {
            "Invalid bridge pane id"
        }
    }

    private fun fail(cause: Throwable?): BridgeProtocolException {
        val failure = cause?.let { normalize(it, "Bridge protocol failed") }
            ?: BridgeProtocolException("Bridge closed")
        if (!terminalFailure.compareAndSet(null, failure)) return requireNotNull(terminalFailure.get())
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        scope.cancel()
        process.close()
        mutableFailure.value = failure
        return failure
    }

    private fun normalize(failure: Throwable, message: String) = when (failure) {
        is BridgeProtocolException -> failure
        is IOException -> BridgeTransportException("Bridge transport failed", failure)
        else -> BridgeProtocolException(message, failure)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_LINE_BYTES = 1_048_576
        private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; encodeDefaults = true }

        suspend fun start(
            process: RemoteProcess,
            expectation: BridgeExpectation,
            ioDispatcher: CoroutineDispatcher,
            startupTimeoutMillis: Long = STARTUP_TIMEOUT_MILLIS,
        ): BridgeClient {
            require(startupTimeoutMillis > 0) { "startupTimeoutMillis must be positive" }
            val client = BridgeClient(process, expectation, process.takeStdout(), ioDispatcher)
            val initialization = client.scope.async { client.initialize() }
            try {
                withTimeout(startupTimeoutMillis) { initialization.await() }
                return client
            } catch (failure: TimeoutCancellationException) {
                throw client.fail(BridgeTransportException("Bridge startup timed out", failure))
            } catch (failure: CancellationException) {
                client.scope.cancel()
                process.close()
                throw failure
            } catch (failure: Throwable) {
                throw client.fail(client.normalize(failure, "Bridge rejected"))
            }
        }

        private const val STARTUP_TIMEOUT_MILLIS = 15_000L

        private fun versionAtLeast(actual: String, minimum: String): Boolean {
            val left: List<Int> = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: return false }
            val right: List<Int> = minimum.split('.').map { it.toIntOrNull() ?: return false }
            val difference = left.indices.firstOrNull { it !in right.indices || left[it] != right[it] }
            return if (difference == null) left.size >= right.size else difference in right.indices && left[difference] > right[difference]
        }
    }
}
