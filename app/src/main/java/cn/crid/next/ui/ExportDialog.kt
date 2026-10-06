package cn.crid.next.ui

import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import cn.crid.next.core.*
import cn.crid.next.export.ExportFormat
import cn.crid.next.export.ExportRequest
import cn.crid.next.export.ExportUnits
import cn.crid.next.export.ExportView
import cn.crid.next.export.TimetableExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun ExportDialog(state: AppState, calendar: HolidayCalendar, text: UiText, onDismiss: () -> Unit, origin: ModalOrigin? = null) {
    val semester = state.semester
    val plan = state.plan
    if (semester == null || plan == null) {
        AnimatedAppAlertDialog(onDismissRequest = onDismiss, origin = origin, title = { Text(text.t("还没有课表", "No timetable yet")) },
            text = { Text(text.t("先选择一个学期和方案，再来导出。", "Choose a semester and plan before exporting.")) },
            confirmButton = { motion -> TextButton(onClick = motion.dismiss) { VisualCenterText(text.t("知道了", "OK"), modifier = Modifier.centerVisualText()) } })
        return
    }
    val context = LocalContext.current
    val windowHeight = with(LocalDensity.current) {LocalWindowInfo.current.containerSize.height.toDp()}
    val draft:ExportDraftViewModel=viewModel()
    val scope = draft.viewModelScope
    val exporter = remember(context) { TimetableExporter(context.applicationContext) }
    val semesterStart = LocalDate.parse(semester.startDate)
    val semesterEnd = LocalDate.parse(semester.endDate)
    draft.prepare(semester, state.settings.weekStartsSunday)
    var kind by draft.kind
    var view by draft.view
    var fromDay by draft.fromDay
    var toDay by draft.toDay
    var fromWeek by draft.fromWeek
    var toWeek by draft.toWeek
    var fromMonth by draft.fromMonth
    var toMonth by draft.toMonth
    var selectedSemesterIds by draft.selectedSemesterIds
    var semesterPlanIds by draft.semesterPlanIds
    var scale by draft.scale
    var estimated by remember { mutableStateOf<Int?>(null) }
    var estimatedRequests by remember { mutableStateOf<List<ExportRequest>>(emptyList()) }
    var estimating by remember { mutableStateOf(false) }
    var estimateError by remember { mutableStateOf<String?>(null) }
    var busy by draft.busy
    var error by draft.error
    var results by draft.results
    var resultMime by draft.resultMime
    var exportJob by draft.exportJob
    var pendingRequests by draft.pendingRequests
    var pendingPlan by draft.pendingPlan
    var pendingKind by draft.pendingKind
    val close:()->Unit={draft.clear();onDismiss()}
    val dates = when (view) {
        ExportView.DAY -> fromDay to toDay
        ExportView.WEEK -> ExportUnits.weekRange(semester, fromWeek, toWeek, state.settings.weekStartsSunday)
        ExportView.MONTH -> fromMonth.atDay(1) to toMonth.atEndOfMonth()
        ExportView.SEMESTER -> semesterStart to semesterEnd
    }
    val semesterChoices = state.semesters.sortedWith(compareBy<Semester> { it.startDate }.thenBy { it.endDate }.thenBy { it.name })
    val format = if (kind == ExportKind.PNG) ExportFormat.PNG else ExportFormat.PDF
    val selectedTerms = semesterChoices.filter { it.id in selectedSemesterIds }
    val missingSemesterPlan = selectedTerms.any { exportPlanForSemester(state, it.id, semesterPlanIds) == null }
    val requests = if (view == ExportView.SEMESTER) {
        if (missingSemesterPlan) emptyList() else selectedTerms.map { term ->
            val selectedPlan = requireNotNull(exportPlanForSemester(state, term.id, semesterPlanIds))
            ExportRequest(term, selectedPlan, state.settings, calendar, view, LocalDate.parse(term.startDate), LocalDate.parse(term.endDate), format, scale)
        }
    } else listOf(ExportRequest(semester, plan, state.settings, calendar, view, dates.first, dates.second, format, scale))
    val fromOrigin = rememberModalOrigin()
    val toOrigin = rememberModalOrigin()
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val dayFormatter = remember(text.locale) { DateTimeFormatter.ofPattern(if (text.isEnglish) "MMM d, yyyy" else "yyyy年M月d日", text.locale) }
    val monthFormatter = remember(text.locale) { DateTimeFormatter.ofPattern(if (text.isEnglish) "MMMM yyyy" else "yyyy年M月", text.locale) }
    LaunchedEffect(requests, kind) {
        estimated = null
        estimatedRequests = emptyList()
        estimateError = null
        if (kind == ExportKind.DATA) { estimated = 1; estimating = false; return@LaunchedEffect }
        if (requests.isEmpty()) { estimating = false; return@LaunchedEffect }
        estimating = true
        try {
            estimated = withContext(Dispatchers.Default) {
                val estimateContext = currentCoroutineContext()
                exporter.estimate(requests) { estimateContext.ensureActive() }
            }
            estimatedRequests = requests
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            estimateError = if (kind == ExportKind.PNG) text.t("图片过大，请降低分辨率或选择 PDF。", "The image is too large. Lower its resolution or choose PDF.")
                else text.t("当前内容无法完整排版，请检查课程内容后重试。", "This content could not be laid out. Check the course details and try again.")
        } finally { estimating = false }
    }

    val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val selected = pendingRequests
        val selectedPlan = pendingPlan
        val selectedKind = pendingKind
        if (tree != null && (selectedKind == ExportKind.DATA && selectedPlan != null || selectedKind != ExportKind.DATA && selected.isNotEmpty())) {
            busy = true
            error = null
            results = emptyList()
            exportJob = scope.launch {
                try {
                    runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    results = if (selectedKind == ExportKind.DATA) withContext(Dispatchers.IO) {
                        val source = requireNotNull(selectedPlan)
                        val content = PlanCodec.encode(source)
                        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                        val filename = source.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(64).ifBlank { "Crid Next" }
                        val uri = DocumentsContract.createDocument(context.contentResolver, parent, "application/json", "$filename.crid.json")
                            ?: throw IOException("Cannot create document")
                        try {
                            currentCoroutineContext().ensureActive()
                            context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                                ?: throw IOException("Cannot open document")
                            currentCoroutineContext().ensureActive()
                            listOf(uri)
                        } catch (failure: Throwable) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            throw failure
                        }
                    } else exporter.export(selected, tree)
                    resultMime = when (selectedKind) { ExportKind.PNG -> "image/png"; ExportKind.PDF -> "application/pdf"; ExportKind.DATA -> "application/json" }
                } catch (cancel: CancellationException) {
                    error = text.t("已取消导出", "Export cancelled")
                    throw cancel
                } catch (_: Exception) {
                    error = text.t("文件未能保存。请检查文件夹权限和剩余空间。图片过大时可降低分辨率或选择 PDF。", "Could not save the files. Check folder access and free space. For large images, lower the resolution or choose PDF.")
                } finally { busy = false }
            }
        }
    }

    fun openResult() {
        val uri = results.firstOrNull() ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, resultMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { error = text.t("未找到可以打开此文件的应用，可使用分享发送到其他应用。", "No app can open this file. Use Share to send it to another app.") }
    }
    fun shareResults() {
        if (results.isEmpty()) return
        try {
            val clip = ClipData.newUri(context.contentResolver, "Crid Next", results.first())
            results.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            val send = Intent(if (results.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = resultMime
                if (results.size == 1) putExtra(Intent.EXTRA_STREAM, results.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(results))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, text.t("分享课表", "Share timetable")))
        } catch (_: Exception) { error = text.t("暂时无法分享，文件已保存在所选文件夹。", "Sharing is unavailable. Your files are saved in the selected folder.") }
    }

    AnimatedAppDialog(onDismissRequest = close, origin = origin, dismissEnabled = !busy, properties = DialogProperties(usePlatformDefaultWidth = false)) { motion ->
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 720.dp).fillMaxWidth().heightIn(max = windowHeight * .92f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisualCenterText(text.t("导出课表", "Export timetable"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = alignByVisualCenter(Modifier.weight(1f)))
                    Box(alignByVisualCenter()) {
                        GlyphAction("close", text.t("关闭导出", "Close export"), motion.dismiss, enabled = !busy)
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (kind == ExportKind.DATA || view != ExportView.SEMESTER) {
                        Text("${semester.name} · ${plan.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(text.t("文件类型", "File type"), style = MaterialTheme.typography.titleSmall)
                    ExportChoices(ExportKind.entries, kind, !busy, { kind = it }, { when (it) {
                        ExportKind.PNG -> "PNG"; ExportKind.PDF -> "PDF"; ExportKind.DATA -> text.t("课表数据", "Timetable data")
                    } })
                    if (kind == ExportKind.DATA) {
                        Text(text.t("保存完整课程方案，便于以后重新导入。导入时可选择目标学期。", "Save the complete course plan for re-importing later. Choose its destination semester when you import."))
                    } else {
                        Text(text.t("排版视图", "Layout view"), style = MaterialTheme.typography.titleSmall)
                        ExportChoices(ExportView.entries, view, !busy, { view = it; picker = null }, { when (it) {
                            ExportView.DAY -> text.t("日", "Day"); ExportView.WEEK -> text.t("周", "Week"); ExportView.MONTH -> text.t("月", "Month"); ExportView.SEMESTER -> text.t("学期", "Semester")
                        } })
                        Text(text.t("导出范围", "Export range"), style = MaterialTheme.typography.titleSmall)
                        when (view) {
                            ExportView.DAY -> {
                                SelectionField(text.t("起始日期", "Start date"), fromDay.format(dayFormatter), { fromOrigin.capture(); picker = "day_from" },
                                    Modifier.fillMaxWidth().modalOrigin(fromOrigin).testTag("export_range_start"), enabled = !busy)
                                SelectionField(text.t("结束日期", "End date"), toDay.format(dayFormatter), { toOrigin.capture(); picker = "day_to" },
                                    Modifier.fillMaxWidth().modalOrigin(toOrigin).testTag("export_range_end"), enabled = !busy)
                            }
                            ExportView.WEEK -> {
                                SelectionField(text.t("起始周", "First week"), text.t("第 $fromWeek 周", "Week $fromWeek"), { fromOrigin.capture(); picker = "week_from" },
                                    Modifier.fillMaxWidth().modalOrigin(fromOrigin).testTag("export_range_start"), enabled = !busy)
                                SelectionField(text.t("结束周", "Last week"), text.t("第 $toWeek 周", "Week $toWeek"), { toOrigin.capture(); picker = "week_to" },
                                    Modifier.fillMaxWidth().modalOrigin(toOrigin).testTag("export_range_end"), enabled = !busy)
                            }
                            ExportView.MONTH -> {
                                SelectionField(text.t("起始月份", "First month"), fromMonth.format(monthFormatter), { fromOrigin.capture(); picker = "month_from" },
                                    Modifier.fillMaxWidth().modalOrigin(fromOrigin).testTag("export_range_start"), enabled = !busy)
                                SelectionField(text.t("结束月份", "Last month"), toMonth.format(monthFormatter), { toOrigin.capture(); picker = "month_to" },
                                    Modifier.fillMaxWidth().modalOrigin(toOrigin).testTag("export_range_end"), enabled = !busy)
                            }
                            ExportView.SEMESTER -> {
                                semesterChoices.forEach { term ->
                                    val plans = state.plans.filter { it.semesterId == term.id }
                                    val chosen = exportPlanForSemester(state, term.id, semesterPlanIds)
                                    val selected = term.id in selectedSemesterIds
                                    ExportSemesterChoice(term, plans, chosen, selected, !busy, text,
                                        onSelected = { checked -> selectedSemesterIds = if (checked) selectedSemesterIds + term.id else selectedSemesterIds - term.id },
                                        onPlan = { selectedPlan -> semesterPlanIds = semesterPlanIds + (term.id to selectedPlan.id) })
                                }
                                if (requests.isEmpty()) Text(if (missingSemesterPlan) text.t("请为所选学期选择课程方案。", "Choose a course plan for each selected semester.")
                                    else text.t("请选择至少一个学期。", "Choose at least one semester."), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (kind == ExportKind.PNG) {
                            Text(text.t("图片分辨率", "Image resolution"), style = MaterialTheme.typography.titleSmall)
                            ExportChoices(listOf(1, 2, 3), scale, !busy, { scale = it }, { "${it}×" })
                        }
                        if (estimating) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(alignByVisualCenter(Modifier.size(20.dp)), strokeWidth = 2.dp)
                            VisualCenterText(text.t("正在计算页数…", "Calculating pages…"),modifier=alignByVisualCenter(Modifier.weight(1f)))
                        }
                        estimated?.let { count ->
                            Text(if (kind == ExportKind.PNG) text.t("共 $count 张图片，每张包含${exportUnitLabel(view, text)}", "$count ${if (count == 1) "image" else "images"}, with one complete ${exportUnitLabel(view, text)} per image")
                            else text.t("共 $count 页 PDF，每页包含${exportUnitLabel(view, text)}", "$count PDF ${if (count == 1) "page" else "pages"}, with one complete ${exportUnitLabel(view, text)} per page"),
                                fontWeight = FontWeight.Medium, modifier = Modifier.testTag("export_unit_summary"))
                        }
                        estimateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (busy) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(alignByVisualCenter(Modifier.size(24.dp)), strokeWidth = 2.dp)
                        VisualCenterText(text.t("正在保存课表…", "Saving your timetable…"),modifier=alignByVisualCenter(Modifier.weight(1f)))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (results.isNotEmpty()) SoftCard(Modifier.fillMaxWidth(), role = AppSurfaceRole.Raised) {
                        Text(text.t("已保存 ${results.size} 个文件", "${results.size} ${if (results.size == 1) "file" else "files"} saved"), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = ::openResult, modifier = Modifier.heightIn(min = 48.dp)) { ActionLabel("open", text.t("打开", "Open")) }
                            FilledTonalButton(onClick = ::shareResults, modifier = Modifier.heightIn(min = 48.dp)) { ActionLabel("share", text.t("分享", "Share")) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (busy) TextButton(onClick = { exportJob?.cancel() }, modifier = alignByVisualCenter(Modifier.heightIn(min = 48.dp))) { VisualCenterText(text.t("取消导出", "Cancel export")) }
                    else Button(modifier = alignByVisualCenter(Modifier.heightIn(min = 48.dp)), enabled = !estimating && (kind == ExportKind.DATA || estimated != null && requests.isNotEmpty() && estimatedRequests == requests), onClick = {
                        pendingRequests = requests.toList()
                        pendingPlan = plan
                        pendingKind = kind
                        try { destination.launch(null) }
                        catch (_: Exception) { error = text.t("无法打开文件夹选择器，请稍后重试。", "Could not open the folder picker. Please try again.") }
                    }) { ActionLabel("folder", text.t("选择文件夹并保存", "Save to folder")) }
                }
            }
        }
    }
    when (picker) {
        "day_from" -> NativeDatePicker(title = text.t("起始日期", "Start date"), value = fromDay, text = text,
            min = semesterStart, max = toDay, origin = fromOrigin, onDismiss = { picker = null }, onSelect = { fromDay = it; picker = null })
        "day_to" -> NativeDatePicker(title = text.t("结束日期", "End date"), value = toDay, text = text,
            min = fromDay, max = semesterEnd, origin = toOrigin, onDismiss = { picker = null }, onSelect = { toDay = it; picker = null })
        "week_from" -> NativeWeekPicker(title = text.t("起始周", "First week"), value = fromWeek, min = 1, max = toWeek, text = text,
            origin = fromOrigin, onDismiss = { picker = null }, onSelect = { fromWeek = it; picker = null })
        "week_to" -> NativeWeekPicker(title = text.t("结束周", "Last week"), value = toWeek, min = fromWeek,
            max = ExportUnits.weekCount(semester, state.settings.weekStartsSunday), text = text, origin = toOrigin,
            onDismiss = { picker = null }, onSelect = { toWeek = it; picker = null })
        "month_from" -> NativeMonthPicker(title = text.t("起始月份", "First month"), value = fromMonth, min = YearMonth.from(semesterStart), max = toMonth,
            text = text, origin = fromOrigin, onDismiss = { picker = null }, onSelect = { fromMonth = it; picker = null })
        "month_to" -> NativeMonthPicker(title = text.t("结束月份", "Last month"), value = toMonth, min = fromMonth, max = YearMonth.from(semesterEnd),
            text = text, origin = toOrigin, onDismiss = { picker = null }, onSelect = { toMonth = it; picker = null })
    }
}

/** A removed explicit selection must be reselected, never replaced silently by another plan. */
internal fun exportPlanForSemester(state: AppState, semesterId: String, choices: Map<String, String>): Plan? {
    val plans = state.plans.filter { it.semesterId == semesterId }
    choices[semesterId]?.let { chosenId -> return plans.find { it.id == chosenId } }
    return state.plan?.takeIf { it.semesterId == semesterId } ?: plans.firstOrNull()
}

private fun exportUnitLabel(view: ExportView, text: UiText): String = when (view) {
    ExportView.DAY -> text.t("完整一天", "day")
    ExportView.WEEK -> text.t("完整一周", "week")
    ExportView.MONTH -> text.t("完整一个月", "month")
    ExportView.SEMESTER -> text.t("完整一个学期", "semester")
}

@Composable
private fun ExportSemesterChoice(
    semester: Semester,
    plans: List<Plan>,
    chosen: Plan?,
    selected: Boolean,
    enabled: Boolean,
    text: UiText,
    onSelected: (Boolean) -> Unit,
    onPlan: (Plan) -> Unit,
) {
    var plansOpen by remember(semester.id) { mutableStateOf(false) }
    val role = if (selected) AppSurfaceRole.Selected else AppSurfaceRole.Supporting
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.appSurface(role),
        contentColor = MaterialTheme.colorScheme.appOnSurface(role), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = selected, enabled = enabled && (plans.isNotEmpty() || selected), role = Role.Checkbox, onValueChange = onSelected)
                .testTag("export_semester_${semester.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Checkbox(checked = selected, onCheckedChange = null, enabled = enabled && (plans.isNotEmpty() || selected), modifier = alignByVisualCenter())
                Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VisualCenterText(semester.name, style = MaterialTheme.typography.titleSmall)
                    VisualCenterText(if (plans.isEmpty()) text.t("尚无课表", "No timetable yet") else "${semester.startDate} — ${semester.endDate}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected && plans.isNotEmpty()) {
                if (plans.size == 1 && chosen != null) Text(chosen.name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp))
                else Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { plansOpen = true }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("export_semester_plan_${semester.id}"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        VisualCenterText(chosen?.name ?: text.t("选择课程方案", "Choose course plan"), modifier = Modifier.weight(1f).centerVisualText(), textAlign = TextAlign.Start)
                    }
                    DropdownMenu(expanded = plansOpen, onDismissRequest = { plansOpen = false }) {
                        plans.forEach { plan -> DropdownMenuItem(text = { VisualCenterText(plan.name, modifier = Modifier.centerVisualText()) }, onClick = { onPlan(plan); plansOpen = false }) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ExportChoices(items: List<T>, selected: T, enabled: Boolean, onSelect: (T) -> Unit, label: (T) -> String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> FilterChip(selected == item, { onSelect(item) }, { VisualCenterText(label(item), modifier = Modifier.centerVisualText()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}
