package cn.crid.next.ui

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.core.AppState
import cn.crid.next.core.Language
import cn.crid.next.core.Settings
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the real CridApp wiring rather than installing a replacement pager or dialog. */
class MotionIntegrationInstrumentedTest {
    private val durationScale = object : MotionDurationScale {
        var value = 1f
        override val scaleFactor: Float get() = value
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = durationScale)
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        val semester = Defaults.semester().copy(name = "Motion integration term")
        runBlocking {
            repository.update {
                AppState(semesters = listOf(semester), selectedSemesterId = semester.id,
                    settings = Settings(language = Language.EN, holidaysEnabled = false))
            }
        }
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
    }

    @After fun restore() {
        durationScale.value = 1f
        compose.mainClock.autoAdvance = true
        if (::repository.isInitialized && ::previous.isInitialized) {
            runBlocking { repository.update { previous } }
        }
    }

    @Test fun plansPrimaryImportExpandsFromItsButtonAndReturnsToIt() {
        assertImportOrigin("plans_import")
    }

    @Test fun plansEmptyTimetableImportExpandsFromItsOwnButtonAndReturnsToIt() {
        assertImportOrigin("plans_empty_import")
    }

    @Test fun actualBottomNavigationSelectsATappedDestinationBeforeThePageSettles() {
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("nav_week").assertIsSelected()
        val sourceX = screenBounds("page_week").left
        durationScale.value = 5f
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_plans").performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }

        assertSelected("plans")
        // The active heading follows settledPage. Selection must already reflect the tap.
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        val movement = sourceX - screenBounds("page_week").left
        assertTrue("The real outgoing page must be moving", movement > 1f)
        assertTrue("The outgoing page must not have finished its slide",
            movement < screenBounds("main_pager").width * .9f)

        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        assertSelected("plans")
    }

    @Test fun heldDragTakesOverATapAndReversesSelectionBeforeRelease() {
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        durationScale.value = 5f
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_plans").performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        assertSelected("plans")
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        val viewport = screenBounds("main_pager")
        val startX = viewport.width * .85f
        val touchY = viewport.height * .65f
        val pager = compose.onNodeWithTag("main_pager")
        pager.performTouchInput { down(Offset(startX, touchY)) }
        try {
            // First take over the pending tap with an actual reverse drag, keeping Week nearest.
            pager.performTouchInput {
                moveTo(Offset(viewport.width * .95f, touchY), delayMillis = 160)
            }
            compose.mainClock.advanceTimeByFrame()
            assertSelected("week")
            compose.onNodeWithTag("route_header").assertTextEquals("Timetable")

            // The same finger now passes the half-page threshold without ever lifting.
            pager.performTouchInput {
                moveTo(Offset(viewport.width * .20f, touchY), delayMillis = 200)
            }
            compose.mainClock.advanceTimeByFrame()
            assertSelected("plans")
            compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
            val forwardLeft = screenBounds("page_week").left
            assertTrue("The held drag must pass halfway through the actual pager",
                viewport.left - forwardLeft > viewport.width * .5f)

            pager.performTouchInput {
                moveTo(Offset(viewport.width * .80f, touchY), delayMillis = 200)
            }
            compose.mainClock.advanceTimeByFrame()
            assertSelected("week")
            compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
            assertTrue("The source page follows the reversed finger immediately",
                screenBounds("page_week").left > forwardLeft + viewport.width * .4f)
        } finally {
            pager.performTouchInput { cancel() }
        }
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        assertSelected("week")
    }

    private fun assertImportOrigin(trigger: String) {
        compose.onNodeWithTag("nav_plans").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(trigger).performScrollTo().assertIsDisplayed()
        val source = screenBounds(trigger)
        val saved = repository.state.value
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(trigger).performClick()
        val entering = intermediateImportBounds()
        compose.mainClock.advanceTimeBy(1_200)
        val settled = screenBounds("import_surface")
        val distance = (settled.center - source.center).getDistance()
        assertTrue("The real source must be distinguishable from the window center", distance > 20f)
        assertTrue("$trigger must visibly grow into the full-screen import page", entering.width < settled.width * .8f)
        assertTrue("$trigger must send its captured screen origin to ImportDialog",
            (entering.center - source.center).getDistance() < distance * .8f)

        compose.onNodeWithContentDescription("Close import").performClick()
        val leaving = intermediateImportBounds()
        assertTrue("The import page must shrink back toward $trigger", leaving.width < settled.width * .8f)
        assertTrue("Closing must return toward the launching button",
            (leaving.center - source.center).getDistance() < distance * .8f)
        val entryDirection = entering.center - settled.center
        val returnDirection = leaving.center - settled.center
        assertTrue("Entry and return must approach the same side of the screen",
            entryDirection.x * returnDirection.x + entryDirection.y * returnDirection.y > 0f)
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("import_surface").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(trigger).assertIsDisplayed()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        assertEquals("Opening and closing an import must preserve the saved timetable", saved, repository.state.value)
    }

    private fun intermediateImportBounds(): Rect {
        // Observe an actual in-flight frame; Dialog creation/layout can take different frame counts.
        // A missing origin produces the centered 94% fallback, which must not satisfy this test.
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onNodeWithTag("import_surface").fetchSemanticsNode()
            val bounds = screenBounds("import_surface")
            if (bounds.width / node.size.width.toFloat() in .2f.. .75f) return bounds
        }
        throw AssertionError("No outward/return frame was observed from the actual import button")
    }

    private fun assertSelected(route: String) {
        listOf("today", "week", "plans", "settings").forEach { candidate ->
            val tab = compose.onNodeWithTag("nav_$candidate")
            if (candidate == route) tab.assertIsSelected() else tab.assertIsNotSelected()
        }
    }

    private fun screenBounds(tag: String): Rect {
        val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        // Activity and Dialog have different roots; localToScreen includes the graphics-layer transform
        // without clipping a partly translated page or comparing unrelated root coordinate systems.
        return Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(
            Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }
}
