package cn.crid.next.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class CourseDrawingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun highlightRedrawsAndRestoresPixelsWithoutRecomposingCourseContent() {
        val highlight = mutableFloatStateOf(0f)
        val palette = CourseTilePalette(Color(0xffcedcea), Color.Black, Color(0xff2864a0), Color.Transparent)
        var compositions = 0
        compose.setContent {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                .courseTileBackground(palette, highlight, 8.dp, outOfWeek = false, holiday = false)
                .testTag("course")) {
                SideEffect { compositions++ }
            }
        }
        fun centerPixel(): Int {
            val bitmap = compose.onNodeWithTag("course").captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
        val original = centerPixel()
        val initialCompositions = compositions
        for (value in listOf(.25f, .5f, 1f)) {
            compose.runOnIdle { highlight.floatValue = value }
            assertNotEquals("The highlight still has to render", original, centerPixel())
            compose.runOnIdle { assertEquals(initialCompositions, compositions) }
        }
        compose.runOnIdle { highlight.floatValue = 0f }
        assertEquals(original, centerPixel())
        compose.runOnIdle { assertEquals(initialCompositions, compositions) }
    }
}
