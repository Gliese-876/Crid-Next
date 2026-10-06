package cn.crid.next.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import cn.crid.next.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class BottomNavigationInstrumentedTest {
    private val durationScale = object : MotionDurationScale {
        var value = 1f
        override val scaleFactor: Float get() = value
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = durationScale)
    private val destinations = listOf("today" to "Today", "week" to "Timetable",
        "plans" to "Your plans", "settings" to "Settings")

    @After fun restoreClock() {
        compose.mainClock.autoAdvance = true
        durationScale.value = 1f
    }

    @Test fun aSingleCapsuleFollowsThePageSpringAcrossNonAdjacentDestinations() {
        val fixture = install()
        val first = indicator().center.x
        val last = icon("settings").center.x
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_settings").performClick()
        val samples = mutableListOf<Float>()
        repeat(10) {
            compose.mainClock.advanceTimeBy(32)
            assertMatchesPager(fixture)
            samples += indicator().center.x
        }
        assertTrue("The capsule travels through intermediate positions", samples.count { it > first + 2 && it < last - 2 } >= 3)
        val steps = samples.zipWithNext { a, b -> b - a }
        assertTrue("The page spring eases instead of moving at constant speed", steps.max() - steps.min() > (last - first) * .02f)
        compose.mainClock.advanceTimeBy(1_200)
        assertEquals(last, indicator().center.x, 2f)
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.onNodeWithTag("nav_today").assertIsNotSelected()
    }

    @Test fun pageDraggingAndCancellationMoveTheSameCapsuleBackToItsSource() {
        val fixture = install()
        val original = indicator().center
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("navigation_test_pager").performTouchInput {
            down(center)
            moveBy(Offset(-width * .26f, 0f), delayMillis = 160)
        }
        compose.mainClock.advanceTimeByFrame()
        assertMatchesPager(fixture)
        assertTrue("The indicator follows a held drag before the page settles", indicator().center.x > original.x + 4f)
        compose.onNodeWithTag("navigation_test_pager").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1_200)
        assertMatchesPager(fixture)
        assertEquals(original.x, indicator().center.x, 2f)
        compose.onNodeWithTag("nav_today").assertIsSelected()
    }

    @Test fun aTapSelectsItsDestinationBeforeTheScaledPageAnimationSettles() {
        val fixture = install()
        durationScale.value = 5f
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_settings").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        compose.onNodeWithTag("nav_today").assertIsNotSelected()
        assertEquals("The native tab responds while the page is still leaving Today", 0, fixture.pager.settledPage)
        assertTrue(abs(indicator().center.x - icon("settings").center.x) > 2f)
        assertMatchesPager(fixture)
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(3, fixture.pager.settledPage)
        assertMatchesPager(fixture)
    }

    @Test fun aHeldDragSelectsTheNearestPageAndReversesWithoutWaitingForRelease() {
        val fixture = install()
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            compose.runOnIdle { fixture.direction = direction }
            compose.waitForIdle()
            val forward = if (direction == LayoutDirection.Ltr) -1f else 1f
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("navigation_test_pager").performTouchInput {
                down(Offset(if (forward < 0f) width * .85f else width * .15f, center.y))
                moveBy(Offset(forward * width * .65f, 0f), delayMillis = 160)
            }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(0, fixture.pager.settledPage)
            compose.onNodeWithTag("nav_week").assertIsSelected()
            assertMatchesPager(fixture)
            val forwardCenter = indicator().center.x
            compose.onNodeWithTag("navigation_test_pager").performTouchInput {
                moveBy(Offset(-forward * width * .5f, 0f), delayMillis = 160)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("nav_today").assertIsSelected()
            assertMatchesPager(fixture)
            assertTrue("The capsule immediately follows the reversed finger",
                abs(indicator().center.x - icon("today").center.x) < abs(forwardCenter - icon("today").center.x))
            compose.onNodeWithTag("navigation_test_pager").performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(1_200)
            assertEquals(0, fixture.pager.settledPage)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun wrappedLabelsAndRtlKeepTheCapsuleCenteredWithNativeTabSemantics() {
        val fixture = install(fontScale = 2f)
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            compose.runOnIdle { fixture.direction = direction }
            compose.waitForIdle()
            listOf("today", "settings", "week").forEach { route ->
                compose.onNodeWithTag("nav_$route").performClick()
                compose.waitForIdle()
                assertMatchesPager(fixture)
                val target = compose.onNodeWithTag("nav_$route").fetchSemanticsNode()
                assertEquals(Role.Tab, target.config[SemanticsProperties.Role])
                val density = compose.density.density
                assertTrue("Native tab touch area stays at least 48 dp wide", target.boundsInRoot.width >= 48f * density)
                assertTrue("Native tab touch area stays at least 48 dp high", target.boundsInRoot.height >= 48f * density)
                assertEquals(icon(route).center.y, indicator().center.y, 2f)
                compose.onNodeWithTag("nav_$route").assertIsSelected()
                compose.onNodeWithText(destinations.first { it.first == route }.second).assertIsDisplayed()
            }
        }
    }

    @Test fun reversingASelectionAndDisabledAnimationsDoNotLeaveDuplicateIndicators() {
        val fixture = install()
        durationScale.value = 2f
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_settings").performClick()
        compose.mainClock.advanceTimeBy(112)
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        assertMatchesPager(fixture)
        compose.onNodeWithTag("nav_week").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("nav_week").assertIsSelected()
        compose.onNodeWithTag("nav_settings").assertIsNotSelected()
        repeat(8) {
            compose.mainClock.advanceTimeBy(32)
            assertMatchesPager(fixture)
        }
        compose.mainClock.advanceTimeBy(2_400)
        assertEquals(icon("week").center.x, indicator().center.x, 2f)
        durationScale.value = 0f
        compose.onNodeWithTag("nav_settings").performClick()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        assertMatchesPager(fixture)
        assertEquals(icon("settings").center.x, indicator().center.x, 2f)
        compose.onNodeWithTag("nav_settings").assertIsSelected()
    }

    private class Fixture {
        lateinit var pager: PagerState
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var requestedPage by mutableStateOf<Int?>(null)
        var request = 0
        var navigation: Job? = null
    }

    private fun install(fontScale: Float = 1f): Fixture {
        val fixture = Fixture()
        compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides fixture.direction) {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    val pager = rememberPagerState { destinations.size }
                    fixture.pager = pager
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        HorizontalPager(pager, modifier = Modifier.weight(1f).testTag("navigation_test_pager")) { page ->
                            Box(Modifier.fillMaxSize()) { Text("Page $page") }
                        }
                        CridBottomNavigation(destinations, destinations[fixture.requestedPage ?: pager.currentPage].first,
                            pagePosition = { pager.currentPage + pager.currentPageOffsetFraction },
                            onNavigate = { route ->
                                val page = destinations.indexOfFirst { it.first == route }
                                val request = ++fixture.request
                                fixture.requestedPage = page
                                fixture.navigation?.cancel()
                                fixture.navigation = scope.launch {
                                    try { pager.animateScrollToPage(page, animationSpec = AppMotion.pageSpring()) }
                                    finally { if (fixture.request == request) fixture.requestedPage = null }
                                }
                            })
                    }
                }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private fun assertMatchesPager(fixture: Fixture) {
        compose.onAllNodesWithTag("bottom_navigation_indicator", useUnmergedTree = true).assertCountEquals(1)
        val position = (fixture.pager.currentPage + fixture.pager.currentPageOffsetFraction)
            .coerceIn(0f, destinations.lastIndex.toFloat())
        val first = floor(position).toInt()
        val next = (first + 1).coerceAtMost(destinations.lastIndex)
        val start = icon(destinations[first].first).center
        val end = icon(destinations[next].first).center
        val expected = start + (end - start) * (position - first)
        val capsule = indicator()
        assertEquals("Capsule and page share horizontal progress", expected.x, capsule.center.x, 2f)
        assertEquals("Capsule follows actual icon height", expected.y, capsule.center.y, 2f)
        assertTrue(abs(capsule.width / compose.density.density - 64f) < 1f)
        assertTrue(abs(capsule.height / compose.density.density - 32f) < 1f)
    }

    private fun indicator(): Rect = compose.onNodeWithTag("bottom_navigation_indicator", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun icon(route: String): Rect = compose.onNodeWithTag("nav_icon_$route", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot
}
