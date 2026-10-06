package cn.crid.next

import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
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

class StickyTimetableInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        seedWeek()
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
    }

    @After fun restore() {
        runBlocking { repository.update { previous } }
    }

    @Test fun weekdayHeaderStaysFixedWhileCoursesAndTimeAxisScrollTogether() {
        compose.onNodeWithTag("narrow_grid_scroll").assertDoesNotExist()
        val grid = bounds("week_grid")
        for (day in 0..6) {
            val header = bounds("header_day_$day")
            val body = bounds("week_day_$day")
            assertTrue("All seven headers fit the viewport", header.left >= grid.left - 1 && header.right <= grid.right + 1)
            assertTrue("All seven day columns fit the viewport", body.left >= grid.left - 1 && body.right <= grid.right + 1)
            assertEquals("Header and courses share a column", header.left, body.left, .75f)
            assertEquals(header.width, body.width, .75f)
        }
        val headerBefore = bounds("week_grid_header")
        val dateBefore = bounds("header_day_0")
        val periodBefore = bounds("time_period_2")
        val courseBefore = bounds("course_tile_0_1")
        assertEquals("Course starts at its period tick", periodBefore.top, courseBefore.top, .75f)

        node("week_grid_body").performTouchInput {
            swipe(Offset(centerX, centerY + 40f), Offset(centerX, centerY - 40f), durationMillis = 600)
        }
        compose.waitForIdle()

        val headerAfter = bounds("week_grid_header")
        val dateAfter = bounds("header_day_0")
        val periodAfter = bounds("time_period_2")
        val courseAfter = bounds("course_tile_0_1")
        val courseMovement = courseBefore.top - courseAfter.top
        assertTrue("The gesture must actually scroll course content", courseMovement > 12f)
        assertEquals("The weekday row must remain fixed", headerBefore.top, headerAfter.top, .5f)
        assertEquals(headerBefore.bottom, headerAfter.bottom, .5f)
        assertEquals("The date remains fixed with its weekday", dateBefore.top, dateAfter.top, .5f)
        assertEquals("Time labels and courses must move by the same amount", courseMovement, periodBefore.top - periodAfter.top, .75f)
        assertEquals(periodAfter.top, courseAfter.top, .75f)

        node("course_tile_0_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("course_details").assertExists()
        compose.onNode(hasText("Teacher 2") and hasAnyAncestor(hasTestTag("course_details")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Room 2") and hasAnyAncestor(hasTestTag("course_details")), useUnmergedTree = true).assertExists()
    }

    @Test fun extremelyNarrowWeekScrollsHeaderAndBodyHorizontallyAsOneSurface() {
        val previousSize = Regex("Override size:\\s*(\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val previousDensity = Regex("Override density:\\s*(\\d+)").find(shell("wm density"))?.groupValues?.get(1)
        try {
            shell("wm size 560x1600")
            shell("wm density 320")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("narrow_grid_scroll").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val headerBefore = bounds("header_day_4")
            val bodyBefore = bounds("week_day_4")
            node("narrow_grid_scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(100f, 0f) }
            compose.waitForIdle()
            val headerAfter = bounds("header_day_4")
            val bodyAfter = bounds("week_day_4")
            val movement = headerBefore.left - headerAfter.left
            assertTrue("The narrow grid must actually move horizontally", movement > 8f)
            assertEquals("One horizontal surface keeps the day labels aligned", movement, bodyBefore.left - bodyAfter.left, .75f)
            assertEquals(headerAfter.left, bodyAfter.left, .75f)
            assertEquals(headerAfter.width, bodyAfter.width, .75f)
            assertEquals("Horizontal scrolling must not move the header vertically", headerBefore.top, headerAfter.top, .5f)
        } finally {
            shell("wm size ${previousSize ?: "reset"}")
            shell("wm density ${previousDensity ?: "reset"}")
            compose.waitForIdle()
        }
    }

    private fun seedWeek() {
        val first = ScheduleEngine.weekStart(LocalDate.now())
        val semester = Defaults.semester().copy(name = "Sticky header fixture", startDate = first.minusWeeks(1).toString(),
            endDate = first.plusWeeks(16).plusDays(6).toString(), weeks = 18)
        val courses = (0..6).flatMap { day ->
            listOf(1, 2, 12).map { period ->
                val date = first.plusDays(day.toLong())
                Course(name = "Class $day-$period", lessons = listOf(Lesson(date = date.toString(), weekday = date.dayOfWeek.value,
                    startPeriod = period, endPeriod = period, teacher = "Teacher $period", location = "Room $period")))
            }
        }
        val plan = Plan(semesterId = semester.id, name = "Sticky week", courses = CourseColors.assign(courses))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false)) } }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true)
    private fun bounds(tag: String): Rect = node(tag).fetchSemanticsNode().boundsInRoot
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }
}
