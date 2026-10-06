package cn.crid.next.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import java.time.LocalDate
import java.time.LocalTime
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class TodayAgendaInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    private val today = LocalDate.now()

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
    }

    @After fun restore() { runBlocking { repository.update { previous } } }

    @Test fun fullTodayPageShowsLiveStatusInBothThemesAndOpensTheOriginalCourse() {
        val clock = LocalTime.now()
        val minute = clock.hour * 60 + clock.minute
        fun time(value: Int) = LocalTime.of(value / 60, value % 60).toString()
        val end = minOf(minute + 30, 1_439)
        val current = course("数学分析 III", time(maxOf(minute - 30, 0)), time(end), "张老师", "木铎楼 A101")
        val nextIsTomorrow = end + 75 > 1_439
        val next = if (nextIsTomorrow) {
            val nextCourse = course("Python 程序设计", "08:00", "09:40", "李老师", "励教楼 B202")
            nextCourse.copy(lessons = nextCourse.lessons.map { it.copy(date = today.plusDays(1).toString()) })
        } else course("Python 程序设计", time(end + 15), time(end + 75), "李老师", "励教楼 B202")
        seed(listOf(current, next), language = Language.ZH_CN)
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.12/today").apply { mkdirs() }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = mode)) } }
            compose.waitForIdle()
            page("today_summary").assertIsDisplayed()
            page("today_next_0_name").assertTextEquals("Python 程序设计")
            compose.onNodeWithTag("nav_today").assertIsDisplayed()
            compose.onNodeWithTag("nav_settings").assertIsDisplayed()
            assertSingleDate()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "full-${if (mode == ThemeMode.DARK) "dark" else "light"}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        val currentPresent = compose.onAllNodes(hasTestTag("today_current_0") and hasAnyAncestor(hasTestTag("page_today")))
            .fetchSemanticsNodes().isNotEmpty()
        page(if (currentPresent) "today_current_0" else "today_next_0").performClick()
        compose.onNodeWithTag("course_details").assertExists()
        compose.onNode(hasText(if (currentPresent) current.name else next.name) and hasAnyAncestor(hasTestTag("course_details")),
            useUnmergedTree = true).assertExists()
    }

    @Test fun threePeriodsKeepCompleteScrollableCoursesWithoutClockTimeGapsOrADuplicateDate() {
        seed(listOf(course("Course A", "08:00", "08:45"), course("Course B", "12:00", "13:40"), course("Course C", "18:00", "21:00")))
        page("today_agenda").assertExists()
        page("today_grid").assertDoesNotExist()
        page("today_grid_header").assertDoesNotExist()
        page("time_axis").assertDoesNotExist()
        assertSingleDate()
        val periods = listOf("morning", "afternoon", "evening")
        val headings = listOf("Morning", "Afternoon", "Evening")
        val heights = mutableListOf<Int>()
        listOf("08:00" to "08:45", "12:00" to "13:40", "18:00" to "21:00").forEachIndexed { index, (start, end) ->
            val period = periods[index]
            page("today_period_$period").performScrollTo().assertIsDisplayed()
            page("period_heading_$period").assertTextEquals(headings[index]).assertIsDisplayed()
            page("period_empty_$period").assertDoesNotExist()
            page("today_course_$index").assertIsDisplayed()
            assertInPeriod(index, period)
            val card = bounds("today_course_$index")
            val viewport = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
            assertTrue("Each course can be fully brought into the phone viewport", card.top >= viewport.top - 1f && card.bottom <= viewport.bottom + 1f)
            heights += page("today_course_$index").fetchSemanticsNode().size.height
            val heading = bounds("period_heading_$period")
            val compactGap = 16f * compose.activity.resources.displayMetrics.density
            assertTrue("A period heading must sit close to its first class instead of leaving clock-time space", card.top - heading.bottom in 0f..compactGap)
            assertEquals("Period headings align with the course blocks", card.left, heading.left, .75f)
            page("agenda_start_$index").assertTextEquals(start)
            page("agenda_end_$index").assertTextEquals(end)
            val axis = bounds("agenda_time_$index")
            assertTrue("The independent time axis is left of the course block", axis.right < card.left)
            assertEquals(card.top, bounds("agenda_start_$index").top, .75f)
            assertEquals(card.bottom, bounds("agenda_end_$index").bottom, .75f)
            listOf(start, end).forEach { time ->
                compose.onAllNodes(hasText(time) and hasAnyAncestor(hasTestTag("today_agenda")), useUnmergedTree = true).assertCountEquals(1)
                compose.onAllNodes(hasText(time) and hasAnyAncestor(hasTestTag("today_course_$index")), useUnmergedTree = true).assertCountEquals(0)
            }
        }
        assertTrue("The 180-minute class is taller than the 100-minute class across periods", heights[2] > heights[1])
        assertTrue("The 100-minute class is taller than the 45-minute class across periods", heights[1] > heights[0])
        val density = compose.activity.resources.displayMetrics.density
        val scale = compose.activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(720, 820), MinuteSpan(1080, 1260))
        val sharedAxis = TimetableAxis(emptyList(), spans, (24f + 60f * scale) / 100f,
            minimumLessonHeight = 16f + 60f * scale, heightSmoothing = 2f * scale,
            minimumHeightStep = 1f / density)
        assertEquals("Today and the timetable use the same smooth duration function",
            spans.map { (sharedAxis.lessonHeight(it) * density).roundToInt() }, heights)
        assertTrue("A normal two-period class remains within 1 dp of its previous height",
            abs(((24f + 60f * scale) * density).roundToInt() - heights[1]) <= density)
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.12/today").apply { mkdirs() }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = mode)) } }
            compose.waitForIdle()
            (0..2).forEach { index ->
                page("today_course_$index").performScrollTo().assertIsDisplayed()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File(directory, "duration-$index-${if (mode == ThemeMode.DARK) "dark" else "light"}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
        assertSingleDate()
    }

    @Test fun twoPeriodCardKeepsItsOriginalHeightAloneAndWithACustomTimetable() {
        val density = compose.activity.resources.displayMetrics.density
        val scale = compose.activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val expected = ((24f + 60f * scale) * density).roundToInt()
        seed(listOf(course("Two periods", "08:00", "09:40")))
        assertTrue("The isolated 100-minute card stays near the previous two-period height",
            abs(expected - page("today_course_0").fetchSemanticsNode().size.height) <= density)
        val custom = listOf(Period(1, "08:00", "08:40"), Period(2, "08:45", "09:25"),
            Period(3, "13:00", "13:40"), Period(4, "13:45", "14:25"))
        seed(listOf(course("Short course", "08:00", "08:40"), course("Two periods", "13:00", "14:25"),
            course("Long course", "18:00", "20:10")), periods = custom)
        val heights = (0..2).map { index ->
            page("today_course_$index").performScrollTo().assertIsDisplayed()
            page("today_course_$index").fetchSemanticsNode().size.height
        }
        assertTrue("The semester's 85-minute pair remains within 1 dp of its previous height",
            abs(expected - heights[1]) <= density)
        assertTrue(heights[0] < heights[1] && heights[1] < heights[2])
    }

    @Test fun rowsPrioritizeNameAndRoomBeforeTeacherAndOpenTheOriginalCourseDetails() {
        seed(listOf(course("Discrete mathematics", "08:00", "09:00", "Dr Ada Lin", "North hall, Room 202")))
        val title = bounds("agenda_name_0")
        val teacher = bounds("course_teacher")
        val location = bounds("course_location")
        assertTrue(title.bottom <= location.top)
        assertTrue(location.bottom <= teacher.top)
        page("course_teacher").assertTextEquals("Teacher: Dr Ada Lin")
        page("course_location").assertTextEquals("Location: North hall, Room 202")
        compose.onNodeWithContentDescription("Discrete mathematics 08:00–09:00").performClick()
        compose.onNodeWithTag("course_details").assertExists()
        compose.onNode(hasText("Dr Ada Lin") and hasAnyAncestor(hasTestTag("course_details")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("North hall, Room 202") and hasAnyAncestor(hasTestTag("course_details")), useUnmergedTree = true).assertExists()
    }

    @Test fun consecutiveSameCourseRecordsMergeButOverlappingDifferentCoursesRemainSeparateRows() {
        val merged = Course(name = "Merged course", lessons = listOf(
            Lesson(date = today.toString(), weekday = today.dayOfWeek.value, startPeriod = 1, endPeriod = 1, teacher = "Ada", location = "Room 1"),
            Lesson(date = today.toString(), weekday = today.dayOfWeek.value, startPeriod = 2, endPeriod = 2, teacher = "Ada", location = "Room 1")))
        seed(listOf(merged, course("Overlapping course", "08:30", "09:30")),
            periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")))
        page("today_course_0").assertExists()
        page("today_course_1").assertExists()
        page("today_course_2").assertDoesNotExist()
        page("agenda_start_0").assertTextEquals("08:00")
        page("agenda_end_0").assertTextEquals("09:40")
        page("agenda_start_1").assertTextEquals("08:30")
        page("agenda_end_1").assertTextEquals("09:30")
        page("course_conflict").assertDoesNotExist()
        page("course_arrangements").assertDoesNotExist()
        assertTrue(bounds("today_course_1").top >= bounds("today_course_0").bottom)
        compose.onNodeWithContentDescription("Merged course 08:00–09:40").performClick()
        compose.onNodeWithTag("teaching_record_0").assertExists()
        compose.onNodeWithTag("teaching_record_1").assertExists()
    }

    @Test fun existingMixedScriptFieldsGainDisplaySpacingWithoutChangingTheirStoredCourse() {
        val original = course("Python程序设计", "08:00", "09:00", "Ada老师", "励耘楼203")
        seed(listOf(original))
        page("agenda_name_0").assertTextEquals("Python 程序设计")
        page("course_teacher").assertTextEquals("Teacher: Ada 老师")
        page("course_location").assertTextEquals("Location: 励耘楼 203")
        page("today_course_0").performClick()
        compose.onNodeWithTag("course_details_title").assertTextEquals("Python 程序设计")
        listOf("Ada 老师", "励耘楼 203").forEach { value ->
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("teaching_record_0")), useUnmergedTree = true).assertExists()
        }
        compose.runOnIdle {
            val stored = repository.state.value.plans.single().courses.single()
            assertEquals(original.id, stored.id)
            assertEquals(original.name, stored.name)
            assertEquals(original.lessons, stored.lessons)
        }
    }

    @Test fun largeTextKeepsTheCompleteTeacherAndRoomWithNaturalWrapping() {
        val teacher = "Professor Alexandra Chen and Associate Professor Benjamin Williams"
        val room = "Science and Engineering Building, East Wing, Seminar Room 1206"
        seed(listOf(course("Advanced course", "08:00", "09:00", teacher, room)))
        compose.runOnUiThread {
            val density = compose.activity.resources.displayMetrics.density
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) { CridApp(repository) }
            }
        }
        compose.waitForIdle()
        listOf("course_teacher" to "Teacher: $teacher", "course_location" to "Location: $room").forEach { (tag, value) ->
            val node = page(tag)
            node.assertTextEquals(value).performScrollTo()
            val layout = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertTrue("Long fields wrap into multiple lines", layout.single().lineCount >= 2)
            assertFalse("The full metadata must remain visible", layout.single().hasVisualOverflow)
            val fieldBounds = bounds(tag)
            val cardBounds = bounds("today_course_0")
            assertTrue("Measured course height contains the complete field", fieldBounds.bottom <= cardBounds.bottom)
            assertEquals("Text fields share the title's left edge", bounds("agenda_name_0").left, fieldBounds.left, .75f)
        }
        page("agenda_start_0").assertTextEquals("08:00")
        page("agenda_end_0").assertTextEquals("09:00")
        assertSingleDate()
    }

    @Test fun anEmptyDayHasOneStatusCardAndRestIllustrationInsteadOfEmptyPeriods() {
        seed(emptyList())
        page("today_status_title").assertIsDisplayed().assertTextEquals("Today, make a little time for yourself")
        compose.onAllNodes(hasText("Today, make a little time for yourself") and hasAnyAncestor(hasTestTag("page_today")), useUnmergedTree = true).assertCountEquals(1)
        page("today_phrase_slot").assertDoesNotExist()
        page("today_summary").assertIsDisplayed()
        page("today_status_message").assertDoesNotExist()
        page("today_rest_illustration").assertIsDisplayed()
        page("today_agenda").assertDoesNotExist()
        assertNoPeriod("morning", "afternoon", "evening")
        page("today_course_0").assertDoesNotExist()
        assertSingleDate()
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.12/today").apply { mkdirs() }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = mode, language = Language.ZH_CN)) } }
            compose.waitForIdle()
            page("today_rest_illustration").assertIsDisplayed()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(directory, "empty-full-${if (mode == ThemeMode.DARK) "dark" else "light"}.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun onlyOccupiedPeriodsAppearInSimplifiedTraditionalAndEnglish() {
        listOf(Language.EN, Language.ZH_CN, Language.ZH_TW).forEach { language ->
            listOf(Triple("morning", "08:00", "09:00"), Triple("afternoon", "13:00", "14:00")).forEach { (period, start, end) ->
                seed(listOf(course("Only class", start, end)), language = language)
                val label = if (language == Language.EN) period.replaceFirstChar(Char::uppercaseChar)
                    else if (period == "morning") "上午" else "下午"
                page("period_heading_$period").assertTextEquals(label).assertIsDisplayed()
                assertInPeriod(0, period)
                page("today_course_0").assertIsDisplayed()
                listOf("morning", "afternoon", "evening").filterNot { it == period }.forEach { assertNoPeriod(it) }
                page("period_empty_$period").assertDoesNotExist()
                page("today_rest_illustration").assertDoesNotExist()
                page("today_course_1").assertDoesNotExist()
                assertSingleDate()
            }
        }
    }

    @Test fun finishedTodayKeepsItsCourseAndTomorrowWhileShowingTheRestMessage() {
        val finished = course("Finished class", "08:00", "09:00")
        val tomorrow = course("Tomorrow class", "08:00", "09:00").let { original ->
            original.copy(lessons = original.lessons.map { it.copy(date = today.plusDays(1).toString()) })
        }
        seed(listOf(finished, tomorrow))
        var selected: Occurrence? = null
        renderFixedDay(repository.state.value, HolidayCalendar(), LocalTime.of(18, 0)) { selected = it }
        page("today_status_title").assertTextEquals("Today's classes are over, time to relax")
        page("today_status_message").assertDoesNotExist()
        page("today_next_0_name").assertTextEquals("Tomorrow class")
        page("today_next_label").assertTextEquals("Next class · Tomorrow")
        page("today_course_0").performScrollTo().assertIsDisplayed()
        page("agenda_name_0").assertTextEquals("Finished class")
        assertNoPeriod("afternoon", "evening")
        page("today_rest_illustration").assertDoesNotExist()
        page("today_course_0").performClick()
        compose.runOnIdle { assertEquals(finished.id, selected?.course?.id) }
        assertSingleDate()
    }

    @Test fun holidayAndOutOfWeekRecordsLeaveTheTodayPeriodsEmptyWithoutRemovingStoredCourses() {
        val holidayCourse = course("Holiday record", "08:00", "09:00")
        val otherWeek = Course(name = "Other week", lessons = listOf(Lesson(weekday = today.dayOfWeek.value,
            weeks = listOf(3), startTime = "13:00", endTime = "14:00")))
        seed(listOf(holidayCourse, otherWeek))
        val original = repository.state.value
        val state = original.copy(settings = original.settings.copy(holidaysEnabled = true, showOutOfWeek = true))
        val calendar = HolidayCalendar(days = listOf(HolidayDay(today.toString(), "Rest day", statutory = true)))
        val occurrences = ScheduleEngine.occurrences(state.semester!!, state.plan!!, today, state.settings, calendar)
        assertEquals(2, occurrences.size)
        assertTrue(occurrences.all { !it.isActual })
        assertTrue(occurrences.any { OccurrenceStatus.OUT_OF_WEEK in it.statuses })
        assertTrue(occurrences.any { OccurrenceStatus.HOLIDAY in it.statuses })
        renderFixedDay(state, calendar, LocalTime.of(10, 0)) {}
        page("today_status_title").assertTextEquals("Today, make a little time for yourself")
        page("today_rest_illustration").performScrollTo().assertIsDisplayed()
        page("today_agenda").assertDoesNotExist()
        assertNoPeriod("morning", "afternoon", "evening")
        page("today_course_0").assertDoesNotExist()
        compose.runOnIdle { assertEquals(original, repository.state.value) }
        assertSingleDate()
    }

    @Test fun noonAndEveningBoundariesKeepCrossingClassesWholeAndEditingUsesTheirOriginalCourse() {
        val morning = course("Crossing noon", "11:59", "12:29")
        val noon = course("At noon", "12:00", "12:30")
        val afternoon = course("Crossing evening", "17:59", "18:29", "Original teacher", "Original room")
        val evening = course("At evening", "18:00", "18:30")
        // Repository order differs from the chronological, globally indexed display order.
        seed(listOf(evening, afternoon, noon, morning))
        val stored = requireNotNull(repository.state.value.plan).courses
        val expected = listOf(
            Triple("morning", "11:59", "12:29"), Triple("afternoon", "12:00", "12:30"),
            Triple("afternoon", "17:59", "18:29"), Triple("evening", "18:00", "18:30"),
        )
        expected.forEachIndexed { index, (period, start, end) ->
            page("today_course_$index").performScrollTo().assertIsDisplayed()
            assertInPeriod(index, period)
            page("agenda_start_$index").assertTextEquals(start)
            page("agenda_end_$index").assertTextEquals(end)
            val original = listOf(morning, noon, afternoon, evening)[index]
            page("today_course_$index").assertContentDescriptionEquals("${original.name} $start–$end")
        }
        page("today_course_4").assertDoesNotExist()
        listOf("morning", "afternoon", "evening").forEach { page("period_empty_$it").assertDoesNotExist() }
        assertSingleDate()

        page("today_course_2").performScrollTo().performClick()
        compose.onNodeWithTag("course_occurrence_time").performScrollTo().assertTextContains("17:59–18:29")
        compose.onNodeWithTag("edit_course").performScrollTo().performClick()
        compose.onNodeWithTag("course_editor").assertExists()
        compose.onNodeWithTag("editor_name").performScrollTo().assertTextContains(afternoon.name)
            .performTextReplacement("Edited crossing evening")
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            val saved = requireNotNull(repository.state.value.plan).courses
            assertEquals(stored.map { it.id }, saved.map { it.id })
            assertEquals(stored.filterNot { it.id == afternoon.id }, saved.filterNot { it.id == afternoon.id })
            val edited = saved.single { it.id == afternoon.id }
            assertEquals("Edited crossing evening", edited.name)
            assertEquals(afternoon.lessons, edited.lessons)
        }
    }

    @Test fun allPeriodsShareEqualDurationHeightsAndStrictGrowthEvenWhenAShortClassHasLongMetadata() {
        seed(listOf(
            course("Short morning", "08:00", "08:30",
                "Professor Alexandra Chen and Associate Professor Benjamin Williams",
                "Science and Engineering Building, East Wing, Seminar Room 1206"),
            course("Longer morning", "09:00", "09:31"),
            course("Equal afternoon", "12:00", "12:30"),
            course("Equal evening", "18:00", "18:30"),
            course("Longest evening", "19:00", "20:00"),
        ))
        val heights = (0..4).map { index ->
            page("today_course_$index").performScrollTo().assertIsDisplayed()
            page("today_course_$index").fetchSemanticsNode().size.height
        }
        assertInPeriod(0, "morning")
        assertInPeriod(1, "morning")
        assertInPeriod(2, "afternoon")
        assertInPeriod(3, "evening")
        assertInPeriod(4, "evening")
        assertEquals("Equal durations use the same all-day height even when their text lengths differ", heights[0], heights[2])
        assertEquals("Equal durations also keep the same height in the evening", heights[0], heights[3])
        assertTrue("One extra minute stays strictly taller across all content minimums", heights[1] > heights[0])
        assertTrue("Evening duration growth must continue from the morning content minimum", heights[4] > heights[1])
        listOf("course_teacher", "course_location").forEach { tag ->
            val node = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("today_course_0")), useUnmergedTree = true)
                .performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("The shortest class must retain its full metadata", layouts.single().hasVisualOverflow)
        }
    }

    private fun seed(courses: List<Course>, periods: List<Period> = Defaults.periods, language: Language = Language.EN) {
        val semester = Defaults.semester().copy(startDate = today.minusDays(7).toString(), endDate = today.plusDays(118).toString(), weeks = 18, periods = periods)
        val plan = Plan(semesterId = semester.id, name = "Agenda", courses = CourseColors.assign(courses))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = language, holidaysEnabled = false)) } }
        compose.onNodeWithTag("nav_today").performClick()
        compose.waitForIdle()
    }

    private fun course(name: String, start: String, end: String, teacher: String = "Teacher", location: String = "Room") =
        Course(name = name, lessons = listOf(Lesson(date = today.toString(), weekday = today.dayOfWeek.value,
            startTime = start, endTime = end, teacher = teacher, location = location)))

    private fun renderFixedDay(state: AppState, calendar: HolidayCalendar, now: LocalTime,
        onSelect: (Occurrence) -> Unit) {
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                Column(Modifier.fillMaxSize().testTag("page_today")) {
                    TodayScreen(state, calendar, today, today, now, UiText(Language.EN), {}, {}, null) { item, _ -> onSelect(item) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertNoPeriod(vararg periods: String) = periods.forEach { period ->
        page("today_period_$period").assertDoesNotExist()
        page("period_heading_$period").assertDoesNotExist()
        page("period_empty_$period").assertDoesNotExist()
    }

    private fun page(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_today")), useUnmergedTree = true)
    private fun bounds(tag: String): Rect = page(tag).fetchSemanticsNode().let { node ->
        // Layout bounds stay complete when a long page scrolls; boundsInRoot may be clipped.
        Rect(node.positionInRoot.x, node.positionInRoot.y,
            node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
    }
    private fun assertInPeriod(index: Int, period: String) {
        compose.onNode(hasTestTag("today_course_$index") and hasAnyAncestor(hasTestTag("today_period_$period")),
            useUnmergedTree = true).assertExists()
        listOf("morning", "afternoon", "evening").filterNot { it == period }.forEach { other ->
            compose.onNode(hasTestTag("today_course_$index") and hasAnyAncestor(hasTestTag("today_period_$other")),
                useUnmergedTree = true).assertDoesNotExist()
        }
    }
    private fun assertSingleDate() {
        val label = compose.onNodeWithTag("today_date").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("page_today")), useUnmergedTree = true).assertCountEquals(1)
    }
}
