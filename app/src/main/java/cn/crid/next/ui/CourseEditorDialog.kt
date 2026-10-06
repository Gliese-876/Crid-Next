package cn.crid.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.crid.next.core.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseEditorDialog(
    state: AppState,
    target: CourseEditTarget,
    text: UiText,
    onDismiss: () -> Unit,
    onSave: suspend (CourseEditTarget, Course) -> Unit,
    initialDate: LocalDate? = null,
    origin: ModalOrigin? = null,
    sessionId: String = "${target.planId}:${target.expectedCourses.firstOrNull()?.id ?: "new"}",
    initialLessonIndex: Int? = null,
) {
    val model: EditorDraftViewModel = viewModel()
    val targetSemester = state.semesters.find { it.id == target.semesterId }
    if (targetSemester != null) model.prepare(sessionId, target, targetSemester, initialDate)
    val semester = model.semester
    val draft = model.draft
    val frozenTarget = model.target
    if (semester == null || draft == null || frozenTarget == null) {
        AnimatedAppAlertDialog(onDismissRequest = onDismiss, origin = origin,
            title = { Text(text.t("无法编辑课程", "Course unavailable")) },
            text = { Text(text.t("这个学期或方案已被删除。", "This semester or plan has been deleted.")) },
            confirmButton = { motion -> TextButton(onClick = motion.dismiss) { VisualCenterText(text.t("关闭", "Close"), modifier = Modifier.centerVisualText()) } })
        return
    }
    var confirmDiscard by rememberSaveable(sessionId) { mutableStateOf(false) }
    var expandedLesson by rememberSaveable(sessionId) { mutableStateOf(draft.lessons.getOrNull(initialLessonIndex ?: 0)?.rowId) }
    var initialFocus by rememberSaveable(sessionId) { mutableStateOf(initialLessonIndex != null) }
    val close = { model.clear(); onDismiss() }
    val plan = state.plans.find { it.id == frozenTarget.planId && it.semesterId == frozenTarget.semesterId }
    val contextError = when {
        plan == null || targetSemester == null -> text.t("这个学期或方案已被删除。更改尚未保存。", "This semester or plan has been deleted. Your changes have not been saved.")
        state.selectedPlanId != frozenTarget.planId || state.selectedSemesterId != frozenTarget.semesterId ->
            text.t("请切回这个方案后再保存，更改已保留。", "Switch back to this plan to save your changes.")
        frozenTarget.expectedCourses.any { expected -> plan.courses.find { it.id == expected.id } != expected } ->
            text.t("这门课程已被修改或删除。请保留所需内容，关闭后重新打开课程。", "This course has changed or been deleted. Keep any details you need, then close and reopen it.")
        else -> null
    }
    AnimatedAppDialog(onDismissRequest = close, origin = origin, dismissEnabled = !model.busy,
        backNavigatesContent = true, onContentBack = { if (!model.busy) confirmDiscard = true },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) { motion ->
        LaunchedEffect(model.saved) { if (model.saved) motion.finish(close) }
        fun requestClose() {
            if (model.busy) return
            if (model.dirty) confirmDiscard = true else motion.finish(close)
        }
        // Back navigation stays inside the editor until the user has resolved a dirty draft.
        LaunchedEffect(confirmDiscard, model.dirty) {
            if (confirmDiscard && !model.dirty) { confirmDiscard = false; motion.finish(close) }
        }
        Surface(Modifier.padding(horizontal = 12.dp).widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(.94f)
            .testTag("course_editor"), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.appSurface(AppSurfaceRole.Reading)) {
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                    Text(if (frozenTarget.expectedCourses.isEmpty()) text.t("添加课程", "Add course") else text.t("编辑课程", "Edit course"),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(plan?.name ?: semester.name, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp).testTag("editor_content"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(draft.name, { value -> model.edit { it.copy(name = value) } },
                        label = { Text(text.t("课程名称", "Course name")) }, singleLine = true, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth().testTag("editor_name"))
                    OutlinedTextField(draft.credits, { value -> model.edit { it.copy(credits = value) } },
                        label = { Text(text.t("学分（可选）", "Credits (optional)")) }, singleLine = true, enabled = !model.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("editor_credits"))
                    EditorColorPalette(draft.color, text, !model.busy, frozenTarget.expectedCourses.isEmpty()) { color -> model.edit { it.copy(color = color) } }
                    if (draft.extra.isNotEmpty()) Text(text.t("其他信息", "Other details"), style = MaterialTheme.typography.titleMedium)
                    draft.extra.forEachIndexed { index, entry ->
                        key(entry.rowId) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(entry.key, { value -> model.edit { current -> current.copy(extra = current.extra.map {
                                    if (it.rowId == entry.rowId) it.copy(key = value) else it }) } },
                                    label = { Text(text.t("信息名称", "Detail name")) }, singleLine = true, enabled = !model.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("editor_extra_key_$index"))
                                OutlinedTextField(entry.value, { value -> model.edit { current -> current.copy(extra = current.extra.map {
                                    if (it.rowId == entry.rowId) it.copy(value = value) else it }) } },
                                    label = { Text(entry.key.ifBlank { text.t("内容", "Value") }) }, enabled = !model.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("editor_extra_value_$index"))
                                TextButton(onClick = { model.edit { current -> current.copy(extra = current.extra.filterNot { it.rowId == entry.rowId }) } },
                            enabled = !model.busy) { VisualCenterText(text.t("删除这条信息", "Remove detail"), modifier = Modifier.centerVisualText()) }
                            }
                        }
                    }
                    TextButton(onClick = { model.edit { it.copy(extra = it.extra + EditorExtra("", "")) } }, enabled = !model.busy,
                        modifier = Modifier.testTag("editor_add_extra")) { VisualCenterText(text.t("添加其他信息", "Add detail"), modifier = Modifier.centerVisualText()) }
                    HorizontalDivider()
                    Text(text.t("授课安排", "Teaching arrangements"), style = MaterialTheme.typography.titleLarge)
                    draft.lessons.forEachIndexed { index, entry ->
                        key(entry.rowId) {
                            EditorLessonCard(entry, index, semester, text, !model.busy, expandedLesson == entry.rowId,
                                focus = initialFocus && expandedLesson == entry.rowId, onFocused = { initialFocus = false },
                                onToggle = { expandedLesson = if (expandedLesson == entry.rowId) null else entry.rowId },
                                onChange = { change -> model.editLesson(entry.rowId, change) },
                                onRemove = { model.edit { current -> current.copy(lessons = current.lessons.filterNot { it.rowId == entry.rowId }) } })
                        }
                    }
                    OutlinedButton(onClick = {
                        val entry = EditorLesson(newEditorLesson(semester, initialDate))
                        model.edit { it.copy(lessons = it.lessons + entry) }
                        expandedLesson = entry.rowId
                    }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("editor_add_lesson")) {
                        VisualCenterText(text.t("添加授课安排", "Add teaching arrangement"), modifier = Modifier.centerVisualText())
                    }
                }
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (model.saved) null else contextError ?: model.error)?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("editor_error"))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::requestClose, enabled = !model.busy, modifier = alignByVisualCenter(Modifier.testTag("editor_cancel"))) {
                            VisualCenterText(text.t("取消", "Cancel"))
                        }
                        Button(onClick = {
                            val error = contextError ?: editorValidationError(draft, semester, text)
                            if (error != null) model.showError(error)
                            else model.save(onSave) { failure -> editorSaveError(failure, text) }
                        }, enabled = !model.busy && !model.saved && contextError == null, modifier = alignByVisualCenter(Modifier.testTag("editor_save"))) {
                            if (model.busy) {
                                CircularProgressIndicator(alignByVisualCenter(Modifier.size(18.dp)), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            VisualCenterText(if (model.busy) text.t("正在保存", "Saving") else text.t("保存", "Save"), modifier = alignByVisualCenter())
                        }
                    }
                }
            }
        }
        if (confirmDiscard && model.dirty) {
            AnimatedAppAlertDialog(onDismissRequest = { confirmDiscard = false },
                title = { Text(text.t("放弃未保存的更改？", "Discard unsaved changes?")) },
                text = { Text(text.t("离开后，这次修改将不会保存。", "Your changes will be lost when you leave.")) },
                dismissButton = { confirmation -> TextButton(onClick = confirmation.dismiss,
                modifier = Modifier.testTag("editor_keep_editing")) { VisualCenterText(text.t("继续编辑", "Keep editing"), modifier = Modifier.centerVisualText()) } },
                confirmButton = { confirmation -> TextButton(onClick = {
                    confirmation.finish { confirmDiscard = false; motion.finish(close) }
            }, modifier = Modifier.testTag("editor_discard_changes")) { VisualCenterText(text.t("放弃修改", "Discard changes"), modifier = Modifier.centerVisualText()) } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorColorPalette(selected: Int, text: UiText, enabled: Boolean, automaticAllowed: Boolean, onSelect: (Int) -> Unit) {
    val palette = listOf(
        0xff2864a0.toInt() to text.t("蓝色", "Blue"), 0xffc78932.toInt() to text.t("金色", "Gold"),
        0xff2f8a84.toInt() to text.t("青色", "Teal"), 0xff8c65af.toInt() to text.t("紫色", "Purple"),
        0xff599ebb.toInt() to text.t("浅蓝", "Sky blue"), 0xffb67594.toInt() to text.t("玫瑰色", "Rose"),
        0xff9ba371.toInt() to text.t("橄榄绿", "Olive"), 0xff93623b.toInt() to text.t("棕色", "Brown"),
    ).let { if (selected != 0 && it.none { pair -> pair.first == selected }) it + (selected to text.t("当前颜色", "Current color")) else it }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.t("课程颜色", "Course color"), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (automaticAllowed) FilterChip(selected == 0, { onSelect(0) }, label = { VisualCenterText(text.t("自动", "Automatic"), modifier = Modifier.centerVisualText()) }, enabled = enabled)
            else if (selected == 0) Text(text.t("默认颜色", "Default color"), modifier = Modifier.padding(vertical = 12.dp))
            palette.forEach { (color, label) ->
                Box(Modifier.size(48.dp).selectable(selected == color, enabled, Role.RadioButton) { onSelect(color) }
                    .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(34.dp).background(Color(color), CircleShape).then(if (selected == color)
                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                if (selected == color) VisualCenterText("✓", color = Color(CourseColors.swatch(color).content),
                    modifier = Modifier.background(Color(CourseColors.swatch(color).fill), CircleShape).padding(horizontal = 4.dp).centerVisualText())
                    }
                }
            }
        }
    }
}

private fun editorSaveError(failure: Throwable, text: UiText): String = when (failure) {
    is CourseNameConflictException -> text.t("方案中已有同名课程。请修改名称，或取消后编辑已有课程。", "A course with this name already exists. Change the name, or cancel and edit that course.")
    is CourseEditConflictException -> text.t("课程或方案已发生变化。更改仍在这里，请关闭后重新打开课程。", "The course or plan has changed. Your draft is still here; close and reopen the course.")
    is CourseDraftValidationException -> text.t(failure.errors.joinToString("\n"), "Check the course name, teaching weeks and times, then try again.")
    else -> text.t("未能保存课程。更改已保留，请重试。", "Could not save the course. Your changes are still here; try again.")
}

internal fun editorValidationError(draft: EditorDraft, semester: Semester, text: UiText): String? {
    if (draft.name.isBlank()) return text.t("请填写课程名称。", "Enter a course name.")
    if (draft.extra.any { it.key.isBlank() }) return text.t("请填写其他信息的名称，或删除空白信息。", "Name each detail, or remove empty details.")
    if (draft.extra.map { it.key }.distinct().size != draft.extra.size) return text.t("其他信息的名称不能重复。", "Use a different name for each detail.")
    if (draft.lessons.isEmpty()) return text.t("请添加一条授课安排，也可以选择待排课。", "Add a teaching arrangement; you can mark it as unscheduled.")
    draft.lessons.forEachIndexed { index, entry ->
        val lesson = entry.lesson
        val prefix = text.t("安排 ${index + 1}：", "Arrangement ${index + 1}: ")
        if (lesson.date == null && lesson.weeks.isEmpty()) return prefix + text.t("请选择授课周次。", "Select the teaching weeks.")
        if (lesson.weeks.any { it < 1 || (it > editorWeekCount(semester) && it !in entry.originalWeeks) }) return prefix + text.t("授课周次超出了学期范围。", "Teaching weeks must fall within this semester.")
        if (!lesson.unscheduled) {
            if (lesson.date != null) {
                val date = runCatching { LocalDate.parse(lesson.date) }.getOrNull()
                if (date == null || date.isBefore(LocalDate.parse(semester.startDate)) || date.isAfter(LocalDate.parse(semester.endDate)))
                    return prefix + text.t("请选择学期内的日期。", "Choose a date within this semester.")
            }
            val startPeriod = lesson.startPeriod
            val endPeriod = lesson.endPeriod
            if (startPeriod != null || endPeriod != null) {
                if (startPeriod == null || endPeriod == null || endPeriod < startPeriod ||
                    (startPeriod..endPeriod).any { number -> semester.periods.none { it.number == number } })
                    return prefix + text.t("请选择有效的起止节次。", "Choose valid starting and ending periods.")
            } else {
                val start = runCatching { LocalTime.parse(lesson.startTime) }.getOrNull()
                val end = runCatching { LocalTime.parse(lesson.endTime) }.getOrNull()
                if (start == null || end == null || !start.isBefore(end))
                    return prefix + text.t("结束时间应晚于开始时间。", "The end time must be after the start time.")
            }
        }
    }
    return CourseEditing.validateDraft(draft.course(), semester).takeIf { it.isNotEmpty() }?.let {
        text.t(it.joinToString("\n"), "Check the course details and shorten any unusually long text, then try again.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorLessonCard(
    entry: EditorLesson, index: Int, semester: Semester, text: UiText, enabled: Boolean, expanded: Boolean,
    focus: Boolean, onFocused: () -> Unit,
    onToggle: () -> Unit, onChange: ((Lesson) -> Lesson) -> Unit, onRemove: () -> Unit,
) {
    val lesson = entry.lesson
    val mode = if (lesson.unscheduled) "pending" else if (lesson.date != null) "date" else "weekly"
    val clock = lesson.startPeriod == null && lesson.endPeriod == null
    var picker by rememberSaveable(entry.rowId) { mutableStateOf<String?>(null) }
    var rangeStart by rememberSaveable(entry.rowId) { mutableIntStateOf(lesson.weeks.minOrNull()?.coerceIn(1, editorWeekCount(semester)) ?: 1) }
    var rangeEnd by rememberSaveable(entry.rowId) { mutableIntStateOf(lesson.weeks.maxOrNull()?.coerceIn(1, editorWeekCount(semester)) ?: editorWeekCount(semester)) }
    val dateOrigin = rememberModalOrigin()
    val startOrigin = rememberModalOrigin()
    val endOrigin = rememberModalOrigin()
    val fromOrigin = rememberModalOrigin()
    val toOrigin = rememberModalOrigin()
    val periods = semester.periods.sortedBy { it.number }
    val date = lesson.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.parse(semester.startDate)
    val startTime = runCatching { LocalTime.parse(lesson.startTime) }.getOrNull() ?: LocalTime.of(8, 0)
    val endTime = runCatching { LocalTime.parse(lesson.endTime) }.getOrNull() ?: LocalTime.of(8, 45)
    val weekCount = editorWeekCount(semester)
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focus) {
        if (focus) { bringIntoView.bringIntoView(); onFocused() }
    }
    fun setMode(next: String) {
        if (next == mode) return
        onChange { old -> changeEditorLessonMode(old, next, semester, date) }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("editor_lesson_$index"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.appSurface(
            if (expanded) AppSurfaceRole.Focused else AppSurfaceRole.Supporting))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).testTag("editor_lesson_toggle_$index")) {
                Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VisualCenterText(text.t("授课安排 ${index + 1}", "Arrangement ${index + 1}"), style = MaterialTheme.typography.titleMedium)
                    VisualCenterText(editorLessonSummary(lesson, semester, text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VisualCenterText(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge, modifier = alignByVisualCenter())
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("weekly" to text.t("每周课", "Weekly"), "date" to text.t("具体日期", "Specific date"),
                        "pending" to text.t("待排课", "Unscheduled")).forEach { (value, label) ->
                        FilterChip(mode == value, { setMode(value) }, label = { VisualCenterText(label, modifier = Modifier.centerVisualText()) }, enabled = enabled,
                            modifier = Modifier.testTag("editor_mode_${value}_$index"))
                    }
                }
                if (mode == "weekly") {
                    Text(text.t("星期", "Day of week"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..7).forEach { weekday ->
                            FilterChip(lesson.weekday == weekday, { onChange { it.copy(weekday = weekday) } },
                                label = { VisualCenterText(java.time.DayOfWeek.of(weekday).getDisplayName(TextStyle.SHORT, text.locale), modifier = Modifier.centerVisualText()) }, enabled = enabled,
                                modifier = Modifier.testTag("editor_weekday_${index}_$weekday"))
                        }
                    }
                }
                if (mode == "date") SelectionField(text.t("授课日期", "Teaching date"), pickerDateLabel(date, text),
                    { dateOrigin.capture(); picker = "date" }, Modifier.fillMaxWidth().modalOrigin(dateOrigin).testTag("editor_date_$index"), enabled)
                else {
                    Text(text.t("授课周次", "Teaching weeks"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        editorWeekChoices(semester, entry).forEach { week ->
                            FilterChip(week in lesson.weeks, { onChange { old -> old.copy(weeks =
                                if (week in old.weeks) old.weeks.filterNot { it == week } else (old.weeks + week).distinct().sorted()) } },
                                label = { VisualCenterText(week.toString(), modifier = Modifier.centerVisualText()) }, enabled = enabled,
                                modifier = Modifier.testTag("editor_week_${index}_$week").semantics { contentDescription = text.t("第 $week 周", "Week $week") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionField(text.t("起始周", "From week"), rangeStart.toString(), { fromOrigin.capture(); picker = "fromWeek" },
                            Modifier.weight(1f).modalOrigin(fromOrigin).testTag("editor_from_week_$index"), enabled, "")
                        SelectionField(text.t("终止周", "To week"), rangeEnd.toString(), { toOrigin.capture(); picker = "toWeek" },
                            Modifier.weight(1f).modalOrigin(toOrigin).testTag("editor_to_week_$index"), enabled, "")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onChange { it.copy(weeks = (it.weeks + (rangeStart..rangeEnd)).distinct().sorted()) } }, enabled = enabled) { VisualCenterText(text.t("选中范围", "Select range"), modifier = Modifier.centerVisualText()) }
                        TextButton(onClick = { onChange { it.copy(weeks = it.weeks.filterNot { week -> week in rangeStart..rangeEnd }) } }, enabled = enabled) { VisualCenterText(text.t("取消范围", "Clear range"), modifier = Modifier.centerVisualText()) }
                        TextButton(onClick = { onChange { it.copy(weeks = (rangeStart..rangeEnd).filter { week -> week % 2 == 1 }) } }, enabled = enabled) { VisualCenterText(text.t("仅单周", "Odd weeks"), modifier = Modifier.centerVisualText()) }
                        TextButton(onClick = { onChange { it.copy(weeks = (rangeStart..rangeEnd).filter { week -> week % 2 == 0 }) } }, enabled = enabled) { VisualCenterText(text.t("仅双周", "Even weeks"), modifier = Modifier.centerVisualText()) }
                    }
                }
                if (mode != "pending") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!clock, { if (clock && periods.isNotEmpty()) onChange { it.copy(startPeriod = periods.first().number,
                            endPeriod = periods.first().number, startTime = null, endTime = null) } },
                            label = { VisualCenterText(text.t("按节次", "Periods"), modifier = Modifier.centerVisualText()) }, enabled = enabled && periods.isNotEmpty(), modifier = Modifier.testTag("editor_time_mode_periods_$index"))
                        FilterChip(clock, { if (!clock) onChange { old -> old.copy(startPeriod = null, endPeriod = null,
                            startTime = old.startTime ?: periods.find { it.number == old.startPeriod }?.start ?: "08:00",
                            endTime = old.endTime ?: periods.find { it.number == old.endPeriod }?.end ?: "08:45") } },
                            label = { VisualCenterText(text.t("具体时间", "Clock times"), modifier = Modifier.centerVisualText()) }, enabled = enabled, modifier = Modifier.testTag("editor_time_mode_clock_$index"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionField(text.t("开始", "Start"), if (clock) pickerTimeLabel(startTime, text) else editorPeriodLabel(lesson.startPeriod, periods, text),
                            { startOrigin.capture(); picker = if (clock) "startTime" else "startPeriod" },
                            Modifier.weight(1f).modalOrigin(startOrigin).testTag(if (clock) "editor_start_time_$index" else "editor_start_period_$index"), enabled, "")
                        SelectionField(text.t("结束", "End"), if (clock) pickerTimeLabel(endTime, text) else editorPeriodLabel(lesson.endPeriod, periods, text),
                            { endOrigin.capture(); picker = if (clock) "endTime" else "endPeriod" },
                            Modifier.weight(1f).modalOrigin(endOrigin).testTag(if (clock) "editor_end_time_$index" else "editor_end_period_$index"), enabled, "")
                    }
                }
                OutlinedTextField(lesson.location, { value -> onChange { it.copy(location = value) } },
                    label = { Text(text.t("地点", "Location")) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("editor_location_$index"))
                OutlinedTextField(lesson.teacher, { value -> onChange { it.copy(teacher = value) } },
                    label = { Text(text.t("教师", "Teacher")) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("editor_teacher_$index"))
                OutlinedTextField(lesson.note, { value -> onChange { it.copy(note = value) } },
                    label = { Text(text.t("备注", "Notes")) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("editor_note_$index"))
                TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.testTag("editor_remove_lesson_$index")) {
                VisualCenterText(text.t("删除这条安排", "Remove arrangement"), color = MaterialTheme.colorScheme.error, modifier = Modifier.centerVisualText())
                }
            }
        }
    }
    when (picker) {
        "date" -> NativeDatePicker(text.t("授课日期", "Teaching date"), date, text, { picker = null }, { value ->
            onChange { it.copy(date = value.toString(), weekday = value.dayOfWeek.value) }; picker = null
        }, min = LocalDate.parse(semester.startDate), max = LocalDate.parse(semester.endDate), origin = dateOrigin)
        "startTime" -> NativeTimePicker(text.t("开始时间", "Start time"), startTime, text, { picker = null }, { value ->
            onChange { it.copy(startTime = value.format(DateTimeFormatter.ofPattern("HH:mm"))) }; picker = null
        }, origin = startOrigin)
        "endTime" -> NativeTimePicker(text.t("结束时间", "End time"), endTime, text, { picker = null }, { value ->
            onChange { it.copy(endTime = value.format(DateTimeFormatter.ofPattern("HH:mm"))) }; picker = null
        }, origin = endOrigin)
        "fromWeek" -> NativeWeekPicker(text.t("起始周", "From week"), rangeStart, 1, weekCount, text, { picker = null }, {
            rangeStart = it; rangeEnd = maxOf(rangeEnd, it); picker = null
        }, origin = fromOrigin)
        "toWeek" -> NativeWeekPicker(text.t("终止周", "To week"), rangeEnd, rangeStart, weekCount, text, { picker = null }, {
            rangeEnd = it; picker = null
        }, origin = toOrigin)
        "startPeriod", "endPeriod" -> if (periods.isNotEmpty()) {
            val starting = picker == "startPeriod"
            val lower = if (starting) 0 else periods.indexOfFirst { it.number == lesson.startPeriod }.coerceAtLeast(0)
            val current = periods.indexOfFirst { it.number == if (starting) lesson.startPeriod else lesson.endPeriod }.coerceIn(lower, periods.lastIndex)
            NativeNumberPicker(if (starting) text.t("开始节次", "Starting period") else text.t("结束节次", "Ending period"),
                current, lower, periods.lastIndex, text, { picker = null }, { selected ->
                    onChange { old ->
                        if (starting) {
                            val oldStart = periods.indexOfFirst { it.number == old.startPeriod }.coerceAtLeast(0)
                            val oldEnd = periods.indexOfFirst { it.number == old.endPeriod }.coerceAtLeast(oldStart)
                            old.copy(startPeriod = periods[selected].number, endPeriod = periods[(selected + oldEnd - oldStart).coerceAtMost(periods.lastIndex)].number,
                                startTime = null, endTime = null)
                        } else old.copy(endPeriod = periods[selected].number, startTime = null, endTime = null)
                    }
                    picker = null
                }, origin = if (starting) startOrigin else endOrigin,
                format = { position -> editorPeriodLabel(periods[position].number, periods, text) })
        }
    }
}

private fun editorPeriodLabel(number: Int?, periods: List<Period>, text: UiText): String {
    val period = periods.find { it.number == number }
    return if (period == null) text.t("选择节次", "Choose period")
    else text.t("第 ${period.number} 节", "Period ${period.number}") + " · ${period.start}–${period.end}"
}

private fun editorLessonSummary(lesson: Lesson, semester: Semester, text: UiText): String = buildList {
    add(when {
        lesson.unscheduled -> text.t("待排课", "Unscheduled")
        lesson.date != null -> lesson.date
        else -> java.time.DayOfWeek.of(lesson.weekday.coerceIn(1, 7)).getDisplayName(TextStyle.FULL, text.locale)
    })
    if (!lesson.unscheduled) add(lessonTimeText(lesson, semester, text))
    if (lesson.location.isNotBlank()) add(lesson.location)
    if (lesson.date == null) add(text.t("${lesson.weeks.size} 个周次", "${lesson.weeks.size} weeks"))
    if (lesson.teacher.isNotBlank()) add(lesson.teacher)
}.joinToString(" · ")
