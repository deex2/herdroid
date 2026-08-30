package dev.herdroid.feature.terminal

import dev.herdroid.core.ui.ActionButton
import dev.herdroid.core.ui.HerdrColors
import dev.herdroid.core.ui.HerdrIconButton
import dev.herdroid.core.ui.HerdrSectionLabel
import dev.herdroid.core.ui.HerdrStatusChip
import dev.herdroid.core.ui.OutlinedActionButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.SplitDirection
import dev.herdroid.core.model.OpenLevel

@Composable
fun HierarchySwitcher(
    sessions: Map<String, SessionState>,
    selectedSession: String?,
    modifier: Modifier = Modifier,
    pendingClose: HierarchyTarget? = null,
    message: String? = null,
    level: OpenLevel = OpenLevel.Panes,
    workspaceId: String? = null,
    tabId: String? = null,
    onSelectSession: (String) -> Unit = {},
    onFocusSpace: (String, String) -> Unit = { _, _ -> },
    onFocusTab: (String, String) -> Unit = { _, _ -> },
    onFocusPane: (String, String) -> Unit = { _, _ -> },
    onCreateSpace: (String) -> Unit = {},
    onCreateTab: (String, String) -> Unit = { _, _ -> },
    onSplitPane: (String, String, SplitDirection) -> Unit = { _, _, _ -> },
    onRename: (HierarchyTarget, String) -> Unit = { _, _ -> },
    onRequestClose: (HierarchyTarget) -> Unit = {},
    onZoomPane: (String, String) -> Unit = { _, _ -> },
    onConfirmClose: () -> Unit = {},
    onCancelClose: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    var rename by remember { mutableStateOf<HierarchyTarget?>(null) }
    var creating by remember { mutableStateOf(false) }
    val sessionName = selectedSession?.takeIf(sessions::containsKey)
    val session = sessionName?.let(sessions::get)
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Hierarchy switcher" }
                    .swipeDown(onDismiss)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Workspace", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${sessionName ?: "No session"} session",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HerdrIconButton("×", "Close hierarchy", onClick = onDismiss)
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sessions.size > 1 || level == OpenLevel.Sessions) {
                    SectionTitle("Sessions")
                    sessions.keys.forEach { name ->
                        HierarchyRow("Session", "◎", name, name == sessionName, "session") {
                            onSelectSession(name)
                        }
                    }
                }
                if (sessionName != null && session != null && level.ordinal >= OpenLevel.Workspaces.ordinal) {
                    SectionTitle("Spaces")
                    session.workspaces.values.sortedBy { it.number }.forEach { workspace ->
                        val target = HierarchyTarget.Space(sessionName, workspace.workspaceId, workspace.label)
                        HierarchyRow(
                            "Space",
                            "▦",
                            workspace.label,
                            workspace.workspaceId == session.focusedWorkspaceId,
                            agentStatusLabel(workspace.agentStatus),
                            "Focused",
                            actions = listOf(
                                "Rename" to { rename = target },
                                "Close" to { onRequestClose(target) },
                            ),
                        ) { onFocusSpace(sessionName, workspace.workspaceId) }
                    }

                    val workspace = workspaceId?.takeIf(session.workspaces::containsKey) ?: session.focusedWorkspaceId
                    if (level.ordinal >= OpenLevel.Tabs.ordinal) {
                        SectionTitle("Tabs")
                        HierarchyIndent {
                            session.tabs.values.filter { it.workspaceId == workspace }.sortedBy { it.number }.forEach { tab ->
                                val target = HierarchyTarget.Tab(sessionName, tab.tabId, tab.label)
                                HierarchyRow(
                                    "Tab",
                                    "▤",
                                    tab.label,
                                    tab.tabId == session.focusedTabId,
                                    agentStatusLabel(tab.agentStatus),
                                    "Active",
                                    actions = listOf(
                                        "Rename" to { rename = target },
                                        "Close" to { onRequestClose(target) },
                                    ),
                                ) { onFocusTab(sessionName, tab.tabId) }
                            }
                        }

                    }

                    if (level.ordinal >= OpenLevel.Panes.ordinal) {
                        val shownTab = tabId?.takeIf { id -> session.tabs[id]?.workspaceId == workspace }
                            ?: session.focusedTabId
                        val focusedPane = session.focusedPaneId
                        SectionTitle("Panes & agents")
                        if (session.uncoveredAgentPaneIds.isNotEmpty()) {
                            Text("Reduced live coverage; status checks every 5 seconds")
                        }
                        HierarchyIndent {
                            session.panes.values.filter { it.tabId == shownTab }.forEach { pane ->
                                val label = pane.label ?: pane.displayAgent ?: pane.title ?: pane.paneId
                                val target = HierarchyTarget.Pane(sessionName, pane.paneId, label)
                                HierarchyRow(
                                    "Pane",
                                    "›_",
                                    label,
                                    pane.paneId == session.focusedPaneId,
                                    paneStatusLabel(pane.agentStatus, pane.paneId in session.uncoveredAgentPaneIds),
                                    actions = listOf(
                                        "Split right" to { onSplitPane(sessionName, pane.paneId, SplitDirection.Right) },
                                        "Split down" to { onSplitPane(sessionName, pane.paneId, SplitDirection.Down) },
                                        "Zoom / unzoom" to { onZoomPane(sessionName, pane.paneId) },
                                        "Rename" to { rename = target },
                                        "Close" to { onRequestClose(target) },
                                    ),
                                ) { onFocusPane(sessionName, pane.paneId) }
                            }
                        }
                    }
                }
            }
            ActionButton(
                "＋ Create",
                "Create",
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 28.dp),
            ) { creating = true }
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Create") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Create space", "Create space") {
                    sessionName?.let(onCreateSpace)
                    creating = false
                }
                OutlinedActionButton("Create tab", "Create tab") {
                    val workspace = workspaceId?.takeIf { session?.workspaces?.containsKey(it) == true }
                        ?: session?.focusedWorkspaceId
                    if (workspace != null) sessionName?.let { onCreateTab(it, workspace) }
                    creating = false
                }
                OutlinedActionButton("Split pane right", "Split pane right") {
                    sessionName?.let { name ->
                        session?.focusedPaneId?.let { pane -> onSplitPane(name, pane, SplitDirection.Right) }
                    }
                    creating = false
                }
            }
        },
        confirmButton = { OutlinedActionButton("Cancel") { creating = false } },
    )

    pendingClose?.let { target ->
        AlertDialog(
            onDismissRequest = onCancelClose,
            title = { Text("Close ${target.label}?") },
            text = { Text("This closes the ${target.javaClass.simpleName.lowercase()} in Herdr.") },
            confirmButton = { TextButton(onClick = onConfirmClose) { Text("Close") } },
            dismissButton = { TextButton(onClick = onCancelClose) { Text("Cancel") } },
        )
    }
    rename?.let { target ->
        var value by remember(target) { mutableStateOf(target.label) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename ${target.label}") },
            text = {
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text("Name") },
                    modifier = Modifier.semantics { contentDescription = "Rename field" },
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(target, value); rename = null }, enabled = value.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text("Herdr action needs attention") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    HerdrSectionLabel(title, Modifier.padding(top = 8.dp))
}

@Composable
private fun HierarchyRow(
    kind: String,
    icon: String,
    label: String,
    focused: Boolean,
    status: String,
    focusedLabel: String? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (focused) HerdrColors.Selected else MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                selected = focused
                contentDescription = "$kind $label"
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Surface(color = HerdrColors.Elevated, shape = MaterialTheme.shapes.large) {
            Box(Modifier.width(36.dp).height(36.dp).semantics { contentDescription = "$kind icon" }, contentAlignment = Alignment.Center) {
                Text(icon, color = if (focused) HerdrColors.Mauve else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Bold)
            if (kind == "Pane") Text(status, color = hierarchyStatusColor(status), style = MaterialTheme.typography.bodySmall)
        }
        if (focused && focusedLabel != null) {
            HerdrStatusChip(focusedLabel, color = HerdrColors.Green)
        } else if (kind != "Pane") {
            Box(Modifier.width(8.dp).height(8.dp).background(hierarchyStatusColor(status), MaterialTheme.shapes.extraLarge))
        }
        if (actions.isNotEmpty()) {
            HerdrIconButton("⋮", "$label actions") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { (name, action) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; action() })
                }
            }
        }
    }
}

@Composable
private fun HierarchyIndent(content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.outline
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(line, Offset(11.dp.toPx(), 0f), Offset(11.dp.toPx(), size.height), 1.dp.toPx()) }
            .padding(start = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private fun hierarchyStatusColor(status: String) = when {
    status.startsWith("running") -> HerdrColors.Teal
    status.startsWith("waiting") -> HerdrColors.Yellow
    status.startsWith("done") -> HerdrColors.Green
    status.startsWith("unknown") -> HerdrColors.Red
    else -> HerdrColors.Subtext
}

private fun Modifier.swipeDown(dismiss: () -> Unit) = pointerInput(dismiss) {
    var distance = 0f
    detectVerticalDragGestures(
        onDragStart = { distance = 0f },
        onDragEnd = { if (distance > 20f) dismiss() },
    ) { change, amount ->
        change.consume()
        distance += amount
    }
}
