package cn.crid.next.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/** The displayed week must satisfy shared tick geometry as well as duration ordering. */
class SoftplusWeekInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    private val monday = ScheduleEngine.weekStart(LocalDate.now())
    private val semester = Defaults.semester().copy(startDate = monday.minusWeeks(1).toString(),
        endDate = monday.plusWeeks(17).minusDays(1).toString(), weeks = 18)

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
    }

    @After fun restore() { runBlocking { repository.update { previous } } }

    @Test fun isolatedTwoPeriodWeekCardKeepsThePreviousScaleAndBothTickEndpoints() {
        seed(listOf(course("Pair", 0, 1, 2)))
        val pair = bounds("course_tile_0_0")
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("An isolated 100-minute week card remains within 1 dp of 160 dp",
            abs(pair.height / density - 160f) <= 1f)
        assertEquals(bounds("time_period_1").top, pair.top, 1f)
        assertEquals(bounds("time_period_2").bottom, pair.bottom, 1f)
        capture("week-isolated-100-light")
    }

    @Test fun shorterClassesAcrossDaysKeepTheSharedAxisAlignedWithoutForcingTheLongCardBackTo160() {
        seed(listOf(course("Pair", 0, 1, 2), course("First", 1, 1, 1), course("Second", 2, 2, 2)))
        val density = compose.activity.resources.displayMetrics.density
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = theme)) } }
            compose.waitForIdle()
            val pair = bounds("course_tile_0_0")
            val first = bounds("course_tile_1_0")
            val second = bounds("course_tile_2_0")
            assertTrue("100 minutes stays taller than either 45-minute class", pair.height > maxOf(first.height, second.height))
            assertEquals("Same clock start on different dates uses the same y coordinate", pair.top, first.top, 1f)
            assertEquals("Same clock end on different dates uses the same y coordinate", pair.bottom, second.bottom, 1f)
            assertEquals(bounds("time_period_1").top, first.top, 1f)
            assertEquals(bounds("time_period_1").bottom, first.bottom, 1f)
            assertEquals(bounds("time_period_2").top, second.top, 1f)
            assertEquals(bounds("time_period_2").bottom, second.bottom, 1f)
            assertTrue("Short-course constraints may expand the shared 100-minute span slightly",
                pair.height / density in 160f..168f)
            assertEquals(first.height, second.height, 1f)
            capture("week-shared-45-100-${if (theme == ThemeMode.DARK) "dark" else "light"}")
        }
    }

    private fun course(name: String, day: Int, start: Int, end: Int) = Course(name = name,
        lessons = listOf(Lesson(date = monday.plusDays(day.toLong()).toString(), startPeriod = start,
            endPeriod = end, teacher = "Ada", location = "R1")))

    private fun seed(courses: List<Course>) {
        val plan = Plan(semesterId = semester.id, name = "Softplus week", courses = CourseColors.assign(courses))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(theme = ThemeMode.LIGHT, language = Language.EN, holidaysEnabled = false)) } }
        compose.onNodeWithTag("nav_week").performClick()
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNode(hasTestTag(tag) and
        hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true).fetchSemanticsNode().let {
        Rect(it.positionInRoot.x, it.positionInRoot.y, it.positionInRoot.x + it.size.width, it.positionInRoot.y + it.size.height)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.12/week").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
