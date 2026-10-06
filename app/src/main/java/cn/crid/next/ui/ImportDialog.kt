package cn.crid.next.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import cn.crid.next.core.*
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun ImportDialog(repository:AppRepository,state:AppState,text:UiText,incoming:Uri?,onIncomingConsumed:()->Unit,onDismiss:()->Unit,onManage:()->Unit,origin:ModalOrigin?=null) {
    val context=LocalContext.current
    val draft:ImportDraftViewModel=viewModel()
    val scope=draft.viewModelScope
    if(!draft.initialized) {draft.semesterId.value=state.selectedSemesterId ?: state.semesters.firstOrNull()?.id;draft.planId.value=state.selectedPlanId;draft.initialized=true}
    var busy by draft.busy
    var busyLabel by draft.busyLabel
    var error by draft.error
    var filename by draft.filename
    var result by draft.result
    var semesterId by draft.semesterId
    var planId by draft.planId
    var mode by draft.mode
    var planName by draft.planName
    var onlyKnown by draft.onlyKnown
    var acceptSemester by draft.acceptSemester
    var campusUrl by rememberSaveable {mutableStateOf<String?>(null)}
    val beijingOrigin=rememberModalOrigin()
    val zhuhaiOrigin=rememberModalOrigin()
    var campusOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    var success by draft.success
    val cancel:()->Unit={draft.clear();onDismiss()}
    LaunchedEffect(state.semesters) {
        if(state.semesters.none {it.id==semesterId}) {semesterId=state.selectedSemesterId?:state.semesters.firstOrNull()?.id;acceptSemester=false}
    }
    val semester=state.semesters.find {it.id==semesterId}
    val selectedPlan=state.plans.find {it.id==planId&&it.semesterId==semesterId}
    val readyResult=result?.let { original -> original.copy(
        unresolved=if(onlyKnown) emptyList() else original.unresolved,
        courses=if(onlyKnown)original.courses.filter {it.lessons.isNotEmpty()}else original.courses,
        sourceSemester=if(acceptSemester)null else original.sourceSemester
    )}
    var preview by remember {mutableStateOf<ImportPreview?>(null)}
    var checking by remember {mutableStateOf(false)}
    LaunchedEffect(readyResult,semester,selectedPlan,mode) {
        checking=true
        preview=if(readyResult!=null&&semester!=null)withContext(Dispatchers.Default) {ImportEngine.preview(readyResult,semester,selectedPlan,mode)}else null
        checking=false
    }
    val load:(Uri)->Unit={uri -> scope.launch {
        busy=true;error=null;busyLabel=text.t("正在读取课表…","Reading your timetable…")
        onlyKnown=false;acceptSemester=false;success=false;result=null
        try {
            val loaded=withContext(Dispatchers.IO) {
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if(cursor.moveToFirst())cursor.getString(0)else null } ?: "timetable"
                val data=context.contentResolver.openInputStream(uri)?.use {it.readAtMost(15*1024*1024+1)} ?: throw java.io.IOException()
                require(data.size<=15*1024*1024)
                name to TimetableParser.parse(data,name)
            }
            filename=loaded.first;result=loaded.second;planName=loaded.second.name
        } catch(_:Exception) {error=text.t("无法读取这份文件。请选择 15 MB 以内的课表表格或 Crid Next 数据文件。","Could not read this file. Choose a timetable spreadsheet or Crid Next data file up to 15 MB.")}
        finally {
            if(uri.authority=="${context.packageName}.files"&&uri.pathSegments.firstOrNull()=="campus_downloads") {
                runCatching {context.contentResolver.delete(uri,null,null)}
            }
            busy=false;draft.incomingBeingRead=null; if(uri==incoming)onIncomingConsumed()
        }
    }}
    val chooseFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null)load(uri)}
    LaunchedEffect(incoming) {if(incoming!=null&&draft.incomingBeingRead!=incoming.toString()) {draft.incomingBeingRead=incoming.toString();load(incoming);onIncomingConsumed()}}
    fun launchCampus(url:String) {
        campusUrl=url
    }
    AnimatedAppDialog(onDismissRequest=cancel,fullScreen=true,dismissEnabled=!busy,origin=origin,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) { motion ->
        MaterialTheme {
            MatchDialogSystemBars()
            Surface(Modifier.fillMaxSize().testTag("import_surface"),color=MaterialTheme.colorScheme.background) {
                Scaffold(
                    containerColor=MaterialTheme.colorScheme.background,
                    topBar={TopAppBar(title={VisualCenterText(text.t("导入课表","Import timetable"),modifier=Modifier.centerVisualText())},navigationIcon={GlyphAction("close",text.t("关闭导入","Close import"),motion.dismiss,enabled=!busy)})},
                    bottomBar={
                        if(result!=null&&!success)Surface(shadowElevation=4.dp) {
                            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=14.dp,vertical=16.dp)) {
                                Button(onClick={
                                    val imported=readyResult
                                    val target=semesterId
                                    val targetPlan=planId
                                    val chosenMode=mode
                                    val chosenName=planName
                                    scope.launch {
                                        busy=true;error=null;busyLabel=text.t("正在保存…","Saving…")
                                        try {
                                            repository.update {current -> ImportEngine.apply(current,requireNotNull(imported),requireNotNull(target),targetPlan,chosenMode,chosenName)}
                                            success=true
                                        }catch(_:Exception) {error=text.t("导入未能保存，已有课表保持不变。请检查目标学期与方案后重试。","The import was not saved. Your existing timetable is unchanged. Check the target and try again.")}
                                        finally {busy=false}
                                    }
                                },enabled=!busy&&!checking&&preview?.valid==true&&readyResult?.valid==true&&planName.isNotBlank()&&(mode==ImportMode.NEW||selectedPlan!=null),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                                    ActionLabel("check",if(mode==ImportMode.REPLACE)text.t("确认替换此方案","Confirm replacement")else text.t("确认导入","Confirm import"))
                                }
                            }
                        }
                    }
                ) {padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=14.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        if(success) {
                            EmptyPanel(text.t("课表已就位","Your timetable is ready"),text.t("打开今天或课表，查看新的安排","Your new classes are ready in Today and Timetable"),text.t("完成","Done"),motion.dismiss,illustration=IllustrationScene.PLANNING)
                        } else {
                            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(busyLabel,style=MaterialTheme.typography.bodyMedium)}
                            error?.let {IssueCard(text.t("还差一步","One more thing"),listOf(it),true,text)}
                            if(result==null) {
                                AppIllustration(IllustrationScene.IMPORT,Modifier.widthIn(max=360.dp).fillMaxWidth().height(152.dp).align(Alignment.CenterHorizontally))
                                SectionTitle(text.t("选择导入方式","Choose an import method","選擇匯入方式"),text.t("选择已有课表，或前往教务系统下载","Choose a saved timetable or download one from your campus portal"))
                                SoftCard(Modifier.fillMaxWidth(), role = AppSurfaceRole.Raised) {
                                    Text(text.t("从本地文件导入","From a local file"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                    Text(text.t("支持课表表格，以及 Crid Next 导出的数据文件","Choose a timetable spreadsheet or an exported Crid Next data file"),style=MaterialTheme.typography.bodyMedium)
                                    Button(onClick={chooseFile.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) {ActionLabel("folder",text.t("选择文件","Choose file"))}
                                }
                                SoftCard(Modifier.fillMaxWidth()) {
                                    Text(text.t("从教务系统导入","From the campus portal"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                    Text(text.t("登录教务系统并下载课表，下载完成后即可检查课程","Sign in to your campus portal and download your timetable to review the classes here"),style=MaterialTheme.typography.bodyMedium)
                                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick={beijingOrigin.capture();campusOrigin=beijingOrigin;launchCampus(CampusPortals.BEIJING)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).modalOrigin(beijingOrigin)){ActionLabel("open",text.t("北京校区","Beijing campus"))}
                                        OutlinedButton(onClick={zhuhaiOrigin.capture();campusOrigin=zhuhaiOrigin;launchCampus(CampusPortals.ZHUHAI)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).modalOrigin(zhuhaiOrigin)){ActionLabel("open",text.t("珠海校区","Zhuhai campus"))}
                                    }
                                }
                            }
                            result?.let {parsed ->
                                SectionTitle(text.t("检查导入内容","Review your import"),filename)
                                TextButton(onClick={chooseFile.launch(arrayOf("*/*"))},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) {ActionLabel("folder",text.t("重新选择文件","Choose another file"))}
                                if(parsed.errors.isNotEmpty()) IssueCard(text.t("暂时无法完成解析","This file needs another look"),parsed.errors,true,text)
                                if(parsed.unresolved.isNotEmpty()) {
                                    IssueCard(text.t("有些课程还没有明确时间","Some classes have no confirmed time"),parsed.unresolved,false,text)
                                    ImportCheckOption(
                                        label=text.t("仅导入已确定安排","Import confirmed arrangements only"),
                                        body=text.t("缺少时间的项目不会生成课程磁贴","Entries without a confirmed time will not appear on your timetable"),
                                        checked=onlyKnown,enabled=!busy,onCheckedChange={onlyKnown=it}
                                    )
                                }
                                if(state.semesters.isEmpty()) {
                                    EmptyPanel(text.t("先选择一个学期","First, choose a semester"),text.t("为这份课表创建学期，设置日期和上课时间","Create a semester with dates and class times for this timetable"),text.t("管理学期","Manage semesters"),{motion.finish(onManage)},illustration=IllustrationScene.PLANNING)
                                } else {
                                    SoftCard(Modifier.fillMaxWidth()) {
                                        Text(text.t("导入到","Import into"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                        ImportChoice(text.t("目标学期","Semester"),state.semesters.map {it.id to it.name},semesterId,!busy) {
                                            semesterId=it
                                            val firstPlan=state.plans.firstOrNull {p->p.semesterId==it}
                                            planId=firstPlan?.id
                                            if(mode!=ImportMode.NEW)planName=firstPlan?.name.orEmpty()
                                            acceptSemester=false
                                        }
                                        ImportChoice(text.t("写入方式","How to import"),listOf(ImportMode.NEW.name to text.t("新建方案","New plan"),ImportMode.MERGE.name to text.t("合并方案","Merge"),ImportMode.REPLACE.name to text.t("替换方案","Replace")),mode.name,!busy) {
                                            mode=ImportMode.valueOf(it)
                                            planName=if(mode==ImportMode.NEW)parsed.name else selectedPlan?.name.orEmpty()
                                        }
                                        if(mode!=ImportMode.NEW)ImportChoice(text.t("目标方案","Plan"),state.plans.filter {it.semesterId==semesterId}.map {it.id to it.name},planId,!busy){id->planId=id;state.plans.find {it.id==id}?.let {planName=it.name}}
                                        OutlinedTextField(value=planName,onValueChange={planName=it},label={Text(text.t("方案名称","Plan name"))},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
                                        semester?.let {Text("${it.startDate} — ${it.endDate} · ${it.weeks} "+text.t("周","weeks"),style=MaterialTheme.typography.bodySmall)}
                                        parsed.sourceSemester?.let {source ->
                                            Text(text.t("文件中的学期：","Semester in file: ")+source,style=MaterialTheme.typography.bodySmall)
                                            ImportCheckOption(label=text.t("确认使用所选学期","Use the selected semester"),checked=acceptSemester,enabled=!busy,onCheckedChange={acceptSemester=it})
                                        }
                                        if(mode==ImportMode.REPLACE&&selectedPlan!=null)Text(text.t("将替换「${selectedPlan.name}」中的 ${selectedPlan.courses.size} 门课程、${selectedPlan.courses.sumOf {it.lessons.size}} 条授课安排。其他方案不受影响。","This replaces ${selectedPlan.courses.size} courses and ${selectedPlan.courses.sumOf {it.lessons.size}} arrangements in “${selectedPlan.name}”. Other plans are kept."),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                                        if(mode==ImportMode.MERGE)Text(text.t("原有课程保留；完全相同的授课安排合并，差异内容分别保留。","Existing courses are kept. Identical arrangements are combined; different arrangements remain separate."),style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if(checking)LinearProgressIndicator(Modifier.fillMaxWidth())
                                preview?.let {check ->
                                    if(check.errors.isNotEmpty()) {
                                        IssueCard(text.t("需要处理的问题","Please resolve these items"),check.errors,true,text)
                                        TextButton(onClick={motion.finish(onManage)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) {ActionLabel("edit",text.t("修改学期日期或作息","Edit semester dates or class times"))}
                                    }
                                    SoftCard(Modifier.fillMaxWidth(), role = AppSurfaceRole.Raised) {
                                        Text(text.t("保存后的课表","Timetable after import"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                        Text(text.t("${check.courses.size} 门课程 · ${check.courses.sumOf {it.lessons.size}} 条授课安排","${check.courses.size} courses · ${check.courses.sumOf {it.lessons.size}} arrangements"))
                                        val unscheduled=check.courses.sumOf {course->course.lessons.count {it.unscheduled}}
                                        if(unscheduled>0)Text(text.t("$unscheduled 条安排待排时间，周次与教师已保留。","$unscheduled arrangements await a class time. Their weeks and teachers are saved."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text.t("${check.duplicates} 条重复 · ${check.conflicts} 处时间冲突","${check.duplicates} duplicates · ${check.conflicts} time conflicts"),style=MaterialTheme.typography.bodySmall)
                                    }
                                    if(check.warnings.isNotEmpty())IssueCard(text.t("请核对","Please review"),check.warnings,false,text)
                                    var coursePage by remember(check.courses) {mutableIntStateOf(0)}
                                    if(check.courses.size>20)PageControls(coursePage,check.courses.size,20,text){coursePage=it}
                                    check.courses.drop(coursePage*20).take(20).forEach {course ->CoursePreview(course,text)}
                                    if(check.courses.size>20)PageControls(coursePage,check.courses.size,20,text){coursePage=it}
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
    campusUrl?.let { url ->
        CampusBrowser(initialUrl=url,text=text,onDownloaded={uri->campusUrl=null;load(uri)},onDismiss={campusUrl=null},origin=campusOrigin)
    }
}

@Composable private fun ImportCheckOption(label:String,checked:Boolean,enabled:Boolean,onCheckedChange:(Boolean)->Unit,body:String="") {
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onCheckedChange).padding(vertical=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)
    ) {
        Checkbox(checked=checked,onCheckedChange=null,enabled=enabled,modifier=alignByVisualCenter())
        Column(alignByVisualCenter(Modifier.weight(1f)),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            VisualCenterText(label,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Start)
            if(body.isNotBlank())VisualCenterText(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Start)
        }
    }
}

@Composable private fun ImportChoice(label:String,options:List<Pair<String,String>>,value:String?,enabled:Boolean=true,onSelect:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick={open=true},enabled=enabled&&options.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp)){VisualCenterText(options.find {it.first==value}?.second ?: "—",modifier=Modifier.weight(1f).centerVisualText(),textAlign=TextAlign.Start,maxLines=2,overflow=TextOverflow.Ellipsis)}
            DropdownMenu(expanded=open,onDismissRequest={open=false}) {options.forEach {(id,name)->DropdownMenuItem(text={VisualCenterText(name,modifier=Modifier.centerVisualText())},onClick={onSelect(id);open=false})}}
        }
    }
}

@Composable private fun IssueCard(title:String,items:List<String>,critical:Boolean,text:UiText) {
    var page by remember(items){mutableIntStateOf(0)}
    Surface(color=if(critical)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleSmall)
            items.drop(page*20).take(20).forEach { item ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("•",modifier=Modifier.alignByBaseline(),style=MaterialTheme.typography.bodySmall)
                    Text(diagnostic(item,text),modifier=Modifier.weight(1f).alignByBaseline(),textAlign=TextAlign.Start,style=MaterialTheme.typography.bodySmall)
                }
            }
            if(items.size>20)PageControls(page,items.size,20,text){page=it}
        }
    }
}

@Composable private fun CoursePreview(course:Course,text:UiText) {
    var expanded by remember(course.id) {mutableStateOf(false)}
    var page by remember(course.id){mutableIntStateOf(0)}
    SoftCard(Modifier.fillMaxWidth()) {
        Text(course.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        if(course.credits.isNotBlank())Text(text.t("学分：","Credits: ")+course.credits,style=MaterialTheme.typography.bodySmall)
        course.lessons.drop(page*20).take(20).forEachIndexed {index,lesson ->
            if(index>0)HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(lesson.unscheduled) {
                    Text(text.t("待排时间","Time to be arranged"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Medium)
                } else {
                    val day=lesson.date ?: text.t("星期${"一二三四五六日".getOrNull(lesson.weekday-1) ?: '?'}","${listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").getOrNull(lesson.weekday-1) ?: "?"}")
                    val timing=if(lesson.startTime!=null)"${lesson.startTime}–${lesson.endTime}"else text.t("第 ${lesson.startPeriod}–${lesson.endPeriod} 节","Periods ${lesson.startPeriod}–${lesson.endPeriod}")
                    Text("$day · $timing",style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                }
                if(lesson.location.isNotBlank())Text(text.t("地点：","Location: ")+lesson.location,style=MaterialTheme.typography.bodyMedium)
                if(lesson.weeks.isNotEmpty())Text(text.t("第 ${previewWeeks(lesson.weeks)} 周","Weeks ${previewWeeks(lesson.weeks)}"),style=MaterialTheme.typography.bodySmall)
                if(lesson.teacher.isNotBlank())Text(text.t("教师：","Teacher: ")+lesson.teacher,style=MaterialTheme.typography.bodySmall)
                else if(course.extra.containsKey("教师名单"))Text(text.t("教师待确认","Teacher to be confirmed"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(lesson.note.isNotBlank()&&!lesson.unscheduled)Text(lesson.note,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(course.lessons.size>20)PageControls(page,course.lessons.size,20,text){page=it}
        if(course.extra.isNotEmpty()) {
            TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)) {VisualCenterText(if(expanded)text.t("收起其他信息","Hide extra information")else text.t("其他信息","More information"),modifier=Modifier.centerVisualText())}
            if(expanded)course.extra.forEach {(key,value)->Text("$key: $value",style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable private fun PageControls(page:Int,total:Int,size:Int,text:UiText,onPage:(Int)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Box(alignByVisualCenter()) {
            GlyphAction("back",text.t("上一页","Previous page"),{onPage(page-1)},enabled=page>0)
        }
        VisualCenterText("${page*size+1}–${minOf((page+1)*size,total)} / $total",style=MaterialTheme.typography.labelSmall,modifier=alignByVisualCenter())
        Box(alignByVisualCenter()) {
            GlyphAction("next",text.t("下一页","Next page"),{onPage(page+1)},enabled=(page+1)*size<total)
        }
    }
}

private fun previewWeeks(weeks:List<Int>):String {
    val sorted=weeks.distinct().sorted()
    if(sorted.isEmpty())return ""
    val ranges=mutableListOf<String>()
    var start=sorted.first()
    var end=start
    fun appendRange() {ranges+=if(start==end)"$start"else "$start–$end"}
    sorted.drop(1).forEach {week->if(week==end+1)end=week else {appendRange();start=week;end=week}}
    appendRange()
    return ranges.joinToString(", ")
}

/** Keeps source names verbatim while translating predictable parser diagnostics. */
private fun diagnostic(value:String,t:UiText):String {
    if(value=="暂不支持此课表格式，请尝试其他导出格式")return t.t(value,"This timetable format is not supported yet. Try another export format","暫不支援此課表格式，請嘗試其他匯出格式")
    Regex("第 (\\d+) 行的课程代码或名称无法识别。").matchEntire(value)?.let {
        val row = it.groupValues[1]
        return t.t("第 $row 行的课程代码或名称无法识别", "Could not read the course code or name in row $row", "第 $row 行的課程代碼或名稱無法識別")
    }
    Regex("(.+)：课程代码不一致，请核对源文件。", RegexOption.DOT_MATCHES_ALL).matchEntire(value)?.let {
        val name = it.groupValues[1]
        return t.t("$name：课程代码不一致，请核对源文件", "$name: course codes differ. Check the source file", "$name：課程代碼不一致，請核對來源檔案")
    }
    if(!t.isEnglish)return t.t(value,value)
    Regex("文件中的学期（(.*)）与目标学期不同，请确认目标学期后重新导入").matchEntire(value)?.let {return "The semester in the file (${it.groupValues[1]}) differs from the selected semester. Confirm the target above."}
    Regex("文件标注的学期为“(.*)”，请核对目标学期").matchEntire(value)?.let {return "The file names the semester “${it.groupValues[1]}”. Check the selected semester."}
    Regex("将替换“(.*)”中的 (\\d+) 门课程、(\\d+) 项授课安排").matchEntire(value)?.let {return "This replaces ${it.groupValues[2]} courses and ${it.groupValues[3]} arrangements in “${it.groupValues[1]}”."}
    Regex("(.+)与(.+)存在时间重叠，请核对授课安排").matchEntire(value)?.let {return "${it.groupValues[1]} / ${it.groupValues[2]}: classes overlap in time. Please review both arrangements."}
    Regex("已列出前 50 处时间重叠，共确认 (\\d+) 处").matchEntire(value)?.let {return "Showing the first 50 overlaps; ${it.groupValues[1]} have been confirmed."}
    Regex("授课安排较密集，冲突数量仅为已确认下限（至少 (\\d+) 处）；请继续按日期核对课表").matchEntire(value)?.let {return "This timetable is dense. At least ${it.groupValues[1]} conflicts were confirmed; review each date for any additional overlaps."}
    val replacements=mapOf(
        "一次最多处理 500 门课程，请拆分课表文件" to "Import up to 500 courses at a time. Split this timetable into smaller files.",
        "一个方案最多处理 3000 项授课安排，请拆分课表文件" to "A plan supports up to 3,000 arrangements. Split this timetable into smaller files.",
        "单项授课安排最多包含 366 个周次，请检查课表文件" to "Each arrangement supports up to 366 teaching weeks. Check the source file.",
        "部分课程文字过长，请精简课程名称或附加信息后再导入" to "Some course text is too long. Shorten the course names or additional information and try again.",
        "文件中没有识别到课程。" to "No courses were found in the file.",
        "此课表未提供学分，已留空。" to "Credits were not provided and have been left blank.",
        "文件损坏或内容无法读取，请重新导出后再试。" to "The file is damaged or unreadable. Export it again and retry.",
        "同名课程的不同授课安排均已保留，请核对时间、地点与教师" to "Different arrangements for this course are preserved. Check times, locations and teachers.",
        "部分安排未提供地点" to "Some arrangements have no location",
        "部分安排未提供教师" to "Some arrangements have no teacher",
        "未提供教师。" to "Teacher not provided.", "未提供学分。" to "Credits not provided.", "未提供地点。" to "Location not provided.",
        "文件仅提供教师名单，已原样保留，未推断各次授课对应教师。" to "The source provides a teacher list only. It has been preserved without assigning teachers to individual lessons.",
        "教师名单与授课安排数量不同，名单已保留，各次教师待确认。" to "The teacher list does not match the number of arrangements. The list is saved; individual assignments need confirmation.",
        "部分授课时间待安排，已保留周次和教师。" to "Some class times are to be arranged. Their weeks and teachers are saved.",
        "待排课安排不能同时指定星期、日期、节次或时间" to "An unscheduled arrangement cannot also specify a weekday, date, period or time",
        "学分信息不同，已保留原有值及导入记录" to "Credits differ. Both the existing value and the imported information are preserved.",
        "文件中有不同学分值，已保留。" to "The source contains different credit values; all are preserved.",
        "缺少星期或节次" to "Weekday or periods missing", "未提供上课时间" to "Class time not provided",
        "存在时间重叠，请核对授课安排" to " overlap in time; please review the arrangements",
        "请选择目标学期中的方案" to "Choose a plan in the selected semester",
        "请选择要合并或替换的方案" to "Choose a plan to merge into or replace",
        "当前方案不属于目标学期" to "This plan does not belong to the selected semester",
        "缺少可确定的授课安排" to "No confirmed class arrangement",
        "没有可导入的课程" to "No courses are available to import",
        "授课周次超出目标学期，请调整目标学期或授课安排" to "Teaching weeks extend beyond the semester. Adjust the semester dates or the source arrangements.",
        "授课日期超出目标学期" to "The class date is outside the selected semester",
        "节次无法对应目标学期作息，请先调整作息" to "A class period is missing from the semester's class times. Update the class times first.",
        "指定时间与目标学期节次时间不一致，请确认后保留一种时间表达" to "The explicit times differ from the semester's period times. Review the source and keep one time format.",
        "星期必须在 1 至 7 之间" to "The weekday must be between 1 (Monday) and 7 (Sunday)",
        "缺少授课周次或具体日期" to "Teaching weeks or an exact date are missing",
        "周次必须为正整数" to "Teaching week numbers must be positive integers",
        "授课日期无效" to "The class date is invalid",
        "星期与授课日期不一致" to "The weekday does not match the class date",
        "星期无法确定" to "The weekday could not be determined",
        "缺少节次或起止时间" to "Periods or start/end times are missing",
        "起止节次无效" to "Start or end period is invalid",
        "起止时间无效" to "Start or end time is invalid",
        "学期标识不能为空" to "The semester is invalid",
        "请填写学期名称" to "Enter a semester name",
        "请设置作息时间" to "Set the class times",
        "作息节次编号必须从 1 开始连续且不重复" to "Number class periods consecutively starting at 1",
        "的起止时间无效" to ": invalid start or end time",
        "与上一节的时间重叠" to " overlaps with the previous period",
        "请填写起始日、终止日和周数中的至少两项" to "Enter at least two of the start date, end date and week count",
        "周数必须为正整数" to "The week count must be a positive whole number",
        "起始日不能晚于终止日" to "The start date must not be after the end date",
        "起止日期与周数不一致" to "The dates and week count do not match",
        "请填写有效的日期（YYYY-MM-DD）" to "Enter valid dates (YYYY-MM-DD)",
        "日期范围过大" to "The date range is too large",
        "方案名称不能为空" to "Enter a plan name",
        "数据文件过大" to "The data file is too large",
        "课表文件结构过于复杂" to "The file contains an unsupported structure",
        "不是 Crid Next 课表文件" to "This is not a Crid Next timetable file",
        "暂不支持此课表文件版本" to "This timetable file version is not supported",
        "课表文件包含不允许的设置字段" to "The file includes settings that cannot be imported with a timetable",
        "课表文件结构无效或包含不支持的字段，请重新选择文件" to "The timetable structure is invalid or unsupported. Choose another file.",
        "文件为空，请重新选择。" to "The file is empty. Choose another file.",
        "文件过大，请选择不超过 15 MB 的课表文件。" to "The file is too large. Choose a timetable up to 15 MB.",
        "尚不支持此文件格式。请选择 XLS、XLSX、HTML 课表或 Crid Next 数据文件。" to "Choose an XLS, XLSX, HTML timetable or Crid Next data file.",
        "工作表数量过多。" to "The file has too many sheets.",
        "工作表范围过大。" to "The sheet is too large.",
        "表格行数过多。" to "The table has too many rows.",
        "表格跨度过大。" to "A merged table cell spans too far.",
        "表格范围过大。" to "The table is too large.",
        "文件包含过多压缩项目。" to "The file contains too many compressed entries.",
        "文件解压后过大。" to "The expanded file is too large.",
        "这不是有效的 XLSX 工作簿。" to "This is not a valid XLSX workbook.",
        "文件文本项目过多。" to "The file contains too much text.",
        "工作簿中没有可读取的工作表。" to "The workbook has no readable sheets.",
        "课表含公式，请先在表格软件中将公式转换为值。" to "The file contains formulas. Convert them to values in your spreadsheet app first.",
        "工作簿文字索引损坏。" to "The workbook's text data is damaged.",
        "文件包含不支持的 XML 声明。" to "The file contains unsupported document declarations.",
        "文件包含外部引用。" to "The file contains external references.",
        "文件内容无法识别。" to "The file contents could not be read.",
        "缺少课程名" to "The course name is missing",
        "无法识别上课时间" to "Unrecognized class time: ",
        "无法识别授课安排" to "Unrecognized class arrangement: ",
        "无法确定未排课项目的课程名或时间" to "Unscheduled entry has no confirmed name or time: ",
        "的课程内容无法识别。" to ": course information could not be read.",
        "无法识别课程及上课时间" to "Course name or class time could not be read: ",
        "周次或节次无效" to "Invalid teaching weeks or periods: ",
        "周次无效" to "Invalid teaching weeks: ",
        "源文件学期" to "Source semester", "目标学期" to "Target semester", "不一致" to "does not match",
        "周次超出学期范围" to "Teaching weeks exceed the semester",
        "节次无法映射到所选作息" to "Periods are missing from the selected class times",
        "：" to ": ", "；" to "; "
    )
    var output=value
    replacements.forEach {(source,target)->output=output.replace(source,target)}
    output=output.replace(Regex("（安排 (\\d+)）")) {" (arrangement ${it.groupValues[1]})"}
        .replace(Regex("第 (\\d+) 行")) {"Row ${it.groupValues[1]}"}
        .replace(Regex("第 (\\d+) 门课程")) {"Course ${it.groupValues[1]}"}
        .replace(Regex("第 (\\d+) 节")) {"Period ${it.groupValues[1]}"}
    return output
}
