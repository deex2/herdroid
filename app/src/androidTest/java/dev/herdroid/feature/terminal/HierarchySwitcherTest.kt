package dev.herdroid.feature.terminal

import dev.herdroid.core.ui.HerdroidTheme
import dev.herdroid.core.ui.HerdrColors

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.Pane
import dev.herdroid.core.model.SessionState
import dev.herdroid.core.model.Tab
import dev.herdroid.core.model.Workspace
import dev.herdroid.session.api.TerminalLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

class HierarchySwitcherTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun resetWindow() {
        compose.runOnIdle {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, true)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        if (compose.activity.isImeVisible()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").close()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
        }
    }

    @Test
    fun focused_terminal_tab_exposes_selected_semantics() {
        compose.setContent {
            HerdroidTheme {
                TerminalScreen(
                    routeName = "office",
                    connectionLabel = "Connected",
                    sessionName = "work",
                    session = session(),
                    client = null,
                    onPreviousTab = {},
                    onNextTab = {},
                    onOpenSwitcher = {},
                )
            }
        }

        val selected = compose.onNodeWithText("● Tab one · running").fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Selected) { false }
        assertTrue(selected)
    }

    @Test
    fun hierarchy_keeps_desktop_order_and_exposes_selected_row() {
        compose.setContent {
            HerdroidTheme {
                HierarchySwitcher(sessions = mapOf("work" to session()), selectedSession = "work")
            }
        }

        val tree = compose.onRoot(useUnmergedTree = true).printToString()
        val spaces = tree.indexOf("Text = '[spaces]'")
        val tabs = tree.indexOf("Text = '[tabs]'")
        val panes = tree.indexOf("Text = '[panes & agents]'")
        assertTrue("hierarchy order", spaces >= 0 && spaces < tabs && tabs < panes)
        val selected = compose.onNodeWithContentDescription("Space Space one").fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Selected) { false }
        assertTrue(selected)
    }

    @Test
    fun system_back_dismisses_the_hierarchy_switcher() {
        var dismissed = 0
        compose.setContent {
            HierarchySwitcher(
                sessions = mapOf("work" to session()),
                selectedSession = "work",
                onDismiss = { dismissed++ },
            )
        }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun terminal_switch_uses_the_existing_loading_view() {
        val loading = mutableStateOf(true)
        var focused = 0
        compose.setContent {
            TerminalScreen(
                routeName = "office",
                connectionLabel = "Connected",
                sessionName = "work",
                session = session(),
                client = null,
                onPreviousTab = {},
                onNextTab = {},
                onOpenSwitcher = {},
                onFocusTab = { focused++ },
                loading = loading.value,
            )
        }

        compose.onNodeWithText("Opening terminal…").assertIsDisplayed()
        compose.onNodeWithText("● Tab one · running").assertIsNotDisplayed()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("● Tab one · running").assertIsDisplayed()
        compose.onNodeWithText("● Tab one · running").performClick()
        compose.runOnIdle { assertEquals(1, focused) }
    }

    @Test
    fun chrome_owns_only_tab_and_handle_gestures() {
        var previous = 0
        var next = 0
        var opened = 0
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                TerminalScreen(
                    routeName = "office",
                    connectionLabel = "Connected",
                    sessionName = "work",
                    session = session(),
                    client = harness,
                    onPreviousTab = { previous++ },
                    onNextTab = { next++ },
                    onOpenSwitcher = { opened++ },
                )
            }
            compose.waitForIdle()

            compose.onNodeWithContentDescription("Tab strip").performTouchInput { swipeLeft() }
            compose.runOnIdle {
                assertEquals(0, previous)
                assertEquals(1, next)
            }
            compose.onNodeWithContentDescription("Tab strip").performTouchInput { swipeRight() }
            compose.runOnIdle {
                assertEquals(1, previous)
                assertEquals(1, next)
            }
            compose.onNodeWithContentDescription("Open hierarchy switcher").performTouchInput { swipeUp() }
            val requestsFocus = SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)
            compose.onNode(
                requestsFocus and hasAnyDescendant(requestsFocus),
                useUnmergedTree = true,
            ).performTouchInput { swipeLeft() }

            compose.runOnIdle {
                assertEquals(1, previous)
                assertEquals(1, next)
                assertEquals(1, opened)
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun terminal_surface_enables_keyboard_suggestions() {
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                TerminalSurface(harness) { _, _, _ -> }
            }
            compose.waitForIdle()

            var inputType = InputType.TYPE_NULL
            compose.runOnIdle {
                val editor = EditorInfo()
                compose.activity.textEditor().onCreateInputConnection(editor)
                inputType = editor.inputType
            }

            assertEquals(InputType.TYPE_CLASS_TEXT, inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        } finally {
            harness.close()
        }
    }

    @Test
    fun overflowing_tabs_scroll_without_triggering_tab_navigation() {
        var navigation = 0
        compose.setContent {
            TerminalScreen(
                routeName = "office",
                connectionLabel = "Connected",
                sessionName = "work",
                session = sessionWithManyTabs(),
                client = null,
                onPreviousTab = { navigation++ },
                onNextTab = { navigation++ },
                onOpenSwitcher = {},
            )
        }

        compose.onNodeWithText("Tab 12 long label · idle").assertIsNotDisplayed()
        repeat(12) {
            compose.onNodeWithContentDescription("Scrollable tabs").performTouchInput { swipeLeft() }
        }
        compose.onNodeWithText("Tab 12 long label · idle").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigation) }
    }

    @Test
    fun long_header_keeps_back_action_compact() {
        compose.setContent {
            MaterialTheme {
                TerminalScreen(
                    routeName = "Windows workstation",
                    connectionLabel = "Connected",
                    sessionName = "default",
                    session = session(),
                    client = null,
                    onPreviousTab = {},
                    onNextTab = {},
                    onOpenSwitcher = {},
                    onBack = {},
                )
            }
        }

        val backHeight = compose.onNodeWithContentDescription("Back to connections")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue("back height=$backHeight", backHeight <= 56 * compose.activity.resources.displayMetrics.density)
        val backLeft = compose.onNodeWithContentDescription("Back to connections").fetchSemanticsNode().boundsInRoot.left
        val routeLeft = compose.onNodeWithText("Windows workstation", substring = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue("back=$backLeft route=$routeLeft", backLeft < routeLeft)
    }

    @Test
    fun initial_and_replacement_terminal_attach_share_loading_view() {
        val harness = TerminalClientHarness()
        val client = mutableStateOf<TerminalLease?>(null)
        try {
            compose.setContent {
                TerminalScreen(
                    routeName = "office",
                    connectionLabel = "Connected",
                    sessionName = "work",
                    session = session(),
                    client = client.value,
                    onPreviousTab = {},
                    onNextTab = {},
                    onOpenSwitcher = {},
                )
            }

            compose.onNodeWithText("Opening terminal…").assertIsDisplayed()
            compose.runOnIdle { client.value = harness }
            compose.onNodeWithText("Opening terminal…").assertDoesNotExist()
            compose.runOnIdle { client.value = null }
            compose.onNodeWithText("Opening terminal…").assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    @Test
    fun terminal_toolbar_has_the_approved_key_order() {
        lateinit var emulator: TerminalEmulator
        compose.setContent {
            emulator = remember {
                TerminalEmulatorFactory.create(
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = Color.White,
                    defaultBackground = Color.Black,
                    onKeyboardInput = {},
                )
            }
            MaterialTheme {
                TerminalToolbar(
                    emulator = emulator,
                    modifiers = TerminalModifiers(),
                    selectionActive = false,
                    enabled = true,
                    onToggleSelection = {},
                    onCopy = {},
                    onPaste = {},
                    onPageUp = {},
                    onPageDown = {},
                )
            }
        }

        val expected = listOf(
            "Escape key", "Tab key", "Ctrl modifier", "Alt modifier",
            "Home key", "End key", "Remote page up", "Remote page down",
            "Left arrow", "Up arrow", "Down arrow", "Right arrow",
            "Paste clipboard", "Toggle terminal selection", "Copy terminal selection",
        )
        val toolbar = compose.onNodeWithContentDescription("Scrollable terminal keys")
        val actual = toolbar.fetchSemanticsNode().children.mapNotNull { child ->
            child.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.singleOrNull()
        }
        assertEquals(expected, actual)
        val toolbarHeight = toolbar.fetchSemanticsNode().boundsInRoot.height
        assertTrue(toolbarHeight <= 56 * compose.activity.resources.displayMetrics.density)
        compose.onNodeWithContentDescription("Slash key").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dash key").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hide keyboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show keyboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("Control C").assertDoesNotExist()
        compose.onNodeWithContentDescription("Control D").assertDoesNotExist()
        compose.runOnIdle { (emulator as? AutoCloseable)?.close() }
    }

    @Test
    fun terminal_header_places_styled_hierarchy_beside_scrollable_tabs() {
        compose.setContent {
            TerminalScreen(
                routeName = "office",
                connectionLabel = "Connected",
                sessionName = "work",
                session = sessionWithManyTabs(),
                client = null,
                onPreviousTab = {},
                onNextTab = {},
                onOpenSwitcher = {},
            )
        }

        val route = compose.onNodeWithText("office").fetchSemanticsNode().boundsInRoot
        val hierarchyNode = compose.onNodeWithContentDescription("Open hierarchy switcher").assertIsDisplayed()
        val hierarchy = hierarchyNode.fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithContentDescription("Scrollable tabs").fetchSemanticsNode().boundsInRoot
        val inactiveTab = compose.onNodeWithText("Tab 2 long label", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        compose.onNodeWithText("work › Space one", substring = true).assertIsDisplayed()
        assertTrue("hierarchy follows connection", hierarchy.top >= route.bottom)
        assertTrue("hierarchy precedes tabs", hierarchy.right <= tabs.left)
        assertTrue("hierarchy and tabs share a row", kotlin.math.abs(hierarchy.center.y - tabs.center.y) < 2f)
        assertTrue("hierarchy and tabs share a height", kotlin.math.abs(hierarchy.height - inactiveTab.height) < 2f)
        val screenImage = compose.onRoot().captureToImage().toPixelMap()
        assertEquals(
            HerdrColors.Elevated,
            screenImage[hierarchy.right.toInt() - 20, hierarchy.center.y.toInt()],
        )
        assertEquals(
            HerdrColors.Elevated,
            screenImage[inactiveTab.left.toInt() + 10, inactiveTab.center.y.toInt()],
        )
    }

    @Test
    fun terminal_surface_routes_ime_input_to_replacement_client() {
        val first = TerminalClientHarness()
        val replacement = TerminalClientHarness()
        val client = mutableStateOf<TerminalLease>(first)
        try {
            compose.setContent {
                TerminalSurface(client.value) { _, _, _ -> }
            }
            compose.waitForIdle()
            compose.runOnIdle { client.value = replacement }
            compose.waitForIdle()

            compose.runOnIdle {
                compose.activity.textEditor().onCreateInputConnection(EditorInfo()).apply {
                    commitText("new", 1)
                    sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                }
            }

            compose.waitUntil(5_000) { replacement.stdin.toString().contains("new") }
            assertEquals(0, first.stdin.size())
        } finally {
            first.close()
            replacement.close()
        }
    }

    @Test
    fun terminal_controls_stay_above_the_ime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                TerminalSurface(harness) { _, _, _ -> }
            }

            compose.waitUntil(10_000) { compose.activity.isImeVisible() }
            val imeTop = compose.activity.visibleWindowBounds().bottom
            val controlsBottom = compose.activity.contentTop() +
                compose.onNodeWithContentDescription("Scrollable terminal keys")
                    .fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("controls bottom=$controlsBottom IME top=$imeTop", controlsBottom <= imeTop)
        } finally {
            harness.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, true)
                compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
            }
        }
    }

    @Test
    fun terminal_surface_restores_keyboard_state_after_switcher_closes() {
        val harness = TerminalClientHarness()
        val replacement = TerminalClientHarness()
        val hiddenReplacement = TerminalClientHarness()
        val switcherOpen = mutableStateOf(false)
        val client = mutableStateOf<TerminalLease?>(harness)
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize()) {
                    TerminalScreen(
                        routeName = "office",
                        connectionLabel = "Connected",
                        sessionName = "work",
                        session = session(),
                        client = client.value,
                        onPreviousTab = {},
                        onNextTab = {},
                        onOpenSwitcher = { switcherOpen.value = true },
                        switcherOpen = switcherOpen.value,
                    )
                    if (switcherOpen.value) {
                        HierarchySwitcher(
                            sessions = mapOf("work" to session()),
                            selectedSession = "work",
                            onDismiss = { switcherOpen.value = false },
                        )
                    }
                }
            }

            compose.waitUntil(5_000) { compose.activity.isImeVisible() }
            compose.runOnIdle { switcherOpen.value = true }
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
            compose.onNodeWithContentDescription("Scrollable terminal keys").assertDoesNotExist()

            compose.runOnIdle { client.value = null }
            compose.waitForIdle()
            compose.runOnIdle { client.value = replacement }
            compose.waitForIdle()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
            compose.onNodeWithContentDescription("Scrollable terminal keys").assertDoesNotExist()

            compose.runOnIdle { switcherOpen.value = false }
            compose.waitUntil(5_000) { compose.activity.isImeVisible() }
            compose.onNodeWithContentDescription("Scrollable terminal keys").assertIsDisplayed()

            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").close()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }

            compose.runOnIdle { switcherOpen.value = true }
            compose.onNodeWithContentDescription("Space one actions").performClick()
            compose.onNodeWithText("Rename").performClick()
            compose.onNodeWithContentDescription("Rename field").performClick()
            compose.waitUntil(5_000) { compose.activity.isImeVisible() }

            compose.runOnIdle { client.value = null }
            compose.waitForIdle()
            compose.runOnIdle {
                client.value = hiddenReplacement
                switcherOpen.value = false
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
            compose.onNodeWithContentDescription("Scrollable terminal keys").assertDoesNotExist()
        } finally {
            harness.close()
            replacement.close()
            hiddenReplacement.close()
        }
    }

    @Test
    fun sub_slop_terminal_tap_restores_keyboard_after_space_switch_and_explicit_hide() {
        val first = TerminalClientHarness()
        val replacement = TerminalClientHarness()
        val client = mutableStateOf<TerminalLease?>(first)
        try {
            compose.setContent {
                TerminalScreen(
                    routeName = "office",
                    connectionLabel = "Connected",
                    sessionName = "work",
                    session = session(),
                    client = client.value,
                    onPreviousTab = {},
                    onNextTab = {},
                    onOpenSwitcher = {},
                )
            }

            compose.waitUntil(5_000) { compose.activity.isImeVisible() }
            compose.runOnIdle { client.value = null }
            compose.waitForIdle()
            compose.runOnIdle { client.value = replacement }
            compose.waitUntil(5_000) { compose.activity.isImeVisible() }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("Hide keyboard")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Hide keyboard").performClick()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
            val requestsFocus = SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)
            val terminal = compose.onNode(
                requestsFocus and hasAnyDescendant(requestsFocus),
                useUnmergedTree = true,
            )
            terminal.performTouchInput {
                val start = center
                down(start)
                repeat(8) { step ->
                    moveTo(start + Offset(0f, (step + 1) * 12f))
                    advanceEventTime(16)
                }
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(compose.activity.isImeVisible()) }

            terminal.performTouchInput {
                val left = center - Offset(30f, 0f)
                val right = center + Offset(30f, 0f)
                down(0, left)
                down(1, right)
                moveTo(0, left - Offset(20f, 0f))
                moveTo(1, right + Offset(20f, 0f))
                up(1)
                up(0)
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(compose.activity.isImeVisible()) }

            terminal.performTouchInput {
                down(center)
                advanceEventTime(16)
                updatePointerTo(0, center + Offset(3f, 0f))
                up()
            }

            compose.waitUntil(5_000) { compose.activity.isImeVisible() }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("Scrollable terminal keys")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Scrollable terminal keys").assertIsDisplayed()

            compose.onNodeWithContentDescription("Hide keyboard").performClick()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
            terminal.performTouchInput {
                down(center)
                advanceEventTime(700)
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse(compose.activity.isImeVisible()) }
        } finally {
            first.close()
            replacement.close()
        }
    }

    @Test
    fun switcher_directly_selects_every_level_exposes_creates_and_swipes_down() {
        val selected = mutableListOf<String>()
        val selectedSession = mutableStateOf("work")
        var dismissed = 0
        compose.setContent {
            HerdroidTheme {
                HierarchySwitcher(
                    sessions = mapOf("work" to session(), "other" to session()),
                    selectedSession = selectedSession.value,
                    onSelectSession = { selected += "session:$it"; selectedSession.value = it },
                    onFocusSpace = { session, id -> selected += "space:$session:$id" },
                    onFocusTab = { session, id -> selected += "tab:$session:$id" },
                    onFocusPane = { session, id -> selected += "pane:$session:$id" },
                    onCreateSpace = { selected += "create-space:$it" },
                    onCreateTab = { session, workspace -> selected += "create-tab:$session:$workspace" },
                    onSplitPane = { session, pane, direction -> selected += "split:$session:$pane:${direction.name.lowercase()}" },
                    onRename = { target, name -> selected += "rename:${target.javaClass.simpleName}:$name" },
                    onRequestClose = { selected += "close:${it.label}" },
                    onZoomPane = { session, pane -> selected += "zoom:$session:$pane" },
                    onDismiss = { dismissed++ },
                )
            }
        }

        compose.onNodeWithText("Workspace").assertIsDisplayed()
        compose.onNodeWithText("work session", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close hierarchy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Create").assertIsDisplayed()
        compose.onNodeWithContentDescription("Space icon").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tab icon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Pane icon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Focused").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Active").performScrollTo().assertIsDisplayed()
        val space = compose.onNodeWithContentDescription("Space Space one")
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithContentDescription("Tab Tab one")
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithContentDescription("Pane Pane one")
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue("space=$space tab=$tab pane=$pane", tab.left > space.left && pane.left > space.left)

        compose.onNodeWithText("other").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("other", selectedSession.value) }
        compose.onNodeWithText("Space one", substring = true).performClick()
        compose.onNodeWithText("Tab one", substring = true).performClick()
        compose.onNodeWithText("Pane one", substring = true).performClick()
        compose.onNodeWithContentDescription("Create").performClick()
        compose.onNodeWithContentDescription("Create space").performClick()
        compose.onNodeWithContentDescription("Create").performClick()
        compose.onNodeWithContentDescription("Create tab").performClick()
        compose.onNodeWithContentDescription("Create").performClick()
        compose.onNodeWithContentDescription("Split pane right").performClick()
        compose.onNodeWithContentDescription("Space one actions").performScrollTo().performClick()
        compose.onNodeWithText("Rename").performClick()
        renameTo("Renamed space")
        compose.onNodeWithContentDescription("Space one actions").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Tab one actions").performScrollTo().performClick()
        compose.onNodeWithText("Rename").performClick()
        renameTo("Renamed tab")
        compose.onNodeWithContentDescription("Tab one actions").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Pane one actions").performScrollTo().performClick()
        compose.onNodeWithText("Split right").performClick()
        compose.onNodeWithContentDescription("Pane one actions").performScrollTo().performClick()
        compose.onNodeWithText("Split down").performClick()
        compose.onNodeWithContentDescription("Pane one actions").performScrollTo().performClick()
        compose.onNodeWithText("Zoom / unzoom").performClick()
        compose.onNodeWithContentDescription("Pane one actions").performScrollTo().performClick()
        compose.onNodeWithText("Rename").performClick()
        renameTo("Renamed pane")
        compose.onNodeWithContentDescription("Pane one actions").performScrollTo().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Hierarchy switcher").performTouchInput { swipeDown() }

        compose.runOnIdle {
            val expected = listOf(
                "session:other", "space:other:w1", "tab:other:t1", "pane:other:p1",
                "create-space:other", "create-tab:other:w1", "split:other:p1:right", "split:other:p1:down",
                "rename:Space:Renamed space", "close:Space one", "rename:Tab:Renamed tab", "close:Tab one",
                "zoom:other:p1", "rename:Pane:Renamed pane", "close:Pane one",
            )
            assertTrue("missing=${expected - selected.toSet()} selected=$selected", selected.containsAll(expected))
            assertEquals("swipe-down dismissal", 1, dismissed)
        }
    }

    @Test
    fun switcher_marks_reduced_coverage_and_confirms_or_cancels_close() {
        var closed = 0
        compose.setContent {
            HierarchySwitcher(
                sessions = mapOf("work" to session()),
                selectedSession = "work",
                pendingClose = HierarchyTarget.Pane("work", "p1", "Pane one"),
                onRequestClose = { closed++ },
                onConfirmClose = { closed += 10 },
                onCancelClose = { closed += 100 },
            )
        }

        compose.onNodeWithText("Reduced live coverage; status checks every 5 seconds").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("waiting · reduced live coverage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(100, closed) }

        compose.onNodeWithText("Close Pane one?").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(110, closed) }
    }

    private fun session() = SessionState(
        epoch = "e1",
        workspaces = mapOf("w1" to Workspace("w1", 1, "Space one", true, 1, 1, "t1", AgentStatus.Blocked)),
        tabs = mapOf("t1" to Tab("t1", "w1", 1, "Tab one", true, 1, AgentStatus.Working)),
        panes = mapOf("p1" to Pane("p1", "term", "w1", "t1", true, label = "Pane one", agentStatus = AgentStatus.Blocked)),
        focusedWorkspaceId = "w1",
        focusedTabId = "t1",
        focusedPaneId = "p1",
        uncoveredAgentPaneIds = setOf("p1"),
    )

    private fun sessionWithManyTabs() = session().copy(
        tabs = (1..12).associate { number ->
            "t$number" to Tab(
                "t$number",
                "w1",
                number,
                "Tab $number long label",
                number == 1,
                1,
                AgentStatus.Idle,
            )
        },
    )

    private fun renameTo(value: String) {
        compose.onNodeWithContentDescription("Rename field").performTextClearance()
        compose.onNodeWithContentDescription("Rename field").performTextInput(value)
        compose.onNodeWithText("Rename").performClick()
    }
}

private fun ComponentActivity.textEditor(): View {
    val pending = java.util.ArrayDeque<View>().apply { add(window.decorView) }
    return generateSequence { pending.pollFirst() }
        .onEach { view ->
            if (view is ViewGroup) repeat(view.childCount) { pending.addLast(view.getChildAt(it)) }
        }
        .first(View::onCheckIsTextEditor)
}

private fun ComponentActivity.contentTop(): Int {
    val location = IntArray(2)
    findViewById<android.view.View>(android.R.id.content).getLocationInWindow(location)
    return location[1]
}

private fun ComponentActivity.visibleWindowBounds() = android.graphics.Rect().also {
    window.decorView.getWindowVisibleDisplayFrame(it)
}
