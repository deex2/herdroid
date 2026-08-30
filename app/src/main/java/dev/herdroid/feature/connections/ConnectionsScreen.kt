package dev.herdroid.feature.connections

import dev.herdroid.R
import dev.herdroid.core.ui.ActionButton
import dev.herdroid.core.ui.HerdrColors
import dev.herdroid.core.ui.HerdrIconButton
import dev.herdroid.core.ui.HerdrPanel
import dev.herdroid.core.ui.HerdrSectionLabel
import dev.herdroid.core.ui.HerdrStatusChip
import dev.herdroid.core.ui.OutlinedActionButton

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdroid.core.data.LocalDataAvailability
import dev.herdroid.core.model.SavedRouteSummary
import dev.herdroid.core.model.HardwareKeyMetadata
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.session.api.ConnectStage
import dev.herdroid.session.api.ConnectionDiagnostic
import dev.herdroid.session.api.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionsScreen(
    state: ConnectionsUiState,
    onEdit: (Long?) -> Unit,
    onKeys: () -> Unit,
    onTerminal: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onHostKeyReset: (Boolean) -> Unit,
    onInstall: (Boolean) -> Unit,
    onRetry: (Long) -> Unit,
    onDiagnostics: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onDismissOpenMessage: () -> Unit,
    onDuplicate: (Long) -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    if (state.availability == LocalDataAvailability.Initializing) {
        Text("Opening encrypted route storage…")
        return
    }
    if (state.availability == LocalDataAvailability.Unavailable) {
        Text("Encrypted route storage is unavailable.")
        return
    }
    RouteList(
        routes = state.routes,
        connectionState = state.connectionState,
        onAdd = { onEdit(null) },
        onEdit = { onEdit(it) },
        onDuplicate = onDuplicate,
        onDelete = onDelete,
        onConnect = onConnect,
        onOpenTerminal = { (state.connectionState as? ConnectionState.Connected)?.routeId?.let(onTerminal) },
        onDisconnect = onDisconnect,
        onManageKeys = onKeys,
        onDiagnostics = onDiagnostics,
    )
    if (state.showDiagnostics) {
        DiagnosticsDialog(state.diagnostics, onDismiss = onDismissDiagnostics)
    } else {
        ConnectionStatus(
            state = state.connectionState,
            activityBound = state.activityBound,
            onDisconnect = onDisconnect,
            onTrust = onTrust,
            onHostKeyReset = onHostKeyReset,
            onInstall = onInstall,
            onRetry = onRetry,
            onDiagnostics = onDiagnostics,
        )
    }
    state.openMessage?.let {
        AlertDialog(
            onDismissRequest = onDismissOpenMessage,
            title = { Text("Notification target unavailable") },
            text = { Text(it) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismissOpenMessage) { Text("OK") } },
        )
    }
}

@Composable
fun ConnectionEditorScreen(
    state: ConnectionEditorUiState,
    onDraftChange: (RouteDraft) -> Unit,
    onCreateKey: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val current = state.draft ?: return
    RouteEditor(
        draft = current,
        keys = state.keys,
        onDraftChange = onDraftChange,
        onCreateKey = onCreateKey,
        saveError = state.saveError,
        busy = state.loading,
        confirmDelete = state.confirmDelete,
        onSave = onSave,
        onRequestDelete = onRequestDelete,
        onDismissDelete = onDismissDelete,
        onDelete = onDelete,
        onCancel = onCancel,
    )
}

@Composable
private fun RouteList(
    routes: List<SavedRouteSummary>,
    connectionState: ConnectionState,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onDuplicate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onOpenTerminal: () -> Unit,
    onDisconnect: () -> Unit,
    onManageKeys: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val connected = connectionState as? ConnectionState.Connected
    var deleteRoute by remember { mutableStateOf<SavedRouteSummary?>(null) }
    val liveSpaces = connected?.sessions?.values?.sumOf { it.workspaces.size } ?: 0
    val waiting = connected?.sessions?.values?.sumOf { session ->
        session.panes.values.count { it.agentStatus == AgentStatus.Blocked }
    } ?: 0
    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Image(
                    painter = painterResource(R.drawable.herdroid_android_head_mark),
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Herdroid", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Remote workspaces",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onManageKeys,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Manage keys" },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(0.dp),
            ) { Icon(painterResource(R.drawable.ic_key), contentDescription = null) }
        }
        Spacer(Modifier.height(4.dp))
        HerdrPanel(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Diagnostics" }
                .clickable(onClick = onDiagnostics),
            padding = PaddingValues(20.dp),
            spacing = 8.dp,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Systems ready", style = MaterialTheme.typography.titleMedium)
                Text("●", color = if (routes.isEmpty()) HerdrColors.Muted else HerdrColors.Green)
            }
            Text(
                if (routes.isEmpty()) "Add a trusted route to begin." else "${routes.size} trusted route${if (routes.size == 1) "" else "s"} available.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RouteStat(routes.size.toString(), "connections", Modifier.weight(1f))
                RouteStat(liveSpaces.toString(), "live spaces", Modifier.weight(1f))
                RouteStat(waiting.toString(), "agent waiting", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        HerdrSectionLabel("saved connections")
        if (routes.isEmpty()) Text("No connections")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(routes, key = SavedRouteSummary::id) { route ->
                val (status, statusColor) = routeStatus(route.id, connectionState)
                var menuExpanded by remember { mutableStateOf(false) }
                HerdrPanel(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp), spacing = 8.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(route.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        RouteStatus(status, statusColor)
                        Spacer(Modifier.width(6.dp))
                        Box {
                            HerdrIconButton("⋮", "More actions for ${route.name}") { menuExpanded = true }
                            DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit(route.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        menuExpanded = false
                                        onDuplicate(route.id)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuExpanded = false
                                        deleteRoute = route
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        route.target.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    route.jump?.let {
                        Text(
                            "via ${it.label}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RouteChip(if (route.usesHardwareKey) "Hardware key" else "Password")
                        RouteChip(if (route.jump == null) "Direct" else "Jump host")
                        RouteChip("Herdr 0.8")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (connected?.routeId == route.id) {
                            ActionButton("Open terminal", "Open terminal", Modifier.weight(1f), onClick = onOpenTerminal)
                            OutlinedActionButton(
                                "Disconnect",
                                "Disconnect ${route.name}",
                                Modifier.weight(1f),
                                onClick = onDisconnect,
                            )
                        } else {
                            ActionButton("Connect", "Connect ${route.name}", Modifier.fillMaxWidth()) { onConnect(route.id) }
                        }
                    }
                }
            }
        }
        ActionButton("＋ Add connection", "Add connection", Modifier.fillMaxWidth(), onClick = onAdd)
    }
    deleteRoute?.let { route ->
        AlertDialog(
            onDismissRequest = { deleteRoute = null },
            title = { Text("Delete ${route.name}?") },
            text = {
                Text(
                    if (connected?.routeId == route.id) "This disconnects and removes the saved connection."
                    else "This removes the saved connection.",
                )
            },
            confirmButton = {
                ActionButton("Delete", "Confirm delete ${route.name}") {
                    deleteRoute = null
                    onDelete(route.id)
                }
            },
            dismissButton = { OutlinedActionButton("Cancel") { deleteRoute = null } },
        )
    }
}

@Composable
private fun RouteStat(value: String, label: String, modifier: Modifier = Modifier) = Column(modifier) {
    Text(value, style = MaterialTheme.typography.titleMedium)
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
    )
}

@Composable
private fun RouteChip(label: String) = Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = MaterialTheme.shapes.extraLarge,
) {
    Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun RouteStatus(label: String, color: Color) = Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = MaterialTheme.shapes.extraLarge,
) {
    Text(
        "● $label",
        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun routeStatus(routeId: Long, state: ConnectionState): Pair<String, Color> = when (state) {
    is ConnectionState.Connected -> if (state.routeId == routeId) "Ready" to HerdrColors.Green else "Offline" to HerdrColors.Subtext
    is ConnectionState.Connecting -> if (state.routeId == routeId) "Connecting" to HerdrColors.Yellow else "Offline" to HerdrColors.Subtext
    is ConnectionState.Reconnecting -> if (state.routeId == routeId) "Retrying" to HerdrColors.Yellow else "Offline" to HerdrColors.Subtext
    is ConnectionState.Failed -> if (state.routeId == routeId) "Offline" to HerdrColors.Red else "Offline" to HerdrColors.Subtext
    is ConnectionState.NeedsTrust -> if (state.prompt.routeId == routeId) "Trust needed" to HerdrColors.Yellow else "Offline" to HerdrColors.Subtext
    is ConnectionState.NeedsHostKeyReset -> if (state.prompt.routeId == routeId) "Key changed" to HerdrColors.Red else "Offline" to HerdrColors.Subtext
    else -> "Offline" to HerdrColors.Subtext
}

@Composable
private fun RouteEditor(
    draft: RouteDraft,
    keys: List<HardwareKeyMetadata>,
    onDraftChange: (RouteDraft) -> Unit,
    onCreateKey: (Boolean) -> Unit,
    saveError: String?,
    busy: Boolean,
    confirmDelete: Boolean,
    onSave: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().imePadding().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HerdrIconButton("‹", "Back to connections", onClick = onCancel)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(if (draft.id == 0L) "Add connection" else "Edit connection", style = MaterialTheme.typography.titleMedium)
                Text(
                    draft.name.ifBlank { "New SSH route" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            if (draft.id != 0L) HerdrIconButton(
                "⌫",
                "Delete ${draft.name}",
                enabled = !busy,
                onClick = onRequestDelete,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledTextField(
                label = "Connection name",
                description = "Connection name input",
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            EndpointForm(
                title = "Target",
                value = draft.target,
                keys = keys,
                showHerdrPath = true,
                onValueChange = { onDraftChange(draft.copy(target = it)) },
                onCreateKey = { onCreateKey(true) },
            )
            HerdrSectionLabel("Routing")
            HerdrPanel(Modifier.fillMaxWidth().semantics { contentDescription = "Routing group" }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Jump host", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    HerdrStatusChip(if (draft.jump == null) "Not used" else "Enabled")
                }
                Text(
                    if (draft.jump == null) "Connect directly to this target." else "Connect through this host before the target.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (draft.jump == null) {
                    OutlinedActionButton("Add jump host", "Use jump host") {
                        onDraftChange(draft.copy(jump = EndpointDraft()))
                    }
                } else {
                    EndpointFields(
                        title = "Jump",
                        value = requireNotNull(draft.jump),
                        keys = keys,
                        showHerdrPath = false,
                        onValueChange = { onDraftChange(draft.copy(jump = it)) },
                        onCreateKey = { onCreateKey(false) },
                    )
                    OutlinedActionButton("Remove jump host") { onDraftChange(draft.copy(jump = null)) }
                }
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        ActionButton(
            "Save connection",
            "Save connection",
            Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = onSave,
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = onDismissDelete,
        title = { Text("Delete ${draft.name}?") },
        text = { Text("This removes the saved connection. Reusable SSH keys remain in the key manager.") },
        confirmButton = {
            ActionButton(
                "Delete",
                "Confirm delete ${draft.name}",
                enabled = !busy,
                onClick = onDelete,
            )
        },
        dismissButton = { OutlinedActionButton("Cancel", onDismissDelete) },
    )
}

@Composable
private fun EndpointForm(
    title: String,
    value: EndpointDraft,
    keys: List<HardwareKeyMetadata>,
    showHerdrPath: Boolean,
    onValueChange: (EndpointDraft) -> Unit,
    onCreateKey: () -> Unit,
) {
    HerdrSectionLabel(title)
    HerdrPanel(Modifier.fillMaxWidth().semantics { contentDescription = "$title group" }) {
        EndpointFields(title, value, keys, showHerdrPath, onValueChange, onCreateKey)
    }
}

@Composable
private fun EndpointFields(
    title: String,
    value: EndpointDraft,
    keys: List<HardwareKeyMetadata>,
    showHerdrPath: Boolean,
    onValueChange: (EndpointDraft) -> Unit,
    onCreateKey: () -> Unit,
) {
    var choosingKey by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledTextField(
            "Host",
            "$title host input",
            value.hostname,
            { onValueChange(value.copy(hostname = it)) },
            Modifier.weight(1f),
        )
        LabeledTextField(
            "Port",
            "$title port input",
            value.port,
            { onValueChange(value.copy(port = it)) },
            Modifier.width(88.dp),
        )
    }
    LabeledTextField(
        "Username",
        "$title user input",
        value.username,
        { onValueChange(value.copy(username = it)) },
        Modifier.fillMaxWidth(),
    )
    if (showHerdrPath) {
        LabeledTextField(
            "Herdr executable (optional)",
            "$title Herdr executable optional input",
            value.herdrPath,
            { onValueChange(value.copy(herdrPath = it)) },
            Modifier.fillMaxWidth(),
        )
    }
    if (value.keyId == null) {
        LabeledTextField(
            "Password",
            "$title password input",
            value.password,
            { onValueChange(value.copy(password = it)) },
            Modifier.fillMaxWidth(),
            PasswordVisualTransformation(),
        )
        OutlinedActionButton("Use hardware key", "Use $title hardware key", Modifier.fillMaxWidth()) { choosingKey = true }
    } else {
        val key = keys.firstOrNull { it.id == value.keyId }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { choosingKey = true }
                .semantics { contentDescription = "Change $title hardware key" },
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text("◇", Modifier.padding(10.dp), color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        key?.name ?: "Hardware key unavailable",
                        Modifier.semantics {
                            contentDescription = "$title hardware key ${key?.name ?: "unavailable"}"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "ECDSA P-256 · Android Keystore",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedActionButton("Use password", "Use $title password", Modifier.fillMaxWidth()) {
            onValueChange(value.copy(keyId = null))
        }
    }
    if (choosingKey) AlertDialog(
        onDismissRequest = { choosingKey = false },
        title = { Text("Select hardware key for $title") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (keys.isEmpty()) Text("No SSH keys")
                keys.forEach { key ->
                    OutlinedActionButton("Select ${key.name} for $title") {
                        onValueChange(value.withKey(key.id))
                        choosingKey = false
                    }
                }
                OutlinedActionButton("Create key for $title") {
                    choosingKey = false
                    onCreateKey()
                }
            }
        },
        confirmButton = { OutlinedActionButton("Cancel") { choosingKey = false } },
    )
}

@Composable
private fun LabeledTextField(
    label: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) = Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        label,
        Modifier.padding(start = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = description },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun ConnectionStatus(
    state: ConnectionState,
    activityBound: Boolean,
    onDisconnect: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onHostKeyReset: (Boolean) -> Unit,
    onInstall: (Boolean) -> Unit,
    onRetry: (Long) -> Unit,
    onDiagnostics: () -> Unit,
) {
    when (state) {
        ConnectionState.Disconnected -> Unit
        is ConnectionState.Connecting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Connecting") },
            text = {
                StatusWithDiagnostics(
                    connectionStage(state.stage) + activityBoundWarning(state, activityBound),
                    onDiagnostics,
                    showProgress = state.stage == ConnectStage.InstallingBridge,
                )
            },
            confirmButton = { OutlinedActionButton("Disconnect", onDisconnect) },
        )
        is ConnectionState.NeedsTrust -> AlertDialog(
            onDismissRequest = { onTrust(false) },
            title = { Text("Trust ${state.prompt.candidate.hop.name.lowercase()} host key?") },
            text = { Text("Host: ${state.prompt.candidate.hostname}:${state.prompt.candidate.port}\nAlgorithm: ${state.prompt.candidate.algorithm}\nSHA-256: ${state.prompt.candidate.sha256}" + activityBoundWarning(state, activityBound)) },
            confirmButton = { ActionButton("Trust") { onTrust(true) } },
            dismissButton = { OutlinedActionButton("Cancel") { onTrust(false) } },
        )
        is ConnectionState.NeedsHostKeyReset -> AlertDialog(
            onDismissRequest = { onHostKeyReset(false) },
            title = { Text("Saved host key changed") },
            text = {
                Text(
                    "Host: ${state.prompt.actual.hostname}:${state.prompt.actual.port}\n" +
                        "Saved SHA-256: ${state.prompt.expected.sha256}\nPresented SHA-256: ${state.prompt.actual.sha256}\n" +
                        "Verify the new key out of band. Forgetting only removes this exact saved key; you must then trust the new key explicitly." +
                        activityBoundWarning(state, activityBound),
                )
            },
            confirmButton = { ActionButton("Forget saved key") { onHostKeyReset(true) } },
            dismissButton = { OutlinedActionButton("Cancel") { onHostKeyReset(false) } },
        )
        is ConnectionState.NeedsBridgeApproval -> AlertDialog(
            onDismissRequest = { onInstall(false) },
            title = { Text("Install or update Herdroid Bridge?") },
            text = { Text("Route: ${state.preview.routeName}\nOS/arch: ${state.preview.osLabel}/${state.preview.architecture}\nBridge: ${state.preview.bridgeVersion}\nMinimum Herdr: ${state.preview.minimumHerdrVersion}\nSHA-256: ${state.preview.sha256}" + activityBoundWarning(state, activityBound)) },
            confirmButton = { ActionButton("Install") { onInstall(true) } },
            dismissButton = { OutlinedActionButton("Cancel") { onInstall(false) } },
        )
        is ConnectionState.Connected -> Unit
        is ConnectionState.Reconnecting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Reconnecting") },
            text = { StatusWithDiagnostics("Retry attempt ${state.attempt}" + activityBoundWarning(state, activityBound), onDiagnostics) },
            confirmButton = { OutlinedActionButton("Disconnect", onDisconnect) },
        )
        is ConnectionState.Failed -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Connection failed") },
            text = { StatusWithDiagnostics(failureMessage(state) + activityBoundWarning(state, activityBound), onDiagnostics) },
            confirmButton = { ActionButton("Retry") { onRetry(state.routeId) } },
            dismissButton = { OutlinedActionButton("Disconnect", onDisconnect) },
        )
    }
}

@Composable
private fun StatusWithDiagnostics(message: String, onDiagnostics: () -> Unit, showProgress: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showProgress) CircularProgressIndicator()
        Text(message)
        OutlinedActionButton("Diagnostics", onDiagnostics)
    }
}

@Composable
private fun DiagnosticsDialog(diagnostics: List<ConnectionDiagnostic>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = formatConnectionDiagnostics(diagnostics)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text)
                Text(
                    "\nAndroid robot modified from work by Google · CC BY 3.0\nCascadia Mono · SIL OFL 1.1",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            ActionButton("Copy", "Copy diagnostics") {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(diagnosticClip(text))
            }
        },
        dismissButton = { OutlinedActionButton("Close", onDismiss) },
    )
}

internal fun formatConnectionDiagnostics(diagnostics: List<ConnectionDiagnostic>): String {
    if (diagnostics.isEmpty()) return "No connection diagnostics yet."
    val timestamps = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return diagnostics.joinToString("\n") { "${timestamps.format(Date(it.timestampMillis))}  ${it.message}" }
}

internal fun diagnosticClip(text: String): ClipData =
    ClipData.newPlainText("Herdroid connection diagnostics", text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }

private fun activityBoundWarning(state: ConnectionState, activityBound: Boolean) =
    if (activityBound && state != ConnectionState.Disconnected) "\nThis connection ends when you leave the app." else ""

private fun connectionStage(stage: ConnectStage) = when (stage) {
    ConnectStage.LoadingRoute -> "Loading saved route"
    ConnectStage.ConnectingSsh -> "Authenticating and verifying SSH hops"
    ConnectStage.DiscoveringHerdr -> "Discovering Herdr"
    ConnectStage.InstallingBridge -> "Installing Herdroid Bridge"
    ConnectStage.StartingBridge -> "Validating companion and bootstrapping sessions"
}

private fun failureMessage(state: ConnectionState.Failed) = when (state.code) {
    "host_key_changed" -> "Saved host key changed. Verify it out of band before updating saved trust."
    "herdr_missing" -> "Herdr was not found. Edit this target and set an absolute Herdr path under Advanced."
    else -> state.message
}
