package dev.herdroid.feature.terminal

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdroid.core.ui.HerdroidTheme
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
        } finally {
            harness.close()
        }
    }
}
