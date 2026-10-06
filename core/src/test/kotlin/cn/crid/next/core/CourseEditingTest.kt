package cn.crid.next.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CourseEditingTest {
    private val semester = Semester(
        id = "semester", name = "Autumn", startDate = "2026-09-07", endDate = "2026-09-27", weeks = 3,
        periods = listOf(
            Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40"),
            Period(3, "10:00", "10:45"), Period(4, "10:55", "11:40"),
        ),
    )
    private val recurring = Lesson(
        weeks = listOf(1, 2, 3), weekday = 1, startPeriod = 1, endPeriod = 2,
        location = "Room A", teacher = "Teacher A", note = "Lecture",
    )
    private val primary = Course(
        id = "math-a", name = "Math", color = 0xff2864a0.toInt(), credits = "2",
        extra = mapOf("Department" to "Science", "Source" to "First record"), lessons = listOf(recurring),
    )
    private val secondary = primary.copy(
        id = "math-b", name = "  ＭＡＴＨ  ", color = 0xffc78932.toInt(), credits = "3",
        extra = mapOf("Department" to "Engineering", "Source" to "Second record", "Code" to "M102"),
        lessons = listOf(recurring.copy(weeks = listOf(2), teacher = "Teacher B", location = "Room B", note = "Lab")),
    )
    private val unrelated = primary.copy(id = "history", name = "History", color = 0xff2f8a84.toInt())
    private val plan = Plan(id = "plan", semesterId = semester.id, name = "My plan", courses = listOf(primary, unrelated, secondary))
    private val otherPlan = Plan(id = "other-plan", semesterId = semester.id, name = "Other plan", courses = listOf(primary.copy(id = "other-math")))
    private val initial = AppState(
        semesters = listOf(semester), plans = listOf(plan, otherPlan), selectedSemesterId = semester.id, selectedPlanId = plan.id,
    )

    @Test fun `opening a course captures all normalized matches from only its own plan`() {
        val target = CourseEditing.editTarget(initial, plan.id, secondary.id)

        assertEquals(plan.id, target.planId)
        assertEquals(semester.id, target.semesterId)
        assertEquals(listOf(primary, secondary), target.expectedCourses)
        assertEquals(target, Json.decodeFromString<CourseEditTarget>(Json.encodeToString(target)))
        assertEquals(plan.courses, initial.courses())
    }

    @Test fun `combined draft preserves every source lesson and metadata value`() {
        val third = secondary.copy(
            id = "math-c", credits = "4", extra = mapOf("Department" to "Arts", "Source" to "Third record"),
            lessons = listOf(recurring, recurring),
        )
        val state = initial.withCourses(listOf(primary, unrelated, secondary, third))
        val target = CourseEditing.editTarget(state, plan.id, secondary.id)
        val combined = requireNotNull(CourseEditing.combinedCourse(target))

        assertEquals(primary.id, combined.id)
        assertEquals(primary.color, combined.color)
        assertEquals(primary.credits, combined.credits)
        assertEquals(primary.lessons + secondary.lessons + third.lessons, combined.lessons)
        assertEquals("Science", combined.extra["Department"])
        assertEquals("First record", combined.extra["Source"])
        assertEquals("M102", combined.extra["Code"])
        listOf("Engineering", "Arts", "Second record", "Third record", "3", "4").forEach { value ->
            assertTrue("Missing source metadata: $value", combined.extra.values.any { value in it })
        }

        val saved = CourseEditing.save(state, target, combined)
        assertEquals(combined, saved.courses().first())
        assertEquals(listOf(primary, unrelated, secondary, third), state.courses())
    }

    @Test fun `opening and validating a draft leave the original state untouched`() {
        val before = Json.encodeToString(initial)
        val editTarget = CourseEditing.editTarget(initial, plan.id, primary.id)
        val createTarget = CourseEditing.newTarget(initial, plan.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(editTarget))

        assertTrue(CourseEditing.validateDraft(draft, semester).isEmpty())
        assertTrue(CourseEditing.validateDraft(draft.copy(name = " "), semester).isNotEmpty())
        assertTrue(createTarget.expectedCourses.isEmpty())
        assertNull(CourseEditing.combinedCourse(createTarget))
        assertEquals(before, Json.encodeToString(initial))
    }

    @Test fun `saving aggregate replaces its first position while preserving other course order and colors`() {
        val before = unrelated.copy(id = "before", name = "Before", color = 0)
        val after = unrelated.copy(id = "after", name = "After", color = primary.color)
        val state = initial.withCourses(listOf(before, primary, unrelated, secondary, after))
        val target = CourseEditing.editTarget(state, plan.id, secondary.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(target)).copy(name = "Advanced Math")

        val saved = CourseEditing.save(state, target, draft)

        assertEquals(listOf(before, draft, unrelated, after), saved.courses())
        assertEquals(otherPlan, saved.plans.single { it.id == otherPlan.id })
        assertEquals(listOf(before, primary, unrelated, secondary, after), state.courses())
    }

    @Test fun `saving uses current state and preserves unrelated concurrent changes`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(target)).copy(name = "Advanced Math")
        val changedOther = unrelated.copy(name = "Modern History", lessons = listOf(recurring.copy(note = "Updated elsewhere")))
        val added = unrelated.copy(id = "new-other", name = "Geography", color = 0)
        val changedPlan = otherPlan.copy(name = "Renamed elsewhere", courses = emptyList())
        val extraPlan = otherPlan.copy(id = "new-plan", name = "New plan", courses = listOf(added))
        val current = initial.copy(
            plans = listOf(plan.copy(name = "Updated plan title", courses = listOf(primary, added, changedOther, secondary)), changedPlan, extraPlan),
            selectedPlanId = otherPlan.id,
            settings = initial.settings.copy(remindersEnabled = true),
        )
        val before = Json.encodeToString(current)

        val saved = CourseEditing.save(current, target, draft)

        assertEquals(listOf(draft, added, changedOther), saved.courses())
        assertEquals("Updated plan title", saved.plans.first().name)
        assertEquals(listOf(changedPlan, extraPlan), saved.plans.drop(1))
        assertEquals(current.settings, saved.settings)
        assertEquals(current.selectedPlanId, saved.selectedPlanId)
        assertEquals(before, Json.encodeToString(current))
    }

    @Test fun `target changes or removal reject save delete and lesson deletion`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(target))
        val variants = listOf(
            listOf(primary.copy(name = "Renamed elsewhere"), unrelated, secondary),
            listOf(primary.copy(color = 123), unrelated, secondary),
            listOf(primary.copy(extra = mapOf("Concurrent" to "Change")), unrelated, secondary),
            listOf(primary, unrelated, secondary.copy(lessons = listOf(recurring.copy(teacher = "New teacher")))),
            listOf(unrelated, secondary),
            listOf(primary, unrelated),
        )
        variants.forEach { courses ->
            val current = initial.withCourses(courses)
            val before = Json.encodeToString(current)
            expectFailure<CourseEditConflictException> { CourseEditing.save(current, target, draft) }
            expectFailure<CourseEditConflictException> { CourseEditing.delete(current, target) }
            expectFailure<CourseEditConflictException> { CourseEditing.deleteLesson(current, target, primary.id, 0) }
            assertEquals(before, Json.encodeToString(current))
        }
    }

    @Test fun `removed target plan semester or changed plan semester reject pending transactions`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val createTarget = CourseEditing.newTarget(initial, plan.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(target))
        val anotherSemester = semester.copy(id = "another-semester")
        val variants = listOf(
            initial.copy(plans = listOf(otherPlan)),
            initial.copy(semesters = emptyList()),
            initial.copy(semesters = listOf(semester, anotherSemester), plans = listOf(plan.copy(semesterId = anotherSemester.id), otherPlan)),
        )
        variants.forEach { current ->
            expectFailure<CourseEditConflictException> { CourseEditing.save(current, target, draft) }
            expectFailure<CourseEditConflictException> { CourseEditing.save(current, createTarget, unrelated.copy(id = "new", name = "New")) }
            expectFailure<CourseEditConflictException> { CourseEditing.delete(current, target) }
            expectFailure<CourseEditConflictException> { CourseEditing.deleteLesson(current, target, primary.id, 0) }
        }
    }

    @Test fun `new course receives a color without recoloring existing records`() {
        val oldUncolored = unrelated.copy(color = 0)
        val oldShared = unrelated.copy(id = "shared", name = "Geography", color = primary.color)
        val state = initial.withCourses(listOf(primary, oldUncolored, oldShared, secondary))
        val target = CourseEditing.newTarget(state, plan.id)
        val draft = unrelated.copy(id = "new-course", name = "Art", color = 0)

        val saved = CourseEditing.save(state, target, draft)
        val created = saved.courses().single { it.id == draft.id }

        assertNotEquals(0, created.color)
        assertEquals(draft.copy(color = created.color), created)
        assertEquals(state.courses(), saved.courses().filterNot { it.id == draft.id })
        assertEquals(otherPlan, saved.plans.last())
    }

    @Test fun `create rejects normalized duplicate names and exposes all conflicting course IDs`() {
        val target = CourseEditing.newTarget(initial, plan.id)
        val draft = primary.copy(id = "new-course", name = " math ")

        val failure = expectFailure<CourseNameConflictException> { CourseEditing.save(initial, target, draft) }

        assertEquals(setOf(primary.id, secondary.id), failure.courseIds.toSet())
        assertEquals(listOf(primary, unrelated, secondary), initial.courses())
    }

    @Test fun `rename rejects existing normalized names outside the captured target`() {
        val target = CourseEditing.editTarget(initial, plan.id, unrelated.id)
        val draft = unrelated.copy(name = "Ｍａｔｈ")

        val failure = expectFailure<CourseNameConflictException> { CourseEditing.save(initial, target, draft) }

        assertEquals(setOf(primary.id, secondary.id), failure.courseIds.toSet())
    }

    @Test fun `a concurrently created same name course is never silently overwritten`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val newcomer = primary.copy(id = "concurrent-math", name = "math")
        val current = initial.withCourses(plan.courses + newcomer)

        val failure = expectFailure<CourseNameConflictException> {
            CourseEditing.save(current, target, requireNotNull(CourseEditing.combinedCourse(target)))
        }

        assertEquals(listOf(newcomer.id), failure.courseIds)
        assertEquals(plan.courses + newcomer, current.courses())
    }

    @Test fun `course name uniqueness is scoped to the selected plan`() {
        val state = initial.withCourses(listOf(unrelated))
        val target = CourseEditing.newTarget(state, plan.id)
        val draft = primary.copy(id = "new-course")

        val saved = CourseEditing.save(state, target, draft)

        assertEquals(listOf(unrelated, draft), saved.courses())
        assertEquals(otherPlan, saved.plans.last())
    }

    @Test fun `create rejects reused IDs while an edit keeps its captured first ID`() {
        val createTarget = CourseEditing.newTarget(initial, plan.id)
        expectFailure<CourseEditConflictException> {
            CourseEditing.save(initial, createTarget, unrelated.copy(name = "A new name"))
        }

        val editTarget = CourseEditing.editTarget(initial, plan.id, secondary.id)
        val draft = requireNotNull(CourseEditing.combinedCourse(editTarget)).copy(id = "replacement-id")
        val saved = CourseEditing.save(initial, editTarget, draft)

        assertEquals(draft.copy(id = primary.id), saved.courses().first())
        assertEquals(listOf(primary.id, unrelated.id), saved.courses().map { it.id })
    }

    @Test fun `period clock fixed date and unscheduled lessons retain every detail on save`() {
        val clock = recurring.copy(weekday = 2, startPeriod = null, endPeriod = null, startTime = "14:10", endTime = "15:20", teacher = "Clock teacher")
        val fixed = clock.copy(weeks = emptyList(), weekday = 1, date = "2026-09-14", note = "One-off lesson")
        val pending = Lesson(weeks = listOf(2, 3), weekday = 0, teacher = "Pending teacher", location = "Pending room", note = "Time to be confirmed", unscheduled = true)
        val draft = unrelated.copy(id = "new-course", name = "Mixed arrangements", lessons = listOf(recurring, clock, fixed, pending))
        val before = Json.encodeToString(initial)

        assertTrue(CourseEditing.validateDraft(draft, semester).isEmpty())
        val saved = CourseEditing.save(initial, CourseEditing.newTarget(initial, plan.id), draft)

        assertEquals(draft, saved.courses().last())
        assertEquals(before, Json.encodeToString(initial))
    }

    @Test fun `compatible imported date week and clock period expressions remain valid`() {
        val dual = recurring.copy(date = "2026-09-07", startTime = "08:00", endTime = "09:40")
        val draft = primary.copy(lessons = listOf(dual))
        val state = initial.withCourses(listOf(draft, unrelated))
        val target = CourseEditing.editTarget(state, plan.id, primary.id)

        assertTrue(CourseEditing.validateDraft(draft, semester).isEmpty())
        assertEquals(draft, CourseEditing.save(state, target, draft).courses().first())
        assertEquals(dual, state.courses().first().lessons.single())
    }

    @Test fun `invalid drafts are rejected in validation and save without changing the source`() {
        val invalidLessons = listOf(
            recurring.copy(weeks = emptyList()), recurring.copy(weeks = listOf(0)), recurring.copy(weeks = listOf(-1)),
            recurring.copy(weeks = listOf(99)), recurring.copy(weekday = 0), recurring.copy(weekday = 8),
            recurring.copy(startPeriod = null), recurring.copy(endPeriod = null), recurring.copy(startPeriod = 0),
            recurring.copy(startPeriod = 3, endPeriod = 2), recurring.copy(endPeriod = 5),
            recurring.copy(startPeriod = null, endPeriod = null),
            recurring.copy(startPeriod = null, endPeriod = null, startTime = "09:00"),
            recurring.copy(startPeriod = null, endPeriod = null, endTime = "10:00"),
            recurring.copy(startPeriod = null, endPeriod = null, startTime = "25:00", endTime = "26:00"),
            recurring.copy(startPeriod = null, endPeriod = null, startTime = "10:00", endTime = "10:00"),
            recurring.copy(startPeriod = null, endPeriod = null, startTime = "11:00", endTime = "10:00"),
            recurring.copy(startPeriod = null, endPeriod = null, startTime = "09:00:01", endTime = "10:00"),
            recurring.copy(startTime = "09:00", endTime = "10:00"),
            recurring.copy(weeks = emptyList(), date = "2026-02-30"),
            recurring.copy(weeks = emptyList(), date = "2026-09-06"),
            recurring.copy(weeks = emptyList(), date = "2026-09-28"),
            recurring.copy(unscheduled = true),
            Lesson(weeks = emptyList(), weekday = 0, unscheduled = true),
            Lesson(weeks = listOf(1), weekday = 0, date = "2026-09-07", unscheduled = true),
            Lesson(weeks = listOf(1), weekday = 0, startTime = "09:00", endTime = "10:00", unscheduled = true),
        )
        val valid = unrelated.copy(id = "new-course", name = "New course")
        val invalidDrafts = invalidLessons.map { valid.copy(lessons = listOf(it)) } + listOf(
            valid.copy(id = " "), valid.copy(name = " \t "), valid.copy(lessons = emptyList()), valid.copy(name = "x".repeat(301)),
        )
        val target = CourseEditing.newTarget(initial, plan.id)
        val before = Json.encodeToString(initial)

        invalidDrafts.forEachIndexed { index, draft ->
            val errors = CourseEditing.validateDraft(draft, semester)
            assertTrue("Invalid draft $index was accepted", errors.isNotEmpty())
            val failure = expectFailure<CourseDraftValidationException> { CourseEditing.save(initial, target, draft) }
            assertTrue("Invalid draft $index did not expose validation errors", failure.errors.isNotEmpty())
            assertEquals(before, Json.encodeToString(initial))
        }
    }

    @Test fun `delete removes only captured course records and retains unrelated concurrent changes`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val changedOther = unrelated.copy(credits = "5")
        val newcomer = primary.copy(id = "later-math")
        val current = initial.withCourses(listOf(primary, changedOther, secondary, newcomer))
        val before = Json.encodeToString(current)

        val deleted = CourseEditing.delete(current, target)

        assertEquals(listOf(changedOther, newcomer), deleted.courses())
        assertEquals(otherPlan, deleted.plans.last())
        assertEquals(before, Json.encodeToString(current))
    }

    @Test fun `deleting one identical lesson by source index keeps the remaining copies`() {
        val repeated = primary.copy(lessons = listOf(recurring, recurring, recurring.copy(note = "Last")))
        val state = initial.withCourses(listOf(repeated, unrelated, secondary))
        val target = CourseEditing.editTarget(state, plan.id, primary.id)

        val saved = CourseEditing.deleteLesson(state, target, primary.id, 1)

        assertEquals(listOf(recurring, recurring.copy(note = "Last")), saved.courses().first().lessons)
        assertEquals(repeated.copy(lessons = listOf(recurring, recurring.copy(note = "Last"))), saved.courses().first())
        assertEquals(listOf(unrelated, secondary), saved.courses().drop(1))
        assertEquals(listOf(recurring, recurring, recurring.copy(note = "Last")), state.courses().first().lessons)
    }

    @Test fun `deleting a source final lesson keeps its metadata in the remaining logical course`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)

        val saved = CourseEditing.deleteLesson(initial, target, secondary.id, 0)

        val survivor = saved.courses().first()
        assertEquals(listOf(primary.id, unrelated.id), saved.courses().map { it.id })
        assertEquals(primary.name, survivor.name)
        assertEquals(primary.color, survivor.color)
        assertEquals(primary.lessons, survivor.lessons)
        assertEquals(primary.credits, survivor.credits)
        assertTrue(survivor.extra.values.containsAll(primary.extra.values + secondary.extra.values + secondary.credits))
        assertEquals(unrelated, saved.courses().last())
        assertEquals(otherPlan, saved.plans.last())
        assertEquals(plan.courses, initial.courses())
    }

    @Test fun `metadata survives consecutive source deletions without restoring any deleted lesson`() {
        val finalSource = primary.copy(id = "math-c", credits = "四学分",
            extra = mapOf("Department" to "Arts", "其他学分记录" to "来源学分说明"),
            lessons = listOf(recurring.copy(teacher = "Teacher C", weeks = listOf(3))))
        val state = initial.withCourses(listOf(primary, unrelated, secondary, finalSource))
        val firstTarget = CourseEditing.editTarget(state, plan.id, primary.id)
        val afterFirst = CourseEditing.deleteLesson(state, firstTarget, primary.id, 0)
        val secondTarget = CourseEditing.editTarget(afterFirst, plan.id, secondary.id)

        val saved = CourseEditing.deleteLesson(afterFirst, secondTarget, secondary.id, 0)

        val survivor = saved.courses().single { it.id == finalSource.id }
        assertEquals(finalSource.lessons, survivor.lessons)
        assertEquals(finalSource.color, survivor.color)
        assertEquals(finalSource.credits, survivor.credits)
        assertTrue(survivor.extra.values.containsAll(primary.extra.values + secondary.extra.values + finalSource.extra.values))
        assertTrue(survivor.extra.values.containsAll(listOf(primary.credits, secondary.credits)))
        assertEquals(listOf(unrelated.id, finalSource.id), saved.courses().map { it.id })
        assertEquals(otherPlan, saved.plans.last())
        assertEquals(listOf(primary, unrelated, secondary, finalSource), state.courses())
    }

    @Test fun `deleting an emptying source preserves uncaptured concurrent courses exactly`() {
        val target = CourseEditing.editTarget(initial, plan.id, secondary.id)
        val concurrent = primary.copy(id = "new-source", credits = "5", extra = mapOf("Added" to "Later"))
        val changedOther = unrelated.copy(credits = "6")
        val current = initial.withCourses(listOf(concurrent, primary, changedOther, secondary))

        val saved = CourseEditing.deleteLesson(current, target, secondary.id, 0)

        assertEquals(concurrent, saved.courses().first())
        assertEquals(changedOther, saved.courses().last())
        val survivor = saved.courses().single { it.id == primary.id }
        assertEquals(primary.lessons, survivor.lessons)
        assertTrue(survivor.extra.values.containsAll(secondary.extra.values + secondary.credits))
        assertEquals(current.settings, saved.settings)
        assertEquals(otherPlan, saved.plans.last())
    }

    @Test fun `deleting the only lesson in a plan leaves the plan available`() {
        val state = initial.withCourses(listOf(primary))
        val target = CourseEditing.editTarget(state, plan.id, primary.id)

        val saved = CourseEditing.deleteLesson(state, target, primary.id, 0)

        assertEquals(plan.copy(courses = emptyList()), saved.plans.first())
        assertEquals(state.selectedPlanId, saved.selectedPlanId)
        assertEquals(listOf(primary), state.courses())
    }

    @Test fun `lesson deletion rejects out of range indices and courses outside the captured target`() {
        val target = CourseEditing.editTarget(initial, plan.id, primary.id)
        val before = Json.encodeToString(initial)

        expectFailure<IllegalArgumentException> { CourseEditing.deleteLesson(initial, target, primary.id, -1) }
        expectFailure<IllegalArgumentException> { CourseEditing.deleteLesson(initial, target, primary.id, primary.lessons.size) }
        expectFailure<IllegalArgumentException> { CourseEditing.deleteLesson(initial, target, unrelated.id, 0) }
        expectFailure<IllegalArgumentException> { CourseEditing.deleteLesson(initial, target, "missing", 0) }

        assertEquals(before, Json.encodeToString(initial))
    }

    private fun AppState.courses(): List<Course> = plans.single { it.id == this@CourseEditingTest.plan.id }.courses

    private fun AppState.withCourses(courses: List<Course>): AppState = copy(
        plans = plans.map { if (it.id == this@CourseEditingTest.plan.id) it.copy(courses = courses) else it },
    )

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure::class.java.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
