package dev.herdroid.feature.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdroid.core.ui.HerdroidTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TerminalRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun resetWindow() {
        compose.runOnIdle {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, true)
            compose.activity.window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        if (compose.activity.isImeVisible()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").close()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
        }
    }

    @Test
    fun systemBackThenTerminalTapRestoresKeyboardInput() {
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                HerdroidTheme {
                    TerminalScreen(
                        routeName = "test",
                        connectionLabel = "Connected",
                        sessionName = null,
                        session = null,
                        client = harness,
                        onPreviousTab = {},
                        onNextTab = {},
                        onOpenSwitcher = {},
                    )
                }
            }
            val requestsFocus = SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)
            val terminal = compose.onNode(
                requestsFocus and hasAnyDescendant(requestsFocus),
                useUnmergedTree = true,
            )
            compose.waitUntil(10_000) { compose.activity.isImeVisible() }
            repeat(5) { cycle ->
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").use { stream ->
                        java.io.FileInputStream(stream.fileDescriptor).readBytes()
                    }
                compose.waitUntil(5_000) { !compose.activity.isImeVisible() }
                terminal.performTouchInput { click() }
                compose.waitUntil(5_000) { compose.activity.isImeVisible() }
                compose.runOnIdle {
                    val editor = requireNotNull(compose.activity.currentFocus)
                    assertTrue("cycle $cycle: terminal lost editor focus", editor.onCheckIsTextEditor())
                    assertTrue(
                        "cycle $cycle: IME is not connected to the terminal",
                        editor.context.getSystemService(InputMethodManager::class.java)?.isActive(editor) == true,
                    )
                    editor.onCreateInputConnection(EditorInfo()).apply {
                        commitText("probe$cycle", 1)
                        sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    }
                }
                compose.waitUntil(5_000) { harness.stdin.toString().contains("probe$cycle") }
                compose.runOnIdle {
                    assertEquals("probe$cycle\r", harness.stdin.toString())
                    harness.stdin.reset()
                }
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun terminalRouteTracksSystemAndExplicitKeyboardVisibility() {
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                HerdroidTheme {
                    TerminalScreen(
                        routeName = "office",
                        connectionLabel = "Connected",
                        sessionName = null,
                        session = null,
                        client = harness,
                        onPreviousTab = {},
                        onNextTab = {},
                        onOpenSwitcher = {},
                    )
                }
            }
            fun keyBarVisible() = compose
                .onAllNodes(hasContentDescription("Scrollable terminal keys"))
                .fetchSemanticsNodes()
                .isNotEmpty()

            compose.waitUntil(10_000) { compose.activity.isImeVisible() && keyBarVisible() }
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent ${KeyEvent.KEYCODE_BACK}").close()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() && !keyBarVisible() }

            compose.onNodeWithContentDescription("Show keyboard").performClick()
            compose.waitUntil(5_000) { compose.activity.isImeVisible() && keyBarVisible() }
            compose.onNodeWithContentDescription("Hide keyboard").performClick()
            compose.waitUntil(5_000) { !compose.activity.isImeVisible() && !keyBarVisible() }
            compose.runOnIdle { assertTrue(compose.activity.currentFocus?.onCheckIsTextEditor() == true) }
            compose.onNodeWithContentDescription("Show keyboard").performClick()
            compose.waitUntil(5_000) { compose.activity.isImeVisible() && keyBarVisible() }
        } finally {
            harness.close()
        }
    }
}
