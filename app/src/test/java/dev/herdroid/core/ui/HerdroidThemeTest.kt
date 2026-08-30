package dev.herdroid.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class HerdroidThemeTest {
    @Test
    fun dynamic_action_color_does_not_replace_herdr_semantics() {
        val scheme = herdrDarkColorScheme(Color(0xff123456))

        assertEquals(Color(0xff123456), scheme.primary)
        assertEquals(Color(0xff181825), scheme.background)
        assertEquals(Color(0xff313244), scheme.surfaceVariant)
        assertEquals(Color(0xfff38ba8), scheme.error)
    }
}
