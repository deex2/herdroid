package dev.herdroid.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun gradualFingerDragRevealsScrollback() {
        val harness = TerminalClientHarness()
        try {
            compose.setContent {
                TerminalSurface(
                    client = harness,
                    switcherOpen = true,
                    modifier = Modifier.size(320.dp).testTag("scroll-terminal"),
                ) { _, _, _ -> }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                harness.writeInput(buildString {
                    append("\u001b[?25l")
                    repeat(80) { append("\u001b[41mhistory-$it\u001b[0m\r\n") }
                    repeat(32) { append("current-$it\r\n") }
                }.encodeToByteArray())
            }
            compose.waitForIdle()

            val terminal = compose.onNodeWithTag("scroll-terminal")
            assertEquals(0, terminal.captureToImage().redBackgroundPixels())
            terminal.performTouchInput {
                val start = Offset(centerX, height * 0.30f)
                down(start)
                repeat(16) { step ->
                    moveTo(start + Offset(0f, (step + 1) * 12f))
                    advanceEventTime(16)
                }
                up()
            }
            compose.waitForIdle()
            assertTrue(terminal.captureToImage().redBackgroundPixels() > 0)
        } finally {
            harness.close()
        }
    }

    @Test
    fun toolbarModifiersDriveRealTerminalInputAndClearOnlyStickyState() {
        val output = ByteArrayOutputStream()
        val received = CountDownLatch(2)
        val modifiers = TerminalModifiers()
        lateinit var emulator: TerminalEmulator

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            emulator = TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = {
                    output.write(it)
                    received.countDown()
                },
            )
            modifiers.toggleCtrl()
            emulator.dispatchCharacter(modifiers.mask, 'c')
            modifiers.clearTransients()
            assertFalse(modifiers.isCtrlActive())

            modifiers.toggleAlt()
            modifiers.toggleAlt()
            emulator.dispatchKey(modifiers.mask, VTermKey.ESCAPE)
            modifiers.clearTransients()
            assertTrue(modifiers.isAltActive())
        }

        assertTrue(received.await(1, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0x03, 0x1b, 0x1b), output.toByteArray())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (emulator as? AutoCloseable)?.close()
        }
    }
}

private fun ImageBitmap.redBackgroundPixels(): Int {
    val pixels = toPixelMap()
    return (0 until width).sumOf { x ->
        (0 until height).count { y ->
            pixels[x, y].let { it.red > 0.8f && it.green < 0.2f && it.blue < 0.2f }
        }
    }
}
