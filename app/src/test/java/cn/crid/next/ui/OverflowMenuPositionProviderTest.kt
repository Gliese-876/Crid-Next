package cn.crid.next.ui

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverflowMenuPositionProviderTest {
    @Test fun horizontalAlignmentFollowsLayoutDirectionAndVerticalPlacementUsesAvailableSpace() {
        var origin = TransformOrigin.Center
        val provider = OverflowMenuPositionProvider(margin = 8, onOrigin = { origin = it })
        val window = IntSize(400, 800)
        val menu = IntSize(180, 240)
        val cases = listOf(
            Triple(IntRect(180, 100, 220, 140), LayoutDirection.Ltr, IntOffset(40, 140)),
            Triple(IntRect(180, 100, 220, 140), LayoutDirection.Rtl, IntOffset(180, 140)),
            Triple(IntRect(180, 700, 220, 740), LayoutDirection.Ltr, IntOffset(40, 460)),
            Triple(IntRect(180, 700, 220, 740), LayoutDirection.Rtl, IntOffset(180, 460)),
            Triple(IntRect(0, 100, 40, 140), LayoutDirection.Ltr, IntOffset(8, 140)),
            Triple(IntRect(360, 100, 400, 140), LayoutDirection.Rtl, IntOffset(212, 140)),
        )
        cases.forEach { (anchor, direction, expected) ->
            assertEquals("Position for $anchor in $direction", expected,
                provider.calculatePosition(anchor, window, direction, menu))
            assertOriginAtAnchor(anchor, expected, menu, origin)
        }
    }

    @Test fun placementsStayInsideMarginsWhenNeitherVerticalSideHasEnoughSpace() {
        var origin = TransformOrigin.Center
        val provider = OverflowMenuPositionProvider(margin = 8, onOrigin = { origin = it })
        val window = IntSize(300, 300)
        val menu = IntSize(240, 240)
        for (direction in LayoutDirection.entries) {
            for (anchor in listOf(IntRect(120, 130, 160, 170), IntRect(-40, -40, 0, 0), IntRect(300, 300, 340, 340))) {
                val position = provider.calculatePosition(anchor, window, direction, menu)
                assertTrue("The menu keeps the left margin", position.x >= 8)
                assertTrue("The menu keeps the top margin", position.y >= 8)
                assertTrue("The menu keeps the right margin", position.x + menu.width <= window.width - 8)
                assertTrue("The menu keeps the bottom margin", position.y + menu.height <= window.height - 8)
                assertOriginAtAnchor(anchor, position, menu, origin)
            }
        }
    }

    @Test fun narrowWindowsAndZeroSizedMenusKeepFiniteTransformOrigins() {
        val origins = mutableListOf<TransformOrigin>()
        val provider = OverflowMenuPositionProvider(margin = 8, onOrigin = { origins += it })
        val anchor = IntRect(10, 10, 30, 30)
        for (direction in LayoutDirection.entries) for (menu in listOf(IntSize(20, 20), IntSize.Zero)) {
            val position = provider.calculatePosition(anchor, IntSize(40, 40), direction, menu)
            assertTrue(position.x in 8..(32 - menu.width))
            assertTrue(position.y in 8..(32 - menu.height))
            if (menu != IntSize.Zero) assertOriginAtAnchor(anchor, position, menu, origins.last())
        }
        assertEquals(4, origins.size)
        origins.forEach { origin ->
            assertTrue("Horizontal animation origin stays finite", origin.pivotFractionX.isFinite())
            assertTrue("Vertical animation origin stays finite", origin.pivotFractionY.isFinite())
        }
    }

    private fun assertOriginAtAnchor(anchor: IntRect, position: IntOffset, menu: IntSize, origin: TransformOrigin) {
        assertEquals("Horizontal pivot returns to the actual anchor center", anchor.center.x.toFloat(),
            position.x + origin.pivotFractionX * menu.width, .001f)
        assertEquals("Vertical pivot returns to the actual anchor center", anchor.center.y.toFloat(),
            position.y + origin.pivotFractionY * menu.height, .001f)
    }
}
