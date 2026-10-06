package cn.crid.next.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import cn.crid.next.MainActivity
import cn.crid.next.core.Language
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutSettingsEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeAboutIconKeepsItsTintAndCalendarCutoutsInBothThemes() {
        var dark by mutableStateOf(false)
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(dark)) {
                AboutSettingsEntry(UiText(Language.EN)) {}
            }
        }
        for (darkTheme in listOf(false, true)) {
            compose.runOnIdle { dark = darkTheme }
            val node = compose.onNodeWithTag("about_entry_icon", useUnmergedTree = true)
            node.assertIsDisplayed()
            val pixels = node.captureToImage().toPixelMap()
            fun sample(x: Int, y: Int): Color = pixels[
                (x * pixels.width / 108).coerceIn(0, pixels.width - 1),
                (y * pixels.height / 108).coerceIn(0, pixels.height - 1),
            ]
            val scheme = cridColorScheme(darkTheme)
            fun assertColor(label: String, expected: Color, actual: Color) {
                assertEquals("$label red", expected.red, actual.red, .025f)
                assertEquals("$label green", expected.green, actual.green, .025f)
                assertEquals("$label blue", expected.blue, actual.blue, .025f)
            }
            assertColor("Calendar header uses the theme tint", scheme.primary, sample(54, 38))
            assertColor("Calendar frame remains visible", scheme.primary, sample(35, 60))
            for ((x, y) in listOf(42 to 53, 42 to 68, 54 to 61, 66 to 56, 66 to 71)) {
                assertColor("Lesson cutout $x/$y shows the icon background", scheme.primaryContainer, sample(x, y))
            }
        }
    }
}
