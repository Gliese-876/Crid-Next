package cn.crid.next

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Run unchanged on the phone and tablet devices; all drags use the actual page viewport. */
class NavigationGestureInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        val first = ScheduleEngine.weekStart(LocalDate.now())
        val semester = Defaults.semester().copy(name = "Gesture term", startDate = first.minusWeeks(2).toString(),
            endDate = first.plusWeeks(15).plusDays(6).toString(), weeks = 18)
        val courses = (1..7).flatMap { day ->
            (1..12).map { period ->
                Course(name = "Class $day-$period", lessons = listOf(Lesson(weeks = (1..18).toList(), weekday = day,
                    startPeriod = period, endPeriod = period)))
            }
        }
        val plan = Plan(semesterId = semester.id, name = "Gesture timetable", courses = CourseColors.assign(courses))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false)) } }
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        runBlocking { repository.update { previous } }
    }

    @Test fun partialAndReversedDragsMoveTheWholePageTogetherWithoutVerticalJumps() {
        val viewport = bounds("main_pager")
        val switcher = bounds("week_switcher")
        val dates = weekBounds("week_grid_header")
        val sourcePositions = listOf("page_week", "page_content_week", "route_toolbar", "route_header", "action_add_course", "week_switcher").associateWith(::sourcePosition)
        val navigation = bounds("nav_week")
        // Cross the pager target threshold in both directions, then reverse before releasing.
        listOf(-1f to "plans", 1f to "today").forEach { (direction, neighbor) ->
            compose.mainClock.autoAdvance = false
            val startX = if (direction < 0) viewport.width * .87f else viewport.width * .13f
            val touchY = weekBounds("week_grid_body").center.y - viewport.top
            compose.onNodeWithTag("main_pager").performTouchInput { down(Offset(startX, touchY)) }
            listOf(.18f, .38f, .58f, .24f, .04f).forEach { fraction ->
                compose.onNodeWithTag("main_pager").performTouchInput {
                    moveTo(Offset(startX + direction * viewport.width * fraction, touchY), delayMillis = 120)
                }
                compose.mainClock.advanceTimeByFrame()
                assertEquals("Dragging must not resize the shell", viewport.top, bounds("main_pager").top, .75f)
                assertEquals(viewport.bottom, bounds("main_pager").bottom, .75f)
                assertEquals("The source page must not jump", viewport.top, bounds("page_week").top, .75f)
                assertEquals(switcher.top, bounds("week_switcher").top, .75f)
                assertEquals(dates.top, weekBounds("week_grid_header").top, .75f)
                compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
                val movement = position("page_week").x - sourcePositions.getValue("page_week").x
                if (fraction >= .18f) assertTrue("The drag must actually move the page", abs(movement) > 8f)
                sourcePositions.forEach { (tag, initial) ->
                    val moved = sourcePosition(tag)
                    assertEquals("$tag moves horizontally with its page", movement, moved.x - initial.x, .75f)
                    assertEquals("$tag keeps its vertical position", initial.y, moved.y, .75f)
                }
                assertEquals("Global navigation stays fixed", navigation, bounds("nav_week"))
                if (fraction >= .38f) {
                    assertEquals("The incoming page shares the same viewport", viewport.top, bounds("page_$neighbor").top, .75f)
                    val incomingPage = position("page_$neighbor")
                    val incomingHeader = position("route_header_$neighbor")
                    val incomingContent = position("page_content_$neighbor")
                    assertEquals("The incoming content follows its own heading", incomingPage.x, incomingContent.x, .75f)
                    assertEquals("Both headings retain the same page inset", sourcePositions.getValue("route_header").x - sourcePositions.getValue("page_week").x,
                        incomingHeader.x - incomingPage.x, .75f)
                    compose.onNodeWithTag("route_header_$neighbor").assertTextEquals(if (neighbor == "plans") "Plans" else "Today")
                    compose.onNodeWithTag("page_$neighbor").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
                    compose.onAllNodesWithTag("route_header").assertCountEquals(1)
                }
            }
            compose.onNodeWithTag("main_pager").performTouchInput { advanceEventTime(200); up() }
            compose.mainClock.advanceTimeBy(2_000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        }
    }

    @Test fun toolbarSwipesNavigateAndDestinationActionsRemainUsable() {
        val before = weekLabel()
        compose.onNodeWithTag("route_toolbar").performTouchInput { swipeLeft(durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        compose.onNodeWithTag("action_add_course").assertDoesNotExist()
        compose.onNodeWithTag("action_import").performClick()
        compose.onNodeWithContentDescription("Close import").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        compose.onNodeWithTag("route_toolbar").performTouchInput { swipeRight(durationMillis = 500) }
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        assertEquals("Swiping the page heading must not change teaching weeks", before, weekLabel())
        compose.onNodeWithTag("action_add_course").performClick()
        compose.onNodeWithTag("course_editor").assertIsDisplayed()
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
        compose.onNodeWithTag("action_more").performClick()
        compose.onNodeWithTag("action_manage_courses").performClick()
        compose.onNodeWithTag("course_manager").assertIsDisplayed()
    }

    @Test fun navigationButtonsMovePageHeadingsAndContentInTheSameSpring() {
        val initialPage = position("page_week")
        val initialHeader = position("route_header")
        val initialContent = position("page_content_week")
        val navigation = bounds("nav_week")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav_plans").performClick()
        compose.mainClock.advanceTimeBy(96)
        val movement = position("page_week").x - initialPage.x
        assertTrue("A navigation tap starts a visible slide", movement < -8f)
        assertEquals(movement, position("route_header").x - initialHeader.x, .75f)
        assertEquals(movement, position("page_content_week").x - initialContent.x, .75f)
        compose.onNodeWithTag("route_header_plans").assertTextEquals("Plans")
        assertEquals(navigation, bounds("nav_week"))
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        compose.onNodeWithTag("page_plans").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        compose.onAllNodesWithTag("route_header").assertCountEquals(1)
    }

    @Test fun cancelledPageDragAndRapidTabChangesKeepOneStableViewport() {
        val viewport = bounds("main_pager")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("main_pager").performTouchInput {
            down(Offset(width * .75f, height * .65f))
            moveBy(Offset(-width * .16f, 0f), delayMillis = 400)
            cancel()
        }
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        listOf("plans", "today", "settings", "week").forEach { route ->
            compose.onNodeWithTag("nav_$route").performClick()
            compose.mainClock.advanceTimeBy(32)
            assertEquals(viewport.top, bounds("main_pager").top, .75f)
            assertEquals(viewport.bottom, bounds("main_pager").bottom, .75f)
        }
        compose.mainClock.advanceTimeBy(2_500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        assertEquals(viewport.top, bounds("main_pager").top, .75f)
    }

    @Test fun bothFixedWeekRowsChangeExactlyOneWeekWithoutPagingAndIgnoreCancelledDrags() {
        listOf("week_switcher", "week_grid_header").forEach { tag ->
            val before = weekLabel()
            node(tag).performTouchInput { swipeLeft(durationMillis = 500) }
            compose.waitForIdle()
            val after = weekLabel()
            assertNotEquals("A horizontal swipe on $tag changes week", before, after)
            compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
            // One arrow click exactly reverses even a long swipe: no repeated week changes.
            compose.onNodeWithTag("week_previous").performClick()
            compose.waitForIdle()
            assertEquals(before, weekLabel())
            node(tag).performTouchInput {
                down(Offset(width * .8f, centerY))
                moveTo(Offset(width * .2f, centerY), delayMillis = 300)
                cancel()
            }
            compose.waitForIdle()
            assertEquals("Cancelling a header drag keeps the current week", before, weekLabel())
            node(tag).performTouchInput { swipeRight(durationMillis = 500) }
            compose.waitForIdle()
            assertNotEquals(before, weekLabel())
            compose.onNodeWithTag("week_next").performClick()
            compose.waitForIdle()
            assertEquals(before, weekLabel())
        }
        val before = weekLabel()
        node("header_day_2").performTouchInput { click(center) }
        assertEquals("A date tap is not a week swipe", before, weekLabel())
    }

    @Test fun courseBodyPagesHorizontallyAndScrollsVerticallyWithoutChangingWeek() {
        val before = weekLabel()
        val header = weekBounds("week_grid_header")
        val course = weekBounds("course_tile_0_1")
        node("week_grid_body").performTouchInput {
            swipe(Offset(centerX, centerY + 60f), Offset(centerX, centerY - 60f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue("The body must actually scroll", weekBounds("course_tile_0_1").top < course.top - 10f)
        assertEquals(header.top, weekBounds("week_grid_header").top, .75f)
        assertEquals(before, weekLabel())
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        node("week_grid_body").performTouchInput { swipeLeft(durationMillis = 400) }
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
        assertEquals(before, weekLabel())
        node("week_grid_body").performTouchInput { swipeRight(durationMillis = 400) }
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Today")
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
        assertEquals(before, weekLabel())
    }

    @Test fun datedWidgetIntentsNavigateRepeatedlyAndSurviveRecreation() {
        val date = LocalDate.now().plusWeeks(2).plusDays(1)
        launchWidget("timetable", date)
        val targetWeek = weekLabel()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(targetWeek, weekLabel())
        compose.onNodeWithTag("week_next").performClick()
        assertNotEquals(targetWeek, weekLabel())
        launchWidget("timetable", date)
        assertEquals(targetWeek, weekLabel())
        launchWidget("today", date)
        val selectedDate = compose.onNodeWithTag("today_date").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("today_date").assertTextEquals(selectedDate)
        // Tapping Today is a fresh request for the actual current day.
        compose.onNodeWithTag("nav_today").performClick()
        assertNotEquals(selectedDate, compose.onNodeWithTag("today_date").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
    }

    private fun launchWidget(route: String, date: LocalDate) {
        compose.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity,
                Intent(compose.activity.intent)
                    .putExtra("route", route).putExtra("date", date.toString()))
        }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true)
    private fun weekBounds(tag: String): Rect = node(tag).fetchSemanticsNode().boundsInRoot
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    // Unlike clipped bounds, the node origin tracks a page as it slides partly out of the viewport.
    private fun position(tag: String): Offset = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot
    private fun sourcePosition(tag: String): Offset = if (tag == "page_week") position(tag) else node(tag).fetchSemanticsNode().positionInRoot
    private fun weekLabel(): String = compose.onNodeWithTag("week_current").fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString("|") { it.text }
}
