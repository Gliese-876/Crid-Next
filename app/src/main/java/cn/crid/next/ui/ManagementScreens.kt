package cn.crid.next.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.crid.next.core.*
import cn.crid.next.data.Defaults
import cn.crid.next.platform.ReminderDelivery
import cn.crid.next.platform.PlatformCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun PlansScreen(state: AppState, text: UiText, onUpdate: (AppState) -> Unit, onImport: (ModalOrigin) -> Unit) {
    var semesterEditor by remember { mutableStateOf<Semester?>(null) }
    var creatingSemester by remember { mutableStateOf(false) }
    var deleteSemester by remember { mutableStateOf<Semester?>(null) }
    var planEditor by remember { mutableStateOf<Plan?>(null) }
    var creatingPlan by remember { mutableStateOf(false) }
    var deletePlan by remember { mutableStateOf<Plan?>(null) }
    var editorOrigin by remember { mutableStateOf<ModalOrigin?>(null) }
    var removalOrigin by remember { mutableStateOf<ModalOrigin?>(null) }
    val newSemesterOrigin = rememberModalOrigin()
    val emptySemesterOrigin = rememberModalOrigin()
    val newPlanOrigin = rememberModalOrigin()
    val importOrigin = rememberModalOrigin()
    val emptyImportOrigin = rememberModalOrigin()
    val selected = state.semester

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { importOrigin.capture(); onImport(importOrigin) }, modifier = alignByVisualCenter(Modifier.weight(1f).heightIn(min = 48.dp)).modalOrigin(importOrigin).testTag("plans_import")) { ActionLabel("import", text.t("导入课表", "Import")) }
                OutlinedButton(onClick = { newSemesterOrigin.capture(); editorOrigin = newSemesterOrigin; creatingSemester = true }, modifier = alignByVisualCenter(Modifier.weight(1f).heightIn(min = 48.dp)).modalOrigin(newSemesterOrigin)) { ActionLabel("add", text.t("新建学期", "New term")) }
            }
        }
        if (state.semesters.isEmpty()) {
            item { EmptyPanel(text.t("从一个新学期开始", "Start a new term"), text.t("创建学期，再添加你的课程方案", "Create a term, then add your timetable"), text.t("创建学期", "Create term"), { emptySemesterOrigin.capture(); editorOrigin = emptySemesterOrigin; creatingSemester = true }, illustration = IllustrationScene.PLANNING, actionModifier = Modifier.modalOrigin(emptySemesterOrigin)) }
        }
        items(state.semesters, key = { it.id }) { semester ->
            val isSelected = semester.id == selected?.id
            val editOrigin = rememberModalOrigin()
            val deleteOrigin = rememberModalOrigin()
            PlanCard(isSelected, Modifier.fillMaxWidth().testTag("term_card_${semester.id}")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                        onUpdate(state.copy(selectedSemesterId = semester.id, selectedPlanId = state.plan?.takeIf { it.semesterId == semester.id }?.id ?: state.plans.firstOrNull { it.semesterId == semester.id }?.id))
                    }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioButton(selected = isSelected, onClick = null, modifier = alignByVisualCenter())
                    Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VisualCenterText(semester.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        VisualCenterText("${semester.startDate} — ${semester.endDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        VisualCenterText(text.t("${semester.weeks} 周 · ${semester.periods.size} 节 / 天", "${semester.weeks} weeks · ${semester.periods.size} periods / day"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    GlyphAction("edit", text.t("编辑学期", "Edit term"), { editOrigin.capture(); editorOrigin = editOrigin; semesterEditor = semester }, modifier = Modifier.modalOrigin(editOrigin))
                    GlyphAction("delete", text.t("删除学期", "Delete term"), { deleteOrigin.capture(); removalOrigin = deleteOrigin; deleteSemester = semester }, tint = MaterialTheme.colorScheme.error, modifier = Modifier.modalOrigin(deleteOrigin))
                }
            }
        }
        if (selected != null) {
            item { SectionTitle(text.t("课表方案", "Timetables"), selected.name) { GlyphAction("add", text.t("新建方案", "New plan"), { newPlanOrigin.capture(); editorOrigin = newPlanOrigin; creatingPlan = true }, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.modalOrigin(newPlanOrigin)) } }
            val plans = state.plans.filter { it.semesterId == selected.id }
            if (plans.isEmpty()) {
                item { EmptyPanel(text.t("这个学期还没有课表", "No timetable for this term"), text.t("导入一份课表，或先创建空白方案", "Import a timetable or create an empty plan"), text.t("导入课表", "Import timetable"), { emptyImportOrigin.capture(); onImport(emptyImportOrigin) }, illustration = IllustrationScene.PLANNING, actionModifier = Modifier.modalOrigin(emptyImportOrigin).testTag("plans_empty_import")) }
            }
            items(plans, key = { it.id }) { plan ->
                val editOrigin = rememberModalOrigin()
                val deleteOrigin = rememberModalOrigin()
                PlanCard(state.plan?.id == plan.id, Modifier.fillMaxWidth().testTag("plan_card_${plan.id}")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = state.plan?.id == plan.id, role = Role.RadioButton, onClick = { onUpdate(state.copy(selectedSemesterId = plan.semesterId, selectedPlanId = plan.id)) }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RadioButton(selected = state.plan?.id == plan.id, onClick = null, modifier = alignByVisualCenter())
                        Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            VisualCenterText(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            VisualCenterText(text.t("${plan.courses.size} 门课程 · ${plan.courses.sumOf { it.lessons.size }} 项授课安排", "${plan.courses.size} courses · ${plan.courses.sumOf { it.lessons.size }} lessons"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        GlyphAction("edit", text.t("重命名方案", "Rename plan"), { editOrigin.capture(); editorOrigin = editOrigin; planEditor = plan }, modifier = Modifier.modalOrigin(editOrigin))
                        GlyphAction("delete", text.t("删除方案", "Delete plan"), { deleteOrigin.capture(); removalOrigin = deleteOrigin; deletePlan = plan }, tint = MaterialTheme.colorScheme.error, modifier = Modifier.modalOrigin(deleteOrigin))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    if (creatingSemester || semesterEditor != null) {
        SemesterDialog(
            original = semesterEditor,
            origin = editorOrigin,
            relatedPlans = state.plans.filter { it.semesterId == semesterEditor?.id }, text = text,
            weekStartsSunday = state.settings.weekStartsSunday,
            onDismiss = { creatingSemester = false; semesterEditor = null },
            onSave = { semester ->
                val exists = state.semesters.any { it.id == semester.id }
                onUpdate(state.copy(
                    semesters = if (exists) state.semesters.map { if (it.id == semester.id) semester else it } else state.semesters + semester,
                    selectedSemesterId = if (exists) state.selectedSemesterId else semester.id,
                    selectedPlanId = if (exists) state.selectedPlanId else null,
                ))
                creatingSemester = false; semesterEditor = null
            },
        )
    }
    if ((creatingPlan && selected != null) || planEditor != null) {
        NameDialog(text.t(if (creatingPlan) "新建方案" else "重命名方案", if (creatingPlan) "New plan" else "Rename plan"), planEditor?.name.orEmpty(), text,
            origin = editorOrigin, onDismiss = { creatingPlan = false; planEditor = null }, onSave = { name ->
                val existing = planEditor
                val plan = existing?.copy(name = name) ?: Plan(semesterId = requireNotNull(selected).id, name = name, courses = emptyList())
                onUpdate(state.copy(plans = if (existing != null) state.plans.map { if (it.id == plan.id) plan else it } else state.plans + plan, selectedSemesterId = plan.semesterId, selectedPlanId = plan.id))
                creatingPlan = false; planEditor = null
            })
    }
    deleteSemester?.let { semester ->
        val count = state.plans.count { it.semesterId == semester.id }
        ConfirmRemoval(text.t("删除学期？", "Delete term?"), "“${semester.name}”" + text.t("及其 $count 个课表方案将被删除。", " and its $count timetable plans will be deleted."), text, { deleteSemester = null }, origin = removalOrigin) {
            val semesters = state.semesters.filterNot { it.id == semester.id }
            val plans = state.plans.filterNot { it.semesterId == semester.id }
            val nextSemester = if (state.selectedSemesterId == semester.id) semesters.firstOrNull()?.id else state.selectedSemesterId
            val nextPlan = state.selectedPlanId.takeIf { id -> plans.any { it.id == id && it.semesterId == nextSemester } } ?: plans.firstOrNull { it.semesterId == nextSemester }?.id
            onUpdate(state.copy(semesters = semesters, plans = plans, selectedSemesterId = nextSemester, selectedPlanId = nextPlan))
            deleteSemester = null
        }
    }
    deletePlan?.let { plan ->
        ConfirmRemoval(text.t("删除方案？", "Delete plan?"), "“${plan.name}”" + text.t("中的课程及授课安排将被删除。", ": all courses and lessons will be deleted."), text, { deletePlan = null }, origin = removalOrigin) {
            val plans = state.plans.filterNot { it.id == plan.id }
            onUpdate(state.copy(plans = plans, selectedPlanId = if (state.selectedPlanId == plan.id) plans.firstOrNull { it.semesterId == state.selectedSemesterId }?.id else state.selectedPlanId))
            deletePlan = null
        }
    }
}

@Composable
private fun SemesterDialog(original: Semester?, relatedPlans: List<Plan>, text: UiText, onDismiss: () -> Unit, onSave: (Semester) -> Unit,
    weekStartsSunday: Boolean, origin: ModalOrigin? = null) {
    val initial = remember(original?.id, weekStartsSunday) {
        original ?: Defaults.semester().let { defaults ->
            val start = ScheduleEngine.weekStart(LocalDate.parse(defaults.startDate), weekStartsSunday)
            val dates = SemesterDates.resolve(start.toString(), null, defaults.weeks, weekStartsSunday)
            defaults.copy(startDate = dates.startDate, endDate = dates.endDate)
        }
    }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var editor by remember(initial.id, weekStartsSunday) {
        mutableStateOf(SemesterEditor(initial.startDate, initial.endDate, initial.weeks,
            manualOrder = listOf(SemesterField.START, SemesterField.END), weekStartsSunday = weekStartsSunday))
    }
    var periods by remember(initial.id) { mutableStateOf(initial.periods) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var dateField by remember { mutableStateOf<SemesterField?>(null) }
    var timeField by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var pickerOrigin by remember { mutableStateOf<ModalOrigin?>(null) }
    val startOrigin = rememberModalOrigin()
    val endOrigin = rememberModalOrigin()
    val weeksOrigin = rememberModalOrigin()
    fun updateEditor(next: SemesterEditor) {
        editor = next
        errors = emptyList()
    }
    AnimatedAppAlertDialog(onDismissRequest = onDismiss, origin = origin,
        title = { Text(text.t(if (original == null) "新建学期" else "编辑学期", if (original == null) "New term" else "Edit term")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it; errors = emptyList() }, label = { Text(text.t("学期名称", "Term name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (original == null) Text(text.t("已填入秋季学期安排，可在下方调整", "The autumn term is ready to adjust below"), style = MaterialTheme.typography.bodySmall)
                SelectionField(fieldLabel(text.t("起始日", "Start date"), editor.derivedField == SemesterField.START, text), pickerDateLabel(LocalDate.parse(editor.start ?: initial.startDate), text),
                    { startOrigin.capture(); pickerOrigin = startOrigin; dateField = SemesterField.START }, Modifier.fillMaxWidth().modalOrigin(startOrigin).testTag("semester_start"))
                SelectionField(fieldLabel(text.t("终止日", "End date"), editor.derivedField == SemesterField.END, text), pickerDateLabel(LocalDate.parse(editor.end ?: initial.endDate), text),
                    { endOrigin.capture(); pickerOrigin = endOrigin; dateField = SemesterField.END }, Modifier.fillMaxWidth().modalOrigin(endOrigin).testTag("semester_end"))
                SelectionField(fieldLabel(text.t("周数", "Weeks"), editor.derivedField == SemesterField.WEEKS, text), text.t("${editor.weeks} 周", "${editor.weeks} weeks"),
                    { weeksOrigin.capture(); pickerOrigin = weeksOrigin; dateField = SemesterField.WEEKS }, Modifier.fillMaxWidth().modalOrigin(weeksOrigin).testTag("semester_weeks"))
                Text(text.t("调整任意两项，其余一项会自动更新", "Change any two fields to calculate the third"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (weekStartsSunday) text.t("从周日开始，到周六结束", "Start on Sunday and end on Saturday")
                    else text.t("从周一开始，到周日结束", "Start on Monday and end on Sunday"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                editor.errors.forEach { Text(localizeManagementError(it, text), modifier = Modifier.testTag("semester_date_error"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VisualCenterText(text.t("每日作息", "Daily class times"), style = MaterialTheme.typography.titleMedium, modifier = alignByVisualCenter(Modifier.weight(1f)))
                    TextButton(onClick = { periods = Defaults.periods; errors = emptyList() }, modifier = alignByVisualCenter()) { VisualCenterText(text.t("学校作息", "BNU times")) }
                }
                periods.forEachIndexed { index, period ->
                    val periodStartOrigin = rememberModalOrigin()
                    val periodEndOrigin = rememberModalOrigin()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisualCenterText("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = alignByVisualCenter(Modifier.width(22.dp)))
                        SelectionField(text.t("开始", "Start"), pickerTimeLabel(LocalTime.parse(period.start), text),
                            { periodStartOrigin.capture(); pickerOrigin = periodStartOrigin; timeField = index to true }, alignByVisualCenter(Modifier.weight(1f)).modalOrigin(periodStartOrigin).testTag("period_${index + 1}_start"), glyph = "")
                        SelectionField(text.t("结束", "End"), pickerTimeLabel(LocalTime.parse(period.end), text),
                            { periodEndOrigin.capture(); pickerOrigin = periodEndOrigin; timeField = index to false }, alignByVisualCenter(Modifier.weight(1f)).modalOrigin(periodEndOrigin).testTag("period_${index + 1}_end"), glyph = "")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val start = runCatching { LocalTime.parse(periods.lastOrNull()?.end ?: "07:50").plusMinutes(10) }.getOrDefault(LocalTime.of(8, 0))
                        periods = periods + Period(periods.size + 1, start.toString(), start.plusMinutes(45).toString()); errors = emptyList()
                    }, modifier = alignByVisualCenter(Modifier.weight(1f).heightIn(min = 48.dp))) { ActionLabel("add", text.t("增加一节", "Add period")) }
                    Box(alignByVisualCenter()) {
                        GlyphAction("delete", text.t("移除末节", "Remove last period"), { periods = periods.dropLast(1); errors = emptyList() }, enabled = periods.size > 1, tint = MaterialTheme.colorScheme.error)
                    }
                }
                errors.forEach { Text(localizeManagementError(it, text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { motion ->
            Button(onClick = {
                val dates = editor.result
                if (dates == null) { errors = editor.errors; return@Button }
                val semester = initial.copy(name = name.trim(), startDate = dates.startDate, endDate = dates.endDate, weeks = dates.weeks, periods = periods)
                val validation = DataValidator.validateSemester(semester) + relatedPlans.filter { it.courses.isNotEmpty() }.flatMap { DataValidator.validateCourses(it.courses, semester) }
                errors = validation.distinct()
                if (errors.isEmpty()) motion.finish { onSave(semester) }
            }, enabled = editor.result != null, modifier = Modifier.testTag("semester_save")) { VisualCenterText(text.t("保存", "Save"), modifier = Modifier.centerVisualText()) }
        }, dismissButton = { motion -> TextButton(onClick = motion.dismiss, modifier = Modifier.testTag("semester_cancel")) { VisualCenterText(text.t("取消", "Cancel"), modifier = Modifier.centerVisualText()) } })
    dateField?.let { field ->
        if (field == SemesterField.WEEKS) NativeNumberPicker(text.t("周数", "Weeks"), editor.weeks ?: initial.weeks, 1, maxOf(104, editor.weeks ?: 1), text,
            onDismiss = { dateField = null }, onSelect = {
                if (it != editor.weeks) updateEditor(editor.editWeeks(it))
                dateField = null
            }, origin = pickerOrigin,
            format = { text.t("$it 周", "$it weeks") })
        else NativeDatePicker(title = text.t(if (field == SemesterField.START) "起始日" else "终止日", if (field == SemesterField.START) "Start date" else "End date"),
            value = LocalDate.parse(if (field == SemesterField.START) editor.start ?: initial.startDate else editor.end ?: initial.endDate), text = text,
            onDismiss = { dateField = null }, onSelect = {
                val selectedDate = it.toString()
                val previousDate = if (field == SemesterField.START) editor.start else editor.end
                if (selectedDate != previousDate) {
                    updateEditor(if (field == SemesterField.START) editor.editStart(selectedDate) else editor.editEnd(selectedDate))
                }
                dateField = null
            }, origin = pickerOrigin, requiredWeekday = if (field == SemesterField.START)
                SemesterDates.startWeekday(weekStartsSunday) else SemesterDates.endWeekday(weekStartsSunday))
    }
    timeField?.let { (index, start) ->
        NativeTimePicker(text.t("第 ${index + 1} 节 · ${if (start) "开始" else "结束"}", "Period ${index + 1} · ${if (start) "Start" else "End"}"),
            value = LocalTime.parse(if (start) periods[index].start else periods[index].end), text = text, onDismiss = { timeField = null },
            onSelect = { value ->
                periods = periods.mapIndexed { i, period -> if (i != index) period else if (start) period.copy(start = value.toString()) else period.copy(end = value.toString()) }
                errors = emptyList(); timeField = null
            }, origin = pickerOrigin)
    }
}

private fun fieldLabel(label: String, derived: Boolean, text: UiText) = if (derived) "$label · ${text.t("自动计算", "Calculated")}" else label

@Composable
private fun NameDialog(title: String, initial: String, text: UiText, onDismiss: () -> Unit, onSave: (String) -> Unit, origin: ModalOrigin? = null) {
    var name by remember { mutableStateOf(initial) }
    AnimatedAppAlertDialog(onDismissRequest = onDismiss, origin = origin, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(text.t("方案名称", "Plan name")) }, singleLine = true) },
        confirmButton = { motion -> Button(onClick = { val savedName = name.trim(); motion.finish { onSave(savedName) } }, enabled = name.isNotBlank()) { VisualCenterText(text.t("保存", "Save"), modifier = Modifier.centerVisualText()) } }, dismissButton = { motion -> TextButton(onClick = motion.dismiss) { VisualCenterText(text.t("取消", "Cancel"), modifier = Modifier.centerVisualText()) } })
}

@Composable
private fun ConfirmRemoval(title: String, body: String, text: UiText, onDismiss: () -> Unit, origin: ModalOrigin? = null, onConfirm: () -> Unit) {
    AnimatedAppAlertDialog(onDismissRequest = onDismiss, origin = origin, title = { Text(title) }, text = { Text(body) },
        confirmButton = { motion -> TextButton(onClick = { motion.finish(onConfirm) }) { VisualCenterText(text.t("删除", "Delete"), modifier = Modifier.centerVisualText(), color = MaterialTheme.colorScheme.error) } }, dismissButton = { motion -> TextButton(onClick = motion.dismiss) { VisualCenterText(text.t("取消", "Cancel"), modifier = Modifier.centerVisualText()) } })
}

@Composable
internal fun SettingsScreen(state: AppState, calendar: HolidayCalendar, text: UiText, onUpdate: (AppState) -> Unit) {
    val context = LocalContext.current
    val settings = state.settings
    fun update(value: Settings) = onUpdate(state.copy(settings = value))
    var reminderDelivery by remember(context) { mutableStateOf(ReminderDelivery.read(context)) }
    var reminderSettingsError by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Permission results refresh the diagnosis without overwriting the user's reminder preference.
        reminderDelivery = ReminderDelivery.read(context)
        PlatformCoordinator.refresh(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reminderDelivery = ReminderDelivery.read(context)
                reminderSettingsError = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showingAbout by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showingReminderSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var choosingReminderMinutes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val reminderMinutesOrigin = rememberModalOrigin()
    val reminderStatusOrigin = rememberModalOrigin()
    val aboutOrigin = rememberModalOrigin()

    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SoftCard(Modifier.fillMaxWidth(), role = AppSurfaceRole.Raised) {
                SettingsHeading("sun", text.t("外观", "Appearance"))
                ThemeChoices(settings.theme, text) { update(settings.copy(theme = it)) }
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SettingsHeading("language", text.t("语言", "Language"))
                ChoiceRow(text.t("跟随系统", "Use system language"), settings.language == Language.SYSTEM && text.systemSupported, text.systemSupported) { update(settings.copy(language = Language.SYSTEM)) }
                if (!text.systemSupported) Text(text.t("当前系统语言暂不支持，可选择以下语言", "Choose a supported language below"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow("简体中文", settings.language == Language.ZH_CN) { update(settings.copy(language = Language.ZH_CN)) }
                ChoiceRow("繁體中文", settings.language == Language.ZH_TW) { update(settings.copy(language = Language.ZH_TW)) }
                ChoiceRow("English", settings.language == Language.EN || (settings.language == Language.SYSTEM && !text.systemSupported)) { update(settings.copy(language = Language.EN)) }
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SettingsHeading("calendar", text.t("课表显示", "Timetable"))
                Text(text.t("一周起始日", "First day of the week"), style = MaterialTheme.typography.titleSmall)
                ChoiceRow(text.t("周一", "Monday"), !settings.weekStartsSunday) { update(settings.copy(weekStartsSunday = false)) }
                ChoiceRow(text.t("周日", "Sunday"), settings.weekStartsSunday) { update(settings.copy(weekStartsSunday = true)) }
                HorizontalDivider()
                SwitchRow(text.t("显示非本周课程", "Show other-week lessons"), text.t("以虚线显示，方便查看课程安排", "Dashed outlines keep other weeks in view"), settings.showOutOfWeek) { update(settings.copy(showOutOfWeek = it)) }
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SettingsHeading("calendar", text.t("节假日", "Holidays"))
                SwitchRow(text.t("法定节假日", "Public holidays"), text.t("标记休假时段和停课课程", "Mark days off and affected lessons"), settings.holidaysEnabled) { update(settings.copy(holidaysEnabled = it)) }
                if (settings.holidaysEnabled) {
                    HorizontalDivider()
                    Text(text.t("调休", "Schedule adjustments"), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(text.t("启用", "Apply days off and makeup classes"), settings.makeupMode == MakeupMode.ON) { update(settings.copy(makeupMode = MakeupMode.ON)) }
                    ChoiceRow(text.t("启用且不补班（补课）", "Apply days off without makeup classes"), settings.makeupMode == MakeupMode.HOLIDAYS_ONLY) { update(settings.copy(makeupMode = MakeupMode.HOLIDAYS_ONLY)) }
                    ChoiceRow(text.t("禁用", "Public holidays only"), settings.makeupMode == MakeupMode.OFF) { update(settings.copy(makeupMode = MakeupMode.OFF)) }
                    Text(text.t("最新同步：", "Last synced: ") + syncLabel(calendar, text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (calendar.year < LocalDate.now().year) Text(text.t("当前节假日安排已过期，等待更新。", "The holiday calendar has expired. An update is pending."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                SettingsHeading("notification", text.t("课程提醒", "Class reminders", "課程提醒"))
                SwitchRow(text.t("上课前提醒", "Remind me before class", "上課前提醒"),
                    text.t("根据当前方案发送课程通知", "Receive reminders for your selected plan", "根據目前方案傳送課程通知"),
                    settings.remindersEnabled, modifier = Modifier.testTag("reminder_toggle")) { enabled ->
                    update(settings.copy(remindersEnabled = enabled))
                    if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (settings.remindersEnabled) SelectionField(text.t("提前分钟数", "Minutes before class", "提前分鐘數"),
                    text.t("${settings.reminderMinutes} 分钟", "${settings.reminderMinutes} minutes", "${settings.reminderMinutes} 分鐘"),
                    { reminderMinutesOrigin.capture(); choosingReminderMinutes = true },
                    Modifier.fillMaxWidth().modalOrigin(reminderMinutesOrigin).testTag("reminder_minutes"), glyph = "time")
                SelectionField(text.t("提醒设置", "Reminder settings", "提醒設定"),
                    reminderDeliverySummary(reminderDelivery, settings.remindersEnabled, text, settings.reminderAlarmClock),
                    { reminderStatusOrigin.capture(); reminderSettingsError = false; showingReminderSettings = true },
                    Modifier.fillMaxWidth().modalOrigin(reminderStatusOrigin).testTag("reminder_status"), glyph = "notification")
            }
        }
        item { AboutSettingsEntry(text, Modifier.modalOrigin(aboutOrigin)) { aboutOrigin.capture(); showingAbout = true } }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (showingAbout) AboutDialog(text, origin = aboutOrigin) { showingAbout = false }
    if (showingReminderSettings) ReminderDeliverySettingsDialog(reminderDelivery, text,
        remindersEnabled = settings.remindersEnabled, alarmClockEnabled = settings.reminderAlarmClock,
        onAlarmClockEnabledChange = { update(settings.copy(reminderAlarmClock = it)) },
        onDismiss = { showingReminderSettings = false },
        onOpenSettings = { target -> reminderSettingsError = !ReminderDelivery.openSettings(context, target) },
        openFailed = reminderSettingsError, origin = reminderStatusOrigin)
    if (choosingReminderMinutes && settings.remindersEnabled) NativeNumberPicker(text.t("提前分钟数", "Minutes before class", "提前分鐘數"),
        settings.reminderMinutes, 0, 1440, text, onDismiss = { choosingReminderMinutes = false },
        onSelect = { update(settings.copy(reminderMinutes = it)); choosingReminderMinutes = false }, origin = reminderMinutesOrigin,
        format = { text.t("$it 分钟", "$it minutes", "$it 分鐘") })
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = alignByVisualCenter())
        VisualCenterText(label, modifier = alignByVisualCenter(Modifier.weight(1f)), textAlign = TextAlign.Start, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChoices(selected: ThemeMode, text: UiText, onSelect: (ThemeMode) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            val glyph = when (mode) { ThemeMode.SYSTEM -> "system"; ThemeMode.LIGHT -> "sun"; ThemeMode.DARK -> "moon" }
            val label = when (mode) { ThemeMode.SYSTEM -> text.t("自动", "System"); ThemeMode.LIGHT -> text.t("浅色", "Light"); ThemeMode.DARK -> text.t("深色", "Dark") }
            FilterChip(selected = selected == mode, onClick = { onSelect(mode) }, label = { VisualCenterText(label, modifier = Modifier.centerVisualText()) },
                leadingIcon = { AppGlyph(glyph, tint = LocalContentColor.current, modifier = Modifier.size(20.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun SettingsHeading(glyph: String, label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppGlyph(glyph, tint = MaterialTheme.colorScheme.primary, modifier = alignByVisualCenter(Modifier.size(24.dp)))
        VisualCenterText(label, modifier = alignByVisualCenter(Modifier.weight(1f)), textAlign = TextAlign.Start, style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlyphAction(glyph: String, label: String, onClick: () -> Unit, enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
            AppGlyph(glyph, tint = if (enabled) tint else tint.copy(alpha = .38f))
        }
    }
}

@Composable
internal fun ActionLabel(glyph: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppGlyph(glyph, tint = LocalContentColor.current, modifier = alignByVisualCenter(Modifier.size(20.dp)))
        VisualCenterText(label, modifier = alignByVisualCenter(Modifier.weight(1f, fill = false)), textAlign = TextAlign.Start)
    }
}

@Composable
internal fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onChecked: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VisualCenterText(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            VisualCenterText(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .6f))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = alignByVisualCenter())
    }
}

private fun syncLabel(calendar: HolidayCalendar, text: UiText): String = calendar.syncedAt?.let { value ->
    runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", text.locale)) }.getOrDefault(value)
} ?: text.t("尚未同步 · 内置 ${calendar.year} 年安排", "Not yet synced · ${calendar.year} calendar included")

private fun localizeManagementError(value: String, text: UiText): String {
    val english = when {
        value == "起始日请选择周日" -> "Choose a Sunday for the start date"
        value == "起始日请选择周一" -> "Choose a Monday for the start date"
        value == "终止日请选择周六" -> "Choose a Saturday for the end date"
        value == "终止日请选择周日" -> "Choose a Sunday for the end date"
        value == "请选择完整的周次" -> "Choose complete weeks"
        "名称" in value -> "Enter a term name."
        "至少两项" in value -> "Enter at least two valid term fields."
        "正整数" in value -> "Weeks must be a positive whole number."
        "不能晚于" in value -> "The start date must not follow the end date."
        "日期" in value && "授课" !in value -> "Check the dates and week count."
        "重叠" in value -> "Class periods must not overlap."
        "起止时间" in value -> "Each period must end after it starts."
        "目标学期" in value || "授课" in value -> "Existing lessons do not fit these dates or class times. Keep their dates and periods within the term."
        else -> "Review the term details and class times."
    }
    return text.t(value, english)
}
