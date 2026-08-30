package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.ClientRequest
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrActionsTest {
    @Test
    fun `every action emits the exact stock Herdr 0_8 method and params once`() = runBlocking {
        val cases = listOf(
            ActionCase("workspace.create", "{\"focus\":true}") { createWorkspace("work") },
            ActionCase("workspace.create", "{\"cwd\":\"/repo\",\"label\":\"Space\",\"env\":{\"A\":\"B\"},\"focus\":true}") {
                createWorkspace("work", "/repo", "Space", mapOf("A" to "B"))
            },
            ActionCase("workspace.focus", "{\"workspace_id\":\"w1\"}") { focusWorkspace("work", "w1") },
            ActionCase("workspace.rename", "{\"workspace_id\":\"w1\",\"label\":\"Renamed\"}") { renameWorkspace("work", "w1", "Renamed") },
            ActionCase("workspace.close", "{\"workspace_id\":\"w1\"}") { closeWorkspace("work", "w1") },
            ActionCase("tab.create", "{\"focus\":true}") { createTab("work") },
            ActionCase("tab.create", "{\"workspace_id\":\"w1\",\"cwd\":\"/repo\",\"label\":\"Tab\",\"env\":{\"A\":\"B\"},\"focus\":true}") {
                createTab("work", "w1", "/repo", "Tab", mapOf("A" to "B"))
            },
            ActionCase("tab.focus", "{\"tab_id\":\"t1\"}") { focusTab("work", "t1") },
            ActionCase("tab.rename", "{\"tab_id\":\"t1\",\"label\":\"Renamed\"}") { renameTab("work", "t1", "Renamed") },
            ActionCase("tab.close", "{\"tab_id\":\"t1\"}") { closeTab("work", "t1") },
            ActionCase("pane.focus", "{\"pane_id\":\"p1\"}") { focusPane("work", "p1") },
            ActionCase("pane.split", "{\"target_pane_id\":\"p1\",\"direction\":\"right\",\"focus\":true}") {
                splitPane("work", "p1", SplitDirection.Right)
            },
            ActionCase("pane.split", "{\"target_pane_id\":\"p1\",\"direction\":\"down\",\"focus\":true,\"ratio\":0.5,\"cwd\":\"/repo\",\"env\":{\"A\":\"B\"}}") {
                splitPane("work", "p1", SplitDirection.Down, 0.5, "/repo", mapOf("A" to "B"))
            },
            ActionCase("pane.zoom", "{\"mode\":\"toggle\"}") { zoomPane("work", mode = ZoomMode.Toggle) },
            ActionCase("pane.zoom", "{\"pane_id\":\"p1\",\"mode\":\"on\"}") { zoomPane("work", "p1", ZoomMode.On) },
            ActionCase("pane.zoom", "{\"pane_id\":\"p1\",\"mode\":\"off\"}") { zoomPane("work", "p1", ZoomMode.Off) },
            ActionCase("pane.rename", "{\"pane_id\":\"p1\",\"label\":\"Renamed\"}") { renamePane("work", "p1", "Renamed") },
            ActionCase("pane.close", "{\"pane_id\":\"p1\"}") { closePane("work", "p1") },
        )
        val ids = mutableSetOf<String>()

        cases.forEach { case ->
            val captured = mutableListOf<ClientRequest>()
            val actions = HerdrActions { request ->
                captured += request
                ServerMessage.Response(request.id, request.session, buildJsonObject {})
            }

            val result = case.invoke(actions)

            val request = captured.single()
            assertEquals(case.method, ActionOutcome.Succeeded, result)
            assertEquals(case.method, request.method)
            assertEquals(Json.parseToJsonElement(case.params), request.params)
            assertEquals("work", request.session)
            assertFalse(request.params.toString().contains("\"name\""))
            assertTrue("duplicate request id ${request.id}", ids.add(request.id))
        }
    }

    @Test
    fun `actions await one response and never retry uncertain failures`() = runBlocking {
        val response = CompletableDeferred<ServerMessage.Response>()
        var attempts = 0
        val waiting = HerdrActions { request ->
            attempts++
            response.await().copy(id = request.id, session = request.session)
        }
        val call = async { waiting.closePane("work", "p1") }
        yield()
        assertFalse(call.isCompleted)
        assertEquals(1, attempts)
        response.complete(ServerMessage.Response("ignored", "ignored", buildJsonObject {}))
        assertEquals(ActionOutcome.Succeeded, call.await())
        assertEquals(1, attempts)

        val remote = BridgeRemoteException("permission_denied", "denied", "work")
        attempts = 0
        val failing = HerdrActions {
            attempts++
            throw remote
        }
        val thrown = expectFailure<BridgeRemoteException> { failing.renamePane("work", "p1", "name") }
        assertSame(remote, thrown)
        assertEquals(1, attempts)
    }

    @Test
    fun `confirmation required is typed and never retried`() = runBlocking {
        var attempts = 0
        val actions = HerdrActions {
            attempts++
            throw BridgeRemoteException("confirmation_required", "Confirm on the host", "work")
        }

        val result = actions.closeWorkspace("work", "w1")

        assertEquals(ActionOutcome.HostConfirmationRequired("Confirm on the host"), result)
        assertEquals(1, attempts)
    }

    private data class ActionCase(
        val method: String,
        val params: String,
        val invoke: suspend HerdrActions.() -> ActionOutcome,
    )
}
