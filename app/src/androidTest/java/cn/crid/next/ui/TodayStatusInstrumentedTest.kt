package cn.crid.next.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.crid.next.MainActivity
import cn.crid.next.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TodayStatusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val date = LocalDate.of(2026, 9, 28)
    private val english = UiText(Language.EN)

    @Test fun currentNextAndWaitUpdateAtTheRealLessonBoundaries() {
        val classes = listOf(item("Mathematics", "08:00", "09:40", "A101"), item("Physics", "10:00", "11:40", "B202"))
        var time by mutableStateOf(LocalTime.of(7, 59, 59))
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                TodayStatusCard(todayCourseStatus(classes, date, date, time), 1, english) { _, _ -> }
            }
        }
        compose.onNodeWithTag("today_status_title").assertTextEquals("Classes haven't started yet")
        compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).assertTextEquals("Mathematics")
        compose.onNodeWithTag("today_next_wait").assertTextEquals("In 1 min")
        compose.runOnIdle { time = LocalTime.of(8, 0) }
        compose.onNodeWithTag("today_status_title").assertTextEquals("In class")
        compose.onNodeWithTag("today_current_0_name", useUnmergedTree = true).assertTextEquals("Mathematics")
        compose.onNodeWithTag("today_current_0_detail", useUnmergedTree = true).assertTextEquals("08:00–09:40 · A101")
        compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).assertTextEquals("Physics")
        compose.runOnIdle { time = LocalTime.of(9, 40) }
        compose.onNodeWithTag("today_status_title").assertTextEquals("Between classes")
        compose.onNodeWithTag("today_current_0").assertDoesNotExist()
        compose.onNodeWithTag("today_next_wait").assertTextEquals("In 20 min")
        compose.runOnIdle { time = LocalTime.of(10, 0) }
        compose.onNodeWithTag("today_current_0_name", useUnmergedTree = true).assertTextEquals("Physics")
        compose.onNodeWithTag("today_next_0").assertDoesNotExist()
        compose.runOnIdle { time = LocalTime.of(11, 40) }
        compose.onNodeWithTag("today_status_title").assertTextEquals("Today's classes are over, time to relax")
        compose.onNodeWithTag("today_current_0").assertDoesNotExist()
        compose.onNodeWithTag("today_phrase").assertDoesNotExist()
        compose.onNodeWithTag("today_phrase_source").assertDoesNotExist()
    }

    @Test fun simultaneousCoursesHaveBoundedPreviewAndOpenTheOriginalOccurrence() {
        val current = (1..7).map { item("Current $it", "08:00", "09:40", "A$it") }
        val upcoming = (1..5).map { item("Next $it", "10:00", "11:40", "B$it") }
        var selected: Occurrence? = null
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TodayStatusCard(todayCourseStatus(current + upcoming, date, date, LocalTime.of(8, 30)), 1, english) { item, _ -> selected = item }
                }
            }
        }
        compose.onNodeWithTag("today_current_0").assertExists()
        compose.onNodeWithTag("today_current_1").assertExists()
        compose.onNodeWithTag("today_current_2").assertDoesNotExist()
        compose.onNodeWithTag("today_current_more").assertTextEquals("5 more classes in progress")
        compose.onNodeWithTag("today_next_more").assertTextEquals("4 more classes at the same time")
        compose.onNodeWithTag("today_next_1").assertDoesNotExist()
        compose.onNodeWithTag("today_current_1").performClick()
        compose.runOnIdle { assertEquals(current[1], selected) }
        compose.onNodeWithTag("today_next_0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(upcoming[0], selected) }
    }

    @Test fun localizedStatusAndCountdownRemainReadableAtTwoHundredPercentText() = withSystemFontScale(2f) {
        var language by mutableStateOf(Language.ZH_CN)
        val lesson = item("现代教育技术与跨学科课堂教学研究", "10:00", "11:40", "木铎楼A101")
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    TodayStatusCard(todayCourseStatus(listOf(lesson), date, date, LocalTime.of(8, 30)), 1, UiText(language)) { _, _ -> }
                }
            }
        }
        listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { next ->
            compose.runOnIdle { language = next }
            listOf("today_status_title", "today_next_label", "today_next_wait").forEach { tag ->
                val node = compose.onNodeWithTag(tag).performScrollTo()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("$next $tag must wrap without losing text", layouts.single().hasVisualOverflow)
                assertReadableText(layouts.single(), 2f, "$next-$tag")
            }
            compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).assertTextEquals(lesson.course.name)
            compose.onNodeWithTag("today_next_0_detail", useUnmergedTree = true).assertTextEquals("10:00–11:40 · 木铎楼 A101")
        }
    }

    @Test fun localizedRestTitlesKeepNextClassAtTwoHundredPercentText() {
        var language by mutableStateOf(Language.ZH_CN)
        var finished by mutableStateOf(false)
        var width by mutableStateOf(280.dp)
        val lesson = item("Finished class", "08:00", "09:00", "A101")
        val tomorrow = item("Tomorrow's class", "10:00", "11:40", "B202").copy(date = date.plusDays(1))
        for (scale in listOf(1f, 2f)) withSystemFontScale(scale) {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(finished)) {
                    Column(Modifier.width(width).verticalScroll(rememberScrollState())) {
                        val status = todayCourseStatus(if (finished) listOf(lesson) else emptyList(), date, date,
                            LocalTime.of(10, 0), listOf(tomorrow))
                        TodayStatusCard(status, 1, UiText(language)) { _, _ -> }
                    }
                }
            }
            for (availableWidth in listOf(280.dp, 360.dp)) {
                compose.runOnIdle { width = availableWidth }
                listOf(false, true).forEach { isFinished ->
                    listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { nextLanguage ->
                        compose.runOnIdle { finished = isFinished; language = nextLanguage }
                        val text = UiText(nextLanguage)
                        val title = if (isFinished) text.t("今日课程已结束，放松一下吧", "Today's classes are over, time to relax", "今日課程已結束，放鬆一下吧")
                            else text.t("今天，留一点时间给自己", "Today, make a little time for yourself", "今天，留一點時間給自己")
                        compose.onNodeWithTag("today_status_title").performScrollTo().assertTextEquals(title).assertIsDisplayed()
                        listOf("today_status_title", "today_next_label").forEach { tag ->
                            val layouts = mutableListOf<TextLayoutResult>()
                            compose.onNodeWithTag(tag).performScrollTo()
                                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertReadableText(layouts.single(), scale, "$availableWidth-$nextLanguage-$isFinished-$tag")
                        }
                        if (nextLanguage == Language.ZH_CN) captureShortCopy("today-${if (isFinished) "finished" else "empty"}-${availableWidth.value.toInt()}-font$scale")
                        compose.onNodeWithTag("today_next_label").assertTextEquals(text.t("下一节 · 明天", "Next class · Tomorrow", "下一節 · 明天"))
                        compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).performScrollTo()
                            .assertTextEquals(tomorrow.course.name).assertIsDisplayed()
                        compose.onNodeWithTag("today_next_wait").assertDoesNotExist()
                    }
                }
            }
            // Reuse the narrow-width fixture for the shared empty-panel title and longer explanation.
            val panelTitle = "还没有课表方案"
            val panelBody = "导入一份课表，或在方案中创建空白课表"
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        EmptyPanel(panelTitle, panelBody, "", {})
                    }
                }
            }
            for (value in listOf(panelTitle, panelBody)) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(value).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertReadableText(layouts.single(), scale, "empty-panel-$value")
            }
            captureShortCopy("empty-panel-280-font$scale")
        }
    }

    private fun assertReadableText(layout: TextLayoutResult, fontScale: Float, label: String) {
        assertEquals("Actual system font size for $label", fontScale, layout.layoutInput.density.fontScale, .001f)
        assertFalse("$label must wrap without losing text", layout.hasVisualOverflow)
        val text = layout.layoutInput.text.text
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) assertFalse("$label must not ellipsize", layout.isLineEllipsized(line))
    }

    private fun withSystemFontScale(scale: Float, block: () -> Unit) {
        val previous = shell("settings get system font_scale").trim()
        val oldScale = compose.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale $scale")
            compose.waitUntil(10_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - scale) < .001f }
            block()
        } finally {
            if (previous == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $previous")
            compose.waitUntil(10_000) { kotlin.math.abs(compose.activity.resources.configuration.fontScale - oldScale) < .001f }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun captureShortCopy(name: String) {
        compose.waitForIdle()
        val folder = File(compose.activity.getExternalFilesDir(null), "qa-v1.14.1/today").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun datePreviewsKeepDateSpecificCopy() {
        var language by mutableStateOf(Language.ZH_CN)
        var previewDate by mutableStateOf(date.minusDays(1))
        var hasClass by mutableStateOf(false)
        val lesson = item("Preview class", "08:00", "09:00", "A101")
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val items = if (hasClass) listOf(lesson.copy(date = previewDate)) else emptyList()
                    TodayStatusCard(todayCourseStatus(items, previewDate, date, LocalTime.of(8, 30)), 1, UiText(language)) { _, _ -> }
                }
            }
        }
        listOf(Language.ZH_CN, Language.ZH_TW, Language.EN).forEach { nextLanguage ->
            listOf(date.minusDays(1), date.plusDays(1)).forEach { shownDate ->
                listOf(false, true).forEach { showClass ->
                    compose.runOnIdle { language = nextLanguage; previewDate = shownDate; hasClass = showClass }
                    val text = UiText(nextLanguage)
                    val title = when {
                        !showClass -> text.t("这一天没有课程", "No classes on this day", "這一天沒有課程")
                        shownDate < date -> text.t("当天课程已结束", "Classes on this day have ended", "當天課程已結束")
                        else -> text.t("当天课程安排", "Classes on this day", "當天課程安排")
                    }
                    compose.onNodeWithTag("today_status_title").assertTextEquals(title)
                    compose.onNodeWithTag("today_current_0").assertDoesNotExist()
                    compose.onNodeWithTag("today_next_wait").assertDoesNotExist()
                    if (showClass && shownDate > date) {
                        compose.onNodeWithTag("today_next_label").assertTextEquals(text.t("首节课", "First class", "首節課"))
                        compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).assertTextEquals(lesson.course.name)
                    } else compose.onNodeWithTag("today_next_0").assertDoesNotExist()
                }
            }
        }
    }

    @Test fun todayScreenShowsCurrentNextAndOnlyPopulatedPeriodsInLightAndDark() {
        val courses = listOf(
            course("数学分析 III", "08:00", "09:40", "张老师", "木铎楼 A101"),
            course("Python 程序设计", "10:00", "11:40", "李老师", "励教楼 B202"),
            course("教育心理学", "14:00", "15:30", "陈老师", "乐育楼 C301"),
        )
        val term = Semester(name = "Term", startDate = date.minusDays(21).toString(), endDate = date.plusDays(104).toString(), weeks = 18, periods = emptyList())
        val plan = Plan(semesterId = term.id, name = "Plan", courses = CourseColors.assign(courses))
        val state = AppState(listOf(term), listOf(plan), term.id, plan.id, Settings(language = Language.ZH_CN))
        var dark by mutableStateOf(false)
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(dark)) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().testTag("today_status_capture")) {
                    Text("今天", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(start = 20.dp, top = 26.dp, bottom = 14.dp))
                    TodayScreen(state, HolidayCalendar(), date, date, LocalTime.of(8, 30), UiText(Language.ZH_CN), {}, {}, null) { _, _ -> }
                }
            }
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.10.1/today").apply { mkdirs() }
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark = isDark }
            compose.onNodeWithTag("today_status_title").assertTextEquals("正在上课")
            compose.onNodeWithTag("today_current_0_name", useUnmergedTree = true).assertTextEquals("数学分析 III")
            compose.onNodeWithTag("today_next_0_name", useUnmergedTree = true).assertTextEquals("Python 程序设计")
            compose.onNodeWithTag("today_next_wait").assertTextEquals("还有 1 小时 30 分钟")
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("today_status_capture").captureToImage().asAndroidBitmap()
            File(directory, "today-${if (isDark) "dark" else "light"}.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        listOf("morning", "afternoon").forEach {
            compose.onNodeWithTag("today_period_$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("today_period_evening").assertDoesNotExist()
        compose.onNodeWithTag("period_empty_evening").assertDoesNotExist()
        val shownDate = UiText(Language.ZH_CN).date(date)
        compose.onAllNodesWithText(shownDate, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun scheduleClockPausesInBackgroundAndRefreshesImmediatelyOnResume() {
        val owner = TestOwner()
        val clock = MutableClock(date.atTime(8, 0).toInstant(java.time.ZoneOffset.UTC))
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.RESUMED
            compose.activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val now by rememberScheduleClock(clock)
                    Text(now.toLocalTime().toString(), Modifier.testTag("schedule_clock"))
                }
            }
        }
        compose.onNodeWithTag("schedule_clock").assertTextEquals("08:00")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { clock.current = clock.current.plusSeconds(3_600) }
        compose.onNodeWithTag("schedule_clock").assertTextEquals("08:00")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("schedule_clock").assertTextEquals("09:00")
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }

    private fun course(name: String, start: String, end: String, teacher: String, location: String) =
        Course(name = name, lessons = listOf(Lesson(date = date.toString(), startTime = start, endTime = end, teacher = teacher, location = location)))

    private fun item(name: String, start: String, end: String, location: String): Occurrence {
        val course = course(name, start, end, "Teacher", location)
        return Occurrence(course, course.lessons.single(), date, LocalTime.parse(start), LocalTime.parse(end), 1)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class MutableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
