package cn.crid.next

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CridInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        runBlocking { repository.update { AppState(settings = Settings(language = Language.EN)) } }
        compose.waitForIdle()
    }
    @After fun restore() {
        runBlocking { repository.update { previous } }
    }
    @Test fun createDefaultSemesterAndNavigateAllFourTabs() {
        compose.onNode(hasText("Start with a semester") and hasAnyAncestor(hasTestTag("page_today")),useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("nav_plans").performClick()
        compose.onNodeWithText("New term").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { repository.state.value.semesters.size == 1 }
        val semester = repository.state.value.semesters.single()
        assertEquals("2026-09-07", semester.startDate)
        assertEquals("2027-01-10", semester.endDate)
        assertEquals(18, semester.weeks)
        assertEquals(12, semester.periods.size)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithTag("nav_week").performClick()
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        compose.onNodeWithTag("nav_today").performClick()
        compose.onNode(hasText("Your timetable starts here") and hasAnyAncestor(hasTestTag("page_today")),useUnmergedTree=true).assertIsDisplayed()
    }
    @Test fun actualAndroidReadsAllSuppliedWorkbooksAndPersistsImport() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        data class WorkbookFixture(val name: String, val courses: Int, val lessons: Int, val pending: Int)
        val fixtures = listOf(
            WorkbookFixture("北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls", 11, 25, 2),
            WorkbookFixture("校园课表-2025-2026春季学期.xls", 14, 40, 2),
            WorkbookFixture("学生选课课程表.xls", 14, 40, 2),
            WorkbookFixture("学生选课课程表(1).xls", 14, 40, 2),
            WorkbookFixture("学生选课课程表(2).xls", 14, 26, 0),
            WorkbookFixture("学生选课课程表-1.xls", 14, 26, 0),
        )
        fixtures.forEach { (name, courses, lessons, pending) ->
            val parsed = assets.open(name).use { TimetableParser.parse(it.readBytes(), name) }
            assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
            assertEquals(courses, parsed.courses.size)
            assertEquals(lessons, parsed.courses.sumOf { it.lessons.size })
            assertTrue(parsed.valid); assertTrue(parsed.unresolved.isEmpty()); assertEquals(pending, parsed.courses.sumOf { course -> course.lessons.count { it.unscheduled } })
        }
        val name = fixtures.first().name
        val parsed = assets.open(name).use { TimetableParser.parse(it.readBytes(), name) }
        val confirmed = parsed.copy(unresolved = emptyList(), courses = parsed.courses.filter { it.lessons.isNotEmpty() }, sourceSemester = null)
        val semester = Defaults.semester()
        runBlocking {
            repository.update { AppState(semesters = listOf(semester), selectedSemesterId = semester.id) }
            repository.update { ImportEngine.apply(it, confirmed, semester.id, null, ImportMode.NEW, "Fixture import") }
        }
        assertEquals(25, repository.state.value.plan!!.courses.sumOf { it.lessons.size })
        val modern=repository.state.value.plan!!.courses.single { it.name == "合成课课课未" }
        assertEquals(4,modern.lessons.size)
        assertEquals(listOf(listOf(11,12,13),listOf(16)),modern.lessons.filter {it.unscheduled}.map {it.weeks})
        assertTrue(modern.lessons.all {it.teacher == "示例戌"})
        val disk = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "timetables-v1.json").readText()
        assertTrue(disk.contains("Fixture import"))
        val before = repository.state.value
        runCatching { runBlocking { repository.update { ImportEngine.apply(it, parsed.copy(errors = listOf("Invalid source")), semester.id, null, ImportMode.NEW, "Must fail") } } }
        assertEquals(before, repository.state.value)
        assertEquals(disk, File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "timetables-v1.json").readText())
        compose.onNodeWithTag("pending_lessons").performClick()
        compose.onAllNodesWithText("合成课课课未").assertCountEquals(1)
        compose.onAllNodesWithText("示例戌", substring=true).assertCountEquals(2)
    }
    @Test fun courseDetailsAndDurationMonotonicityRemainAccessible() {
        val today = LocalDate.now()
        val semester = Defaults.semester().copy(startDate = today.minusDays(7).toString(), endDate = today.plusDays(118).toString(), weeks = 18)
        val courses = listOf(
            Course(name = "Short class", lessons = listOf(Lesson(date = today.toString(), weekday = today.dayOfWeek.value, startTime = "08:00", endTime = "08:45", location = "Room 101", teacher = "Dr A"))),
            Course(name = "Long class", lessons = listOf(Lesson(date = today.toString(), weekday = today.dayOfWeek.value, startTime = "09:00", endTime = "10:30", location = "Room 202", teacher = "Dr B"))),
        )
        val plan = Plan(semesterId = semester.id, name = "Today test", courses = CourseColors.assign(courses))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id, Settings(language = Language.EN, holidaysEnabled = false)) } }
        compose.waitForIdle()
        compose.onNodeWithTag("nav_week").performClick()
        val short = compose.onNode(hasContentDescription("Short class 08:00–08:45") and hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true)
        val long = compose.onNode(hasContentDescription("Long class 09:00–10:30") and hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true)
        val shortHeight = short.fetchSemanticsNode().size.height
        val longHeight = long.fetchSemanticsNode().size.height
        assertTrue("A longer course must be strictly taller", longHeight > shortHeight)
        short.performScrollTo().performClick()
        compose.onNode(hasText("Room 101") and hasAnyAncestor(hasTestTag("course_details")),useUnmergedTree=true).assertExists()
        compose.onNode(hasText("Dr A") and hasAnyAncestor(hasTestTag("course_details")),useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithTag("course_details").performTouchInput { swipeDown(durationMillis=350) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_details").fetchSemanticsNodes().isEmpty() }
        short.performScrollTo().performClick()
        compose.onNode(isDialog()).performTouchInput { click(Offset(width / 2f, 20f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("course_details").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun pageSurvivesRecreationAndRepeatedShortcutNavigates() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Appearance").assertExists()
        repeat(2) {
            compose.runOnUiThread {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                    compose.activity, Intent(compose.activity.intent)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("route", "today"))
            }
            compose.waitForIdle()
            compose.onNode(hasText("Start with a semester") and hasAnyAncestor(hasTestTag("page_today")),useUnmergedTree=true).assertIsDisplayed()
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithText("Appearance").assertExists()
        }
    }
    @Test fun tabletUsesSidebarAndReturnsToPhoneNavigationWithoutLosingPage() {
        compose.onNodeWithTag("bottom_navigation").assertExists()
        try {
            shell("wm size 1600x1200")
            shell("wm density 160")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("side_navigation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithText("Appearance").assertExists()
        } finally {
            shell("wm size reset")
            shell("wm density reset")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("side_navigation").assertDoesNotExist()
        compose.onNodeWithText("Appearance").assertExists()
    }
    @Test fun explicitDarkThemeAlsoUsesLightSystemBarIcons() {
        runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = ThemeMode.DARK)) } }
        compose.waitForIdle()
        compose.runOnUiThread {
            val flags = compose.activity.window.insetsController!!.systemBarsAppearance
            assertEquals(0, flags and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
        runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = ThemeMode.LIGHT)) } }
        compose.waitForIdle()
        compose.runOnUiThread {
            val flags = compose.activity.window.insetsController!!.systemBarsAppearance
            assertTrue(flags and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
        }
    }
    @Test fun phoneWeekFitsSevenDaysWithTimeOnlyInTheAxis() {
        val today=LocalDate.now()
        val semester=Defaults.semester().copy(startDate=today.minusDays(7).toString(),endDate=today.plusDays(118).toString(),weeks=18)
        val lesson=Lesson(date=today.toString(),weekday=today.dayOfWeek.value,startPeriod=1,endPeriod=1)
        val plan=Plan(semesterId=semester.id,name="Compact week",courses=listOf(Course(name="Geometry",lessons=listOf(
            lesson,Lesson(weeks=listOf(1,2,3,4),weekday=0,unscheduled=true,teacher="Pending teacher")
        ))))
        runBlocking {repository.update {AppState(listOf(semester),listOf(plan),semester.id,plan.id,Settings(language=Language.EN,holidaysEnabled=false))}}
        compose.onNodeWithTag("nav_week").performClick()
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        compose.onNodeWithText("Crid").assertDoesNotExist()
        compose.onNode(hasText(semester.name) and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertDoesNotExist()
        compose.onNode(hasText(semester.name) and hasAnyAncestor(hasTestTag("route_toolbar")),useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("semester_context").assertDoesNotExist()
        compose.onNode(hasTestTag("pending_lessons") and hasAnyAncestor(hasTestTag("route_toolbar")),useUnmergedTree=true).assertExists()
        compose.onNode(hasTestTag("week_switcher") and hasAnyAncestor(hasTestTag("route_toolbar")),useUnmergedTree=true).assertDoesNotExist()
        compose.onNode(hasTestTag("week_switcher") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertExists()
        val todayMarker=compose.onNode(hasTestTag("current_day") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertEquals("Today's marker stays circular",todayMarker.width,todayMarker.height,.5f)
        val switcherBefore=compose.onNodeWithTag("week_switcher").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("narrow_grid_scroll").assertDoesNotExist()
        val grid=compose.onNodeWithTag("week_grid").fetchSemanticsNode().boundsInRoot
        for(day in 0..6) {
            val bounds=compose.onNodeWithTag("week_day_$day").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left>=grid.left-1 && bounds.right<=grid.right+1)
            assertTrue(bounds.width>0)
        }
        compose.onNode(hasTestTag("time_period_1") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertExists()
        compose.onNode(hasText("08:00") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertExists()
        compose.onNode(hasText("08:45") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertExists()
        compose.onNode(hasText("08:00–08:45") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertDoesNotExist()
        compose.onNode(hasContentDescription("Geometry 08:00–08:45") and hasAnyAncestor(hasTestTag("page_week")),useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("week_grid").performTouchInput { swipeUp() }
        val switcherAfter=compose.onNodeWithTag("week_switcher").fetchSemanticsNode().boundsInRoot
        assertEquals(switcherBefore.top,switcherAfter.top,.5f)
        compose.onNodeWithTag("pending_lessons").performClick()
        compose.onNodeWithText("Weeks 1–4").assertExists()
    }
    @Test fun launcherIconSupportsThemedMonochromeAndNightBackground() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        fun background(night:Int):Int {
            val configured=context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            return ((configured.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable).background as ColorDrawable).color
        }
        assertNotEquals(background(Configuration.UI_MODE_NIGHT_NO),background(Configuration.UI_MODE_NIGHT_YES))
        fun render(resource:Int,night:Int=Configuration.UI_MODE_NIGHT_NO):Bitmap = Bitmap.createBitmap(108,108,Bitmap.Config.ARGB_8888).also { bitmap ->
            val configured=context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            configured.getDrawable(resource)!!.apply {setBounds(0,0,108,108);draw(Canvas(bitmap))}
        }
        val full=render(R.drawable.ic_launcher_foreground)
        val nightForeground=render(R.drawable.ic_launcher_foreground,Configuration.UI_MODE_NIGHT_YES)
        val monochrome=render(R.drawable.ic_launcher_monochrome)
        try {
            assertTrue("Only the background changes with system night mode",full.sameAs(nightForeground))
            var painted=0
            var left=108; var right=0; var top=108; var bottom=0
            for(y in 0 until 108)for(x in 0 until 108) {
                val colorAlpha=Color.alpha(full.getPixel(x,y))
                val monoAlpha=Color.alpha(monochrome.getPixel(x,y))
                if(colorAlpha>32||monoAlpha>32) {
                    painted++
                    left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
                    val radius=kotlin.math.hypot(x+.5-54,y+.5-54)
                    assertTrue("Both icon variants must fit the circular adaptive safe zone",radius<=32.5)
                }
            }
            assertTrue(painted>800)
            assertEquals(54.0,(left+right+1)/2.0,1.0)
            assertEquals(54.0,(top+bottom+1)/2.0,1.0)
            // Color carries the lesson cells; themed icons express those cells through negative space.
            // Compare their exterior in both axes without requiring identical interior alpha masks.
            fun exterior(bitmap:Bitmap,line:Int,horizontal:Boolean):Pair<Int,Int>? {
                val visible=(0 until 108).filter { index ->
                    Color.alpha(bitmap.getPixel(if(horizontal)index else line,if(horizontal)line else index))>32
                }
                return if(visible.isEmpty())null else visible.first() to visible.last()
            }
            for(horizontal in listOf(true,false))for(line in 0 until 108) {
                val colorEdge=exterior(full,line,horizontal)
                val monoEdge=exterior(monochrome,line,horizontal)
                assertEquals("The exterior occupies the same rows and columns",colorEdge==null,monoEdge==null)
                if(colorEdge!=null&&monoEdge!=null) {
                    assertEquals("Exterior start differs on line $line",colorEdge.first.toFloat(),monoEdge.first.toFloat(),1f)
                    assertEquals("Exterior end differs on line $line",colorEdge.second.toFloat(),monoEdge.second.toFloat(),1f)
                }
            }
            val lessonCells=listOf(
                Triple(42,53,Color.parseColor("#30A895")),
                Triple(42,68,Color.parseColor("#7054C7")),
                Triple(54,61,Color.parseColor("#E58B76")),
                Triple(66,56,Color.parseColor("#7054C7")),
                Triple(66,71,Color.parseColor("#30A895")),
            )
            lessonCells.forEachIndexed { index,(x,y,expectedColor) ->
                assertEquals("Lesson cell $index keeps its intended solid color",expectedColor,full.getPixel(x,y))
                assertEquals("Themed lesson cell $index remains visible as negative space",0,Color.alpha(monochrome.getPixel(x,y)))
            }
            assertEquals("The timetable uses three distinguishable lesson fills",3,
                lessonCells.map { (x,y,_) -> full.getPixel(x,y) }.toSet().size)
            assertEquals("The color header remains filled",255,Color.alpha(full.getPixel(54,44)))
            assertEquals("Themed header separator is clear",0,Color.alpha(monochrome.getPixel(54,44)))

            val output=File(context.getExternalFilesDir(null),"qa-v1.7.1/icon-validation").apply { mkdirs() }
            fun preview(name:String,drawable:android.graphics.drawable.Drawable) {
                val bitmap=Bitmap.createBitmap(432,432,Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0,0,432,432);drawable.draw(Canvas(bitmap))
                    File(output,name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                }finally {bitmap.recycle()}
            }
            listOf(Configuration.UI_MODE_NIGHT_NO to "light",Configuration.UI_MODE_NIGHT_YES to "dark").forEach { (night,label) ->
                val configured=context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                })
                preview("adaptive-color-$label.png",configured.getDrawable(R.mipmap.ic_launcher)!!)
                val foreground=configured.getDrawable(R.drawable.ic_launcher_monochrome)!!.mutate().apply {
                    setTint(configured.getColor(if(night==Configuration.UI_MODE_NIGHT_NO)android.R.color.system_accent1_800 else android.R.color.system_accent1_200))
                }
                val backgroundColor=configured.getColor(if(night==Configuration.UI_MODE_NIGHT_NO)android.R.color.system_accent1_100 else android.R.color.system_accent1_800)
                preview("adaptive-themed-$label.png",AdaptiveIconDrawable(ColorDrawable(backgroundColor),foreground))
            }
            preview("foreground-color.png",context.getDrawable(R.drawable.ic_launcher_foreground)!!)
            preview("foreground-monochrome.png",context.getDrawable(R.drawable.ic_launcher_monochrome)!!)
        }finally{full.recycle();nightForeground.recycle();monochrome.recycle()}
    }
    @Test fun consecutivePythonPeriodsShowOneCardWithLocationAboveTeacherAndCompactWeeks() {
        seedDisplayPlan {_,day -> listOf(Course(name="Python合成课甲",lessons=listOf(
            Lesson(weeks=(1..4).toList(),weekday=day,startPeriod=5,endPeriod=6,teacher="Ada",location="Computer laboratory"),
            Lesson(weeks=(1..4).toList(),weekday=day,startPeriod=7,endPeriod=7,teacher="Ada",location="Computer laboratory")
        ))) }
        val card=compose.onNodeWithContentDescription("Python 合成课甲 13:30–16:15")
        card.performScrollTo()
        compose.onAllNodes(hasText("Python 合成课甲") and hasAnyAncestor(hasTestTag("today_agenda"))).assertCountEquals(1)
        val teacher=compose.onNodeWithTag("course_teacher",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val location=compose.onNodeWithTag("course_location",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("The room must appear before the teacher",location.bottom<=teacher.top)
        card.performClick()
        compose.onAllNodesWithText("Weeks 1–4").assertCountEquals(2)
        compose.onAllNodes(hasText("Ada") and hasAnyAncestor(hasTestTag("course_details")),useUnmergedTree=true).assertCountEquals(2)
        compose.onNodeWithText("Close").assertDoesNotExist()
        assertEquals(1,repository.state.value.plan!!.courses.size)
        assertEquals(2,repository.state.value.plan!!.courses.single().lessons.size)
    }
    @Test fun fullScreenDialogsKeepTheirContentUntilSpatialExitCompletes() {
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_entry"))
        compose.onNodeWithTag("about_entry").performClick()
        val settled=compose.onNodeWithTag("about_screen").fetchSemanticsNode().boundsInRoot
        try {
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag("about_back").performClick()
            compose.mainClock.advanceTimeBy(96)
            val closing=compose.onNodeWithTag("about_screen").fetchSemanticsNode().boundsInRoot
            assertTrue("Exit must include spatial motion, not only fading",closing.left>settled.left+.5f || closing.width<settled.width-.5f || closing.top>settled.top+.5f)
            compose.mainClock.advanceTimeBy(2000)
            compose.onNodeWithTag("about_screen").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance=true }
        compose.onNodeWithTag("route_header").assertTextEquals("Settings")
    }
    @Test fun crossWeekCourseUsesOneCardAndPreservesTeachers() {
        val current=seedDisplayPlan {week,day -> listOf(Course(name="Unified course",lessons=listOf(
            Lesson(weeks=listOf(week),weekday=day,startTime="08:00",endTime="08:45",teacher="Teacher A",location="Room A"),
            Lesson(weeks=listOf(week+1),weekday=day,startTime="08:00",endTime="09:30",teacher="Teacher B",location="Room B")
        ))) }
        compose.onNodeWithTag("course_conflict",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("course_arrangements",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Private plan name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Unified course 08:00–08:45").performScrollTo().performClick()
        compose.onNodeWithTag("course_details").assertExists()
        compose.onNode(hasText("Teacher A") and hasAnyAncestor(hasTestTag("teaching_record_0")),useUnmergedTree=true).assertExists()
        compose.onNode(hasText("Teacher B") and hasAnyAncestor(hasTestTag("teaching_record_1")),useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Weeks $current").assertExists()
        compose.onNodeWithText("Weeks ${current+1}").assertExists()
    }
    @Test fun onlyCurrentWeekOverlapsAreCalledConflicts() {
        seedDisplayPlan {week,day -> listOf(
            Course(name="Current course",lessons=listOf(Lesson(weeks=listOf(week),weekday=day,startTime="08:00",endTime="09:00"))),
            Course(name="Other course",lessons=listOf(Lesson(weeks=listOf(week+1),weekday=day,startTime="08:00",endTime="09:00")))
        )}
        compose.onNodeWithTag("nav_week").performClick()
        compose.onNodeWithTag("course_conflict",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("course_arrangements",useUnmergedTree=true).assertExists()
        seedDisplayPlan {week,day -> listOf(
            Course(name="Current course",lessons=listOf(Lesson(weeks=listOf(week),weekday=day,startTime="08:00",endTime="09:00"))),
            Course(name="Another current course",lessons=listOf(Lesson(weeks=listOf(week),weekday=day,startTime="08:30",endTime="09:30")))
        )}
        compose.onNodeWithTag("course_conflict",useUnmergedTree=true).assertExists()
    }
    @Test fun aboutPageShowsAuthorMitAndThirdPartyLicenses() {
        runBlocking {repository.update {it.copy(settings=it.settings.copy(theme=ThemeMode.DARK))}}
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_entry"))
        compose.onNodeWithTag("about_entry").performClick()
        compose.onNodeWithTag("about_screen").assertExists()
        compose.runOnUiThread {
            val focused=WindowInspector.getGlobalWindowViews().first {it.hasWindowFocus()}
            assertEquals(0,focused.windowInsetsController!!.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
        compose.onNodeWithTag("about_content").performScrollToNode(hasTestTag("about_author"))
        compose.onNodeWithText("Gliese-876(@Gliese-876)").assertExists()
        compose.onNodeWithTag("about_content").performScrollToNode(hasTestTag("about_license"))
        compose.onNodeWithTag("about_license").performClick()
        compose.onNodeWithTag("about_license_text").assertTextContains("Permission is hereby granted",substring=true)
        compose.onNodeWithTag("about_document_back").performClick()
        compose.onNodeWithTag("about_content").performScrollToNode(hasTestTag("about_notices"))
        compose.onNodeWithTag("about_notices").performClick()
        compose.onNodeWithTag("about_license_text").assertTextContains("JExcelAPI 2.6.12",substring=true)
        compose.onNodeWithTag("about_license_text").assertTextContains("GNU LESSER GENERAL PUBLIC LICENSE",substring=true)
        compose.onNodeWithTag("about_document_back").performClick()
        compose.onNodeWithTag("about_back").performClick()
        compose.onNodeWithTag("about_screen").assertDoesNotExist()
        compose.onNodeWithTag("route_header").assertTextEquals("Settings")
    }
    @Test fun mainPagesSlideByGestureAndKeepLastNavigationTarget() {
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("route_header").assertTextEquals("Timetable")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("route_header").assertTextEquals("Settings")
        val choosePlans=compose.onNodeWithTag("nav_plans").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val chooseToday=compose.onNodeWithTag("nav_today").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {choosePlans();chooseToday()}
        fun navigationSnapshot(): String {
            val routes = listOf("today", "week", "plans", "settings")
            val header = compose.onAllNodesWithTag("route_header").fetchSemanticsNodes().mapNotNull {
                it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            }.filter { it in setOf("Today", "Timetable", "Plans", "Settings") }
            val positions = routes.associateWith { route ->
                compose.onAllNodesWithTag("page_$route", useUnmergedTree = true).fetchSemanticsNodes().map { it.positionInRoot.x }
            }
            val selected = routes.filter { route ->
                compose.onAllNodesWithTag("nav_$route").fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true }
            }
            return "header=$header, positions=$positions, selected=$selected"
        }
        val initialNavigation = navigationSnapshot()
        runCatching {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("route_header").fetchSemanticsNodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "Today"
                }
            }
        }.getOrElse { failure ->
            throw AssertionError("Rapid navigation did not reach Today; initial: $initialNavigation; final: ${navigationSnapshot()}", failure)
        }
        compose.onNodeWithTag("route_header").assertTextEquals("Today")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("route_header").assertTextEquals("Today")
    }
    @Test fun campusPortalUsesEmbeddedWebViewAndReturnsToImport() {
        compose.onNodeWithTag("action_more").performClick()
        compose.onNodeWithTag("action_import").performClick()
        compose.onNodeWithText("Beijing campus").performScrollTo().performClick()
        compose.onNodeWithTag("campus_webview").assertExists()
        compose.onNodeWithTag("campus_close").performClick()
        compose.onNodeWithTag("campus_webview").assertDoesNotExist()
        compose.onNodeWithText("Choose an import method").assertExists()
        compose.onNodeWithContentDescription("Close import").performClick()
    }
    @Test fun capturedCampusFileEntersCompleteImportPreviewAndClearsCache() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val semester=Defaults.semester()
        runBlocking {repository.update {it.copy(semesters=listOf(semester),selectedSemesterId=semester.id)}}
        val filename="北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls"
        val bytes=instrumentation.context.assets.open(filename).use {it.readBytes()}
        val address="https://jwxt.bnuzh.edu.cn/export/timetable.xls"
        val response=object:HttpURLConnection(URL(address)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getInputStream()=ByteArrayInputStream(bytes)
            override fun getContentLengthLong()=bytes.size.toLong()
            override fun getContentType()="application/vnd.ms-excel"
            override fun getHeaderFields():MutableMap<String,MutableList<String>> = mutableMapOf()
            override fun getHeaderField(name:String?)=if(name.equals("Content-Disposition",true)) "attachment; filename=campus-timetable.xls" else null
        }
        val downloaded=CampusDownload(File(context.cacheDir,"browser-downloads"),openConnection={response}).download(
            CampusDownloadRequest(address,"https://jwxt.bnuzh.edu.cn/student/timetable","CridNextTest"))
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",downloaded)
        val initial=Intent(compose.activity.intent)
        try {
            compose.runOnUiThread {
                instrumentation.callActivityOnNewIntent(compose.activity,Intent(initial).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(uri,"application/vnd.ms-excel").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            compose.waitUntil(10_000) {compose.onAllNodesWithText("Review your import").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Import confirmed arrangements only").assertDoesNotExist()
            compose.onNodeWithText("11 courses · 25 arrangements").assertExists()
            assertFalse(downloaded.exists())
            compose.onNodeWithContentDescription("Close import").performClick()
        }finally {
            compose.runOnUiThread {compose.activity.intent=initial}
            downloaded.delete();downloaded.parentFile?.delete()
        }
    }
    private fun seedDisplayPlan(courses:(Int,Int)->List<Course>):Int {
        val today=LocalDate.now()
        val semester=Defaults.semester().copy(startDate=today.minusDays(7).toString(),endDate=today.plusDays(118).toString(),weeks=18)
        val week=ScheduleEngine.weekNumber(semester,today)!!
        val plan=Plan(semesterId=semester.id,name="Private plan name",courses=CourseColors.assign(courses(week,today.dayOfWeek.value)))
        runBlocking {repository.update {AppState(listOf(semester),listOf(plan),semester.id,plan.id,Settings(language=Language.EN,holidaysEnabled=false))}}
        compose.waitForIdle()
        return week
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
