package cn.crid.next.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.crid.next.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
internal data class EditorExtra(val key: String, val value: String, val rowId: String = newId())

@Serializable
internal data class EditorLesson(
    val lesson: Lesson,
    val rowId: String = newId(),
    val originalWeeks: List<Int> = lesson.weeks,
)

@Serializable
internal data class EditorDraft(
    val id: String,
    val name: String = "",
    val credits: String = "",
    val color: Int = 0,
    val extra: List<EditorExtra> = emptyList(),
    val lessons: List<EditorLesson> = emptyList(),
) {
    fun course() = Course(id, name.trim(), color, credits, extra.associate { it.key to it.value }, lessons.map { it.lesson })

    companion object {
        fun from(course: Course) = EditorDraft(course.id, course.name, course.credits, course.color,
            course.extra.map { EditorExtra(it.key, it.value) }, course.lessons.map { EditorLesson(it) })
    }
}

@Serializable
private data class EditorSession(
    val sessionId: String,
    val target: CourseEditTarget,
    val semester: Semester,
    val original: EditorDraft,
    val draft: EditorDraft,
)

/** The target snapshot and the entire draft survive recreation; a write outlives a rotation. */
internal class EditorDraftViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private var session by mutableStateOf(savedState.get<String>(STATE)?.let {
        runCatching { Json.decodeFromString<EditorSession>(it) }.getOrNull()
    })
    var busy by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val draft: EditorDraft? get() = session?.draft
    val target: CourseEditTarget? get() = session?.target
    val semester: Semester? get() = session?.semester
    val dirty: Boolean get() = session?.let { it.original != it.draft } == true

    fun prepare(sessionId: String, target: CourseEditTarget, semester: Semester, initialDate: LocalDate?) {
        if (session?.sessionId == sessionId) return
        check(!busy)
        val course = CourseEditing.combinedCourse(target) ?: Course(name = "", lessons = listOf(newEditorLesson(semester, initialDate)))
        val draft = EditorDraft.from(course)
        session = EditorSession(sessionId, target, semester, draft, draft)
        error = null
        saved = false
        persist()
    }

    fun edit(change: (EditorDraft) -> EditorDraft) {
        if (busy || saved) return
        session = session?.let { it.copy(draft = change(it.draft)) }
        error = null
        persist()
    }

    fun editLesson(rowId: String, change: (Lesson) -> Lesson) = edit { current ->
        current.copy(lessons = current.lessons.map { if (it.rowId == rowId) it.copy(lesson = change(it.lesson)) else it })
    }

    fun showError(message: String) { error = message }

    fun save(onSave: suspend (CourseEditTarget, Course) -> Unit, failureMessage: (Throwable) -> String) {
        if (busy || saved) return
        val snapshot = session ?: return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                onSave(snapshot.target, snapshot.draft.course())
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failureMessage(failure)
            } finally {
                busy = false
            }
        }
    }

    fun clear() {
        if (busy) return
        session = null
        savedState.remove<String>(STATE)
        saved = false
        error = null
    }

    private fun persist() { savedState[STATE] = session?.let { Json.encodeToString(it) } }

    private companion object { const val STATE = "course_editor_session" }
}

internal fun editorWeekCount(semester: Semester): Int = semester.weeks.coerceAtLeast(1)

/** Historical boundary-week values stay selectable only on the arrangement that already had them. */
internal fun editorWeekChoices(semester: Semester, entry: EditorLesson): List<Int> =
    ((1..editorWeekCount(semester)).toList() + entry.originalWeeks + entry.lesson.weeks).distinct().sorted()

internal fun newEditorLesson(semester: Semester, initialDate: LocalDate? = null): Lesson {
    val first = semester.periods.minByOrNull { it.number }
    val date = initialDate?.coerceIn(LocalDate.parse(semester.startDate), LocalDate.parse(semester.endDate))
    return Lesson(weeks = if (date == null) (1..editorWeekCount(semester)).toList() else emptyList(),
        weekday = date?.dayOfWeek?.value ?: 1, date = date?.toString(),
        startPeriod = first?.number, endPeriod = first?.number,
        startTime = if (first == null) "08:00" else null, endTime = if (first == null) "08:45" else null)
}

internal fun changeEditorLessonMode(old: Lesson, next: String, semester: Semester, date: LocalDate): Lesson {
    val scheduled = if (old.unscheduled) newEditorLesson(semester).copy(weeks = old.weeks,
        teacher = old.teacher, location = old.location, note = old.note) else old
    return when (next) {
        "pending" -> old.copy(unscheduled = true, weekday = 0, date = null,
            weeks = old.weeks.ifEmpty { newEditorLesson(semester).weeks }, startPeriod = null, endPeriod = null, startTime = null, endTime = null)
        "date" -> scheduled.copy(unscheduled = false, date = date.toString(), weekday = date.dayOfWeek.value)
        "weekly" -> scheduled.copy(unscheduled = false, date = null, weekday = scheduled.weekday.coerceIn(1, 7),
            weeks = scheduled.weeks.ifEmpty { newEditorLesson(semester).weeks })
        else -> error("Unknown lesson mode")
    }
}
