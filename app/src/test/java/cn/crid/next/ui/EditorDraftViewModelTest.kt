package cn.crid.next.ui

import androidx.lifecycle.SavedStateHandle
import cn.crid.next.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class EditorDraftViewModelTest {
    private val semester = Semester("term", "Autumn", "2026-09-07", "2026-10-04", 4,
        listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")))
    private val lessons = listOf(
        Lesson(listOf(1, 3), 1, 1, 2, teacher = "Ada", location = "A101", note = "Lecture"),
        Lesson(listOf(2, 4), 1, 1, 2, teacher = "Emmy", location = "B102", note = "Seminar"),
    )
    private val course = Course("course", "Algebra", 0xff123456.toInt(), "3.5",
        linkedMapOf("Course code" to "M101", "Other credits" to "2"), lessons)
    private val target = CourseEditTarget("plan", semester.id, listOf(course))
    private val text = UiText(Language.EN)

    @Test fun openingAndEditingMetadataKeepsEveryTeachingRecordIntact() {
        val draft = EditorDraft.from(course)
        assertEquals(course, draft.course())
        val renamed = draft.copy(name = "Advanced algebra").course()
        assertEquals(course.lessons, renamed.lessons)
        assertEquals(course.color, renamed.color)
        assertEquals(course.credits, renamed.credits)
        assertEquals(course.extra, renamed.extra)
    }

    @Test fun savedStateRestoresSnapshotAndUnsavedArrangements() {
        val handle = SavedStateHandle()
        val first = EditorDraftViewModel(handle)
        first.prepare("session", target, semester, null)
        first.edit { it.copy(name = "My draft", lessons = it.lessons + EditorLesson(
            Lesson(weeks = listOf(1, 4), weekday = 0, unscheduled = true, teacher = "Sofia"))) }
        val restored = EditorDraftViewModel(SavedStateHandle(mapOf("course_editor_session" to handle.get<String>("course_editor_session"))))
        val changedTarget = target.copy(expectedCourses = listOf(course.copy(name = "Concurrent change")))
        restored.prepare("session", changedTarget, semester, null)
        assertEquals(target, restored.target)
        assertEquals(first.draft, restored.draft)
        assertEquals(listOf(1, 4), restored.draft!!.lessons.last().lesson.weeks)
        assertTrue(restored.dirty)
    }

    @Test fun cancelledSessionDoesNotLeakIntoNextCourse() {
        val model = EditorDraftViewModel(SavedStateHandle())
        model.prepare("first", target, semester, null)
        model.edit { it.copy(name = "Discard me") }
        model.clear()
        model.prepare("second", CourseEditTarget("plan", semester.id), semester, LocalDate.of(2026, 9, 9))
        assertEquals("", model.draft!!.name)
        assertEquals("2026-09-09", model.draft!!.lessons.single().lesson.date)
        assertFalse(model.dirty)
        assertFalse(model.saved)
    }

    @Test fun validationCatchesEmptyWeeksReversedTimesAndDuplicateDetailNames() {
        val base = EditorDraft.from(course)
        assertNull(editorValidationError(base, semester, text))
        assertTrue(editorValidationError(base.copy(lessons = listOf(EditorLesson(lessons.first().copy(weeks = emptyList())))), semester, text)!!.contains("teaching weeks"))
        assertTrue(editorValidationError(base.copy(lessons = listOf(EditorLesson(lessons.first().copy(
            startPeriod = null, endPeriod = null, startTime = "10:00", endTime = "09:00")))), semester, text)!!.contains("end time"))
        assertTrue(editorValidationError(base.copy(extra = listOf(EditorExtra("Code", "A"), EditorExtra("Code", "B"))), semester, text)!!.contains("different name"))
    }

    @Test fun explicitPendingLessonsStayPendingWithTheirOriginalWeeksAndTeachers() {
        val pending = course.copy(lessons = listOf(Lesson(weeks = listOf(1, 3), weekday = 0, teacher = "Ada", unscheduled = true)))
        val draft = EditorDraft.from(pending)
        assertEquals(pending, draft.course())
        assertNull(editorValidationError(draft, semester, text))
        assertEquals(4, editorWeekCount(semester))
        assertEquals(listOf(1, 2, 3, 4), newEditorLesson(semester).weeks)
        val legacyWeek = EditorDraft.from(pending.copy(lessons = listOf(pending.lessons.single().copy(weeks = listOf(5)))))
        assertNull(editorValidationError(legacyWeek, semester, text))
        assertEquals(listOf(5), legacyWeek.course().lessons.single().weeks)
        assertEquals("2026-09-07", newEditorLesson(semester, LocalDate.of(2026, 8, 1)).date)
    }

    @Test fun newLessonsAndRegularWeekChoicesUseTheDeclaredWeeksForEitherWeekStart() {
        val sundaySemester = semester.copy(startDate = "2026-09-06", endDate = "2026-10-03")
        listOf(semester, sundaySemester).forEach { term ->
            val lesson = newEditorLesson(term)
            assertEquals(listOf(1, 2, 3, 4), lesson.weeks)
            assertEquals(listOf(1, 2, 3, 4), editorWeekChoices(term, EditorLesson(lesson)))
            assertEquals(4, editorWeekCount(term))
            assertNull(editorValidationError(EditorDraft.from(course.copy(lessons = listOf(lesson))), term, text))
        }
    }

    @Test fun switchingFromSpecificDatesUsesTheSameWeekDefaultsAndPreservesExistingWeeks() {
        val sundaySemester = semester.copy(startDate = "2026-09-06", endDate = "2026-10-03")
        listOf(semester, sundaySemester).forEach { term ->
            val date = LocalDate.parse(term.startDate)
            val dated = newEditorLesson(term, date).copy(teacher = "Sofia", location = "A103", note = "Reading")
            val weekly = changeEditorLessonMode(dated, "weekly", term, date)
            val pending = changeEditorLessonMode(dated, "pending", term, date)
            assertEquals(listOf(1, 2, 3, 4), weekly.weeks)
            assertEquals(listOf(1, 2, 3, 4), pending.weeks)
            assertNull(weekly.date)
            assertTrue(pending.unscheduled)
            assertEquals(0, pending.weekday)
            assertEquals("Sofia", pending.teacher)
            assertEquals("A103", weekly.location)
            assertEquals("Reading", weekly.note)
            val legacy = pending.copy(weeks = listOf(1, 3, 5))
            assertEquals(listOf(1, 3, 5), changeEditorLessonMode(legacy, "weekly", term, date).weeks)
            assertEquals(listOf(1, 3, 5), changeEditorLessonMode(legacy, "date", term, date).weeks)
        }
    }

    @Test fun legacyBoundaryWeeksCanBeReselectedAfterRecreationWithoutAppearingOnNewArrangements() {
        val legacyCourse = course.copy(lessons = listOf(lessons.first().copy(weeks = listOf(1, 3, 5))))
        val legacyTarget = target.copy(expectedCourses = listOf(legacyCourse))
        val handle = SavedStateHandle()
        val first = EditorDraftViewModel(handle)
        first.prepare("legacy", legacyTarget, semester, null)
        val rowId = first.draft!!.lessons.single().rowId
        first.editLesson(rowId) { it.copy(weeks = listOf(1, 3)) }
        val restored = EditorDraftViewModel(SavedStateHandle(mapOf("course_editor_session" to handle.get<String>("course_editor_session"))))
        val restoredEntry = restored.draft!!.lessons.single()
        assertEquals(listOf(1, 2, 3, 4, 5), editorWeekChoices(semester, restoredEntry))
        restored.editLesson(rowId) { it.copy(weeks = it.weeks + 5) }
        assertNull(editorValidationError(restored.draft!!, semester, text))
        assertEquals(listOf(1, 3, 5), restored.draft!!.course().lessons.single().weeks)

        val fresh = EditorLesson(newEditorLesson(semester))
        assertFalse(5 in editorWeekChoices(semester, fresh))
        val invalidNewWeek = restored.draft!!.copy(lessons = listOf(fresh.copy(lesson = fresh.lesson.copy(weeks = listOf(5)))))
        assertTrue(editorValidationError(invalidNewWeek, semester, text)!!.contains("within this semester"))
    }
}
