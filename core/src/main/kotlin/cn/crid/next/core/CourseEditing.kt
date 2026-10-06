package cn.crid.next.core

import kotlinx.serialization.Serializable

/** An edit is scoped to the records seen when the editor opened, never to a whole plan snapshot. */
@Serializable
data class CourseEditTarget(
    val planId: String,
    val semesterId: String,
    val expectedCourses: List<Course> = emptyList(),
)

class CourseEditConflictException(message: String) : IllegalArgumentException(message)

class CourseNameConflictException(val courseIds: List<String>) :
    IllegalArgumentException("方案中已有同名课程，请打开该课程编辑")

class CourseDraftValidationException(val errors: List<String>) :
    IllegalArgumentException(errors.joinToString("；"))

/** Pure transactions shared by the editor and repository; no operation mutates its input. */
object CourseEditing {
    fun newTarget(state: AppState, planId: String): CourseEditTarget {
        val plan = state.plans.singleOrNull { it.id == planId }
            ?: throw CourseEditConflictException("目标方案已不存在，请重新选择方案")
        val target = CourseEditTarget(plan.id, plan.semesterId)
        currentPlan(state, target)
        return target
    }

    /** Include every original record of the logical course, including arrangements in other weeks. */
    fun editTarget(state: AppState, planId: String, courseId: String): CourseEditTarget {
        val target = newTarget(state, planId)
        val plan = currentPlan(state, target)
        val course = plan.courses.singleOrNull { it.id == courseId }
            ?: throw CourseEditConflictException("该课程已不存在，请重新打开课程")
        return target.copy(expectedCourses = plan.courses.filter { courseKey(it.name) == courseKey(course.name) })
    }

    /** Keep conflicting imported metadata as separately named fields; preserve every teaching record. */
    fun combinedCourse(target: CourseEditTarget): Course? {
        val first = target.expectedCourses.firstOrNull() ?: return null
        val extras = linkedMapOf<String, String>()
        fun preserve(label: String, value: String) {
            var name = label
            var suffix = 2
            while (name in extras && extras[name] != value) name = "$label（${suffix++}）"
            extras[name] = value
        }
        target.expectedCourses.forEach { course -> course.extra.forEach { (label, value) -> preserve(label, value) } }
        val credits = target.expectedCourses.firstOrNull { it.credits.isNotBlank() }?.credits ?: first.credits
        target.expectedCourses.forEach { course ->
            if (course.credits.isNotBlank() && course.credits != credits) preserve("其他学分记录", course.credits)
        }
        return first.copy(credits = credits, extra = extras, lessons = target.expectedCourses.flatMap { it.lessons })
    }

    fun validateDraft(draft: Course, semester: Semester): List<String> = buildList {
        if (draft.id.isBlank()) add("课程标识不能为空")
        addAll(DataValidator.validateSemester(semester))
        addAll(DataValidator.validateCourses(listOf(draft), semester))
    }.distinct()

    fun save(state: AppState, target: CourseEditTarget, draft: Course): AppState {
        val plan = currentPlan(state, target)
        checkExpected(plan, target)
        val semester = state.semesters.single { it.id == target.semesterId }
        val errors = validateDraft(draft, semester)
        if (errors.isNotEmpty()) throw CourseDraftValidationException(errors)
        val expectedIds = target.expectedCourses.map { it.id }.toSet()
        val course = draft.copy(id = target.expectedCourses.firstOrNull()?.id ?: draft.id, name = draft.name.trim())
        val conflicts = plan.courses.filter { it.id !in expectedIds && courseKey(it.name) == courseKey(course.name) }
        if (conflicts.isNotEmpty()) throw CourseNameConflictException(conflicts.map { it.id })
        if (expectedIds.isEmpty() && plan.courses.any { it.id == course.id })
            throw CourseEditConflictException("课程标识已被使用，请重新打开新增课程")

        // Assign a new identity only to a new course. Existing colors, including legacy values,
        // stay untouched even if an older file contains different colors for the same name.
        val saved = if (expectedIds.isEmpty() && course.color == 0)
            CourseColors.assign(plan.courses + course).last() else course
        var inserted = false
        val courses = buildList {
            plan.courses.forEach { existing ->
                if (existing.id in expectedIds) {
                    if (!inserted) { add(saved); inserted = true }
                } else add(existing)
            }
            if (!inserted) add(saved)
        }
        return replaceCourses(state, plan, courses, semester)
    }

    /** Delete only the captured logical course, leaving newly added and unrelated records intact. */
    fun delete(state: AppState, target: CourseEditTarget): AppState {
        val plan = currentPlan(state, target)
        checkExpected(plan, target, requireExisting = true)
        val ids = target.expectedCourses.map { it.id }.toSet()
        return replaceCourses(state, plan, plan.courses.filterNot { it.id in ids },
            state.semesters.single { it.id == target.semesterId })
    }

    /** Index refers to the original source course, so two equal arrangements can be removed individually. */
    fun deleteLesson(state: AppState, target: CourseEditTarget, courseId: String, lessonIndex: Int): AppState {
        val plan = currentPlan(state, target)
        checkExpected(plan, target, requireExisting = true)
        val expected = target.expectedCourses.singleOrNull { it.id == courseId }
            ?: throw CourseEditConflictException("该安排不属于正在编辑的课程")
        if (lessonIndex !in expected.lessons.indices)
            throw CourseEditConflictException("该安排已不存在，请重新打开课程")
        // Removing an arrangement does not delete metadata belonging to the logical course.
        // Move it only to another captured source so unrelated concurrent records stay intact.
        val recipient = if (expected.lessons.size == 1)
            target.expectedCourses.firstOrNull { it.id != courseId } else null
        val preservedRecipient = recipient?.let { survivor ->
            requireNotNull(combinedCourse(target.copy(expectedCourses = listOf(survivor, expected))))
                .copy(lessons = survivor.lessons)
        }
        val courses = plan.courses.mapNotNull { course ->
            when (course.id) {
                preservedRecipient?.id -> preservedRecipient
                courseId -> {
                    val remaining = course.lessons.filterIndexed { index, _ -> index != lessonIndex }
                    if (remaining.isEmpty()) null else course.copy(lessons = remaining)
                }
                else -> course
            }
        }
        return replaceCourses(state, plan, courses, state.semesters.single { it.id == target.semesterId })
    }

    private fun currentPlan(state: AppState, target: CourseEditTarget): Plan {
        if (state.semesters.count { it.id == target.semesterId } != 1)
            throw CourseEditConflictException("目标学期已不存在，请重新选择学期")
        val plan = state.plans.singleOrNull { it.id == target.planId && it.semesterId == target.semesterId }
            ?: throw CourseEditConflictException("目标方案已不存在或已更换学期，请重新选择方案")
        if (plan.courses.any { it.id.isBlank() } || plan.courses.map { it.id }.distinct().size != plan.courses.size)
            throw CourseEditConflictException("课程标识重复或缺失，请先修复方案")
        return plan
    }

    private fun checkExpected(plan: Plan, target: CourseEditTarget, requireExisting: Boolean = false) {
        val expected = target.expectedCourses
        if ((requireExisting && expected.isEmpty()) || expected.map { it.id }.distinct().size != expected.size ||
            expected.map { courseKey(it.name) }.distinct().size > 1 ||
            expected.any { snapshot -> plan.courses.singleOrNull { it.id == snapshot.id } != snapshot })
            throw CourseEditConflictException("课程已被修改或删除，请重新打开后再编辑")
    }

    private fun replaceCourses(state: AppState, plan: Plan, courses: List<Course>, semester: Semester): AppState {
        val errors = if (courses.isEmpty()) emptyList() else DataValidator.validateCourses(courses, semester)
        if (errors.isNotEmpty()) throw CourseDraftValidationException(errors)
        return state.copy(plans = state.plans.map { if (it.id == plan.id) it.copy(courses = courses) else it })
    }
}
