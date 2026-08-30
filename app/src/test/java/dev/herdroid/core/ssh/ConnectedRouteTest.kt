package dev.herdroid.core.ssh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedRouteTest {
    @Test
    fun `route owns commands and sftp then closes target direct jump in reverse`() {
        val events = mutableListOf<String>()
        val target = ProcessClient("target", events)
        val jump = ClosingClient("jump", events)
        val direct = ClosingDirectConnection(jump.connection, events)
        val route = ConnectedRoute(target, jump, direct, Dispatchers.Unconfined)

        route.exec("first")
        route.exec("second")
        route.registerSftp(Closeable { events += "sftp.close" })
        route.close()
        route.close()

        assertEquals(listOf("first", "second"), target.commands)
        assertBefore(events, "second.command.close", "first.command.close")
        assertBefore(events, "first.session.close", "sftp.close")
        assertBefore(events, "sftp.close", "target.close")
        assertBefore(events, "target.close", "direct.close")
        assertBefore(events, "direct.close", "jump.close")
        assertEquals(1, events.count { it == "target.close" })
        assertEquals(1, events.count { it == "direct.close" })
        assertEquals(1, events.count { it == "jump.close" })
    }

    @Test
    fun `process closed by caller is unregistered and closed only once`() {
        val events = mutableListOf<String>()
        val target = ProcessClient("target", events)
        val route = ConnectedRoute(target, null, null, Dispatchers.Unconfined)

        val process = route.exec("once")
        process.close()
        route.close()

        assertEquals(1, events.count { it == "once.command.close" })
        assertEquals(1, events.count { it == "once.session.close" })
    }

    @Test
    fun `closed route rejects new commands before opening a session`() {
        val target = ProcessClient("target", mutableListOf())
        val route = ConnectedRoute(target, null, null, Dispatchers.Unconfined)
        route.close()

        assertThrows(IllegalStateException::class.java) { route.exec("too-late") }
        assertTrue(target.commands.isEmpty())
    }

    @Test
    fun `concurrent route close waits through thrown cleanup and reentrant winner returns`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val targetCloseEntered = CountDownLatch(1)
        val releaseTargetClose = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)
        val secondReturned = CountDownLatch(1)
        val jump = ClosingClient("jump", events)
        val target = object : SSHClient() {
            override fun close() {
                events += "target.close"
                targetCloseEntered.countDown()
                releaseTargetClose.await()
                throw IllegalStateException("target close failed")
            }
        }
        val direct = ClosingDirectConnection(jump.connection, events)
        lateinit var route: ConnectedRoute
        route = ConnectedRoute(target, jump, direct, Dispatchers.Unconfined)
        route.registerSftp(Closeable {
            events += "sftp.close"
            route.close()
            events += "reentrant.returned"
        })

        Thread {
            route.close()
            firstReturned.countDown()
        }.start()
        assertTrue(targetCloseEntered.await(1, TimeUnit.SECONDS))
        Thread {
            route.close()
            secondReturned.countDown()
        }.start()
        val secondReturnedBeforeCleanup = secondReturned.await(100, TimeUnit.MILLISECONDS)
        releaseTargetClose.countDown()

        assertTrue("reentrant close did not return", "reentrant.returned" in events)
        assertTrue(events.indexOf("sftp.close") < events.indexOf("target.close"))
        assertFalse("second close returned before cleanup", secondReturnedBeforeCleanup)
        assertTrue(firstReturned.await(1, TimeUnit.SECONDS))
        assertTrue(secondReturned.await(1, TimeUnit.SECONDS))
        assertBefore(events, "target.close", "direct.close")
        assertBefore(events, "direct.close", "jump.close")
        assertEquals(1, events.count { it == "target.close" })
        assertEquals(1, events.count { it == "direct.close" })
        assertEquals(1, events.count { it == "jump.close" })
    }

    private fun assertBefore(events: List<String>, first: String, second: String) {
        val firstIndex = events.indexOf(first)
        val secondIndex = events.indexOf(second)
        assertTrue("Missing $first in $events", firstIndex >= 0)
        assertTrue("Missing $second in $events", secondIndex >= 0)
        assertTrue("Expected $first before $second in $events", firstIndex < secondIndex)
    }

    private open class ClosingClient(
        private val label: String,
        protected val events: MutableList<String>,
    ) : SSHClient() {
        override fun close() {
            events += "$label.close"
            super.close()
            transport.disconnect()
        }
    }

    private inner class ProcessClient(
        label: String,
        events: MutableList<String>,
    ) : ClosingClient(label, events) {
        val commands = mutableListOf<String>()

        override fun startSession(): Session {
            var commandName = ""
            val command = proxy(Session.Command::class.java) { method, _ ->
                when (method.name) {
                    "getOutputStream" -> RecordingOutputStream("$commandName.stdin.close", events)
                    "getInputStream" -> RecordingInputStream("$commandName.stdout.close", events)
                    "getErrorStream" -> RecordingInputStream("$commandName.stderr.close", events)
                    "getExitStatus" -> 0
                    "isOpen" -> false
                    "close" -> events.add("$commandName.command.close")
                    else -> defaultValue(method.returnType)
                }
            }
            return proxy(Session::class.java) { method, args ->
                when (method.name) {
                    "exec" -> {
                        commandName = args!!.single() as String
                        commands += commandName
                        command
                    }
                    "close" -> events.add("$commandName.session.close")
                    else -> defaultValue(method.returnType)
                }
            }
        }
    }

    private class ClosingDirectConnection(
        connection: Connection,
        private val events: MutableList<String>,
    ) : DirectConnection(connection, "target.example", 22) {
        override fun close() {
            events += "direct.close"
        }
    }

    private class RecordingInputStream(
        private val event: String,
        private val events: MutableList<String>,
    ) : ByteArrayInputStream(byteArrayOf()) {
        override fun close() {
            events += event
            super.close()
        }
    }

    private class RecordingOutputStream(
        private val event: String,
        private val events: MutableList<String>,
    ) : ByteArrayOutputStream() {
        override fun close() {
            events += event
            super.close()
        }
    }

    private fun <T> proxy(type: Class<T>, answer: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            answer(method, args)
        })

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }
}
