package cn.crid.next.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

private enum class MoreAction { MANAGE, IMPORT, EXPORT }

@Serializable
private data class CourseSelection(
    val target: CourseEditTarget,
    val semester: Semester,
    val courseId: String,
    val lesson: Lesson? = null,
    val date: String? = null,
    val start: String? = null,
    val end: String? = null,
    val week: Int? = null,
    val statuses: List<String> = emptyList(),
    val holidayName: String? = null,
) {
    fun occurrence(): Occurrence? = target.expectedCourses.firstOrNull { it.id == courseId }?.let {
        if(lesson==null||date==null||start==null||end==null||week==null)return@let null
        Occurrence(it, lesson, LocalDate.parse(date), LocalTime.parse(start), LocalTime.parse(end), week,
            statuses.map { value -> OccurrenceStatus.valueOf(value) }.toSet(), holidayName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CridApp(repository: AppRepository, incomingUri: Uri? = null, onIncomingConsumed: () -> Unit = {}, initialRoute: String = "today", navigationToken: Int = 0, incomingDate: String? = null) {
    val state by repository.state.collectAsState()
    val calendar by repository.holidays.collectAsState()
    val systemLocale = LocalConfiguration.current.locales[0]
    val text = remember(state.settings.language, systemLocale) { UiText(state.settings.language, systemLocale) }
    val dark = when(state.settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
    val hostView = LocalView.current
    SideEffect {
        (hostView.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, hostView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.isNavigationBarContrastEnforced = false
        }
    }
    val baseScheme = cridColorScheme(dark)
    val pageRoutes=remember {listOf("today","week","plans","settings")}
    val launchDate=remember(incomingDate) {incomingDate?.let {runCatching {LocalDate.parse(it)}.getOrNull()}}
    var todayDateOverride by rememberSaveable {mutableStateOf(if(initialRoute=="today")launchDate?.toString()else null)}
    var route by rememberSaveable { mutableStateOf(if(initialRoute=="timetable")"week" else initialRoute.takeIf { it in pageRoutes } ?: "today") }
    var importing by rememberSaveable { mutableStateOf(initialRoute == "import") }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var pendingOpen by rememberSaveable { mutableStateOf(false) }
    val pending=remember(state.plan) {state.plan?.courses.orEmpty().flatMap {course -> course.lessons.filter {it.unscheduled}.map {course to it}}}
    var selectedJson by rememberSaveable { mutableStateOf<String?>(null) }
    val selected=remember(selectedJson) {selectedJson?.let {runCatching {Json.decodeFromString<CourseSelection>(it)}.getOrNull()}}
    var editorTargetJson by rememberSaveable {mutableStateOf<String?>(null)}
    val editorTarget=remember(editorTargetJson) {editorTargetJson?.let {runCatching {Json.decodeFromString<CourseEditTarget>(it)}.getOrNull()}}
    var editorSession by rememberSaveable {mutableStateOf("")}
    var editorCommitted by rememberSaveable {mutableStateOf(false)}
    var editorDate by rememberSaveable {mutableStateOf<String?>(null)}
    var editorLessonIndex by rememberSaveable {mutableStateOf<Int?>(null)}
    var editorOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    var courseActionBusy by remember {mutableStateOf(false)}
    var courseActionError by rememberSaveable {mutableStateOf<String?>(null)}
    var managingCourses by rememberSaveable {mutableStateOf(false)}
    var activeImportOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    var activeExportOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    var selectedOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    val openImport:(ModalOrigin?)->Unit={origin -> activeImportOrigin=origin;importing=true}
    val pendingHighlight = animateFloatAsState(if(pendingOpen)1f else 0f,AppMotion.pageSpring(),label="pending_origin")
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState=rememberPagerState(initialPage=pageRoutes.indexOf(route).coerceAtLeast(0)) {pageRoutes.size}
    var pagerNavigation by remember {mutableStateOf<Job?>(null)}
    var navigationRequest by remember {mutableIntStateOf(0)}
    var requestedPage by remember {mutableStateOf<Int?>(null)}
    val focusManager=LocalFocusManager.current
    val keyboardController=LocalSoftwareKeyboardController.current
    LaunchedEffect(pagerState.interactionSource) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if(interaction is DragInteraction.Start) {
                ++navigationRequest
                pagerNavigation?.cancel()
                pagerNavigation=null
                requestedPage=null
                focusManager.clearFocus(force=true)
                keyboardController?.hide()
            }
        }
    }
    val navigateTo:(String)->Unit={destination ->
        val page=pageRoutes.indexOf(destination)
        if(page>=0) {
            // An editor on the outgoing page must not pull the pager back into view
            // while the IME changes the viewport during navigation.
            focusManager.clearFocus(force=true)
            keyboardController?.hide()
            if(destination=="today")todayDateOverride=null
            val request=++navigationRequest
            requestedPage=page
            route=destination
            pagerNavigation?.cancel()
            pagerNavigation=scope.launch {
                try {pagerState.animateScrollToPage(page,animationSpec=AppMotion.pageSpring())}
                finally {if(navigationRequest==request)requestedPage=null}
            }
        }
    }
    // Page actions stay with the settled page; navigation responds to taps and the nearest dragged page.
    val currentRoute=pageRoutes[pagerState.settledPage]
    val navigationRoute=pageRoutes[requestedPage ?: pagerState.currentPage]
    LaunchedEffect(pagerState) {
        // Restore the selected destination, even if recreation interrupted a scroll.
        pagerState.scrollToPage(pageRoutes.indexOf(route).coerceAtLeast(0))
        snapshotFlow {Triple(pagerState.isScrollInProgress,pagerState.settledPage,requestedPage)}.collect {(scrolling,page,pending) ->
            if(!scrolling&&pending==null)route=pageRoutes[page]
        }
    }
    val now by rememberScheduleClock()
    val today=now.toLocalDate()
    val todayDate=todayDateOverride?.let(LocalDate::parse) ?: today
    var weekDateString by rememberSaveable { mutableStateOf((if(initialRoute in listOf("timetable","week"))launchDate else null)?.toString() ?: today.toString()) }
    val weekDate=LocalDate.parse(weekDateString)
    val closeDetails:()->Unit={selectedJson=null;selectedOrigin=null;courseActionError=null}
    val openEditor:(CourseEditTarget,LocalDate?,Int?,ModalOrigin?)->Unit={target,date,lessonIndex,origin ->
        editorCommitted=false
        editorSession=newId()
        editorTargetJson=Json.encodeToString(target)
        editorDate=date?.toString()
        editorLessonIndex=lessonIndex
        editorOrigin=origin
    }
    val selectCourse:(Occurrence,ModalOrigin)->Unit={occurrence,origin ->
        runCatching {
            val plan=requireNotNull(state.plan)
            val semester=requireNotNull(state.semester)
            val target=CourseEditing.editTarget(state,plan.id,occurrence.course.id)
            selectedJson=Json.encodeToString(CourseSelection(target,semester,occurrence.course.id,occurrence.lesson,
                occurrence.date.toString(),occurrence.start.toString(),occurrence.end.toString(),occurrence.week,
                occurrence.statuses.map {it.name},occurrence.holidayName))
            selectedOrigin=origin
            courseActionError=null
        }.onFailure {scope.launch {snackbar.showSnackbar(text.t("课程已更新，请重新打开。","This course has changed. Open it again."))}}
    }
    val addCourse:(ModalOrigin)->Unit={origin ->
        val plan=state.plan
        if(state.semester==null||plan==null) {
            managingCourses=false
            navigateTo("plans")
            scope.launch {snackbar.showSnackbar(text.t("请先创建学期和课表方案。","Create a term and timetable plan first."))}
        } else {
            runCatching {CourseEditing.newTarget(state,plan.id)}.onSuccess {target ->
                openEditor(target,if(currentRoute=="today")todayDate else null,null,origin)
            }.onFailure {scope.launch {snackbar.showSnackbar(text.t("无法打开课程，请重新选择课表方案。","Select a timetable plan and try again."))}}
        }
    }
    var lastInitialRoute by rememberSaveable {mutableStateOf(initialRoute)}
    var lastHandledToken by rememberSaveable {mutableIntStateOf(navigationToken)}
    var lastHandledDate by rememberSaveable {mutableStateOf(incomingDate)}
    LaunchedEffect(incomingUri) { if(incomingUri != null)openImport(null) }
    LaunchedEffect(initialRoute,navigationToken,incomingDate) {
        if(lastInitialRoute==initialRoute&&lastHandledToken==navigationToken&&lastHandledDate==incomingDate)return@LaunchedEffect
        lastInitialRoute=initialRoute
        lastHandledToken=navigationToken
        lastHandledDate=incomingDate
        when(initialRoute) {
            "import" -> openImport(null)
            "timetable", "week" -> {navigateTo("week");weekDateString=(launchDate ?: LocalDate.now()).toString()}
            "today" -> {navigateTo("today");todayDateOverride=launchDate?.toString()}
            "plans", "settings" -> navigateTo(initialRoute)
        }
    }
    val updateBase=state
    val update: (AppState) -> Unit = { next -> scope.launch { runCatching { repository.update { current -> applyStateChanges(updateBase,next,current) } }.onFailure { snackbar.showSnackbar(text.t("保存失败，请重试。","Could not save. Please try again.")) } } }
    WideColorDisplayProvider {
    MaterialTheme(colorScheme=baseScheme, typography=Typography()) {
        val scheme = MaterialTheme.colorScheme
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideNavigation = maxWidth >= 600.dp
        val expandedNavigation = maxWidth >= 840.dp
        val destinations=listOf("today" to text.t("今天","Today"),"week" to text.t("课表","Timetable"),"plans" to text.t("方案","Plans"),"settings" to text.t("设置","Settings"))
        Row(Modifier.fillMaxSize()) {
        if(sideNavigation) {
            Surface(color=scheme.surfaceContainerLow,modifier=Modifier.width(if(expandedNavigation)232.dp else 104.dp).fillMaxHeight().testTag("side_navigation")) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .padding(horizontal=if(expandedNavigation)12.dp else 4.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(12.dp))
                    destinations.forEach { (id,label) ->
                        if(expandedNavigation) NavigationDrawerItem(
                            selected=navigationRoute==id,onClick={navigateTo(id)},label={VisualCenterText(label,Modifier.centerVisualText(),maxLines=2)},modifier=Modifier.testTag("nav_$id"),
                            icon={NavGlyph(id,if(navigationRoute==id)scheme.primary else scheme.onSurfaceVariant)},
                            colors=NavigationDrawerItemDefaults.colors(selectedContainerColor=scheme.primaryContainer,unselectedContainerColor=Color.Transparent))
                        else NavigationRailItem(
                            selected=navigationRoute==id,onClick={navigateTo(id)},icon={NavGlyph(id,if(navigationRoute==id)scheme.primary else scheme.onSurfaceVariant)},
                            label={Text(label,maxLines=2,textAlign=TextAlign.Center)},
                            modifier=Modifier.fillMaxWidth().testTag("nav_$id"),colors=NavigationRailItemDefaults.colors(indicatorColor=scheme.primaryContainer))
                    }
                }
            }
        }
        Scaffold(
            modifier=Modifier.weight(1f),
            containerColor=scheme.background,
            snackbarHost={ SnackbarHost(snackbar) },
            // Every page owns its top inset; the shell only reserves persistent navigation.
            contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom),
            bottomBar={
                if(!sideNavigation) CridBottomNavigation(
                    destinations=destinations,selectedRoute=navigationRoute,
                    pagePosition={pagerState.currentPage+pagerState.currentPageOffsetFraction},onNavigate=navigateTo,
                )
            }
        ) { padding ->
            HorizontalPager(state=pagerState,key={pageRoutes[it]},modifier=Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("main_pager")) {page ->
                val pageRoute=pageRoutes[page]
                val pageActive=page==pagerState.settledPage
                val pageInteractive=pageActive&&!pagerState.isScrollInProgress
                var moreActions by remember {mutableStateOf(false)}
                var pendingMoreAction by remember {mutableStateOf<MoreAction?>(null)}
                val moreOrigin=rememberModalOrigin()
                val addCourseOrigin=rememberModalOrigin()
                val importOrigin=rememberModalOrigin()
                val exportOrigin=rememberModalOrigin()
                val pendingOrigin=rememberModalOrigin()
                LaunchedEffect(pageInteractive) {if(!pageInteractive){pendingMoreAction=null;moreActions=false}}
                val chooseMoreAction:(MoreAction)->Unit={action ->
                    if(moreActions&&pageInteractive) {pendingMoreAction=action;moreActions=false}
                }
                // Neighboring pages remain visible during a drag without duplicating TalkBack targets.
                Column(Modifier.fillMaxSize().testTag("page_$pageRoute").semantics {
                    if(!pageActive)hideFromAccessibility()
                }) {
                Column(Modifier.fillMaxWidth().testTag(if(pageActive)"route_toolbar" else "route_toolbar_$pageRoute")) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start=20.dp,end=10.dp,top=26.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    VisualCenterText(destinations.first {it.first==pageRoute}.second,modifier=alignByVisualCenter(Modifier.weight(1f)).testTag(if(pageActive)"route_header" else "route_header_$pageRoute"),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(pageRoute in listOf("today","week")&&pending.isNotEmpty())IconButton(onClick={if(pageInteractive){pendingOrigin.capture();pendingOpen=true}},modifier=Modifier.alignBy {it.measuredHeight/2}.size(48.dp).drawBehind {drawCircle(scheme.primaryContainer.copy(alpha=pendingHighlight.value.coerceIn(0f,1f)))}.modalOrigin(pendingOrigin).testTag(if(pageActive)"pending_lessons" else "pending_lessons_$pageRoute").semantics {contentDescription=text.t("${pending.size} 项待排时间","${pending.size} arrangements awaiting a time")}) {
                        BadgedBox(badge={Badge {VisualCenterText(pending.size.toString(),Modifier.centerVisualText())}}) {AppGlyph("time",scheme.onSurface)}
                    }
                    if(pageRoute in listOf("today","week")) {
                        IconButton(onClick={if(pageInteractive){addCourseOrigin.capture();addCourse(addCourseOrigin)}},modifier=alignByVisualCenter(Modifier.size(48.dp)).modalOrigin(addCourseOrigin).testTag(if(pageActive)"action_add_course" else "action_add_course_$pageRoute").semantics {contentDescription=text.t("新增课程","Add course")}) {
                            AppGlyph("add",scheme.onSurface)
                        }
                        Box(alignByVisualCenter()) {
                            IconButton(onClick={if(pageInteractive){moreOrigin.capture();moreActions=true}},modifier=Modifier.size(48.dp).modalOrigin(moreOrigin).testTag(if(pageActive)"action_more" else "action_more_$pageRoute").semantics {contentDescription=text.t("更多操作","More actions")}) {
                                VisualCenterText("⋮",modifier=Modifier.centerVisualText(),fontSize=28.sp,color=scheme.onSurface)
                            }
                            AnimatedOverflowMenu(expanded=moreActions&&pageInteractive,onDismissRequest={moreActions=false},onClosed={
                                val action=pendingMoreAction
                                pendingMoreAction=null
                                if(pageInteractive)when(action) {
                                    MoreAction.MANAGE -> managingCourses=true
                                    MoreAction.IMPORT -> openImport(moreOrigin)
                                    MoreAction.EXPORT -> {activeExportOrigin=moreOrigin;exporting=true}
                                    null -> Unit
                                }
                            },modifier=Modifier.testTag("more_menu_$pageRoute")) {
                                DropdownMenuItem(text={VisualCenterText(text.t("管理课程","Manage courses"),Modifier.centerVisualText())},leadingIcon={AppGlyph("edit")},enabled=state.plan!=null,onClick={chooseMoreAction(MoreAction.MANAGE)},modifier=Modifier.testTag("action_manage_courses"))
                                DropdownMenuItem(text={VisualCenterText(text.t("导入课表","Import timetable"),Modifier.centerVisualText())},leadingIcon={AppGlyph("import")},onClick={chooseMoreAction(MoreAction.IMPORT)},modifier=Modifier.testTag(if(pageActive)"action_import" else "action_import_$pageRoute"))
                                DropdownMenuItem(text={VisualCenterText(text.t("导出课表","Export timetable"),Modifier.centerVisualText())},leadingIcon={AppGlyph("export")},enabled=state.plan!=null,onClick={chooseMoreAction(MoreAction.EXPORT)},modifier=Modifier.testTag(if(pageActive)"action_export" else "action_export_$pageRoute"))
                            }
                        }
                    } else {
                        IconButton(onClick={if(pageInteractive){importOrigin.capture();openImport(importOrigin)}},modifier=alignByVisualCenter(Modifier.size(48.dp)).modalOrigin(importOrigin).testTag(if(pageActive)"action_import" else "action_import_$pageRoute").semantics {contentDescription=text.t("导入","Import")}) {
                            AppGlyph("import",scheme.onSurface)
                        }
                        IconButton(onClick={if(pageInteractive){exportOrigin.capture();activeExportOrigin=exportOrigin;exporting=true}},enabled=state.plan != null,modifier=alignByVisualCenter(Modifier.size(48.dp)).modalOrigin(exportOrigin).testTag(if(pageActive)"action_export" else "action_export_$pageRoute").semantics {contentDescription=text.t("导出","Export")}) {
                            AppGlyph("export",if(state.plan!=null)scheme.onSurface else scheme.onSurface.copy(alpha=.38f))
                        }
                    }
                }
                if(repository.storageNeedsRecovery)Surface(color=scheme.errorContainer) {
                    Text(text.t("已保存的课表暂时无法读取，原文件已保留。请先备份应用数据后恢复课表。","Saved timetables could not be read. The original file is preserved. Back up app data before restoring your timetable."),modifier=Modifier.fillMaxWidth().padding(16.dp),style=MaterialTheme.typography.bodySmall,color=scheme.onErrorContainer)
                }
                }
                Box(Modifier.weight(1f).fillMaxWidth().testTag("page_content_$pageRoute")) {
                when(pageRoute) {
                    "today" -> TodayScreen(state,calendar,todayDate,today,now.toLocalTime(),text,
                        {navigateTo("plans")},{openImport(null)},selectedOrigin,selectCourse)
                    "week" -> WeekScreen(state,calendar,weekDate,text,{weekDateString=it.toString()},{navigateTo("plans")},{openImport(null)},selectedOrigin,selectCourse)
                    "plans" -> PlansScreen(state,text,update,openImport)
                    else -> SettingsScreen(state,calendar,text,update)
                }
                }
                }
            }
        }
        }
        }
        if(importing) ImportDialog(repository,state,text,incomingUri,onIncomingConsumed,{importing=false},{navigateTo("plans");importing=false},origin=activeImportOrigin)
        if(exporting) ExportDialog(state,calendar,text,{exporting=false},origin=activeExportOrigin)
        if(managingCourses)CourseManagerSheet(state.plan?.courses.orEmpty(),text,onDismiss={managingCourses=false},onSelectCourse={course ->
            runCatching {
                val target=CourseEditing.editTarget(state,requireNotNull(state.plan).id,course.id)
                selectedJson=Json.encodeToString(CourseSelection(target,requireNotNull(state.semester),course.id))
                selectedOrigin=null
                courseActionError=null
                managingCourses=false
            }.onFailure {scope.launch {snackbar.showSnackbar(text.t("课程已更新，请重新打开。","This course has changed. Open it again."))}}
        },onAddCourse=addCourse,semester=state.semester)
        if(pendingOpen)PendingLessonsSheet(pending,state.semester,text,onDismiss={pendingOpen=false},onCourse={course ->
            runCatching {
                val target=CourseEditing.editTarget(state,requireNotNull(state.plan).id,course.id)
                selectedJson=Json.encodeToString(CourseSelection(target,requireNotNull(state.semester),course.id))
                selectedOrigin=null
                courseActionError=null
                pendingOpen=false
            }.onFailure {scope.launch {snackbar.showSnackbar(text.t("课程已更新，请重新打开。","This course has changed. Open it again."))}}
        })
        selected?.let { selection ->
            val remove:(String?,Int?)->Unit={courseId,lessonIndex ->
                if(!courseActionBusy) {
                    courseActionBusy=true
                    courseActionError=null
                    scope.launch {
                        try {
                            if(courseId!=null&&lessonIndex!=null)repository.deleteLesson(selection.target,courseId,lessonIndex)
                            else repository.deleteCourse(selection.target)
                            closeDetails()
                            scope.launch {snackbar.showSnackbar(if(courseId!=null)text.t("已删除授课安排","Arrangement deleted")else text.t("已删除课程","Course deleted"))}
                        } catch(cancelled:kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch(failure:Exception) {
                            courseActionError=when(failure) {
                                is CourseEditConflictException -> text.t("课程或课表方案已变更，请重新打开后再删除。","The course or timetable plan has changed. Open the course again before deleting it.")
                                else -> text.t("删除失败，请重试。","Could not delete. Please try again.")
                            }
                        } finally {courseActionBusy=false}
                    }
                }
            }
            CourseDetailsSheet(selection.target.expectedCourses,selection.occurrence(),selection.semester,text,
                onDismiss=closeDetails,
                onEditCourse={origin -> openEditor(selection.target,null,null,origin)},
                onEditLesson={courseId,lessonIndex,origin ->
                    val preceding=selection.target.expectedCourses.takeWhile {it.id!=courseId}.sumOf {it.lessons.size}
                    openEditor(selection.target,null,preceding+lessonIndex,origin)
                },
                onDeleteCourse={remove(null,null)},onDeleteLesson={courseId,lessonIndex -> remove(courseId,lessonIndex)},
                actionBusy=courseActionBusy,actionError=courseActionError)
        }
        editorTarget?.let { target ->
            CourseEditorDialog(state,target,text,onDismiss={
                    editorTargetJson=null;editorOrigin=null
                    if(editorCommitted)closeDetails()
                    editorCommitted=false
                },
                onSave={captured,draft ->
                    repository.saveCourse(captured,draft)
                    editorCommitted=true
                    scope.launch {snackbar.showSnackbar(text.t("已保存课程","Course saved"))}
                },initialDate=editorDate?.let(LocalDate::parse),origin=editorOrigin,sessionId=editorSession,initialLessonIndex=editorLessonIndex)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PendingLessonsSheet(pending:List<Pair<Course,Lesson>>,semester:Semester?,text:UiText,onDismiss:()->Unit,onCourse:(Course)->Unit) {
    AppBottomSheet(onDismissRequest=onDismiss) {
        WindowColorProvider {
        MatchBottomSheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=32.dp).testTag("pending_details"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(text.t("待排时间","Awaiting a time"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            pending.groupBy {courseKey(it.first.name)}.values.forEach {records ->
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(courseNameSpacing(records.first().first.name),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    records.map {it.second}.distinct().sortedBy {it.weeks.minOrNull() ?: Int.MAX_VALUE}.forEach {lesson -> TeachingRecord(lesson,semester,text) }
                    TextButton(onClick={onCourse(records.first().first)}) {VisualCenterText(text.t("查看课程","View course"),Modifier.centerVisualText())}
                }
            }
        }
        }
    }
}

@Composable private fun missingSchedule(state:AppState,text:UiText,onPlans:()->Unit,onImport:()->Unit):Boolean {
    return when {
        state.semester==null -> { EmptyPanel(text.t("从一个学期开始","Start with a semester"),text.t("安排好学期，再把课程放进日常","Set up your semester, then bring in your classes"),text.t("创建学期","Create semester"),onPlans,illustration=IllustrationScene.PLANNING);true }
        state.plan==null -> { EmptyPanel(text.t("还没有课表方案","Your timetable starts here"),text.t("导入一份课表，或在方案中创建空白课表","Import a timetable or create a blank plan"),text.t("导入课表","Import timetable"),onImport,illustration=IllustrationScene.PLANNING);true }
        else -> false
    }
}

@Composable internal fun TodayScreen(state:AppState,calendar:HolidayCalendar,date:LocalDate,today:LocalDate,now:LocalTime,text:UiText,onPlans:()->Unit,onImport:()->Unit,selectedOrigin:ModalOrigin?,onSelect:(Occurrence,ModalOrigin)->Unit) {
    val semester=state.semester
    val plan=state.plan
    val schedule=remember(semester,plan,state.settings,calendar) {
        if(semester!=null&&plan!=null)ScheduleEngine.prepare(semester,plan,state.settings,calendar)else null
    }
    val lessons=remember(schedule,date) {schedule?.occurrences(date).orEmpty()}
    val actualLessons=remember(lessons) {lessons.filter {it.isActual}}
    Column(Modifier.fillMaxSize()) {
        Text(text.date(date),modifier=Modifier.padding(start=20.dp,end=20.dp,bottom=12.dp).testTag("today_date"),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(!missingSchedule(state,text,onPlans,onImport)) {
            val following=remember(state,calendar,date,today) {
                if(date==today)followingTeachingDay(state,calendar,date,schedule)else emptyList()
            }
            val status=remember(lessons,date,today,now,following) {todayCourseStatus(lessons,date,today,now,following)}
            val week=ScheduleEngine.weekNumber(requireNotNull(semester),date,state.settings.weekStartsSunday)
            Box(Modifier.widthIn(max=720.dp).fillMaxWidth()) {TodayStatusCard(status,week,text,onSelect)}
            if(actualLessons.isEmpty()) {
                Box(Modifier.widthIn(max=480.dp).fillMaxWidth().padding(vertical=16.dp).testTag("today_rest_illustration")) {
                    AppIllustration(IllustrationScene.REST,Modifier.height(180.dp))
                }
            } else Box(Modifier.widthIn(max=720.dp).fillMaxWidth()) { TodayAgenda(actualLessons,semester.periods,text,selectedOrigin,onSelect) }
        }
        Spacer(Modifier.height(12.dp))
    }
    }
}

@Composable private fun WeekSwitcher(state:AppState,anchor:LocalDate,text:UiText,onDate:(LocalDate)->Unit) {
    val first=ScheduleEngine.weekStart(anchor,state.settings.weekStartsSunday)
    val dates=(0..6).map { first.plusDays(it.toLong()) }
    val week=state.semester?.let { ScheduleEngine.weekNumber(it,dates.firstOrNull { d -> !d.isBefore(LocalDate.parse(it.startDate)) } ?: anchor,state.settings.weekStartsSunday) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).weekSwipe {direction -> onDate(anchor.plusWeeks(direction.toLong()))}.padding(horizontal=14.dp).padding(bottom=4.dp).testTag("week_switcher"),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Box(alignByVisualCenter()) {WeekArrow(false,text) {onDate(anchor.minusWeeks(1))}}
        TextButton(onClick={onDate(LocalDate.now())},modifier=alignByVisualCenter(Modifier.weight(1f).heightIn(min=48.dp)).testTag("week_current")) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                VisualCenterText(if(week!=null)text.t("第 $week 周","Week $week")else text.t("回到本周","This week"),fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                VisualCenterText("${first.monthValue}.${first.dayOfMonth} – ${dates.last().monthValue}.${dates.last().dayOfMonth}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        Box(alignByVisualCenter()) {WeekArrow(true,text) {onDate(anchor.plusWeeks(1))}}
    }
}

@Composable private fun WeekScreen(state:AppState,calendar:HolidayCalendar,anchor:LocalDate,text:UiText,onDate:(LocalDate)->Unit,onPlans:()->Unit,onImport:()->Unit,selectedOrigin:ModalOrigin?,onSelect:(Occurrence,ModalOrigin)->Unit) {
    val first=ScheduleEngine.weekStart(anchor,state.settings.weekStartsSunday)
    val scrollState=rememberScrollState()
    val semester=state.semester
    val plan=state.plan
    val schedule=remember(semester,plan,state.settings,calendar) {
        if(semester!=null&&plan!=null)ScheduleEngine.prepare(semester,plan,state.settings,calendar)else null
    }
    Column(Modifier.fillMaxSize()) {
        WeekSwitcher(state,anchor,text,onDate)
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=14.dp)) {
        if(!missingSchedule(state,text,onPlans,onImport)) {
            AnimatedContent(targetState=first,modifier=Modifier.fillMaxSize(),label="week_change",transitionSpec={
                val direction=if(targetState>initialState)1 else -1
                (slideInHorizontally(AppMotion.pageSpring()) {it*direction} togetherWith
                    slideOutHorizontally(AppMotion.pageSpring()) {-it*direction}).using(SizeTransform(clip=true) {_,_->AppMotion.pageSpring()})
            }) {displayFirst ->
                val dates=remember(displayFirst) {(0..6).map {displayFirst.plusDays(it.toLong())}}
                val lessons=remember(schedule,displayFirst) {dates.map {day -> requireNotNull(schedule).occurrences(day)}}
                WeekGrid(dates,lessons,state.semester!!.periods,state.settings,calendar,text,selectedOrigin,onSelect,
                    bodyScrollState=scrollState,onWeekSwipe={direction -> onDate(anchor.plusWeeks(direction.toLong()))},emptyContent={
                        EmptyPanel(text.t("这一周没有课程","An open week"),text.t("可以切换周次，或添加新的课程安排","Browse another week or add a course"),"",{})
                    })
            }
        }
    }
    }
}

/** Only the two fixed week controls own this gesture; the course body belongs to the pager. */
@Composable private fun Modifier.weekSwipe(onWeek:(Int)->Unit):Modifier {
    val latestOnWeek by rememberUpdatedState(onWeek)
    val minimumDrag=with(LocalDensity.current) {32.dp.toPx()}
    return pointerInput(minimumDrag) {
        var distance=0f
        detectHorizontalDragGestures(
            onDragStart={distance=0f},
            onHorizontalDrag={change,amount -> change.consume();distance+=amount},
            onDragEnd={if(abs(distance)>=minimumDrag)latestOnWeek(if(distance<0)1 else -1);distance=0f},
            onDragCancel={distance=0f},
        )
    }
}

@Composable private fun WeekArrow(next:Boolean,text:UiText,onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.size(48.dp).testTag(if(next)"week_next" else "week_previous").semantics {
        contentDescription=if(next)text.t("下一周","Next week")else text.t("上一周","Previous week")
    }) {
        AppGlyph(if(next)"next" else "previous",MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WeekGrid(dates:List<LocalDate>,days:List<List<Occurrence>>,periods:List<Period>,settings:Settings,calendar:HolidayCalendar,text:UiText,selectedOrigin:ModalOrigin?,onSelect:(Occurrence,ModalOrigin)->Unit,bodyScrollState:ScrollState,onWeekSwipe:(Int)->Unit,emptyContent:@Composable ()->Unit) {
    val isEmpty=days.all {it.isEmpty()}
    val fontScale=LocalDensity.current.fontScale.coerceAtLeast(1f)
    val k=1.6f*fontScale
    val layouts=remember(days) {days.map(::timetableGroups)}
    val spans=remember(layouts) {layouts.flatten().map {it.span}}
    val minimumHeightStep=with(LocalDensity.current) {1f.toDp().value}+.01f
    val axis=remember(periods,spans,k,minimumHeightStep) { TimetableAxis(periods,spans,k,6f*fontScale,minimumLessonHeight=64f*fontScale,heightSmoothing=8f*fontScale,minimumHeightStep=minimumHeightStep) }
    val periodPositions=remember(periods,axis) {periods.map {period ->
        axis.y(LocalTime.parse(period.start).minuteOfDay()) to axis.y(LocalTime.parse(period.end).minuteOfDay())
    }}
    val restDays=remember(dates,calendar,settings.holidaysEnabled,settings.makeupMode) {dates.map {date ->
        val holiday=calendar.days.find {it.date==date.toString()}
        settings.holidaysEnabled&&(holiday?.statutory==true||(holiday?.extraRest==true&&settings.makeupMode!=MakeupMode.OFF))
    }}
    val lineColor=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f)
    val holidayShade=MaterialTheme.colorScheme.onSurface.copy(alpha=.03f)
    val holidayHatch=MaterialTheme.colorScheme.onSurface.copy(alpha=.075f)
    var selectedGroup by remember {mutableStateOf<TimetableGroup?>(null)}
    var groupOrigin by remember {mutableStateOf<ModalOrigin?>(null)}
    val headerHeight=(56*fontScale).dp
    BoxWithConstraints(Modifier.fillMaxSize().testTag("week_grid")) {
        val columns=timetableColumns(maxWidth.value,dates.size,fontScale)
        val dateDiameter=minOf(columns.dayWidth,32f*fontScale.coerceAtMost(1.2f))
        val dateFontSize=minOf(14f,dateDiameter/(1.5f*fontScale))
        val scrollState=rememberScrollState()
        val gridModifier=if(columns.scrolls)Modifier.horizontalScroll(scrollState).testTag("narrow_grid_scroll") else Modifier
        Column(gridModifier.width(columns.contentWidth.dp).fillMaxHeight()) {
            Row(Modifier.width(columns.contentWidth.dp).height(headerHeight).background(MaterialTheme.colorScheme.surface).weekSwipe(onWeekSwipe).testTag("week_grid_header"),horizontalArrangement=Arrangement.spacedBy(columns.gap.dp)) {
                Box(Modifier.width(columns.timeWidth.dp).fillMaxHeight(),contentAlignment=Alignment.TopCenter) {
                    Text(text.t("节次","Period"),fontSize=10.sp,lineHeight=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                }
                dates.forEachIndexed {index,date ->
                    val rests=restDays[index]
                    Column(Modifier.width(columns.dayWidth.dp).fillMaxHeight().testTag("header_day_$index").semantics {if(rests)contentDescription=text.date(date)+text.t("，休假","; holiday")},horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(text.weekday(date),fontSize=11.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        val isToday=date==LocalDate.now()
                        Box(Modifier.size(dateDiameter.dp).background(if(isToday)MaterialTheme.colorScheme.primary else Color.Transparent,CircleShape)
                            .then(if(isToday)Modifier.testTag("current_day") else Modifier),contentAlignment=Alignment.Center) {
                            VisualCenterText(date.dayOfMonth.toString(),modifier=Modifier.centerVisualText(),fontSize=dateFontSize.sp,lineHeight=(dateFontSize*1.2f).sp,fontWeight=FontWeight.Bold,
                                color=if(isToday)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,maxLines=1)
                        }
                    }
                }
            }
            val body:@Composable ()->Unit={
                Row(Modifier.width(columns.contentWidth.dp).height(axis.height.dp),horizontalArrangement=Arrangement.spacedBy(columns.gap.dp)) {
                    Box(Modifier.width(columns.timeWidth.dp).fillMaxHeight().testTag("time_axis")) {
                        periods.forEachIndexed {index,period ->
                            val (start,end)=periodPositions[index]
                            Column(Modifier.offset(y=start.dp).height((end-start).dp).fillMaxWidth().testTag("time_period_${period.number}").centerVisualText(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                VisualCenterText(period.number.toString(),fontSize=12.sp,lineHeight=15.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
                                VisualCenterText(period.start,fontSize=9.sp,lineHeight=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                                VisualCenterText(period.end,fontSize=9.sp,lineHeight=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                            }
                        }
                    }
                    dates.indices.forEach {index ->
                        val layout=layouts[index]
                        val rests=restDays[index]
                        Box(Modifier.width(columns.dayWidth.dp).fillMaxHeight().testTag("week_day_$index").clip(RoundedCornerShape(8.dp)).drawBehind {
                            if(rests) {
                                drawRect(holidayShade)
                                drawHolidayHatch(holidayHatch)
                            }
                            periodPositions.forEach {(start,_) -> val y=start.dp.toPx();drawLine(lineColor,Offset(0f,y),Offset(size.width,y),.5.dp.toPx())}
                        }) {
                            layout.forEachIndexed {groupIndex,group ->
                                val tileModifier=Modifier.offset(y=axis.y(group.span.start).dp).fillMaxWidth().height(axis.lessonHeight(group.span).dp).testTag("course_tile_${index}_$groupIndex")
                                if(group.courses.size==1) CourseTile(group.representative,text,tileModifier,selectedOrigin,onSelect)
                                else GroupedCourseTile(group,text,tileModifier,if(selectedGroup!=null)groupOrigin else selectedOrigin) {origin -> groupOrigin=origin;selectedGroup=group}
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScrollState).testTag("week_grid_body").padding(bottom=12.dp)) {
                if(isEmpty)emptyContent()else body()
            }
        }
    }
    selectedGroup?.let {group ->
        AppBottomSheet(onDismissRequest={selectedGroup=null;groupOrigin=null}) {
            WindowColorProvider {
            MatchBottomSheetSystemBars()
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=32.dp).testTag("course_group_details"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(group.hasConflict)text.t("同时段的课程","Overlapping classes") else text.t("课程安排","Course arrangements"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                group.courses.sortedBy {!it.representative.isActual}.forEach {course ->
                    val item=course.representative
                    Surface(onClick={selectedGroup=null;groupOrigin?.let {onSelect(item,it)}},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(courseNameSpacing(item.course.name),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Text("${item.start}–${item.end}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                            if(item.lesson.location.isNotBlank())Text(courseLocationSpacing(item.lesson.location),style=MaterialTheme.typography.bodyMedium)
                            if(course.weeks.isNotEmpty())Text(text.t("授课周：","Teaching weeks: ")+formatWeekRanges(course.weeks,if(text.isEnglish)", " else "、"),style=MaterialTheme.typography.bodySmall)
                            StatusLabels(item,text)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable private fun CourseTile(occurrence:Occurrence,text:UiText,modifier:Modifier,selectedOrigin:ModalOrigin?,onSelect:(Occurrence,ModalOrigin)->Unit) {
    val origin=rememberModalOrigin()
    val sourceHighlight = animateFloatAsState(if(selectedOrigin===origin)1f else 0f,AppMotion.pageSpring(),label="course_origin")
    val out=OccurrenceStatus.OUT_OF_WEEK in occurrence.statuses
    val rest=OccurrenceStatus.HOLIDAY in occurrence.statuses
    val palette=courseTilePalette(occurrence.course,out,rest)
    val contentDescription=buildString {append(courseNameSpacing(occurrence.course.name));append(" ${occurrence.start}–${occurrence.end}");if(out)append(text.t("，非本周","; not this week"));if(rest)append(text.t("，休假不上课","; cancelled for holiday"));if(OccurrenceStatus.MAKEUP in occurrence.statuses)append(text.t("，调休补课","; make-up class"))}
    BoxWithConstraints(modifier.modalOrigin(origin).clip(RoundedCornerShape(8.dp)).courseTileBackground(palette,sourceHighlight,8.dp,out,rest)
        .clickable {origin.capture();onSelect(occurrence,origin)}.semantics {this.contentDescription=contentDescription}) {
        CourseTileContent(courseNameSpacing(occurrence.course.name),courseTextSpacing(occurrence.lesson.teacher),courseLocationSpacing(occurrence.lesson.location),palette.content,maxWidth,maxHeight)
    }
}

@Composable private fun GroupedCourseTile(group:TimetableGroup,text:UiText,modifier:Modifier,selectedOrigin:ModalOrigin?,onClick:(ModalOrigin)->Unit) {
    val origin=rememberModalOrigin()
    val sourceHighlight = animateFloatAsState(if(selectedOrigin===origin)1f else 0f,AppMotion.pageSpring(),label="group_origin")
    val first=group.representative
    val allOut=group.occurrences.all {OccurrenceStatus.OUT_OF_WEEK in it.statuses}
    val allRest=group.occurrences.all {OccurrenceStatus.HOLIDAY in it.statuses}
    val palette=courseTilePalette(first.course,allOut,allRest)
    val representatives=group.courses.sortedBy {!it.representative.isActual}.map {it.representative}
    val names=representatives.map {courseNameSpacing(it.course.name)}.distinct()
    val teachers=representatives.map {courseTextSpacing(it.lesson.teacher)}.filter {it.isNotBlank()}.distinct().joinToString(" / ")
    val locations=representatives.map {courseLocationSpacing(it.lesson.location)}.filter {it.isNotBlank()}.distinct().joinToString(" / ")
    val description=buildString {
        append(names.joinToString("、"))
        if(group.hasConflict)append(text.t("，${group.conflictCourseCount} 门同时段课程","; ${group.conflictCourseCount} overlapping classes"))
        else append(text.t("，${group.courses.size} 门课程，点击查看安排","; ${group.courses.size} courses. Open arrangements"))
        if(allOut)append(text.t("，非本周","; not this week"))
        if(allRest)append(text.t("，休假不上课","; cancelled for holiday"))
    }
    BoxWithConstraints(modifier.modalOrigin(origin).clip(RoundedCornerShape(8.dp)).courseTileBackground(palette,sourceHighlight,8.dp,allOut,allRest)
        .clickable(onClick={origin.capture();onClick(origin)}).semantics {contentDescription=description}) {
        CourseTileContent(names.joinToString(" / "),teachers,locations,palette.content,maxWidth,maxHeight) {
            Row(Modifier.testTag(if(group.hasConflict)"course_conflict" else "course_arrangements"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                AppGlyph(if(group.hasConflict)"overlap" else "plans",palette.content,alignByVisualCenter(Modifier.size(11.dp)))
                VisualCenterText((if(group.hasConflict)group.conflictCourseCount else group.courses.size).toString(),modifier=alignByVisualCenter(),fontSize=10.sp,lineHeight=12.sp,color=palette.content)
            }
        }
    }
}

@Composable private fun CourseTileContent(title:String,teacher:String,location:String,color:Color,width:Dp,height:Dp,footer:(@Composable ()->Unit)?=null) {
    val density=LocalDensity.current
    val narrowTile=width<88.dp
    val horizontalPadding=3.dp
    val titleStyle=MaterialTheme.typography.bodyMedium.copy(fontSize=if(narrowTile)11.sp else 14.sp,lineHeight=if(narrowTile)14.sp else 18.sp,fontWeight=FontWeight.SemiBold)
    val detailStyle=MaterialTheme.typography.bodySmall.copy(fontSize=if(narrowTile)10.sp else 12.sp,lineHeight=if(narrowTile)13.sp else 16.sp)
    val styles=listOf(titleStyle,detailStyle,detailStyle)
    val values=listOf(title,location,teacher)
    val measurer=rememberTextMeasurer()
    val textWidth=with(density) {(width-horizontalPadding*2).roundToPx().coerceAtLeast(1)}
    // TextMeasurer's cache also invalidates asynchronously resolved fonts and density changes.
    val desiredLines=values.mapIndexed {index,value -> if(value.isBlank())0 else measurer.measure(value,style=styles[index],constraints=Constraints(maxWidth=textWidth)).lineCount}
    val lineHeights=styles.map {with(density) {it.lineHeight.toDp().value}}
    val footerHeight=if(footer!=null)with(density) {12.sp.toDp().value}.coerceAtLeast(11f) else 0f
    val availableHeight=height.value-10f-footerHeight-(if(footer!=null)3f else 0f)
    val lines=courseTileLines(desiredLines[0],desiredLines[1],desiredLines[2],lineHeights[0],lineHeights[1],availableHeight)
    Column(Modifier.fillMaxSize().padding(horizontal=horizontalPadding,vertical=5.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(title,modifier=Modifier.testTag("course_name"),style=titleStyle,maxLines=lines.title,overflow=TextOverflow.Ellipsis,color=color)
        if(lines.location>0)Text(location,modifier=Modifier.testTag("course_location"),style=detailStyle,maxLines=lines.location,overflow=TextOverflow.Ellipsis,color=color)
        if(lines.teacher>0)Text(teacher,modifier=Modifier.testTag("course_teacher"),style=detailStyle,maxLines=lines.teacher,overflow=TextOverflow.Ellipsis,color=color)
        footer?.invoke()
    }
}

private fun statusText(o:Occurrence,t:UiText)=o.statuses.joinToString(" · ") {when(it){OccurrenceStatus.OUT_OF_WEEK->t.t("非本周","Other week");OccurrenceStatus.HOLIDAY->t.t("休假 · 不上课","Holiday · no class");OccurrenceStatus.MAKEUP->t.t("调休补课","Make-up class")}}
@Composable private fun StatusLabels(o:Occurrence,t:UiText) { if(o.statuses.isNotEmpty())Text(statusText(o,t),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.tertiary) }
