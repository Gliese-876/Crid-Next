package cn.crid.next.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AnimatedOverflowMenuInstrumentedTest {
    private val durationScale = object : MotionDurationScale {
        override val scaleFactor: Float = 1f
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @After fun restoreClock() {
        compose.mainClock.autoAdvance = true
    }

    @Test fun initialClosedMenuDoesNotNotifyAndEntryChangesTheVisibleSize() {
        val fixture = install()
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, fixture.closed)
            assertEquals(0, fixture.mounted)
        }

        expand(fixture)
        compose.mainClock.advanceTimeBy(48)
        val early = screenBounds()
        compose.mainClock.advanceTimeBy(48)
        val later = screenBounds()
        compose.mainClock.advanceTimeBy(2_000)
        val settled = screenBounds()
        assertTrue("The menu visibly scales during entry", abs(early.width - later.width) > .25f)
        assertTrue("An entry frame differs from the final surface size", abs(early.width - settled.width) > .25f)
        menu().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, fixture.mounted)
            assertEquals(0, fixture.disposed)
            assertEquals(0, fixture.closed)
        }
    }

    @Test fun dismissalKeepsThePopupUntilExitCompletesAndNotifiesOnce() {
        val fixture = install()
        expand(fixture)
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { fixture.expanded.value = false }
        compose.mainClock.advanceTimeBy(48)
        menu().assertExists()
        compose.runOnIdle {
            assertEquals("The content remains mounted during exit", 0, fixture.disposed)
            assertEquals("Close actions wait until the exit has finished", 0, fixture.closed)
        }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.disposed)
            assertEquals(1, fixture.closed)
            fixture.expanded.value = false
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertEquals("Remaining closed must not repeat the callback", 1, fixture.closed) }
    }

    @Test fun entryAndExitScaleAlongTheSamePathAroundTheActualButtonCenter() {
        val fixture = install()
        val anchor = screenBounds("overflow_anchor")
        assertEquals("The popup anchor excludes its surrounding padding", 48f * compose.density.density, anchor.width, 1f)
        assertEquals(48f * compose.density.density, anchor.height, 1f)
        expand(fixture)
        compose.mainClock.advanceTimeBy(48)
        val enteringEarly = screenBounds()
        compose.mainClock.advanceTimeBy(48)
        val enteringLater = screenBounds()
        compose.mainClock.advanceTimeBy(2_000)
        val settled = screenBounds()

        fun assertAnchorPath(frame: Rect) {
            // A child inside the graphics layer exposes its transformed screen coordinates.
            // Its width gives the scale independently of the animation's spring parameters.
            val scale = frame.width / settled.width
            assertTrue("Sample an intermediate size", scale > .85f && scale < .999f)
            assertEquals("Both dimensions share one scale", scale, frame.height / settled.height, .002f)
            val expectedCenter = anchor.center + (settled.center - anchor.center) * scale
            assertEquals("Horizontal motion shares the button pivot", expectedCenter.x, frame.center.x, .75f)
            assertEquals("Vertical motion shares the button pivot", expectedCenter.y, frame.center.y, .75f)
        }
        assertAnchorPath(enteringEarly)
        assertAnchorPath(enteringLater)
        assertTrue("Entry moves outward from the button",
            (enteringEarly.center - anchor.center).getDistance() < (enteringLater.center - anchor.center).getDistance())

        compose.runOnIdle { fixture.expanded.value = false }
        compose.mainClock.advanceTimeBy(48)
        val exitingEarly = screenBounds()
        compose.mainClock.advanceTimeBy(48)
        val exitingLater = screenBounds()
        assertAnchorPath(exitingEarly)
        assertAnchorPath(exitingLater)
        assertTrue("Exit returns along that path toward the button",
            (exitingLater.center - anchor.center).getDistance() < (exitingEarly.center - anchor.center).getDistance())
        compose.runOnIdle { assertEquals("The whole return finishes before closing", 0, fixture.closed) }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.closed) }
    }

    @Test fun dismissalDuringEntryStillRunsAnExitBeforeDisposingContent() {
        val fixture = install()
        expand(fixture)
        compose.mainClock.advanceTimeBy(32)
        menu().assertExists()
        compose.runOnIdle { fixture.expanded.value = false }
        compose.mainClock.advanceTimeByFrame()
        menu().assertExists()
        compose.runOnIdle {
            assertEquals(0, fixture.disposed)
            assertEquals(0, fixture.closed)
        }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.mounted)
            assertEquals(1, fixture.disposed)
            assertEquals(1, fixture.closed)
        }
    }

    @Test fun reopeningDuringExitRetainsContentAndCancelsTheCloseCallback() {
        val fixture = install()
        expand(fixture)
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { fixture.expanded.value = false }
        compose.mainClock.advanceTimeBy(48)
        menu().assertExists()
        compose.runOnIdle { fixture.expanded.value = true }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Reopening reuses the original popup content", 1, fixture.mounted)
            assertEquals(0, fixture.disposed)
            assertEquals("An interrupted close must not run the pending action", 0, fixture.closed)
            fixture.expanded.value = false
        }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.disposed)
            assertEquals(1, fixture.closed)
        }
    }

    @Test fun nativeBackDismissalWaitsForTheExitAnimation() {
        val fixture = install()
        expand(fixture)
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertIsDisplayed()
        Espresso.pressBack()
        compose.mainClock.advanceTimeBy(48)
        menu().assertExists()
        compose.runOnIdle {
            assertEquals(1, fixture.dismissed)
            assertEquals(0, fixture.closed)
        }
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.closed) }
    }

    @Test fun longRtlMenuAtLargeFontScaleCanReachAndSelectTheLastItem() {
        val fixture = install(itemCount = 60, layoutDirection = LayoutDirection.Rtl, fontScale = 2f)
        expand(fixture)
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("overflow_item_59").performScrollTo()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("overflow_item_59").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(59, fixture.selected) }
        compose.mainClock.advanceTimeBy(48)
        menu().assertExists()
        compose.mainClock.advanceTimeBy(2_000)
        menu().assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, fixture.closed) }
    }

    private class Fixture {
        val expanded = mutableStateOf(false)
        var mounted = 0
        var disposed = 0
        var dismissed = 0
        var closed = 0
        var selected = -1
    }

    private fun install(itemCount: Int = 3, layoutDirection: LayoutDirection = LayoutDirection.Ltr, fontScale: Float = 1f): Fixture {
        val fixture = Fixture()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection,
                LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                        Box(Modifier.size(48.dp).testTag("overflow_anchor")) {
                            Text("More")
                            AnimatedOverflowMenu(
                                expanded = fixture.expanded.value,
                                onDismissRequest = { fixture.dismissed++; fixture.expanded.value = false },
                                onClosed = { fixture.closed++ },
                                modifier = Modifier.testTag("overflow_surface"),
                            ) {
                                DisposableEffect(Unit) {
                                    fixture.mounted++
                                    onDispose { fixture.disposed++ }
                                }
                                repeat(itemCount) { index ->
                                    DropdownMenuItem(
                                        text = { Text("Menu item $index") },
                                        onClick = { fixture.selected = index; fixture.expanded.value = false },
                                        modifier = Modifier.testTag("overflow_item_$index"),
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        return fixture
    }

    private fun expand(fixture: Fixture) {
        compose.runOnIdle { fixture.expanded.value = true }
        compose.mainClock.advanceTimeByFrame()
        menu().assertExists()
    }

    private fun menu() = compose.onNodeWithTag("overflow_surface", useUnmergedTree = true)

    private fun screenBounds(tag: String = "overflow_item_0"): Rect {
        val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        return Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(
            Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }
}
