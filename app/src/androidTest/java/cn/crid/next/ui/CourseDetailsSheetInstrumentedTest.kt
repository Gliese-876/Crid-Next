package cn.crid.next.ui

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.core.Course
import cn.crid.next.core.Language
import cn.crid.next.core.Lesson
import cn.crid.next.core.Occurrence
import cn.crid.next.core.Period
import cn.crid.next.core.Semester
import cn.crid.next.core.displayCourses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.io.File
import kotlin.math.abs

/** Exercises the public sheet actions without changing the activity's persisted timetable. */
class CourseDetailsSheetInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val text = UiText(Language.EN)
    private val semester = Semester(
        id = "details-semester", name = "Autumn", startDate = "2026-09-07", endDate = "2026-11-01", weeks = 8,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")),
    )
    private val sharedLesson = Lesson(
        weeks = listOf(1, 3), weekday = 1, startPeriod = 1, endPeriod = 2,
        teacher = "Ada", location = "Room 101", note = "Bring a notebook",
    )
    private val courses = listOf(
        Course(id = "details-a", name = "Shared course", lessons = listOf(
            sharedLesson.copy(weeks = listOf(8), teacher = "Grace", location = "Room 202"),
            sharedLesson,
            sharedLesson.copy(),
        )),
        Course(id = "details-b", name = "Shared course", lessons = listOf(sharedLesson.copy())),
    )

    @Test fun teachingRecordShowsTimeAndRoomBeforeTeacherWithoutDroppingWeeks() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) { TeachingRecord(sharedLesson, semester, text) }
            }
        }
        val time = compose.onNodeWithText("08:00–09:40 · Periods 1–2", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val room = compose.onNodeWithText("Room 101", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val teacher = compose.onNodeWithText("Ada", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(time.bottom <= room.top)
        assertTrue(room.bottom <= teacher.top)
        compose.onNodeWithText("Weeks 1, 3", useUnmergedTree = true).assertIsDisplayed()
        listOf("Time" to "08:00–09:40 · Periods 1–2", "Location" to "Room 101", "Teacher" to "Ada",
            "Teaching weeks" to "Weeks 1, 3").forEach { (label, value) ->
            val labelCenter = visibleTextCenter(compose.onNodeWithText(label, useUnmergedTree = true))
            val valueCenter = visibleTextCenter(compose.onNodeWithText(value, useUnmergedTree = true))
            assertEquals("$label and its value have aligned visible ink", valueCenter, labelCenter, 1f)
        }
    }

    @Test fun equalArrangementsAcrossSameNameCoursesKeepEveryOriginalEditTarget() {
        val calls = install()
        // The week-eight record was first in its source course, but is last in the sheet.
        val expected = listOf("details-a" to 1, "details-a" to 2, "details-b" to 0, "details-a" to 0)

        compose.onNodeWithTag("course_details").assertExists()
        expected.indices.forEach { index ->
            compose.onNodeWithTag("teaching_record_$index").assertExists()
            textAction("teaching_record_${index}_delete", "Delete arrangement ${index + 1}").assertHasClickAction()
            val action = textAction("teaching_record_${index}_edit", "Edit arrangement ${index + 1}")
            val source = screenBounds(action)
            action.performClick()
            compose.runOnIdle { assertOriginAt(source, calls.editedLessonOrigins.last()) }
        }
        compose.onNodeWithTag("teaching_record_4").assertDoesNotExist()
        val courseAction = textAction("edit_course", "Edit course")
        val courseSource = screenBounds(courseAction)
        courseAction.performClick()

        compose.runOnIdle {
            assertEquals(expected, calls.editedLessons)
            assertEquals(expected.size, calls.editedLessonOrigins.toSet().size)
            assertEquals(1, calls.editedCourses)
            assertOriginAt(courseSource, calls.editedCourseOrigins.single())
            assertTrue(calls.deletedLessons.isEmpty())
            assertEquals(0, calls.deletedCourses)
        }
    }

    @Test fun arrangementCancellationDoesNotDeleteAndConfirmationUsesItsOriginalCourseAndIndex() {
        val calls = install()

        // This equal-looking record belongs to the second source course.
        clickInSheet("teaching_record_2_delete")
        compose.onNodeWithTag("confirm_delete_arrangement").assertIsDisplayed()
        compose.runOnIdle { assertTrue(calls.deletedLessons.isEmpty()) }
        compose.onNodeWithTag("cancel_delete_arrangement").performClick()
        compose.onNodeWithTag("confirm_delete_arrangement").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(calls.deletedLessons.isEmpty())
            assertEquals(0, calls.deletedCourses)
            assertEquals(0, calls.dismissals)
        }

        clickInSheet("teaching_record_2_delete")
        compose.onNodeWithTag("confirm_delete_arrangement").performClick()
        compose.onNodeWithTag("confirm_delete_arrangement").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(listOf("details-b" to 0), calls.deletedLessons)
            assertEquals(0, calls.deletedCourses)
            assertTrue(calls.editedLessons.isEmpty())
        }
    }

    @Test fun courseCancellationDoesNotDeleteAndConfirmationInvokesOnlyTheCourseCallback() {
        val calls = install()

        textAction("edit_course", "Edit course").assertHasClickAction()
        textAction("delete_course", "Delete course").performClick()
        compose.onNodeWithText(
            "Remove “Shared course” and all 4 teaching arrangements, across every week, from this timetable plan."
        ).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls.deletedCourses) }
        compose.onNodeWithTag("cancel_delete_course").performClick()
        compose.onNodeWithTag("confirm_delete_course").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, calls.deletedCourses)
            assertTrue(calls.deletedLessons.isEmpty())
            assertEquals(0, calls.dismissals)
        }

        clickInSheet("delete_course")
        compose.onNodeWithTag("confirm_delete_course").performClick()
        compose.onNodeWithTag("confirm_delete_course").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(1, calls.deletedCourses)
            assertTrue(calls.deletedLessons.isEmpty())
            assertTrue(calls.editedLessons.isEmpty())
        }
    }

    @Test fun projectedCardKeepsItsFullTimeSpanAndSeparateSourceArrangements() {
        val first = sharedLesson.copy(startPeriod = 1, endPeriod = 1)
        val second = sharedLesson.copy(startPeriod = 2, endPeriod = 2)
        val course = Course(id = "continuous-course", name = "Continuous course", lessons = listOf(first, second))
        val date = LocalDate.parse("2026-09-07")
        val original = listOf(
            Occurrence(course, first, date, LocalTime.of(8, 0), LocalTime.of(8, 45), 1),
            Occurrence(course, second, date, LocalTime.of(8, 55), LocalTime.of(9, 40), 1),
        )
        install(listOf(course), displayCourses(original).single().representative)

        compose.onNodeWithTag("course_occurrence_time").performScrollTo().assertTextContains("08:00–09:40")
        compose.onNode(
            hasText("08:00–08:45 · Period 1") and hasAnyAncestor(hasTestTag("teaching_record_0"))
        ).assertExists()
        compose.onNode(
            hasText("08:55–09:40 · Period 2") and hasAnyAncestor(hasTestTag("teaching_record_1"))
        ).assertExists()
        val selectedTimeStyle = renderedValueStyle("08:00–09:40", "course_occurrence_time")
        listOf(
            renderedValueStyle("08:00–08:45 · Period 1", "teaching_record_0"),
            renderedValueStyle("Ada", "teaching_record_0"),
            renderedValueStyle("Room 101", "teaching_record_0"),
        ).forEach { style ->
            assertEquals(selectedTimeStyle.fontSize, style.fontSize)
            assertEquals(selectedTimeStyle.lineHeight, style.lineHeight)
            assertEquals(selectedTimeStyle.fontWeight, style.fontWeight)
        }
    }

    @Test fun largeTextKeepsWholeCourseActionsBelowTheFullWidthTitleWithoutClipping() {
        val previousSetting = shell("settings get system font_scale").trim()
        val previousScale = compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 1.8")
            waitForFontScale(1.8f)
            install(relatedCourses = listOf(courses[0].copy(name = "Advanced principles of mathematical modelling")))
            assertLargeTextHeader()
        } finally {
            if (previousSetting == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale ${requireNotNull(previousSetting.toFloatOrNull())}")
            waitForFontScale(previousScale)
        }
    }

    private fun assertLargeTextHeader() {
        val title = compose.onNodeWithTag("course_details_title").performScrollTo().assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val headerBounds = compose.onNodeWithTag("course_details_header").fetchSemanticsNode().boundsInRoot
        val edit = textAction("edit_course", "Edit course")
        val editBounds = edit.fetchSemanticsNode().boundsInRoot
        assertTrue("Whole-course actions follow the full-width title at large text sizes", editBounds.top >= titleBounds.bottom)
        assertEquals("The title starts the header", headerBounds.top, titleBounds.top, 1f)
        val delete = textAction("delete_course", "Delete course")
        val deleteBounds = delete.fetchSemanticsNode().boundsInRoot
        assertTrue("Delete stays on the right of the action row", deleteBounds.left >= editBounds.right)
        assertTrue("Delete does not share the title's space", deleteBounds.top >= titleBounds.bottom)
        assertEquals("The title retains the complete header width", headerBounds.width, titleBounds.width, 1f)
        listOf("Advanced principles of mathematical modelling", "Edit", "Delete").forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("course_details_header")), useUnmergedTree = true)
                .performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("The dialog must use the requested large text scale", 1.8f, layout.layoutInput.density.fontScale, .001f)
            assertTrue("$value has visible lines", layout.lineCount > 0)
            assertEquals("Every character of $value is laid out", value.length, layout.getLineEnd(layout.lineCount - 1))
            // A cached MultiParagraph can retain a wider constraint after Text wraps its measured width.
            // Check the rendered line bounds, rather than treating that unused paragraph width as ink.
            (0 until layout.lineCount).forEach { line ->
                assertFalse("$value must not be ellipsized", layout.isLineEllipsized(line))
                assertTrue("$value fits horizontally", layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
                assertTrue("$value fits vertically", layout.getLineTop(line) >= -.5f && layout.getLineBottom(line) <= layout.size.height + .5f)
            }
        }
    }

    private fun waitForFontScale(expected: Float) {
        compose.waitUntil(10_000) {
            runCatching { abs(compose.activity.resources.configuration.fontScale - expected) < .001f }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun managerIncludesPendingAndDatedCoursesAndGroupsAllWeeksBeforeSelectingTheFirstSource() {
        val pending = Course(id = "pending-course", name = "Pending lab", lessons = listOf(
            Lesson(weeks = listOf(6, 7), weekday = 0, teacher = "Lin", unscheduled = true),
        ))
        val dated = Course(id = "dated-course", name = "Dated seminar", lessons = listOf(
            sharedLesson.copy(weeks = emptyList(), date = "2026-10-30", weekday = 5),
        ))
        val selected = mutableListOf<Course>()
        var additions = 0
        var additionOrigin: ModalOrigin? = null
        var dismissals = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    CourseManagerSheet(
                        courses = listOf(courses[0], pending, courses[1], dated), text = text,
                        onDismiss = { dismissals++ }, onSelectCourse = { selected += it },
                        onAddCourse = { origin -> additionOrigin = origin; additions++ },
                        semester = semester,
                    )
                }
            }
        }

        compose.onNodeWithTag("course_manager").assertExists()
        compose.onNodeWithTag("managed_course_0").assertTextContains("Shared course")
            .assertTextContains("4 teaching arrangements").assertTextContains("08:00–09:40 · Periods 1–2")
            .assertTextContains("Room 202").assertTextContains("Room 101")
        compose.onNodeWithTag("managed_course_1").assertTextContains("Pending lab")
            .assertTextContains("1 awaiting a time")
        compose.onNodeWithTag("managed_course_2").assertTextContains("Dated seminar")
        compose.onNodeWithTag("managed_course_3").assertDoesNotExist()
        (0..2).forEach { clickInSheet("managed_course_$it") }
        val addAction = compose.onNodeWithTag("course_manager_add").performScrollTo().assertIsDisplayed()
        val addSource = minimumInteractiveBounds(addAction)
        addAction.performClick()

        compose.runOnIdle {
            assertEquals(listOf(courses[0], pending, dated), selected)
            assertEquals(1, additions)
            assertOriginAt(addSource, requireNotNull(additionOrigin))
            assertEquals(0, dismissals)
        }
    }

    @Test fun busyDetailsDisableEveryMutationAndKeepTheActionErrorVisible() {
        val error = "Could not delete this arrangement. Please try again."
        val calls = install(actionError = error, actionBusy = true)
        compose.onNodeWithTag("course_action_error").performScrollTo()
            .assertIsDisplayed().assertTextEquals(error)

        val actions = listOf("edit_course" to "Edit course", "delete_course" to "Delete course") + (0..3).flatMap { index ->
            listOf("teaching_record_${index}_edit" to "Edit arrangement ${index + 1}",
                "teaching_record_${index}_delete" to "Delete arrangement ${index + 1}")
        }
        actions.forEach { (tag, description) ->
            textAction(tag, description).assertIsNotEnabled()
                .performTouchInput { click() }
        }
        compose.onNodeWithTag("confirm_delete_course").assertDoesNotExist()
        compose.onNodeWithTag("confirm_delete_arrangement").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, calls.editedCourses)
            assertEquals(0, calls.deletedCourses)
            assertTrue(calls.editedLessons.isEmpty())
            assertTrue(calls.deletedLessons.isEmpty())
            assertEquals(0, calls.dismissals)
        }
    }

    @Test fun singleDatedClassShowsTheDateAndTimeOnlyOnceAndKeepsRoomOnTheFirstScreen() {
        val date = LocalDate.of(2026, 9, 30)
        val lesson = sharedLesson.copy(weeks = emptyList(), date = date.toString(), weekday = 3, note = "")
        val course = Course(id = "single-date", name = "Modern education technology", credits = "3", lessons = listOf(lesson))
        val occurrence = Occurrence(course, lesson, date, LocalTime.of(8, 0), LocalTime.of(9, 40), 4)
        for (dark in listOf(false, true)) {
            install(listOf(course), occurrence, dark = dark)
            compose.onNodeWithTag("course_details_title").assertIsDisplayed()
            val titleBounds = compose.onNodeWithTag("course_details_title").fetchSemanticsNode().boundsInRoot
            val titleCenter = visibleTextCenter(compose.onNodeWithTag("course_details_title", useUnmergedTree = true))
            listOf("edit_course" to "Edit", "delete_course" to "Delete").forEach { (tag, label) ->
                val action = compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Whole-course actions stay to the right of the title", action.left >= titleBounds.right)
                val buttonTextCenter = visibleTextCenter(compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true))
                assertEquals("Whole-course button ink is centered against the title ink", titleCenter, buttonTextCenter, 1f)
            }
            compose.onNodeWithTag("course_occurrence_date").assertIsDisplayed()
            compose.onNodeWithTag("course_occurrence_time").assertIsDisplayed()
            compose.onNodeWithText("Room 101", useUnmergedTree = true).assertIsDisplayed()
            val timeLabel = compose.onNode(hasText("Time") and hasAnyAncestor(hasTestTag("course_occurrence_time")),
                useUnmergedTree = true)
            val timeValue = compose.onNodeWithText("08:00–09:40 · Periods 1–2", useUnmergedTree = true)
            assertEquals("Wrapped time and its label have aligned visible ink", visibleTextCenter(timeValue), visibleTextCenter(timeLabel), 1f)
            compose.onAllNodesWithText(text.date(date), useUnmergedTree = true).assertCountEquals(1)
            compose.onAllNodesWithText("08:00–09:40 · Periods 1–2", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithText("Selected class").assertDoesNotExist()
            val time = renderedValueStyle("08:00–09:40 · Periods 1–2", "teaching_record_0")
            for (value in listOf("Room 101", "Ada")) {
                val other = renderedValueStyle(value, "teaching_record_0")
                assertEquals(time.fontSize, other.fontSize)
                assertEquals(time.fontWeight, other.fontWeight)
                assertEquals(time.lineHeight, other.lineHeight)
            }
            capture("single-date-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun recurringSingleRecordKeepsNaturalWeeksAndRegularDayWithoutDuplicatingSelectedDate() {
        val lesson = sharedLesson.copy(weeks = (1..4).toList(), note = "")
        val course = Course(id = "recurring", name = "数学分析与应用", lessons = listOf(lesson))
        val date = LocalDate.of(2026, 9, 28)
        val occurrence = Occurrence(course, lesson, date, LocalTime.of(8, 0), LocalTime.of(9, 40), 4)
        val zh = UiText(Language.ZH_CN)
        install(listOf(course), occurrence, uiText = zh)
        compose.onAllNodesWithText(zh.date(date), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("第 1–4 周", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("course_occurrence_time").assertIsDisplayed()
        compose.onNodeWithText("Room 101", useUnmergedTree = true).assertIsDisplayed()
        capture("single-recurring-zh-light")
        install(listOf(course), uiText = zh, dark = true)
        compose.onNodeWithText(zh.weekday(date), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("第 1–4 周", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("course_occurrence_date").assertDoesNotExist()
        capture("single-recurring-from-manager-zh-dark")
    }

    @Test fun selectedSummaryUsesTheCurrentRoomWhenEarlierWeeksHaveDifferentRooms() {
        val selectedCourse = courses[0]
        val selectedLesson = selectedCourse.lessons[0]
        val selected = Occurrence(selectedCourse, selectedLesson, LocalDate.of(2026, 10, 26),
            LocalTime.of(8, 0), LocalTime.of(9, 40), 8)
        install(courses, selected)
        compose.onNodeWithTag("course_occurrence_time").assertIsDisplayed().assertTextContains("08:00–09:40")
        compose.onNodeWithTag("course_occurrence_location").assertIsDisplayed().assertTextContains("Room 202")
        assertEquals(renderedValueStyle("08:00–09:40", "course_occurrence_time").fontSize,
            renderedValueStyle("Room 202", "course_occurrence_location").fontSize)
        compose.onNode(hasText("Room 101") and hasAnyAncestor(hasTestTag("teaching_record_0")), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("teaching_record_3").assertExists()
        capture("selected-later-week-room-light")
    }

    @Test fun pendingRecordsKeepEveryTeacherWeekAndNoteTogether() {
        val pending = Course(id = "pending", name = "现代教育技术", lessons = listOf(
            Lesson(weeks = listOf(11, 12, 13), weekday = 0, unscheduled = true, teacher = "张老师", location = "木铎楼 A101", note = "小组课堂实践"),
            Lesson(weeks = listOf(16), weekday = 0, unscheduled = true, teacher = "李老师", location = "励耘楼 B202", note = "成果展示"),
        ))
        install(listOf(pending), uiText = UiText(Language.ZH_CN))
        val first = hasAnyAncestor(hasTestTag("teaching_record_0"))
        val second = hasAnyAncestor(hasTestTag("teaching_record_1"))
        listOf("张老师", "木铎楼 A101", "第 11–13 周", "小组课堂实践").forEach {
            compose.onNode(hasText(it) and first, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        listOf("李老师", "励耘楼 B202", "第 16 周", "成果展示").forEach {
            compose.onNode(hasText(it) and second, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("course_details_title").performScrollTo()
        capture("pending-two-records-zh-light")
    }

    @Test fun longCourseAndRoomWrapAtDoubleFontInBothLanguagesAndThemes() = withFontScale(2f) {
        val scenarios = listOf(
            Language.EN to Triple("Advanced principles of mathematical modelling and interdisciplinary research", "North building, room 203, interdisciplinary learning laboratory", "Professor Grace Hopper"),
            Language.ZH_CN to Triple("现代教育技术与跨学科课堂教学实践研究", "木铎楼 A101 跨学科教学实验室第二讨论区", "张老师 / 李老师"),
        )
        for ((language, content) in scenarios) for (dark in listOf(false, true)) {
            val course = Course(id = "long-details", name = content.first, lessons = listOf(
                sharedLesson.copy(weeks = (1..8).toList(), location = content.second, teacher = content.third),
                sharedLesson.copy(weeks = listOf(8), weekday = 3, location = "South room 101", teacher = "Ada"),
            ))
            install(listOf(course), uiText = UiText(language), dark = dark)
            assertCompleteText(content.first, "course_details_header")
            capture("long-title-${language.name.lowercase()}-${if (dark) "dark" else "light"}-font2")
            assertCompleteText(content.second, "teaching_record_0")
            assertCompleteText(content.third, "teaching_record_0")
            capture("long-record-${language.name.lowercase()}-${if (dark) "dark" else "light"}-font2")
        }
    }

    @Test fun managerPairsEachTimeWithItsOwnDayAndRoomAndKeepsTheAddOriginInEmptyState() {
        val first = sharedLesson.copy(weeks = (1..4).toList(), weekday = 1, startTime = "08:00", endTime = "09:00", startPeriod = null, endPeriod = null, location = "North A101")
        val second = first.copy(weekday = 3, startTime = "14:00", endTime = "15:40", location = "South B202", teacher = "Grace")
        val grouped = Course(id = "paired", name = "Mathematical methods", lessons = listOf(first, second))
        var selected: Course? = null
        var addition: ModalOrigin? = null
        for (dark in listOf(false, true)) {
            installManager(listOf(grouped, courses[0]), dark = dark, onSelect = { selected = it }, onAdd = { addition = it })
            listOf("North A101", "08:00–09:00", "Mon").forEach {
                compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("managed_course_0_arrangement_0")), useUnmergedTree = true).assertIsDisplayed()
            }
            listOf("South B202", "14:00–15:40", "Wed").forEach {
                compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("managed_course_0_arrangement_1")), useUnmergedTree = true).assertIsDisplayed()
            }
            capture("manager-paired-${if (dark) "dark" else "light"}")
            compose.onNodeWithTag("managed_course_0").performClick()
            compose.runOnIdle { assertEquals(grouped, selected) }
        }
        installManager(emptyList(), onAdd = { addition = it })
        compose.onNodeWithText("No courses yet").assertIsDisplayed()
        val add = compose.onNodeWithTag("course_manager_add").assertIsDisplayed()
        val origin = minimumInteractiveBounds(add)
        capture("manager-empty-light")
        add.performClick()
        compose.runOnIdle { assertOriginAt(origin, requireNotNull(addition)) }
    }

    @Test fun managerLargeTypeKeepsTitleAddAndCoreCourseFieldsSeparate() = withFontScale(2f) {
        for (language in listOf(Language.ZH_CN, Language.EN)) {
            val uiText = UiText(language)
            val title = if (language == Language.EN) "Advanced mathematical modelling" else "现代教育技术与课堂实践"
            val course = Course(id = "large-manager", name = title, lessons = listOf(sharedLesson.copy(location = "North A101")))
            installManager(listOf(course), dark = true, uiText = uiText)
            compose.onNodeWithText(uiText.t("管理课程", "Manage courses"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("course_manager_add").assertIsDisplayed().assertContentDescriptionEquals(uiText.t("新增课程", "Add course"))
            assertCompleteText(title, "managed_course_0")
            compose.onNode(hasText("08:00–09:40 · " + if (language == Language.EN) "Periods 1–2" else "第 1–2 节") and hasAnyAncestor(hasTestTag("managed_course_0")), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("North A101") and hasAnyAncestor(hasTestTag("managed_course_0")), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("course_manager_add").performScrollTo()
            capture("manager-${language.name.lowercase()}-dark-font2")
        }
    }

    private fun installManager(
        records: List<Course>, dark: Boolean = false,
        onSelect: (Course) -> Unit = {}, onAdd: (ModalOrigin) -> Unit = {},
        uiText: UiText = text,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(dark)) {
                    CourseManagerSheet(records, uiText, {}, onSelect, onAdd, semester)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun visibleTextCenter(textNode: SemanticsNodeInteraction): Float {
        val bitmap = textNode.assertIsDisplayed().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val background = pixels.asSequence().groupingBy { it }.eachCount().maxBy { it.value }.key
        var firstRow = bitmap.height
        var lastRow = -1
        pixels.forEachIndexed { index, color ->
            val contrast = maxOf(abs(Color.red(color) - Color.red(background)),
                abs(Color.green(color) - Color.green(background)), abs(Color.blue(color) - Color.blue(background)))
            if (contrast >= 48) {
                firstRow = minOf(firstRow, index / bitmap.width)
                lastRow = maxOf(lastRow, index / bitmap.width)
            }
        }
        assertTrue("The screenshot must contain visible text ink", lastRow >= firstRow)
        return textNode.fetchSemanticsNode().boundsInRoot.top + (firstRow + lastRow) / 2f
    }

    private fun assertCompleteText(value: String, ancestor: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(ancestor)), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("The dialog must render with the system's 200% text size", 2f, layout.layoutInput.density.fontScale, .001f)
        assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1))
        // A cached paragraph may keep a wider constraint than its wrapped Text node.
        // Verify the painted lines and all characters instead of unused paragraph width.
        (0 until layout.lineCount).forEach { line ->
            assertFalse("Full field must not be ellipsized: $value", layout.isLineEllipsized(line))
            assertTrue("Painted text fits horizontally: $value", layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
            assertTrue("Painted text fits vertically: $value", layout.getLineTop(line) >= -.5f && layout.getLineBottom(line) <= layout.size.height + .5f)
        }
    }

    private fun withFontScale(scale: Float, block: () -> Unit) {
        val previousSetting = shell("settings get system font_scale").trim()
        val previousScale = compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale $scale")
            waitForFontScale(scale)
            block()
        } finally {
            if (previousSetting == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale ${requireNotNull(previousSetting.toFloatOrNull())}")
            waitForFontScale(previousScale)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.14/details").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun clickInSheet(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun textAction(tag: String, description: String): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .assertContentDescriptionEquals(description).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assertTextEquals(if (description.startsWith("Edit")) "Edit" else "Delete")

    private fun screenBounds(action: SemanticsNodeInteraction): Rect = action.fetchSemanticsNode().let { node ->
        node.boundsInWindow.translate(node.positionOnScreen - node.positionInWindow)
    }

    private fun minimumInteractiveBounds(action: SemanticsNodeInteraction): Rect {
        // FilledTonalButton centers its 40 dp surface inside a minimum 48 dp layout footprint.
        // Its outer modalOrigin observes that footprint; detail actions render at 48 dp.
        val visual = screenBounds(action)
        val minimum = 48f * compose.activity.resources.displayMetrics.density
        val halfWidth = maxOf(visual.width, minimum) / 2f
        val halfHeight = maxOf(visual.height, minimum) / 2f
        return Rect(visual.center.x - halfWidth, visual.center.y - halfHeight,
            visual.center.x + halfWidth, visual.center.y + halfHeight)
    }

    private fun assertOriginAt(expected: Rect, origin: ModalOrigin) {
        val captured = requireNotNull(origin.snapshot()) { "The clicked control must capture its animation origin" }
        assertEquals(expected.left, captured.left, 1f)
        assertEquals(expected.top, captured.top, 1f)
        assertEquals(expected.right, captured.right, 1f)
        assertEquals(expected.bottom, captured.bottom, 1f)
    }

    private fun renderedValueStyle(value: String, ancestorTag: String): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(ancestorTag)), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style
    }

    private fun install(
        relatedCourses: List<Course> = courses,
        occurrence: Occurrence? = null,
        actionError: String? = null,
        actionBusy: Boolean = false,
        dark: Boolean = false,
        uiText: UiText = text,
    ): Calls {
        val calls = Calls()
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(dark)) {
                    CourseDetailsSheet(
                        relatedCourses = relatedCourses, occurrence = occurrence, semester = semester, text = uiText,
                        onDismiss = { calls.dismissals++ },
                        onEditCourse = { origin -> calls.editedCourses++; calls.editedCourseOrigins += origin },
                        onEditLesson = { courseId, index, origin -> calls.editedLessons += courseId to index; calls.editedLessonOrigins += origin },
                        onDeleteCourse = { calls.deletedCourses++ },
                        onDeleteLesson = { courseId, index -> calls.deletedLessons += courseId to index },
                        actionError = actionError, actionBusy = actionBusy,
                    )
                }
            }
        }
        compose.waitForIdle()
        return calls
    }

    private class Calls {
        val editedLessons = mutableListOf<Pair<String, Int>>()
        val editedLessonOrigins = mutableListOf<ModalOrigin>()
        val editedCourseOrigins = mutableListOf<ModalOrigin>()
        val deletedLessons = mutableListOf<Pair<String, Int>>()
        var editedCourses = 0
        var deletedCourses = 0
        var dismissals = 0
    }
}
