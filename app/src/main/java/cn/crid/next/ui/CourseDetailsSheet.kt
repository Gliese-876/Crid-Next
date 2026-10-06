package cn.crid.next.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.crid.next.core.Course
import cn.crid.next.core.Lesson
import cn.crid.next.core.Occurrence
import cn.crid.next.core.OccurrenceStatus
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.Semester
import cn.crid.next.core.courseKey
import cn.crid.next.core.courseLocationSpacing
import cn.crid.next.core.courseNameSpacing
import cn.crid.next.core.courseTextSpacing
import cn.crid.next.core.formatWeekRanges
import java.time.LocalDate

/** The original identity and index survive visual sorting and equal imported records. */
private data class TeachingRecordSource(val courseId: String, val lessonIndex: Int, val lesson: Lesson)

/** All same-name source courses must be supplied, including arrangements outside the displayed week. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CourseDetailsSheet(
    relatedCourses: List<Course>,
    occurrence: Occurrence?,
    semester: Semester?,
    text: UiText,
    onDismiss: () -> Unit,
    onEditCourse: (ModalOrigin) -> Unit,
    onEditLesson: (courseId: String, lessonIndex: Int, origin: ModalOrigin) -> Unit,
    onDeleteCourse: () -> Unit,
    onDeleteLesson: (courseId: String, lessonIndex: Int) -> Unit,
    actionError: String? = null,
    actionBusy: Boolean = false,
) {
    val course = relatedCourses.firstOrNull() ?: return
    val courseName = courseNameSpacing(course.name)
    val records = remember(relatedCourses) {
        relatedCourses.flatMap { source ->
            source.lessons.mapIndexed { index, lesson -> TeachingRecordSource(source.id, index, lesson) }
        }.sortedWith(compareBy<TeachingRecordSource> { it.lesson.weeks.minOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.lesson.date ?: "" }.thenBy { it.lesson.weekday }
            .thenBy { it.lesson.startTime ?: "" }.thenBy { it.lesson.startPeriod ?: Int.MAX_VALUE })
    }
    val inlineOccurrence = occurrence?.takeIf { selected ->
        records.singleOrNull()?.let { source ->
            source.courseId == selected.course.id && source.lesson == selected.lesson &&
                semester?.let { ScheduleEngine.lessonTimes(it, source.lesson) } == (selected.start to selected.end)
        } == true
    }
    val credits = relatedCourses.map { it.credits }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
    var deleteCourse by rememberSaveable(relatedCourses, occurrence) { mutableStateOf(false) }
    var deleteRecordCourseId by rememberSaveable(relatedCourses, occurrence) { mutableStateOf<String?>(null) }
    var deleteRecordIndex by rememberSaveable(relatedCourses, occurrence) { mutableStateOf(-1) }
    val editCourseOrigin = rememberModalOrigin()
    val deleteCourseOrigin = rememberModalOrigin()
    var deleteRecordOrigin by remember(relatedCourses, occurrence) { mutableStateOf<ModalOrigin?>(null) }
    val deleteRecord = records.firstOrNull { it.courseId == deleteRecordCourseId && it.lessonIndex == deleteRecordIndex }
    val cancelRecordDelete = { deleteRecordCourseId = null; deleteRecordIndex = -1; deleteRecordOrigin = null }

    // Keep the scroll viewport inside the visible window when opening long teaching records.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        WindowColorProvider {
            MatchBottomSheetSystemBars()
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("course_details"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().testTag("course_details_header"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
                        val titleStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                        val actionStyle = MaterialTheme.typography.labelLarge
                        val editLabel = text.t("编辑", "Edit")
                        val deleteLabel = text.t("删除", "Delete")
                        val title: @Composable (Modifier) -> Unit = { modifier ->
                            VisualCenterText(courseName, modifier = modifier.testTag("course_details_title").semantics { heading() },
                                style = titleStyle)
                        }
                        val edit: @Composable (Modifier) -> Unit = { modifier ->
                            TextButton(onClick = { editCourseOrigin.capture(); onEditCourse(editCourseOrigin) }, enabled = !actionBusy,
                                modifier = modifier.heightIn(min = 48.dp).modalOrigin(editCourseOrigin).testTag("edit_course")
                                    .semantics { contentDescription = text.t("编辑课程", "Edit course") }) {
                                VisualCenterText(editLabel, modifier = if (stacked) Modifier.centerVisualText() else Modifier, style = actionStyle)
                            }
                        }
                        val delete: @Composable (Modifier) -> Unit = { modifier ->
                            TextButton(onClick = { deleteCourseOrigin.capture(); deleteCourse = true }, enabled = !actionBusy,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = modifier.heightIn(min = 48.dp).modalOrigin(deleteCourseOrigin).testTag("delete_course")
                                    .semantics { contentDescription = text.t("删除课程", "Delete course") }) {
                                VisualCenterText(deleteLabel, modifier = if (stacked) Modifier.centerVisualText() else Modifier, style = actionStyle)
                            }
                        }
                        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            title(Modifier.fillMaxWidth())
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                edit(Modifier)
                                delete(Modifier)
                            }
                        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            title(alignByVisualCenter(Modifier.weight(1f)))
                            edit(alignByVisualCenter())
                            delete(alignByVisualCenter())
                        }
                    }
                    if (credits.isNotEmpty()) Text(text.t("$credits 学分", "$credits credits"),
                        modifier = Modifier.padding(top = 4.dp).testTag("course_details_credits"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                actionError?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("course_action_error").semantics { liveRegion = LiveRegionMode.Assertive })
                }
                occurrence?.takeIf { inlineOccurrence == null }?.let { selected ->
                    // DisplayCourse may project several consecutive source periods into this visible span.
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(text.t("本次课程", "Selected class"), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            CourseDetailRow(text.t("日期", "Date"), text.date(selected.date), Modifier.testTag("course_occurrence_date"))
                            CourseDetailRow(text.t("时间", "Time"), "${selected.start}–${selected.end}", Modifier.testTag("course_occurrence_time"))
                            if (selected.lesson.location.isNotBlank()) CourseDetailRow(text.t("地点", "Location"),
                                courseLocationSpacing(selected.lesson.location), Modifier.testTag("course_occurrence_location"))
                            if (selected.statuses.isNotEmpty()) CourseDetailRow(text.t("状态", "Status"), occurrenceStatusText(selected, text))
                        }
                    }
                }
                if (records.size > 1) Text(text.t("授课安排 · ${records.size}", "Teaching arrangements · ${records.size}"),
                    modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                records.forEachIndexed { index, source ->
                    TeachingRecord(
                        source.lesson, semester, text, Modifier.testTag("teaching_record_$index"),
                        onEdit = { origin -> onEditLesson(source.courseId, source.lessonIndex, origin) },
                        onDelete = { origin -> deleteRecordOrigin = origin; deleteRecordCourseId = source.courseId; deleteRecordIndex = source.lessonIndex },
                        actionTag = "teaching_record_$index",
                        actionNumber = index + 1,
                        actionsEnabled = !actionBusy,
                        selectedOccurrence = inlineOccurrence,
                    )
                }
                val extras = relatedCourses.flatMap { it.extra.entries }.filter { it.value.isNotBlank() }.groupBy({ it.key }, { it.value })
                if (extras.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    extras.forEach { (label, values) ->
                        CourseDetailRow(label, values.map(::courseTextSpacing).distinct().joinToString("\n"))
                    }
                }
            }
        }
    }

    if (deleteCourse) {
        AnimatedAppAlertDialog(
            onDismissRequest = { deleteCourse = false },
            origin = deleteCourseOrigin,
            title = { Text(text.t("删除课程？", "Delete course?")) },
            text = {
                Text(text.t(
                    "从当前课表方案中删除「$courseName」及其全部 ${records.size} 条授课安排，包含所有周次。",
                    "Remove “$courseName” and all ${records.size} teaching arrangements, across every week, from this timetable plan.",
                ))
            },
            confirmButton = { motion ->
                TextButton(onClick = { motion.finish { deleteCourse = false; onDeleteCourse() } }, enabled = !actionBusy,
                    modifier = Modifier.testTag("confirm_delete_course"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    VisualCenterText(text.t("删除课程", "Delete course"), Modifier.centerVisualText())
                }
            },
            dismissButton = { motion ->
                TextButton(onClick = motion.dismiss, modifier = Modifier.testTag("cancel_delete_course")) { VisualCenterText(text.t("取消", "Cancel"), Modifier.centerVisualText()) }
            },
        )
    }
    deleteRecord?.let { source ->
        AnimatedAppAlertDialog(
            onDismissRequest = cancelRecordDelete,
            origin = deleteRecordOrigin,
            title = { Text(text.t("删除这条授课安排？", "Delete this arrangement?")) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text.t(
                        "从当前课表方案中删除「$courseName」的以下安排，包含这条安排的所有上课日期。",
                        "Remove the following arrangement for “$courseName”, including every date it covers, from this timetable plan.",
                    ))
                    TeachingRecord(source.lesson, semester, text)
                    if (records.size == 1) Text(text.t(
                        "这是这门课程的最后一条授课安排，删除后课程也会移除。",
                        "This is the course’s last arrangement. The course will also be removed.",
                    ))
                }
            },
            confirmButton = { motion ->
                TextButton(onClick = { motion.finish { cancelRecordDelete(); onDeleteLesson(source.courseId, source.lessonIndex) } }, enabled = !actionBusy,
                    modifier = Modifier.testTag("confirm_delete_arrangement"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    VisualCenterText(text.t("删除安排", "Delete arrangement"), Modifier.centerVisualText())
                }
            },
            dismissButton = { motion ->
                TextButton(onClick = motion.dismiss, modifier = Modifier.testTag("cancel_delete_arrangement")) { VisualCenterText(text.t("取消", "Cancel"), Modifier.centerVisualText()) }
            },
        )
    }
}

/** Labels align across rows while every field value retains the same readable type size. */
@Composable
internal fun CourseDetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            val labelStyle = MaterialTheme.typography.bodySmall
            val valueStyle = MaterialTheme.typography.bodyLarge
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VisualCenterText(label, modifier = alignByVisualCenter(Modifier.width(80.dp)),
                    style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VisualCenterText(value, modifier = alignByVisualCenter(Modifier.weight(1f)), style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Reused by pending-course and grouped-course surfaces without imposing navigation or mutation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeachingRecord(
    lesson: Lesson,
    semester: Semester?,
    text: UiText,
    modifier: Modifier = Modifier,
    onEdit: ((ModalOrigin) -> Unit)? = null,
    onDelete: ((ModalOrigin) -> Unit)? = null,
    actionTag: String? = null,
    actionNumber: Int? = null,
    actionsEnabled: Boolean = true,
    selectedOccurrence: Occurrence? = null,
) {
    val hasActions = onEdit != null || onDelete != null
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (hasActions) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!lesson.unscheduled) {
                    val day = selectedOccurrence?.let { text.date(it.date) } ?: lessonDayText(lesson, text)
                    Text(day.orEmpty(), modifier = (if (selectedOccurrence != null) Modifier.testTag("course_occurrence_date") else Modifier)
                        .semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                CourseDetailRow(text.t("时间", "Time"), lessonTimeText(lesson, semester, text),
                    if (selectedOccurrence != null) Modifier.testTag("course_occurrence_time") else Modifier)
                if (lesson.location.isNotBlank()) CourseDetailRow(text.t("地点", "Location"), courseLocationSpacing(lesson.location))
                if (lesson.teacher.isNotBlank()) CourseDetailRow(text.t("教师", "Teacher"), courseTextSpacing(lesson.teacher))
                val weeks = formatWeekRanges(lesson.weeks, if (text.isEnglish) ", " else "、")
                if (weeks.isNotEmpty()) CourseDetailRow(text.t("周次", "Teaching weeks"), text.t("第 $weeks 周", "Weeks $weeks"))
                selectedOccurrence?.let { selected ->
                    if (lesson.date != null && selected.date.toString() != lesson.date) {
                        CourseDetailRow(text.t("原定日期", "Original date"), lessonDayText(lesson, text).orEmpty())
                    } else if (selected.date.dayOfWeek.value != lesson.weekday && lesson.date == null) {
                        CourseDetailRow(text.t("原定星期", "Regular day"), lessonDayText(lesson, text).orEmpty())
                    }
                    if (selected.statuses.isNotEmpty()) CourseDetailRow(text.t("状态", "Status"), occurrenceStatusText(selected, text))
                }
                if (lesson.note.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    CourseDetailRow(text.t("备注", "Notes"), courseTextSpacing(lesson.note))
                }
            }
            if (hasActions) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    onEdit?.let { edit ->
                        val origin = rememberModalOrigin()
                        val label = actionNumber?.let { text.t("编辑第 $it 条授课安排", "Edit arrangement $it") }
                            ?: text.t("编辑授课安排", "Edit arrangement")
                        TextButton(onClick = { origin.capture(); edit(origin) }, enabled = actionsEnabled,
                            modifier = (actionTag?.let { Modifier.testTag("${it}_edit") } ?: Modifier)
                                .heightIn(min = 48.dp).modalOrigin(origin).semantics { contentDescription = label }) {
                            VisualCenterText(text.t("编辑", "Edit"), Modifier.centerVisualText())
                        }
                    }
                    onDelete?.let { remove ->
                        val origin = rememberModalOrigin()
                        val label = actionNumber?.let { text.t("删除第 $it 条授课安排", "Delete arrangement $it") }
                            ?: text.t("删除授课安排", "Delete arrangement")
                        TextButton(onClick = { origin.capture(); remove(origin) }, enabled = actionsEnabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = (actionTag?.let { Modifier.testTag("${it}_delete") } ?: Modifier)
                                .heightIn(min = 48.dp).modalOrigin(origin).semantics { contentDescription = label }) {
                            VisualCenterText(text.t("删除", "Delete"), Modifier.centerVisualText())
                        }
                    }
                }
            }
        }
    }
}

/** The management list includes every source course, even when the grid has no visible occurrence. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CourseManagerSheet(
    courses: List<Course>,
    text: UiText,
    onDismiss: () -> Unit,
    onSelectCourse: (Course) -> Unit,
    onAddCourse: (ModalOrigin) -> Unit,
    semester: Semester? = null,
) {
    val groups = remember(courses) { courses.groupBy { courseKey(it.name) }.values.toList() }
    val addCourseOrigin = rememberModalOrigin()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        WindowColorProvider {
            MatchBottomSheetSystemBars()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 40.dp).testTag("course_manager"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("course_manager_header")) {
                    val stackActions = maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f
                    val title: @Composable () -> Unit = {
                        VisualCenterText(text.t("管理课程", "Manage courses"), modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    val add: @Composable (Modifier) -> Unit = { modifier ->
                        FilledTonalButton(onClick = { addCourseOrigin.capture(); onAddCourse(addCourseOrigin) },
                            modifier = modifier.heightIn(min = 48.dp).modalOrigin(addCourseOrigin).testTag("course_manager_add")
                                .semantics { contentDescription = text.t("新增课程", "Add course") }) {
                            VisualCenterText(text.t("新增", "Add"), modifier = if (stackActions) Modifier.centerVisualText() else Modifier)
                        }
                    }
                    if (stackActions) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        title()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { add(Modifier) }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(alignByVisualCenter(Modifier.weight(1f))) { title() }
                        add(alignByVisualCenter())
                    }
                }
                if (groups.isEmpty()) Text(text.t("还没有课程", "No courses yet"),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEachIndexed { index, related ->
                    val first = related.first()
                    val lessons = related.flatMap { it.lessons }
                    val previews = courseArrangementPreviews(lessons, semester, text)
                    Surface(onClick = { onSelectCourse(first) }, modifier = Modifier.fillMaxWidth().testTag("managed_course_$index"),
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(courseNameSpacing(first.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            previews.take(2).forEachIndexed { previewIndex, preview ->
                                Column(Modifier.fillMaxWidth().testTag("managed_course_${index}_arrangement_$previewIndex"),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    preview.day?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text(preview.time, style = MaterialTheme.typography.bodyLarge)
                                    if (preview.location.isNotBlank()) Text(preview.location, style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text.t("${lessons.size} 条授课安排", "${lessons.size} teaching arrangements"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val pending = lessons.count { it.unscheduled }
                                if (pending > 0) Text(text.t("$pending 条待排时间", "$pending awaiting a time"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (previews.size > 2) Text(text.t("查看全部安排", "View all arrangements"),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Only identical visible summaries are collapsed; source courses and record counts stay intact. */
internal data class CourseArrangementPreview(val day: String?, val time: String, val location: String)

internal fun courseArrangementPreviews(lessons: List<Lesson>, semester: Semester?, text: UiText): List<CourseArrangementPreview> =
    lessons.map { CourseArrangementPreview(lessonDayText(it, text), lessonTimeText(it, semester, text), courseLocationSpacing(it.location)) }.distinct()

private fun lessonDayText(lesson: Lesson, text: UiText): String? {
    if (lesson.unscheduled) return null
    return lesson.date?.let { runCatching { text.date(LocalDate.parse(it)) }.getOrNull() }
        ?: lesson.weekday.takeIf { it in 1..7 }?.let { text.weekday(LocalDate.of(2026, 1, 5).plusDays((it - 1).toLong())) }
}

internal fun lessonTimeText(lesson: Lesson, semester: Semester?, text: UiText): String {
    if (lesson.unscheduled) return text.t("待排时间", "Awaiting a time")
    val time = semester?.let { ScheduleEngine.lessonTimes(it, lesson) }?.let { "${it.first}–${it.second}" }
        ?: if (lesson.startTime != null && lesson.endTime != null) "${lesson.startTime}–${lesson.endTime}" else null
    val periods = lesson.startPeriod?.let { start -> lesson.endPeriod?.let { end ->
        val range = if (start == end) "$start" else "$start–$end"
        text.t("第 $range 节", if (start == end) "Period $range" else "Periods $range")
    } }
    return listOfNotNull(time, periods).joinToString(" · ").ifEmpty { text.t("待排时间", "Awaiting a time") }
}

private fun occurrenceStatusText(occurrence: Occurrence, text: UiText): String = occurrence.statuses.joinToString(" · ") {
    when (it) {
        OccurrenceStatus.OUT_OF_WEEK -> text.t("非本周", "Other week")
        OccurrenceStatus.HOLIDAY -> text.t("休假 · 不上课", "Holiday · no class")
        OccurrenceStatus.MAKEUP -> text.t("调休补课", "Make-up class")
    }
}
