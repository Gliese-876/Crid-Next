package cn.crid.next

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Full toolbar/sheet/editor wiring through the real atomic repository and on-disk state. */
class CourseCrudInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    private val today = LocalDate.now()

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        val first = ScheduleEngine.weekStart(today).minusWeeks(1)
        val semester = Defaults.semester().copy(startDate = first.toString(), endDate = first.plusWeeks(18).minusDays(1).toString())
        val plan = Plan(semesterId = semester.id, name = "CRUD fixture", courses = emptyList())
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false)) } }
        compose.waitForIdle()
    }

    @After fun restore() { runBlocking { repository.update { previous } } }

    @Test fun todayAddsMultipleArrangementsAndPersistsTheRestoredDraft() {
        compose.onNodeWithTag("action_add_course").performClick()
        replace("editor_name", "Hands-on geometry")
        replace("editor_credits", "2.5")
        expand(0)
        replace("editor_teacher_0", "Ada")
        replace("editor_location_0", "Room 208")
        compose.onNodeWithTag("editor_add_lesson").performScrollTo().performClick()
        expand(1)
        compose.onNodeWithTag("editor_mode_pending_1").performScrollTo().performClick()
        replace("editor_teacher_1", "Grace")
        replace("editor_note_1", "Arrange a laboratory session")

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("editor_name").performScrollTo().assertTextContains("Hands-on geometry")
        compose.onNodeWithTag("editor_credits").performScrollTo().assertTextContains("2.5")
        expand(1)
        compose.onNodeWithTag("editor_teacher_1").performScrollTo().assertTextContains("Grace")
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(10_000) { repository.state.value.plan!!.courses.size == 1 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }

        val course = repository.state.value.plan!!.courses.single()
        assertEquals("Hands-on geometry", course.name)
        assertEquals("2.5", course.credits)
        assertEquals(listOf("Ada", "Grace"), course.lessons.map { it.teacher })
        assertEquals("Room 208", course.lessons[0].location)
        assertTrue(course.lessons[1].unscheduled)
        assertEquals("Arrange a laboratory session", course.lessons[1].note)
        assertEquals(repository.state.value, diskState())
        compose.onNodeWithTag("pending_lessons").assertExists()

        compose.onNodeWithTag("action_more").performClick()
        compose.onNodeWithTag("action_manage_courses").performClick()
        compose.onNodeWithTag("course_manager_add").performScrollTo().performClick()
        replace("editor_name", "Discarded managed course")
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.onNodeWithTag("editor_discard_changes").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("course_manager").assertIsDisplayed()
        compose.onNode(hasText("Hands-on geometry", substring = true) and hasAnyAncestor(hasTestTag("course_manager"))).assertExists()
        assertEquals(listOf(course), repository.state.value.plan!!.courses)
        assertEquals(repository.state.value, diskState())
    }

    @Test fun weekManagerEditsAnImportedPendingCourseAndDeletesOnlyTheSelectedArrangementThenTheCourse() {
        val fileName = "北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls"
        val parsed = InstrumentationRegistry.getInstrumentation().context.assets.open(fileName).use {
            TimetableParser.parse(it.readBytes(), fileName)
        }
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        runBlocking { repository.update { state -> state.copy(plans = listOf(state.plan!!.copy(courses = parsed.courses))) } }
        val before = repository.state.value.plan!!.courses.single { it.name == "合成课课课未" }
        val unrelated = repository.state.value.plan!!.courses.filterNot { it.id == before.id }
        compose.onNodeWithTag("nav_week").performClick()
        manage("合成课课课未")
        compose.onNodeWithTag("edit_course").performScrollTo().performClick()
        replace("editor_credits", "4.5")
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.onNodeWithTag("editor_discard_changes").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("course_details").assertIsDisplayed()
        compose.onNode(hasText("合成课课课未") and hasAnyAncestor(hasTestTag("course_details"))).assertExists()
        assertEquals(before, repository.state.value.plan!!.courses.single { it.id == before.id })
        assertEquals(repository.state.value, diskState())
        compose.onNodeWithTag("edit_course").performScrollTo().performClick()
        replace("editor_credits", "4.5")
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(10_000) { repository.state.value.plan!!.courses.single { it.id == before.id }.credits == "4.5" }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_editor").fetchSemanticsNodes().isEmpty() }
        assertEquals(before.copy(credits = "4.5"), repository.state.value.plan!!.courses.single { it.id == before.id })

        manage("合成课课课未")
        compose.onNodeWithTag("teaching_record_0_delete").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_delete_arrangement").performClick()
        assertEquals(4, repository.state.value.plan!!.courses.single { it.id == before.id }.lessons.size)
        compose.onNodeWithTag("teaching_record_0_delete").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete_arrangement").performClick()
        compose.waitUntil(10_000) { repository.state.value.plan!!.courses.single { it.id == before.id }.lessons.size == 3 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_details").fetchSemanticsNodes().isEmpty() }
        assertEquals(unrelated, repository.state.value.plan!!.courses.filterNot { it.id == before.id })

        manage("合成课课课未")
        compose.onNodeWithTag("delete_course").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_delete_course").performClick()
        compose.waitUntil(10_000) { repository.state.value.plan!!.courses.none { it.id == before.id } }
        assertEquals(unrelated, repository.state.value.plan!!.courses)
        assertEquals(repository.state.value, diskState())
    }

    @Test fun addWithoutAPlanLeadsToSetupWithoutCreatingHiddenData() {
        runBlocking { repository.update { AppState(settings = Settings(language = Language.EN)) } }
        compose.onNodeWithTag("action_add_course").performClick()
        compose.onNodeWithTag("route_header").assertTextEquals("Plans")
        compose.onNodeWithText("New term").assertIsDisplayed()
        assertTrue(repository.state.value.semesters.isEmpty())
        assertTrue(repository.state.value.plans.isEmpty())
        compose.onNodeWithTag("course_editor").assertDoesNotExist()
    }

    private fun manage(name: String) {
        compose.onNodeWithTag("action_more").performClick()
        compose.onNodeWithTag("action_manage_courses").performClick()
        compose.onNode(hasText(name, substring = true) and hasAnyAncestor(hasTestTag("course_manager")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("course_details").assertExists()
    }
    private fun expand(index: Int) {
        if (compose.onAllNodesWithTag("editor_teacher_$index").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("editor_lesson_toggle_$index").performScrollTo().performClick()
    }
    private fun replace(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun diskState(): AppState = Json.decodeFromString(File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "timetables-v1.json",
    ).readText())
}
