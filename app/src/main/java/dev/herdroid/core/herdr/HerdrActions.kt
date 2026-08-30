package dev.herdroid.core.herdr

import dev.herdroid.core.herdr.wire.ClientRequest
import dev.herdroid.core.herdr.wire.ServerMessage
import dev.herdroid.core.herdr.wire.toWire
import dev.herdroid.core.model.ActionOutcome
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.ZoomMode
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HerdrActions internal constructor(
    private val send: suspend (ClientRequest) -> ServerMessage.Response,
) {
    constructor(bridge: BridgeClient) : this(bridge::request)

    suspend fun createWorkspace(
        session: String,
        cwd: String? = null,
        label: String? = null,
        env: Map<String, String> = emptyMap(),
    ) = request(session, "workspace.create", createParams(cwd, label, env))

    suspend fun focusWorkspace(session: String, workspaceId: String) =
        request(session, "workspace.focus", idParams("workspace_id", workspaceId))

    suspend fun renameWorkspace(session: String, workspaceId: String, label: String) =
        request(session, "workspace.rename", renameParams("workspace_id", workspaceId, label))

    suspend fun closeWorkspace(session: String, workspaceId: String) =
        request(session, "workspace.close", idParams("workspace_id", workspaceId))

    suspend fun createTab(
        session: String,
        workspaceId: String? = null,
        cwd: String? = null,
        label: String? = null,
        env: Map<String, String> = emptyMap(),
    ) = request(
        session,
        "tab.create",
        buildJsonObject {
            workspaceId?.let { put("workspace_id", it) }
            cwd?.let { put("cwd", it) }
            label?.let { put("label", it) }
            putEnv(env)
            put("focus", true)
        },
    )

    suspend fun focusTab(session: String, tabId: String) =
        request(session, "tab.focus", idParams("tab_id", tabId))

    suspend fun renameTab(session: String, tabId: String, label: String) =
        request(session, "tab.rename", renameParams("tab_id", tabId, label))

    suspend fun closeTab(session: String, tabId: String) =
        request(session, "tab.close", idParams("tab_id", tabId))

    suspend fun focusPane(session: String, paneId: String) =
        request(session, "pane.focus", idParams("pane_id", paneId))

    suspend fun splitPane(
        session: String,
        paneId: String,
        direction: SplitDirection,
        ratio: Double? = null,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
    ) = request(
        session,
        "pane.split",
        buildJsonObject {
            put("target_pane_id", paneId)
            put("direction", direction.toWire())
            put("focus", true)
            ratio?.let { put("ratio", it) }
            cwd?.let { put("cwd", it) }
            putEnv(env)
        },
    )

    suspend fun zoomPane(session: String, paneId: String? = null, mode: ZoomMode) =
        request(
            session,
            "pane.zoom",
            buildJsonObject {
                paneId?.let { put("pane_id", it) }
                put("mode", mode.toWire())
            },
        )

    suspend fun renamePane(session: String, paneId: String, label: String) =
        request(session, "pane.rename", renameParams("pane_id", paneId, label))

    suspend fun closePane(session: String, paneId: String) =
        request(session, "pane.close", idParams("pane_id", paneId))

    private suspend fun request(session: String, method: String, params: JsonElement): ActionOutcome = try {
        send(ClientRequest(UUID.randomUUID().toString(), session, method, params))
        ActionOutcome.Succeeded
    } catch (failure: BridgeRemoteException) {
        if (failure.code == "confirmation_required") {
            ActionOutcome.HostConfirmationRequired(failure.detail)
        } else {
            throw failure
        }
    }

    private fun createParams(cwd: String?, label: String?, env: Map<String, String>) = buildJsonObject {
        cwd?.let { put("cwd", it) }
        label?.let { put("label", it) }
        putEnv(env)
        put("focus", true)
    }

    private fun idParams(key: String, id: String) = buildJsonObject { put(key, id) }

    private fun renameParams(key: String, id: String, label: String) = buildJsonObject {
        put(key, id)
        put("label", label)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEnv(env: Map<String, String>) {
        if (env.isNotEmpty()) putJsonObject("env") { env.forEach { (key, value) -> put(key, value) } }
    }
}
