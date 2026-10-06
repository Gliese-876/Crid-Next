package cn.crid.next.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import cn.crid.next.core.AppState
import cn.crid.next.core.Course
import cn.crid.next.core.CourseEditTarget
import cn.crid.next.core.CourseEditing
import cn.crid.next.core.Language
import cn.crid.next.core.Lesson
import cn.crid.next.core.Period
import cn.crid.next.core.Plan
import cn.crid.next.core.Semester
import cn.crid.next.core.Settings
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Real editor interactions with an in-memory transaction boundary; no persistent app data writes. */
class EditorInstrumentationTest {
    @get:Rule val compose = createComposeRule()
    private val text = UiText(Language.EN)
    private val semester = Semester(
        id = "editor-term", name = "Autumn", startDate = "2026-09-07", endDate = "2026-10-04", weeks = 4,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")),
    )
    private val firstLesson = Lesson(
        weeks = listOf(1, 3), weekday = 1, startPeriod = 1, endPeriod = 2,
        teacher = "Ada", location = "Room 101", note = "Bring a notebook",
    )
    private val original = Course(
        id = "editor-course", name = "Linear algebra", color = 0x336699,
        credits = "3", extra = mapOf("Course code" to "MATH101"), lessons = listOf(firstLesson),
    )

    @Test fun emptyNameShowsValidationAndKeepsTheEditorOpen() {
        val fixture = fixture()
        install(fixture)
        replace("editor_name", "   ")

        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()

        compose.onNodeWithTag("editor_error").assertTextContains("Enter a course name.")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(0, fixture.saveAttempts)
            assertEquals(0, fixture.dismissals)
            assertEquals(original, fixture.state.plan!!.courses.single())
        }
    }

    @Test fun newCourseSavesOnlyAfterTheTransactionCompletes() {
        val fixture = fixture(courses = emptyList(), create = true)
        val gate = CompletableDeferred<Unit>()
        fixture.saveGate = gate
        install(fixture)
        replace("editor_name", "Discrete mathematics")
        replace("editor_credits", "2.5")

        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil { fixture.saveAttempts == 1 }
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(fixture.open)
            assertTrue(fixture.state.plan!!.courses.isEmpty())
            gate.complete(Unit)
        }
        awaitClosed()

        compose.runOnIdle {
            val saved = fixture.state.plan!!.courses.single()
            assertEquals("Discrete mathematics", saved.name)
            assertEquals("2.5", saved.credits)
            assertTrue(saved.lessons.isNotEmpty())
            assertEquals(1, fixture.saveAttempts)
            assertEquals(1, fixture.dismissals)
        }
    }

    @Test fun failedSaveRetainsEveryEditedFieldAndCanBeRetried() {
        val fixture = fixture()
        fixture.failNextSave = true
        install(fixture)
        replace("editor_name", "Advanced linear algebra")
        replace("editor_credits", "4")
        expandLesson(0)
        replace("editor_teacher_0", "Grace")
        replace("editor_location_0", "Room 202")
        replace("editor_note_0", "Updated reading list")

        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editor_error").assertTextContains(
            "Could not save the course. Your changes are still here; try again.",
        )
        compose.runOnIdle {
            assertEquals(1, fixture.saveAttempts)
            assertEquals(0, fixture.dismissals)
            assertEquals(original, fixture.state.plan!!.courses.single())
        }
        assertField("editor_name", "Advanced linear algebra")
        assertField("editor_credits", "4")
        expandLesson(0)
        assertField("editor_teacher_0", "Grace")
        assertField("editor_location_0", "Room 202")
        assertField("editor_note_0", "Updated reading list")

        compose.onNodeWithTag("editor_save").performClick()
        awaitClosed()
        compose.runOnIdle {
            assertEquals(2, fixture.saveAttempts)
            assertEquals(
                original.copy(
                    name = "Advanced linear algebra", credits = "4",
                    lessons = listOf(firstLesson.copy(teacher = "Grace", location = "Room 202", note = "Updated reading list")),
                ),
                fixture.state.plan!!.courses.single(),
            )
        }
    }

    @Test fun editingOneArrangementPreservesOtherTeachersWeeksAndImportedMetadata() {
        val secondLesson = firstLesson.copy(
            weeks = listOf(2, 4), teacher = "Emmy", location = "Room 303", note = "Seminar",
        )
        val importedPart = original.copy(
            id = "editor-course-imported-part", lessons = listOf(secondLesson),
            extra = mapOf("Department" to "Mathematics"),
        )
        val fixture = fixture(courses = listOf(original, importedPart))
        install(fixture)
        expandLesson(0)
        replace("editor_teacher_0", "Grace")
        compose.onNodeWithTag("editor_week_0_1").performScrollTo().assertIsSelected().performClick()
        compose.onNodeWithTag("editor_week_0_2").performScrollTo().assertIsNotSelected().performClick()
        expandLesson(1)
        assertField("editor_teacher_1", "Emmy")
        compose.onNodeWithTag("editor_week_1_2").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("editor_week_1_4").performScrollTo().assertIsSelected()
        replace("editor_note_1", "Seminar with exercises")

        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        awaitClosed()

        compose.runOnIdle {
            val saved = fixture.state.plan!!.courses.single()
            assertEquals(original.id, saved.id)
            assertEquals(original.color, saved.color)
            assertEquals("3", saved.credits)
            assertEquals(mapOf("Course code" to "MATH101", "Department" to "Mathematics"), saved.extra)
            assertEquals(
                listOf(
                    firstLesson.copy(weeks = listOf(2, 3), teacher = "Grace"),
                    secondLesson.copy(note = "Seminar with exercises"),
                ),
                saved.lessons,
            )
        }
    }

    @Test fun addingAnArrangementLeavesTheOriginalArrangementIntact() {
        val fixture = fixture()
        install(fixture)
        compose.onNodeWithTag("editor_add_lesson").performScrollTo().performClick()
        expandLesson(1)
        replace("editor_teacher_1", "Sofia")
        replace("editor_location_1", "Lab 2")

        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        awaitClosed()

        compose.runOnIdle {
            val lessons = fixture.state.plan!!.courses.single().lessons
            assertEquals(2, lessons.size)
            assertEquals(firstLesson, lessons.first())
            assertEquals("Sofia", lessons[1].teacher)
            assertEquals("Lab 2", lessons[1].location)
        }
    }

    @Test fun nativeDateAndClockSelectionsAreSavedAsNaturalDateAndClockTimes() {
        val fixedLesson = firstLesson.copy(
            weeks = emptyList(), date = "2026-09-07", startPeriod = null, endPeriod = null,
            startTime = "08:00", endTime = "09:00",
        )
        val fixture = fixture(courses = listOf(original.copy(lessons = listOf(fixedLesson))))
        install(fixture)
        expandLesson(0)
        compose.onNodeWithTag("editor_date_0").performScrollTo().performClick()
        compose.onNodeWithTag("native_date_picker").assertIsDisplayed()
        compose.onNode(
            hasText("September 8, 2026", substring = true) and hasAnyAncestor(hasTestTag("native_date_picker")),
        ).performClick()
        compose.onNodeWithText("OK").performClick()

        compose.onNodeWithTag("editor_start_time_0").performScrollTo().performClick()
        selectClockMinutes("30 minutes")
        compose.onNodeWithTag("editor_end_time_0").performScrollTo().performClick()
        selectClockMinutes("45 minutes")
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        awaitClosed()

        compose.runOnIdle {
            val saved = fixture.state.plan!!.courses.single().lessons.single()
            assertEquals("2026-09-08", saved.date)
            assertEquals(2, saved.weekday)
            assertEquals("08:30", saved.startTime)
            assertEquals("09:45", saved.endTime)
            assertEquals(null, saved.startPeriod)
            assertEquals(null, saved.endPeriod)
            assertEquals(fixedLesson.teacher, saved.teacher)
        }
    }

    @Test fun cancelAsksBeforeDiscardingAndKeepEditingPreservesTheDraft() {
        val fixture = fixture()
        install(fixture)
        replace("editor_name", "Draft name")
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.onNodeWithTag("editor_keep_editing").assertIsDisplayed().performClick()

        assertField("editor_name", "Draft name")
        compose.runOnIdle {
            assertEquals(0, fixture.dismissals)
            assertEquals(0, fixture.saveAttempts)
        }
        compose.onNodeWithTag("editor_cancel").performClick()
        compose.onNodeWithTag("editor_discard_changes").assertIsDisplayed().performClick()
        awaitClosed()
        compose.runOnIdle {
            assertEquals(1, fixture.dismissals)
            assertEquals(0, fixture.saveAttempts)
            assertEquals(original, fixture.state.plan!!.courses.single())
        }
    }

    @Test fun changingTheSelectedPlanCannotRedirectTheDraftToAnotherPlan() {
        val fixture = fixture()
        val other = Plan("editor-other-plan", semester.id, "Other plan", emptyList())
        fixture.state = fixture.state.copy(plans = fixture.state.plans + other)
        install(fixture)
        replace("editor_name", "Scoped draft")
        compose.runOnIdle { fixture.state = fixture.state.copy(selectedPlanId = other.id) }
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()

        compose.onNodeWithTag("editor_error").assertTextContains("Switch back to this plan to save your changes.")
        assertField("editor_name", "Scoped draft")
        compose.runOnIdle {
            assertEquals(0, fixture.saveAttempts)
            assertTrue(fixture.state.plans.single { it.id == other.id }.courses.isEmpty())
            assertEquals(original, fixture.state.plans.single { it.id == fixture.target.planId }.courses.single())
            fixture.state = fixture.state.copy(selectedPlanId = fixture.target.planId)
        }
        compose.onNodeWithTag("editor_save").performClick()
        awaitClosed()
        compose.runOnIdle {
            assertEquals("Scoped draft", fixture.state.plans.single { it.id == fixture.target.planId }.courses.single().name)
            assertEquals(other, fixture.state.plans.single { it.id == other.id })
            assertEquals(fixture.target, fixture.savedTargets.single())
        }
    }

    @Test fun draftSurvivesSavedStateRecreationWithoutSubmittingOrLosingArrangements() {
        val fixture = fixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EditorContent(fixture) }
        replace("editor_name", "Restored draft")
        replace("editor_credits", "6")
        expandLesson(0)
        replace("editor_teacher_0", "Katherine")
        replace("editor_note_0", "Keep after recreation")
        compose.onNodeWithTag("editor_add_lesson").performScrollTo().performClick()
        expandLesson(1)
        replace("editor_teacher_1", "Mary")

        restoration.emulateSavedInstanceStateRestore()

        assertField("editor_name", "Restored draft")
        assertField("editor_credits", "6")
        expandLesson(0)
        assertField("editor_teacher_0", "Katherine")
        assertField("editor_note_0", "Keep after recreation")
        expandLesson(1)
        assertField("editor_teacher_1", "Mary")
        compose.runOnIdle {
            assertEquals(0, fixture.saveAttempts)
            assertEquals(0, fixture.dismissals)
        }
        compose.onNodeWithTag("editor_save").assertIsDisplayed().performClick()
        awaitClosed()
        compose.runOnIdle {
            val saved = fixture.state.plan!!.courses.single()
            assertEquals("Restored draft", saved.name)
            assertEquals("6", saved.credits)
            assertEquals(listOf("Katherine", "Mary"), saved.lessons.map { it.teacher })
            assertEquals(firstLesson.weeks, saved.lessons.first().weeks)
        }
    }

    private fun fixture(courses: List<Course> = listOf(original), create: Boolean = false): Fixture {
        val plan = Plan("editor-plan", semester.id, "Teaching plan", courses)
        val state = AppState(
            semesters = listOf(semester), plans = listOf(plan), selectedSemesterId = semester.id,
            selectedPlanId = plan.id, settings = Settings(language = Language.EN, holidaysEnabled = false),
        )
        val target = if (create) CourseEditing.newTarget(state, plan.id)
        else CourseEditing.editTarget(state, plan.id, courses.first().id)
        return Fixture(state, target)
    }

    private fun install(fixture: Fixture) = compose.setContent { EditorContent(fixture) }

    @Composable private fun EditorContent(fixture: Fixture) {
        MaterialTheme {
            if (fixture.open) CourseEditorDialog(
                state = fixture.state,
                target = fixture.target,
                text = text,
                onDismiss = { fixture.dismissals++; fixture.open = false },
                onSave = { target, course ->
                    fixture.saveAttempts++
                    fixture.saveGate?.await()
                    if (fixture.failNextSave) {
                        fixture.failNextSave = false
                        throw IOException("Simulated storage failure")
                    }
                    fixture.state = CourseEditing.save(fixture.state, target, course)
                    fixture.savedTargets += target
                },
                initialDate = LocalDate.of(2026, 9, 7),
                sessionId = "editor-instrumentation",
            )
        }
    }

    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }

    private fun assertField(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertTextContains(value)
    }

    private fun expandLesson(index: Int) {
        if (compose.onAllNodesWithTag("editor_teacher_$index").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("editor_lesson_toggle_$index").performScrollTo().performClick()
        }
    }

    private fun selectClockMinutes(label: String) {
        compose.onNodeWithTag("native_time_picker").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select minutes", substring = true).performClick()
        // Android resources may surround the readable label with bidi formatting characters.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(label, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(label, substring = true).performClick()
        compose.onNodeWithText("OK").performClick()
    }

    private fun awaitClosed() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
    }

    private class Fixture(initialState: AppState, val target: CourseEditTarget) {
        var state by mutableStateOf(initialState)
        var open by mutableStateOf(true)
        var saveAttempts = 0
        var dismissals = 0
        var failNextSave = false
        var saveGate: CompletableDeferred<Unit>? = null
        val savedTargets = mutableListOf<CourseEditTarget>()
    }
}
