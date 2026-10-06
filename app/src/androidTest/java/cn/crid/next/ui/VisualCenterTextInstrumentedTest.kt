package cn.crid.next.ui

import android.graphics.Color as PixelColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.crid.next.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class VisualCenterTextInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeButtonsAlignWithChineseAndEnglishInkAtDifferentTypeSizes() {
        val examples = listOf(
            Triple("数学分析", "编辑", 24 to 14),
            Triple("Calculus", "Edit", 24 to 14),
            Triple("上课地点", "查看", 16 to 12),
            Triple("Teaching\nweeks", "View", 12 to 16),
        )
        for (dark in listOf(false, true)) {
            val clicks = mutableListOf<Int>()
            install(dark) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    examples.forEachIndexed { index, (title, action, sizes) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            VisualCenterText(title, alignByVisualCenter(Modifier.weight(1f)).testTag("title-$index"),
                                fontSize = sizes.first.sp, fontWeight = if (sizes.first == 24) FontWeight.Bold else FontWeight.Normal,
                                style = when (sizes.first) {
                                    24 -> MaterialTheme.typography.headlineSmall
                                    16 -> MaterialTheme.typography.bodyLarge
                                    else -> MaterialTheme.typography.bodySmall
                                })
                            TextButton(onClick = { clicks += index },
                                modifier = alignByVisualCenter(Modifier.heightIn(min = 48.dp)).testTag("action-$index")) {
                                VisualCenterText(action, Modifier.testTag("action-label-$index"), fontSize = sizes.second.sp)
                            }
                        }
                    }
                }
            }
            examples.indices.forEach { index ->
                node("action-$index").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                assertEquals("Native button and title use visible glyph centers", inkBounds("title-$index").center.y,
                    inkBounds("action-label-$index").center.y, 1f)
            }
            examples.indices.forEach { node("action-$it").performClick() }
            compose.runOnIdle { assertEquals(examples.indices.toList(), clicks) }
        }
    }

    @Test fun aMultilineColumnPropagatesItsCombinedInkBoundsToAnAdjacentControl() {
        for (dark in listOf(false, true)) {
            install(dark) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(alignByVisualCenter(Modifier.width(180.dp)).testTag("text-group"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        VisualCenterText("课程安排", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        VisualCenterText("Teaching\nweeks", Modifier.testTag("wrapped-label"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Box(alignByVisualCenter(Modifier.size(24.dp)).testTag("control")
                        .background(MaterialTheme.colorScheme.primary))
                }
            }
            val wrapped = textLayout("wrapped-label")
            assertEquals(2, wrapped.lineCount)
            assertEquals("Teaching\nweeks".length, wrapped.getLineEnd(1))
            node("control").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
            assertEquals("A control follows the complete text group's visible center", inkBounds("text-group").center.y,
                node("control").fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
    }

    @Test fun centeredNativeLabelsKeepTheirTouchSizeAndWrappedOrEllipsizedTextInsideTheSlot() {
        for (dark in listOf(false, true)) {
            var clicks = 0
            install(dark) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextButton(onClick = { clicks++ }, modifier = Modifier.width(160.dp).height(48.dp).testTag("button-slot")) {
                        VisualCenterText("继续 Continue", Modifier.centerVisualText().testTag("button-label"))
                    }
                    Box(Modifier.size(48.dp).testTag("date-slot")
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                        VisualCenterText("30", Modifier.centerVisualText().testTag("date-label"), fontSize = 20.sp)
                    }
                    Box(Modifier.width(180.dp).height(96.dp).testTag("multiline-slot"), contentAlignment = Alignment.Center) {
                        VisualCenterText("Teaching\nweeks", Modifier.centerVisualText().testTag("multiline-label"),
                            fontSize = 16.sp, lineHeight = 24.sp)
                    }
                    Box(Modifier.width(100.dp).height(48.dp).testTag("ellipsis-slot"), contentAlignment = Alignment.Center) {
                        VisualCenterText("Teaching weeks with a long room name", Modifier.width(96.dp).centerVisualText().testTag("ellipsis-label"),
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            listOf("button", "date", "ellipsis").forEach { node("$it-slot").assertHeightIsEqualTo(48.dp) }
            node("button-slot").assertWidthIsEqualTo(160.dp)
            node("date-slot").assertWidthIsEqualTo(48.dp)
            listOf("button", "date", "multiline", "ellipsis").forEach { name ->
                val slot = node("$name-slot").fetchSemanticsNode().boundsInRoot
                val ink = inkBounds("$name-label")
                assertEquals("$name label is optically centered in its original slot", slot.center.y, ink.center.y, 1f)
                assertTrue("$name ink stays inside the slot", ink.left >= slot.left && ink.right <= slot.right &&
                    ink.top >= slot.top && ink.bottom <= slot.bottom)
            }
            val multiline = textLayout("multiline-label")
            assertEquals(2, multiline.lineCount)
            assertFalse(multiline.isLineEllipsized(1))
            assertEquals("Teaching\nweeks".length, multiline.getLineEnd(1))
            assertTrue("The narrow example really exercises an ellipsis", textLayout("ellipsis-label").isLineEllipsized(0))
            node("button-slot").performClick()
            compose.runOnIdle { assertEquals(1, clicks) }
        }
    }

    private fun install(dark: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(dark)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.safeDrawingPadding().padding(24.dp)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Independent screenshot oracle: no font metrics or production alignment-line values. */
    private fun inkBounds(tag: String): Rect {
        val text = node(tag)
        val bitmap = text.captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val background = pixels.asSequence().groupingBy { it }.eachCount().maxBy { it.value }.key
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        pixels.forEachIndexed { index, color ->
            val difference = maxOf(abs(PixelColor.red(color) - PixelColor.red(background)),
                abs(PixelColor.green(color) - PixelColor.green(background)), abs(PixelColor.blue(color) - PixelColor.blue(background)))
            if (difference >= 48) {
                left = minOf(left, index % bitmap.width)
                right = maxOf(right, index % bitmap.width)
                top = minOf(top, index / bitmap.width)
                bottom = maxOf(bottom, index / bitmap.width)
            }
        }
        assertTrue("$tag must render visible glyphs", right >= left && bottom >= top)
        val origin = text.fetchSemanticsNode().boundsInRoot.topLeft
        return Rect(origin.x + left, origin.y + top, origin.x + right + 1, origin.y + bottom + 1)
    }
}
