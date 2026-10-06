package cn.crid.next.export

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.LocaleList
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.toArgb
import cn.crid.next.core.*
import cn.crid.next.core.importer.TimetableParser
import cn.crid.next.data.Defaults
import cn.crid.next.ui.cridColorScheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** Executes the production Canvas, PdfDocument, Bitmap encoder and SAF code on Android. */
@RunWith(AndroidJUnit4::class)
class ExportInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tree = DocumentsContract.buildTreeDocumentUri(ExportDocumentsProvider.AUTHORITY, "root")
    private val output get() = File(context.getExternalFilesDir(null), "export-validation").apply { mkdirs() }
    private val semester = Semester(id = "export-test", name = "2026 Autumn · 秋季学期", startDate = "2026-09-07", endDate = "2027-01-10", weeks = 18,
        periods = listOf(Period(1, "08:00", "08:45"), Period(2, "08:55", "09:40")))
    private val longName = "跨学科研究方法与人工智能辅助科学发现：从实验设计、数据分析到可重复研究的完整实践课程 Advanced interdisciplinary methods"
    private val calendar = HolidayCalendar(days = listOf(
        HolidayDay("2026-10-01", "National Day / 国庆节", statutory = true),
        HolidayDay("2026-10-02", "National Day break", extraRest = true),
        HolidayDay("2026-09-30", "Teaching adjustment", workday = true, teachingDate = "2026-09-28"),
    ))

    @Before fun acquireRealTreeGrant() {
        val activity = Intent().setComponent(ComponentName(instrumentation.context.packageName, ExportGrantActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(activity)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val deadline = System.nanoTime() + 10_000_000_000L
        while (context.checkUriPermission(tree, Process.myPid(), Process.myUid(), flags) != PackageManager.PERMISSION_GRANTED && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
        assertEquals("The test provider must grant a real SAF tree to the app UID", PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(tree, Process.myPid(), Process.myUid(), flags))
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("root", cursor.getString(0))
        }
    }

    @After fun revokeTestTreeGrant() {
        context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, ExportGrantActivity::class.java.name))
            .putExtra("revoke", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val deadline = System.nanoTime() + 5_000_000_000L
        while (context.checkUriPermission(tree, Process.myPid(), Process.myUid(), flags) == PackageManager.PERMISSION_GRANTED && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals("The provider grant must end with this test", PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(tree, Process.myPid(), Process.myUid(), flags))
    }

    @Test fun allFourViewsMatchPngAndPdfAndRetainLongNamesAndStatuses() = runBlocking<Unit> {
        val plan = fixturePlan()
        val metrics = File(output, "visual-comparison.tsv")
        metrics.writeText("view\tpage\twidth\theight\tmean_channel_error\tlarge_error_fraction\tink_fraction\tdark_ink_error\tdark_ink_mismatch\n")
        for (view in ExportView.entries) {
            val exportSemester = semester.copy(endDate = "2026-10-04", weeks = 4)
            val request = ExportRequest(exportSemester, plan, Settings(language = Language.EN, makeupMode = MakeupMode.ON), calendar,
                view, LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-04"), ExportFormat.PNG, pngScale = 1)
            val pages = exportAndCompare(view.name.lowercase(), request, metrics)
            val text = comparableText(pages.joinToString(" "))
            val rawText = Normalizer.normalize(pages.joinToString(" "), Normalizer.Form.NFKC)
            assertTrue("Full long course name must survive wrapping in $view", text.contains(comparableText(longName)))
            assertTrue("Words that fit a column must stay intact in $view", rawText.contains("methods"))
            if (view == ExportView.DAY) {
                assertFalse("Today cards omit inactive lessons", text.contains("Notthisweek"))
                assertFalse("Today cards omit holiday cancellations", text.contains("Holiday·noclass"))
            } else {
                assertTrue("Words that fit a column must stay intact in $view", rawText.contains("laboratory"))
                assertTrue("Out-of-week text must be in $view", text.contains("Notthisweek"))
                assertTrue("Holiday text must be in $view", text.contains("Holiday·noclass"))
                assertTrue("Make-up text must be in $view", text.contains("Make-upclass"))
                assertTrue("Conflicting classes stay visible in $view", text.contains("Overlappingtime"))
            }
            val units = ExportUnits.pages(view, request.semester, request.from, request.to, request.settings.weekStartsSunday)
            assertEquals("Each complete time unit is one page", units.size, pages.size)
            val occurrences = units.flatMap { it.dates }
                .flatMap { date -> displayCourses(ScheduleEngine.occurrences(request.semester, plan, date, request.settings, calendar)
                    .filter { view != ExportView.DAY || it.isActual }) }
            plan.courses.forEach { course ->
                val expectedCount = occurrences.count { courseKey(it.representative.course.name) == courseKey(course.name) }
                val actualCount = Regex(Regex.escape(comparableText(course.name))).findAll(text).count()
                assertEquals("$view must show each course occurrence once without a repeated detail heading: ${course.name}", expectedCount, actualCount)
            }
            assertNoDetailList(text)
        }
    }

    @Test fun allFourViewsRenderMatchingCompletePngAndPdfPagesOnAndroid12() = runBlocking<Unit> {
        val exporter = TimetableExporter(context)
        val sourceSemester = semester.copy(endDate = "2026-10-04", weeks = 4)
        for (view in ExportView.entries) {
            val request = ExportRequest(sourceSemester, fixturePlan(), Settings(language = Language.ZH_CN,
                makeupMode = MakeupMode.ON), calendar, view, LocalDate.parse("2026-09-28"),
                LocalDate.parse("2026-10-04"), ExportFormat.PNG, pngScale = 1)
            val expectedPages = ExportUnits.pages(view, sourceSemester, request.from, request.to, false).size
            assertEquals("Each day/week/month/semester stays a complete page", expectedPages, exporter.estimate(request))
            exportAndCompare("android12-${view.name.lowercase()}", request,
                File(output, "android12-raster-comparison.tsv"), exporter, collectText = false)
        }
    }

    @Test fun sameCourseAcrossWeeksExportsCurrentArrangementOnceWithoutFalseOverlap() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val past = Lesson(weeks = listOf(1, 2), weekday = 1, startTime = "09:00", endTime = "10:30",
            teacher = "Earlier Teacher", location = "Earlier Room", note = "Earlier arrangement")
        val current = past.copy(weeks = listOf(4, 5), endTime = "09:45", teacher = "Current Teacher", location = "Current Room", note = "Current arrangement")
        val sharedCourse = Course(name = "Merged weekly course", lessons = listOf(past, current))
        val otherCourse = Course(name = "Different week seminar", lessons = listOf(past.copy(weeks = listOf(3), teacher = "Seminar Teacher")))
        val plan = Plan(semesterId = semester.id, name = "Weekly identity acceptance", courses = CourseColors.assign(listOf(sharedCourse, otherCourse)))
        for (view in listOf(ExportView.DAY, ExportView.WEEK)) {
            val request = ExportRequest(semester, plan, Settings(language = Language.EN), HolidayCalendar(), view, day, day, ExportFormat.PNG, 1)
            val page = TimetableExporter(context).inspect(request).single()
            val mergedCard = page.cardTexts.map(::comparableText)
                .first { it.contains(comparableText(sharedCourse.name)) }
            assertTrue("The current room must precede the teacher in exported course cards",
                mergedCard.indexOf(comparableText("Current Room")) in 0 until mergedCard.indexOf(comparableText("Current Teacher")))
            val text = comparableText(exportAndCompare("weekly-identity-${view.name.lowercase()}", request,
                File(output, "weekly-identity-comparison.tsv")).joinToString(" "))
            for (course in plan.courses) assertEquals("Only courses shown by this view appear in the main chart in $view",
                if (view == ExportView.DAY && course.id == otherCourse.id) 0 else 1,
                Regex(Regex.escape(comparableText(course.name))).findAll(text).count())
            assertTrue("The visible arrangement uses the current teacher", text.contains(comparableText("Current Teacher")))
            assertFalse("Alternate-week teaching details are not appended below the chart", text.contains(comparableText("Earlier Teacher")))
            assertFalse(text.contains(comparableText("Earlier arrangement")))
            assertFalse(text.contains(comparableText("Current arrangement")))
            assertTrue("Grouping retains the visible teaching record", day to current in page.courseRecords)
            if (view != ExportView.DAY) assertTrue("The timetable grouping retains alternate-week records", day to past in page.courseRecords)
            assertEquals("Only the weekly grid shows the separate, inactive course", if (view == ExportView.DAY) 0 else 1,
                Regex(Regex.escape(comparableText("Not this week"))).findAll(text).count())
            assertFalse("Courses taught in different weeks never acquire an overlap label",
                text.contains(comparableText("Overlapping time")))
            assertNoDetailList(text)
        }
        assertEquals("Export must preserve every original teaching record", listOf(past, current), sharedCourse.lessons)
    }

    @Test fun overflowingDayExpandsOnePageWithoutMissingCourses() = runBlocking<Unit> {
        val courses = (0..15).map { index ->
            Course(name = "Overflow course ${index.toString().padStart(2, '0')}", lessons = listOf(
                Lesson(date = "2026-09-28", startTime = "09:00", endTime = if (index % 2 == 0) "09:45" else "10:30", location = "Room ${index + 1}", teacher = "Teacher $index")
            ))
        }
        val plan = Plan(semesterId = semester.id, name = "Pagination acceptance", courses = CourseColors.assign(courses))
        val request = ExportRequest(semester, plan, Settings(language = Language.EN), HolidayCalendar(), ExportView.DAY,
            LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28"), ExportFormat.PNG, 1)
        assertEquals("A crowded day must remain one complete page", 1, TimetableExporter(context).estimate(request))
        val allText = comparableText(exportAndCompare("pagination", request, File(output, "pagination-comparison.tsv")).joinToString(" "))
        (0..15).forEach { index -> assertTrue("Every course must remain on the single day page", allText.contains("Overflowcourse${index.toString().padStart(2, '0')}")) }
    }

    @Test fun dayUsesOneWideColumnWithDaypartsAndOnlyActualCourses() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val actual = listOf(
            Triple("Morning lecture", "09:00", "10:00"),
            Triple("Concurrent workshop", "09:15", "10:15"),
            Triple("Afternoon practice", "14:00", "15:00"),
            Triple("Evening seminar", "19:00", "20:00"),
        ).mapIndexed { index, (name, start, end) -> Course(name = name, lessons = listOf(
            Lesson(date = day.toString(), startTime = start, endTime = end, location = "Room $index", teacher = "Teacher $index"))) }
        val inactive = Course(name = "Inactive lecture", lessons = listOf(
            Lesson(weeks = listOf(1), weekday = 1, startTime = "11:00", endTime = "12:00")))
        val plan = Plan(semesterId = semester.id, name = "Daily layout acceptance", courses = CourseColors.assign(actual + inactive))
        val request = ExportRequest(semester, plan, Settings(language = Language.EN), HolidayCalendar(), ExportView.DAY,
            day, day, ExportFormat.PNG, 1)
        val page = TimetableExporter(context).inspect(request).single()
        assertEquals("Today shows one card per actual display course", actual.size, page.cardBounds.size)
        assertEquals("All cards share the same left and right edges", 1,
            page.cardBounds.map { it.left to it.right }.distinct().size)
        assertTrue("Daily cards use most of the page width", page.cardBounds.all { it.right - it.left > page.width * .7f })
        page.cardBounds.zipWithNext().forEach { (before, after) ->
            assertTrue("Simultaneous daily cards stack vertically", before.bottom <= after.top)
        }
        page.cardTexts.forEach { raw ->
            val card = comparableText(raw)
            assertTrue("Daily cards label their location", card.contains("Location:"))
            assertTrue("Daily cards label their teacher", card.contains("Teacher:"))
            assertTrue("Location precedes teacher", card.indexOf("Location:") < card.indexOf("Teacher:"))
            assertFalse("Times stay outside the daily course card", Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(card))
        }
        val text = comparableText(exportAndCompare("day-layout", request, File(output, "day-layout-comparison.tsv")).single())
        listOf("Morning", "Afternoon", "Evening", "09:00", "10:00", "19:00", "20:00").forEach {
            assertTrue("The daily layout retains $it", text.contains(comparableText(it)))
        }
        actual.forEach { course -> assertEquals("A course title appears only in its daily card", 1,
            Regex(Regex.escape(comparableText(course.name))).findAll(text).count()) }
        assertFalse(text.contains(comparableText(inactive.name)))
        assertNoDetailList(text)
    }

    @Test fun timetableViewsMergeOverlapsWithoutWideningWeekdayColumns() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-21")
        val term = semester.copy(startDate = day.toString(), endDate = day.plusDays(6).toString(), weeks = 1)
        fun course(name: String, date: LocalDate, room: String, teacher: String) = Course(name = name, lessons = listOf(
            Lesson(date = date.toString(), startTime = "09:00", endTime = "10:00", location = room, teacher = teacher)))
        val first = course("First overlapping course", day, "Room A", "Teacher A")
        val second = course("Second overlapping course", day, "Room B", "Teacher B")
        val nextDay = course("Next day course", day.plusDays(1), "Room C", "Teacher C")
        val plan = Plan(semesterId = term.id, name = "Grouped timetable acceptance", courses = listOf(first, second, nextDay))
        val exporter = TimetableExporter(context)
        for (view in listOf(ExportView.WEEK, ExportView.MONTH, ExportView.SEMESTER)) {
            val request = ExportRequest(term, plan, Settings(language = Language.EN), HolidayCalendar(), view,
                day, day.plusDays(6), ExportFormat.PNG, 1)
            val page = exporter.inspect(request).single()
            val ungroupedPage = exporter.inspect(request.copy(plan = plan.copy(courses = listOf(first, nextDay)))).single()
            assertEquals("$view keeps its fixed weekday widths when courses overlap", ungroupedPage.width, page.width)
            assertEquals("Two overlapping courses occupy one timetable cell", 2, page.cardBounds.size)
            val firstBounds = page.cardBounds[0]
            val nextBounds = page.cardBounds[1]
            assertEquals("All weekday cells have the same width", firstBounds.right - firstBounds.left,
                nextBounds.right - nextBounds.left, .01f)
            assertTrue("The next day's cell remains in the next column", nextBounds.left > firstBounds.right)
            val merged = page.cardTexts.map(::comparableText).single { it.contains(comparableText(first.name)) }
            assertTrue("A merged cell retains both complete course names",
                merged.contains(comparableText("${first.name} / ${second.name}")))
            assertTrue("Both locations precede the teacher list", merged.indexOf("RoomA") in 0 until merged.indexOf("TeacherA") &&
                merged.indexOf("RoomB") in 0 until merged.indexOf("TeacherA"))
            assertTrue("A merged cell retains both teachers", merged.contains("TeacherA") && merged.contains("TeacherB"))
            val text = comparableText(exportAndCompare("grouped-${view.name.lowercase()}", request,
                File(output, "grouped-comparison.tsv"), exporter).single())
            plan.courses.forEach { item -> assertEquals("Every name appears once without a repeated list below", 1,
                Regex(Regex.escape(comparableText(item.name))).findAll(text).count()) }
            assertNoDetailList(text)
        }
    }

    @Test fun timetableCustomTimesStayCompleteAndFollowMergedCourseOrder() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-21")
        val term = semester.copy(startDate = day.toString(), endDate = day.plusDays(6).toString(), weeks = 1,
            periods = listOf(Period(1, "14:00", "15:30")))
        fun course(name: String, start: String, end: String) = Course(name = name, lessons = listOf(
            Lesson(date = day.toString(), startTime = start, endTime = end, location = "Sample room", teacher = "Sample teacher")))
        val standard = course("Standard lesson", "14:00", "15:30")
        val custom = course("Custom lesson", "14:10", "15:20")
        val later = course("Later lesson", "14:15", "15:30")
        val sameTime = course("Second custom lesson", "14:10", "15:20")
        val repeatedFirst = course("Repeated lesson", "14:05", "14:50")
        val repeatedSecond = course("Repeated lesson", "14:10", "15:20")
        val cases = listOf(
            Triple("standard", listOf(standard), ""),
            Triple("custom", listOf(custom), "14:10–15:20"),
            Triple("mixed", listOf(standard, custom, sameTime, later), "14:00–15:30 / 14:10–15:20 / 14:10–15:20 / 14:15–15:30"),
            Triple("repeated-name", listOf(repeatedFirst, repeatedSecond, later), "14:05–14:50 / 14:10–15:20 / 14:15–15:30"),
        )
        val exporter = TimetableExporter(context)
        for (view in listOf(ExportView.WEEK, ExportView.MONTH, ExportView.SEMESTER)) {
            for ((name, courses, times) in cases) {
                val plan = Plan(semesterId = term.id, name = "Custom time acceptance", courses = courses)
                val request = ExportRequest(term, plan, Settings(language = Language.EN), HolidayCalendar(), view,
                    day, day, ExportFormat.PNG, 1)
                val card = comparableText(exporter.inspect(request).single().cardTexts.single())
                val title = comparableText(courses.joinToString(" / ") { it.name })
                assertTrue("The grouped title preserves chronological course order", card.startsWith(title))
                if (times.isEmpty()) {
                    assertFalse("Standard period cards keep clock times on the shared axis",
                        Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(card))
                } else {
                    assertTrue("Every complete time range follows its course title, including repeated ranges",
                        card.startsWith(title + comparableText(times)))
                    assertTrue("Times precede the location and teacher", card.indexOf(comparableText(times)) < card.indexOf("Sampleroom"))
                }
                val text = comparableText(exportAndCompare("custom-time-${view.name.lowercase()}-$name", request,
                    File(output, "custom-time-comparison.tsv"), exporter).single())
                assertTrue("PNG and PDF retain the complete grouped title", text.contains(title))
                if (times.isNotEmpty()) assertTrue("PDF extraction retains every full range in title order", text.contains(comparableText(times)))
                assertNoDetailList(text)
            }
        }
    }

    @Test fun consecutivePeriodsExportOneCardWithoutRepeatingSourceDetails() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val sourceSemester = semester.copy(periods = semester.periods + listOf(
            Period(3, "09:50", "10:35"), Period(4, "10:45", "11:30"), Period(5, "13:30", "14:15"),
            Period(6, "14:25", "15:10"), Period(7, "15:30", "16:15")))
        val first = Lesson(weeks = listOf(4, 5), weekday = 1, startPeriod = 5, endPeriod = 6,
            teacher = "Python Teacher", location = "Lab 301", note = "Python practice")
        val second = first.copy(startPeriod = 7, endPeriod = 7)
        val course = Course(name = "Consecutive Python course", lessons = listOf(first, second))
        val plan = Plan(semesterId = semester.id, name = "Consecutive period acceptance", courses = listOf(course))
        for (view in listOf(ExportView.DAY, ExportView.WEEK)) {
            val request = ExportRequest(sourceSemester, plan, Settings(language = Language.EN), HolidayCalendar(), view,
                day, day, ExportFormat.PNG, 1)
            val page = TimetableExporter(context).inspect(request).single()
            val text = comparableText(exportAndCompare("consecutive-periods-${view.name.lowercase()}", request,
                File(output, "consecutive-periods-comparison.tsv")).joinToString(" "))
            fun count(value: String) = Regex(Regex.escape(comparableText(value))).findAll(text).count()
            assertEquals("The continuous lesson has one main-chart heading in $view", 1, count(course.name))
            assertEquals("The combined display time is not repeated inside course blocks", 0, count("13:30–16:15"))
            assertEquals("The representative teacher appears once", 1, count("Python Teacher"))
            assertEquals("Source week lists are not appended below the chart", 0, count("Weeks 4–5"))
            assertEquals("The representative room appears once", 1, count("Lab 301"))
            assertEquals("Source notes are not appended below the chart", 0, count("Python practice"))
            assertEquals(listOf(day to first, day to second), page.courseRecords)
            assertFalse("One continuous course cannot conflict with itself", text.contains(comparableText("Overlapping time")))
            assertNoDetailList(text)
        }
        assertEquals("The display projection must leave stored teaching periods untouched", listOf(first, second), course.lessons)
    }

    @Test fun pngResolutionChangesPixelsWithoutChangingPageCountOrContent() = runBlocking<Unit> {
        val plan = fixturePlan().copy(courses = fixturePlan().courses.take(1))
        val base = ExportRequest(semester, plan, Settings(language = Language.EN), calendar, ExportView.DAY,
            LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28"), ExportFormat.PNG, 1)
        val exporter = TimetableExporter(context)
        val count = exporter.estimate(base)
        var width = 0
        var height = 0
        for (scale in 1..3) {
            val request = base.copy(pngScale = scale)
            assertEquals(count, exporter.estimate(request))
            val uris = exporter.export(request, tree)
            assertEquals(count, uris.size)
            uris.forEachIndexed { index, uri ->
                val file = copy(uri, "resolution-${scale}x-${index + 1}.png")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (scale == 1) { width = options.outWidth; height = options.outHeight }
                assertEquals(width * scale, options.outWidth)
                assertEquals(height * scale, options.outHeight)
                assertEquals("image/png", options.outMimeType)
                assertTrue(file.length() > 1_000)
                val source = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
                val baseline = requireNotNull(BitmapFactory.decodeFile(File(output, "resolution-1x-${index + 1}.png").absolutePath))
                val resized = Bitmap.createScaledBitmap(source, baseline.width, baseline.height, true)
                try {
                    val stats = compare(baseline, resized)
                    assertTrue("Every resolution must contain the same actual drawing", stats[2] > .001 && stats[0] < 8 && stats[3] < 45)
                } finally {
                    if (resized !== source) resized.recycle()
                    source.recycle()
                    baseline.recycle()
                }
            }
        }
    }

    @Test fun labelsAndOfficialHolidaysFollowAppLanguageEvenOnChineseSystem() = runBlocking<Unit> {
        val officialNames = listOf("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节")
        val first = LocalDate.parse("2026-09-28")
        val localizedCalendar = HolidayCalendar(days = officialNames.mapIndexed { index, name -> HolidayDay(first.plusDays(index.toLong()).toString(), name, statutory = true) } +
            HolidayDay("2026-10-05", "国庆节", workday = true, teachingDate = "2026-09-28"))
        val sourceSemester = semester.copy(name = "原始学期 Source Semester")
        val sourceName = "保留原名 Source Course"
        val plan = Plan(semesterId = semester.id, name = "原始方案 Source Plan", courses = CourseColors.assign(listOf(
            Course(name = sourceName, credits = "3", lessons = (1..7).map { weekday -> Lesson(weeks = (1..18).toList(), weekday = weekday, startTime = "09:00", endTime = "09:45") }),
            Course(name = "Past Course", lessons = listOf(Lesson(weeks = listOf(1), weekday = 1, startTime = "11:00", endTime = "11:45"))),
        )))
        val configuration = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
        val chineseContext = context.createConfigurationContext(configuration)
        assertEquals("zh", chineseContext.resources.configuration.locales[0].language)
        for (language in listOf(Language.ZH_CN, Language.ZH_TW, Language.EN)) {
            val request = ExportRequest(sourceSemester, plan, Settings(language = language, makeupMode = MakeupMode.ON), localizedCalendar,
                ExportView.WEEK, first, first.plusDays(7), ExportFormat.PNG, 1)
            val pages = exportAndCompare("language-${language.name.lowercase()}", request, File(output, "language-comparison.tsv"), TimetableExporter(chineseContext))
            val actual = comparableText(pages.joinToString(" "))
            val labels = when (language) {
                Language.ZH_CN -> listOf("周课表", "周一", "第 4 周", "非本周", "休假停课", "调休补课") + officialNames
                Language.ZH_TW -> listOf("週課表", "週一", "第 4 週", "非本週", "休假停課", "調休補課", "元旦", "春節", "清明節", "勞動節", "端午節", "中秋節", "國慶節")
                else -> listOf("Weekly timetable", "Mon", "Week 4", "Not this week", "Holiday · no class", "Make-up class", "New Year's Day", "Spring Festival", "Qingming Festival", "Labour Day", "Dragon Boat Festival", "Mid-Autumn Festival", "National Day")
            }
            labels.forEach { assertTrue("$language must generate label $it even with a Chinese system locale", actual.contains(comparableText(it))) }
            assertTrue(actual.contains(comparableText(sourceName)))
            assertTrue(actual.contains(comparableText(sourceSemester.name)))
            assertTrue(actual.contains(comparableText(plan.name)))
            assertNoDetailList(actual)
            if (language == Language.EN) assertFalse("Official holiday names must not leak the calendar's original language", actual.contains("国庆节"))
        }
    }

    @Test fun onlyOutOfWeekCoursesHaveDashedBorders() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val course = Course(name = "Border sample", color = 0xFF4263C7.toInt(), lessons = listOf(Lesson(weeks = listOf(4), weekday = 1, startTime = "09:00", endTime = "09:45", teacher = "Sample teacher", location = "Sample room")))
        val plan = Plan(semesterId = semester.id, name = "Border acceptance", courses = listOf(course))
        val base = ExportRequest(semester, plan, Settings(language = Language.EN, theme = ThemeMode.LIGHT), HolidayCalendar(), ExportView.WEEK, day, day, ExportFormat.PNG, 1)
        var top = 0
        var bottom = 0
        for ((name, out, holiday) in listOf(Triple("normal", false, false), Triple("holiday", false, true), Triple("out-of-week", true, false), Triple("out-of-week-holiday", true, true))) {
            val request = base.copy(plan = if (out) plan.copy(courses = listOf(course.copy(lessons = course.lessons.map { it.copy(weeks = listOf(1)) }))) else plan,
                calendar = if (holiday) HolidayCalendar(days = listOf(HolidayDay(day.toString(), "国庆节", statutory = true))) else HolidayCalendar())
            val exporter = TimetableExporter(context)
            val bounds = exporter.inspect(request).single().cardBounds.single()
            val uri = exporter.export(request, tree).single()
            val bitmap = requireNotNull(BitmapFactory.decodeFile(copy(uri, "border-$name.png").absolutePath))
            try {
                if (name == "normal") {
                    val normalFill = CourseColors.swatch(course.color, surface = cridColorScheme(false).surface.toArgb()).fill
                    val sampleX = bounds.left.toInt() + 8
                    val firstFill = (bounds.top.toInt() until bounds.bottom.toInt()).first { bitmap.getPixel(sampleX, it) == normalFill }
                    val fillRows = generateSequence(firstFill) { it + 1 }.takeWhile { it < bounds.bottom && bitmap.getPixel(sampleX, it) == normalFill }.toList()
                    assertTrue("The normal tile must be filled with its course color", fillRows.size > 40)
                    val channels = listOf(Color.red(normalFill), Color.green(normalFill), Color.blue(normalFill))
                    assertTrue("A normal course uses a pale distinguishable fill", channels.min() >= 160 && channels.max() - channels.min() <= 85)
                    top = fillRows.first() + 15
                    bottom = fillRows.last() - 15
                }
                // Compare the edge against nearby fill at the same diagonal-hatch phase.
                // Dark, saturated fills are not borders; an actual stroke differs from its interior.
                val edge = (top..bottom).map { y ->
                    val outer = bitmap.getPixel(bounds.left.toInt() + 1, y)
                    val inner = bitmap.getPixel(bounds.left.toInt() + 4, y - 3)
                    abs(Color.red(outer) - Color.red(inner)) + abs(Color.green(outer) - Color.green(inner)) +
                        abs(Color.blue(outer) - Color.blue(inner)) > 20
                }
                if (!out) assertEquals("$name must have no solid course border", 0, edge.count { it })
                else {
                    assertTrue("$name must keep a visible colored border", edge.count { it } > 10)
                    assertTrue("$name border must contain visible gaps", edge.count { !it } > 5)
                    assertTrue("$name border must alternate dashes and gaps", edge.zipWithNext().count { it.first != it.second } >= 4)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun lightAndDarkExportsUseTheAppBackgroundAndCourseFill() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val term = semester.copy(startDate = day.toString(), endDate = day.plusDays(6).toString(), weeks = 1)
        val course = Course(name = "Theme sample", color = 0xFF4263C7.toInt(), lessons = listOf(
            Lesson(date = day.toString(), startTime = "09:00", endTime = "10:00", location = "Sample room", teacher = "Sample teacher")))
        val plan = Plan(semesterId = term.id, name = "Theme acceptance", courses = listOf(course))
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (view in ExportView.entries) {
            val request = ExportRequest(term, plan, Settings(language = Language.EN, theme = theme), HolidayCalendar(), view,
                day, day, ExportFormat.PNG, 1)
            val exporter = TimetableExporter(context)
            val bounds = exporter.inspect(request).single().cardBounds.single()
            val stem = "theme-${theme.name.lowercase()}-${view.name.lowercase()}"
            exportAndCompare(stem, request, File(output, "theme-comparison.tsv"), exporter, collectText = false)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(File(output, "$stem-001.png").absolutePath))
            try {
                val dark = theme == ThemeMode.DARK
                val colors = cridColorScheme(dark)
                assertEquals("$theme $view uses the app background", colors.background.toArgb(), bitmap.getPixel(0, 0))
                val expectedFill = CourseColors.swatch(course.color, dark = dark, surface = colors.surface.toArgb()).fill
                assertEquals("$theme $view uses the app course fill", expectedFill,
                    bitmap.getPixel(bounds.left.toInt() + 8, ((bounds.top + bounds.bottom) / 2).toInt()))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun pendingEntriesAppearOnlyAsHeaderCountsInEveryViewAndLanguage() = runBlocking<Unit> {
        val sources = listOf("北京师范大学珠海校区2026-2027学年秋季学期学生课表.xls", "学生选课课程表.xls")
        val courses = sources.flatMap { filename ->
            val parsed = TimetableParser.parse(instrumentation.context.assets.open(filename).use { it.readBytes() }, filename)
            assertTrue("The supplied fixture must be fully importable", parsed.valid)
            parsed.courses.mapNotNull { course -> course.copy(lessons = course.lessons.filter { it.unscheduled }).takeIf { it.lessons.isNotEmpty() } }
        }
        assertEquals(4, courses.sumOf { it.lessons.size })
        val plan = Plan(semesterId = semester.id, name = "Pending export acceptance", courses = courses)
        val from = LocalDate.parse("2026-11-16")
        val to = LocalDate.parse("2026-12-21")
        val base = ExportRequest(semester, plan, Settings(showOutOfWeek = false), HolidayCalendar(), ExportView.DAY, from, to, ExportFormat.PNG, 1)
        assertTrue("Pending entries must never become dated occurrences", ScheduleEngine.occurrences(semester, plan, from, base.settings, base.calendar).isEmpty())
        for ((view, language) in listOf(ExportView.DAY to Language.ZH_CN, ExportView.WEEK to Language.ZH_TW,
            ExportView.MONTH to Language.EN, ExportView.SEMESTER to Language.EN)) {
            val request = base.copy(view = view, settings = base.settings.copy(language = language))
            val exporter = TimetableExporter(context)
            val pages = exporter.inspect(request)
            val extracted = exportAndCompare("pending-${view.name.lowercase()}", request, File(output, "pending-comparison.tsv"), exporter)
            assertEquals(pages.size, extracted.size)
            pages.zip(extracted).forEach { (page, raw) ->
                val weeks = page.unit.dates.mapNotNull { ScheduleEngine.weekNumber(semester, it, request.settings.weekStartsSunday) }.toSet()
                val expected = courses.sumOf { course -> course.lessons.count { lesson -> lesson.weeks.any { it in weeks } } }
                assertEquals("The header counts source pending records in the exported unit", expected, page.pendingCount)
                assertTrue("Pending records never create course cards", page.cardTexts.isEmpty())
                val text = comparableText(raw)
                if (expected > 0) {
                    val summary = comparableText(pendingSummary(expected, language))
                    assertTrue("The pending count belongs to the header", comparableText(page.headerTexts.joinToString(" ")).contains(summary))
                    assertEquals("The pending count is shown only once", 1, Regex(Regex.escape(summary)).findAll(text).count())
                }
                courses.forEach { course ->
                    assertFalse("Pending course names are not listed beneath the chart", text.contains(comparableText(course.name)))
                    course.lessons.filter { it.teacher.isNotBlank() }.forEach { lesson ->
                        assertFalse("Pending teaching details are not listed beneath the chart", text.contains(comparableText(lesson.teacher)))
                    }
                }
                assertNoDetailList(text)
            }
            val text = comparableText(extracted.joinToString(" "))
            assertFalse("Pending entries must not acquire fabricated clock times", Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(text))
        }
    }

    @Test fun pendingHeaderCountsRespectRangeAndWeekStart() = runBlocking<Unit> {
        val course = Course(name = "Pending range sample", lessons = listOf(
            Lesson(weeks = listOf(11, 12, 13), weekday = 0, teacher = "Teacher for weeks 11 to 13", unscheduled = true)))
        val plan = Plan(semesterId = semester.id, name = "Range acceptance", courses = listOf(course))
        val cases = listOf(
            Triple("2026-09-06", false, false), // Before the semester.
            Triple("2026-09-07", false, false), // Week 1 cannot include weeks 11–13.
            Triple("2027-01-11", false, false), // After the semester.
            Triple("2026-11-15", false, false), // Sunday still belongs to week 10.
            Triple("2026-11-15", true, true),   // The same Sunday begins week 11 with this setting.
            Triple("2026-11-17", false, true),  // One day intersects week 11.
        )
        cases.forEachIndexed { index, (date, sunday, included) ->
            val day = LocalDate.parse(date)
            val request = ExportRequest(semester, plan, Settings(language = Language.EN, weekStartsSunday = sunday),
                HolidayCalendar(), ExportView.WEEK, day, day, ExportFormat.PNG, 1)
            val exporter = TimetableExporter(context)
            val page = exporter.inspect(request).single()
            val text = comparableText(exportAndCompare("pending-range-$index", request, File(output, "pending-range-comparison.tsv"), exporter).joinToString(" "))
            assertEquals("Only records overlapping a teaching week in the selected range belong in the export",
                if (included) 1 else 0, page.pendingCount)
            assertEquals("The header uses the same filtered records", included,
                text.contains(comparableText("1 unscheduled class entry")))
            assertFalse("Pending course names are not listed", text.contains(comparableText(course.name)))
            assertFalse("Pending teacher/week details are not listed", text.contains(comparableText("Teacher for weeks 11 to 13")))
            assertNoDetailList(text)
            assertFalse("A pending record must not become a fabricated dated class", Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(text))
        }
    }

    @Test fun completeSemesterFixtureFitsOnePngWithOnlyTheMainTimetable() = runBlocking<Unit> {
        val filename = "学生选课课程表.xls"
        val parsed = TimetableParser.parse(instrumentation.context.assets.open(filename).use { it.readBytes() }, filename)
        assertTrue(parsed.valid)
        assertEquals(14, parsed.courses.size)
        assertEquals(40, parsed.courses.sumOf { it.lessons.size })
        val term = semester.copy(periods = Defaults.periods)
        val plan = Plan(semesterId = term.id, name = "Complete semester acceptance", courses = CourseColors.assign(parsed.courses))
        val request = ExportRequest(term, plan, Settings(language = Language.EN), HolidayCalendar(), ExportView.SEMESTER,
            LocalDate.parse(term.startDate), LocalDate.parse(term.endDate), ExportFormat.PNG, 1)
        val exporter = TimetableExporter(context)
        val page = exporter.inspect(request.copy(format = ExportFormat.PDF)).single()
        val standardStarts = term.periods.map { java.time.LocalTime.parse(it.start) }.toSet()
        val standardEnds = term.periods.map { java.time.LocalTime.parse(it.end) }.toSet()
        assertTrue("This fixture uses standard period boundaries for every scheduled lesson", plan.courses.flatMap { it.lessons }
            .filterNot { it.unscheduled }.all { lesson ->
                val times = requireNotNull(ScheduleEngine.lessonTimes(term, lesson))
                times.first in standardStarts && times.second in standardEnds
            })
        assertTrue("Standard period cards keep clock times on the shared axis",
            page.cardTexts.none { Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(it) })
        assertEquals(ExportUnits.weekCount(term), page.weekStarts.size)
        assertEquals(page.unit.dates.size, page.unit.dates.distinct().size)
        val expected = page.unit.dates.flatMap { date -> displayCourses(ScheduleEngine.occurrences(term, plan, date, request.settings, request.calendar))
            .flatMap { it.occurrences.distinctBy { record -> record.lesson }.map { record -> date to record.lesson } } }
        assertEquals(expected.groupingBy { it }.eachCount(), page.courseRecords.groupingBy { it }.eachCount())
        val expectedPending = plan.courses.flatMap { it.lessons }.filter { it.unscheduled && it.weeks.any { week -> week in 1..ExportUnits.weekCount(term) } }
        assertEquals(expectedPending.size, page.pendingCount)
        assertTrue("Readable fonts are preserved on a growing page", page.minimumFont >= 18f)
        page.cardBounds.forEachIndexed { index, bounds ->
            assertTrue("Every complete card fits its measured contents", bounds.bottom - bounds.top + .01f >= bounds.contentHeight)
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= page.width && bounds.bottom <= page.height)
            page.cardBounds.drop(index + 1).forEach { other ->
                assertFalse("Merged timetable cards must not overlap", bounds.left < other.right && other.left < bounds.right && bounds.top < other.bottom && other.top < bounds.bottom)
                if (bounds.durationMinutes != other.durationMinutes) {
                    val (shorter, longer) = if (bounds.durationMinutes < other.durationMinutes) bounds to other else other to bounds
                    assertTrue("Longer lessons stay strictly taller across the whole semester page",
                        longer.bottom - longer.top >= shorter.bottom - shorter.top + .99f)
                }
            }
        }
        File(output, "semester-fixture-dimensions.txt").writeText("${page.width} x ${page.height}; ${page.width.toLong() * page.height} pixels; ${page.courseRecords.size} dated records; ${page.pendingCount} pending records")
        File(output, "semester-fixture-card-constraints.txt").writeText(page.cardBounds.zip(page.cardTexts)
            .sortedByDescending { (bounds, _) -> bounds.contentHeight / bounds.durationMinutes }
            .take(12).joinToString("\n\n") { (bounds, text) ->
                "content=${bounds.contentHeight}; duration=${bounds.durationMinutes}; drawn=${bounds.bottom - bounds.top}; width=${bounds.right - bounds.left}\n$text"
            })
        val startedOne = System.nanoTime()
        val pngs = exporter.export(request, tree)
        assertEquals(1, pngs.size)
        val png = copy(pngs.single(), "semester-fixture.png")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.absolutePath, options)
        assertEquals(page.width, options.outWidth)
        assertEquals(page.height, options.outHeight)
        File(output, "semester-fixture-dimensions.txt").appendText("\nPNG 1x: ${(System.nanoTime() - startedOne) / 1_000_000} ms; ${png.length()} bytes")
        val startedTwo = System.nanoTime()
        val pngTwo = copy(exporter.export(request.copy(pngScale = 2), tree).single(), "semester-fixture-2x.png")
        val optionsTwo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(pngTwo.absolutePath, optionsTwo)
        assertEquals(page.width * 2, optionsTwo.outWidth)
        assertEquals(page.height * 2, optionsTwo.outHeight)
        File(output, "semester-fixture-dimensions.txt").appendText("\nPNG 2x: ${optionsTwo.outWidth} x ${optionsTwo.outHeight}; ${(System.nanoTime() - startedTwo) / 1_000_000} ms; ${pngTwo.length()} bytes")
        val pdf = copy(exporter.export(request.copy(format = ExportFormat.PDF), tree).single(), "semester-fixture.pdf")
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals(1, renderer.pageCount)
            renderer.openPage(0).use { pdfPage ->
                val extracted = Api35PdfText.read(pdfPage)
                File(output, "semester-fixture-text.txt").writeText(extracted)
                val text = comparableText(extracted)
                val visible = page.unit.dates.flatMap { date -> displayCourses(ScheduleEngine.occurrences(term, plan, date, request.settings, request.calendar)) }
                plan.courses.forEach { course ->
                    val expectedCount = visible.count { courseKey(it.representative.course.name) == courseKey(course.name) }
                    assertEquals("A course title occurs only in its visible timetable cells: ${course.name}", expectedCount,
                        Regex(Regex.escape(comparableText(course.name))).findAll(text).count())
                }
                page.cardTexts.forEach { card -> assertTrue("Every measured timetable card survives PDF export", text.contains(comparableText(card))) }
                if (expectedPending.isNotEmpty()) assertTrue("Only the pending total is appended to the header",
                    comparableText(page.headerTexts.joinToString(" ")).contains(comparableText(pendingSummary(expectedPending.size, Language.EN))))
                assertNoDetailList(text)
            }
        }
    }

    @Test fun multipleSemestersShareOnePdfWithOneCompletePageEach() = runBlocking<Unit> {
        val first = semester.copy(endDate = "2026-10-04", weeks = 4)
        val second = semester.copy(id = "second-term", name = "Second semester", startDate = "2027-02-15", endDate = "2027-02-28", weeks = 2)
        val requests = listOf(first, second).mapIndexed { index, term ->
            val lesson = Lesson(weeks = listOf(1, 2), weekday = 1, startTime = "09:00", endTime = "09:45", teacher = "Batch teacher $index", location = "Batch room $index", note = "Batch note $index")
            val plan = Plan(semesterId = term.id, name = "Batch plan $index", courses = listOf(Course(name = "Batch course $index", lessons = listOf(lesson))))
            ExportRequest(term, plan, Settings(language = Language.EN), HolidayCalendar(), ExportView.SEMESTER,
                LocalDate.parse(term.startDate), LocalDate.parse(term.endDate), ExportFormat.PNG, 1)
        }
        val exporter = TimetableExporter(context)
        assertEquals(2, exporter.estimate(requests))
        assertEquals(2, exporter.export(requests, tree).size)
        val pdf = copy(exporter.export(requests.map { it.copy(format = ExportFormat.PDF) }, tree).single(), "semester-batch.pdf")
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals(2, renderer.pageCount)
            requests.forEachIndexed { index, request -> renderer.openPage(index).use { page ->
                val text = comparableText(Api35PdfText.read(page))
                assertTrue(text.contains(comparableText(request.semester.name)))
                assertTrue(text.contains(comparableText(request.plan.courses.single().name)))
                assertTrue(text.contains(comparableText("Batch room $index")))
                assertTrue(text.contains(comparableText("Batch teacher $index")))
                assertFalse("Source notes are not appended to semester exports", text.contains(comparableText("Batch note $index")))
                assertNoDetailList(text)
            } }
        }
        assertThrows(IllegalArgumentException::class.java) { exporter.estimate(listOf(requests[0], requests[1].copy(format = ExportFormat.PDF))) }
        assertThrows(IllegalArgumentException::class.java) { exporter.estimate(listOf(requests[0], requests[1].copy(pngScale = 2))) }
    }

    @Test fun oversizedPngOffersPdfWithoutSplittingItsCalendarUnit() {
        val day = LocalDate.parse("2026-09-28")
        val courses = (1..500).map { index -> Course(name = "Simultaneous course $index", lessons = listOf(
            Lesson(date = day.toString(), startTime = "09:00", endTime = "10:00", teacher = "Teacher $index", location = "Room $index"))) }
        val plan = Plan(semesterId = semester.id, name = "Large unit", courses = courses)
        val request = ExportRequest(semester, plan, Settings(language = Language.EN), HolidayCalendar(), ExportView.DAY, day, day, ExportFormat.PNG, 3)
        val exporter = TimetableExporter(context)
        val failure = assertThrows(IllegalArgumentException::class.java) { exporter.estimate(request) }
        assertTrue(failure.message.orEmpty().contains("PDF"))
        val pdfRequest = request.copy(format = ExportFormat.PDF)
        assertEquals(1, exporter.estimate(pdfRequest))
        assertEquals(courses.size, exporter.inspect(pdfRequest).single().courseRecords.size)
    }

    @Test fun streamedPngHasNoBandSeamsAtEitherResolution() = runBlocking<Unit> {
        val day = LocalDate.parse("2026-09-28")
        val plan = fixturePlan().copy(courses = fixturePlan().courses.take(1))
        val base = ExportRequest(semester, plan, Settings(language = Language.EN), calendar, ExportView.DAY, day, day, ExportFormat.PNG, 1)
        val streamed = TimetableExporter(context, streamPng = true)
        exportAndCompare("streamed-day", base, File(output, "streamed-comparison.tsv"), streamed, collectText = false)
        for (scale in 1..2) {
            val request = base.copy(pngScale = scale)
            val streamFile = copy(streamed.export(request, tree).single(), "streamed-${scale}x.png")
            val bitmapFile = copy(TimetableExporter(context).export(request, tree).single(), "whole-${scale}x.png")
            val one = requireNotNull(BitmapFactory.decodeFile(streamFile.absolutePath))
            val two = requireNotNull(BitmapFactory.decodeFile(bitmapFile.absolutePath))
            try {
                assertEquals(two.width, one.width); assertEquals(two.height, one.height)
                val firstRow = IntArray(one.width)
                val secondRow = IntArray(two.width)
                var maximum = 0
                var channelSum = 0L
                var changed = 0L
                var boundaryChanged = 0L
                var boundaryMaximum = 0
                for (y in 0 until one.height) {
                    one.getPixels(firstRow, 0, one.width, 0, y, one.width, 1)
                    two.getPixels(secondRow, 0, two.width, 0, y, two.width, 1)
                    for (x in firstRow.indices) {
                        val red = abs(Color.red(firstRow[x]) - Color.red(secondRow[x]))
                        val green = abs(Color.green(firstRow[x]) - Color.green(secondRow[x]))
                        val blue = abs(Color.blue(firstRow[x]) - Color.blue(secondRow[x]))
                        val alpha = abs(Color.alpha(firstRow[x]) - Color.alpha(secondRow[x]))
                        val difference = maxOf(red, green, blue, alpha)
                        maximum = maxOf(maximum, difference)
                        channelSum += red + green + blue + alpha
                        if (difference > 0) changed++
                        if (y % 128 == 0 || y % 128 == 127) {
                            boundaryMaximum = maxOf(boundaryMaximum, difference)
                            if (difference > 0) boundaryChanged++
                        }
                    }
                }
                val count = one.width.toLong() * one.height
                val average = channelSum.toDouble() / (count * 4)
                File(output, "streamed-raster-comparison.tsv").appendText("${scale}x\t$count\t$changed\t$maximum\t$average\t$boundaryChanged\t$boundaryMaximum\n")
                // API 35 still rounds three isolated rounded-card edge pixels differently
                // with four guard rows (maximum 4/255); retain strict counts and mean error.
                // The encoder itself is tested losslessly.
                assertTrue("Every channel at ${scale}x stays within the measured AA rounding bound: $maximum", maximum <= 4)
                assertTrue("Translated rendering cannot shift whole edges or rows at ${scale}x: $changed changed pixels", changed <= maxOf(8L, count / 10_000))
                assertTrue("Band boundaries cannot introduce a visible seam at ${scale}x", boundaryMaximum <= 4 && boundaryChanged <= maxOf(4L, count / 100_000))
                assertTrue("Total raster error stays negligible at ${scale}x: $average", average < .001)
            } finally { one.recycle(); two.recycle() }
        }
    }

    private suspend fun exportAndCompare(stem: String, request: ExportRequest, metrics: File, exporter: TimetableExporter = TimetableExporter(context), collectText: Boolean = true): List<String> {
        val expected = exporter.estimate(request)
        assertTrue(expected > 0)
        val pngs = exporter.export(request.copy(format = ExportFormat.PNG, pngScale = 1), tree)
        val pdfRequest = request.copy(format = ExportFormat.PDF)
        assertEquals("Format cannot alter pagination", expected, exporter.estimate(pdfRequest))
        val pdfs = exporter.export(pdfRequest, tree)
        assertEquals(expected, pngs.size)
        assertEquals(1, pdfs.size)
        val pdf = copy(pdfs.single(), "$stem.pdf")
        assertTrue(pdf.length() > 1_000)
        val extracted = mutableListOf<String>()
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        try {
            assertEquals("Saved PDF count must equal estimate", expected, renderer.pageCount)
            for (index in pngs.indices) {
                val pngFile = copy(pngs[index], "$stem-${(index + 1).toString().padStart(3, '0')}.png")
                val png = requireNotNull(BitmapRegionDecoder.newInstance(pngFile.absolutePath, false))
                val page = renderer.openPage(index)
                try {
                    assertTrue("Content can extend the page without shrinking text", png.width >= if (request.view == ExportView.DAY) 800 else 900)
                    assertEquals(png.width, page.width)
                    assertEquals(png.height, page.height)
                    if (collectText) {
                        val pageText = Api35PdfText.read(page)
                        extracted += pageText
                        File(output, "$stem-${index + 1}-text.txt").writeText(pageText)
                    }
                    var accumulator: Comparison? = null
                    var top = 0
                    while (top < page.height) {
                        val bandHeight = minOf(128, page.height - top)
                        val region = requireNotNull(png.decodeRegion(Rect(0, top, page.width, top + bandHeight), BitmapFactory.Options()))
                        val rendered = Bitmap.createBitmap(page.width, bandHeight, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.eraseColor(Color.WHITE)
                            page.render(rendered, null, Matrix().apply { setTranslate(0f, -top.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (accumulator == null) accumulator = Comparison(region.getPixel(0, 0))
                            accumulator.add(region, rendered)
                        } finally { region.recycle(); rendered.recycle() }
                        top += bandHeight
                    }
                    val stats = requireNotNull(accumulator).values()
                    metrics.appendText("$stem\t${index + 1}\t${png.width}\t${png.height}\t${stats[0]}\t${stats[1]}\t${stats[2]}\t${stats[3]}\t${stats[4]}\n")
                    assertTrue("$stem page ${index + 1} must contain drawn content: ${stats[2]}", stats[2] > .001)
                    assertTrue("$stem page ${index + 1}: PNG/PDF mean difference ${stats[0]} exceeds antialiasing tolerance", stats[0] < 8.0)
                    assertTrue("$stem page ${index + 1}: PNG/PDF disagree over too much area: ${stats[1]}", stats[1] < .06)
                    assertTrue("$stem page ${index + 1}: text foreground differs: ${stats[3]}", stats[3] < 45)
                    assertTrue("$stem page ${index + 1}: too much text ink is missing: ${stats[4]}", stats[4] < .5)
                    if (index == 0) {
                        val scale = minOf(1f, 1600f / maxOf(page.width, page.height))
                        val preview = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        try {
                            preview.eraseColor(Color.WHITE)
                            page.render(preview, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            File(output, "$stem-pdf-preview.png").outputStream().use { assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { preview.recycle() }
                    }
                } finally {
                    png.recycle()
                    page.close()
                }
            }
        } finally { renderer.close() }
        return extracted
    }

    /** PDF and bitmap rasterizers differ at antialiased glyph edges, so use bounded image error. */
    private fun compare(a: Bitmap, b: Bitmap): DoubleArray {
        return Comparison(a.getPixel(0, 0)).apply { add(a, b) }.values()
    }

    // Isolate API 35 element types from JUnit's reflective scan of this test class on API 31.
    private object Api35PdfText {
        fun read(page: PdfRenderer.Page): String = page.textContents.joinToString(" ") { it.text }
    }

    /** Accumulate every pixel of every decoded region, without holding a full page bitmap. */
    private class Comparison(private val background: Int) {
        private var error = 0L
        private var count = 0L
        private var significant = 0L
        private var ink = 0L
        private var darkCount = 0L
        private var darkError = 0L
        private var darkMismatch = 0L
        fun add(a: Bitmap, b: Bitmap) {
            val rowA = IntArray(a.width)
            val rowB = IntArray(b.width)
            for (y in 0 until a.height) {
                a.getPixels(rowA, 0, a.width, 0, y, a.width, 1)
                b.getPixels(rowB, 0, b.width, 0, y, b.width, 1)
                for (x in 0 until a.width) {
                    val one = rowA[x]
                    val two = rowB[x]
                    val delta = abs(Color.red(one) - Color.red(two)) + abs(Color.green(one) - Color.green(two)) + abs(Color.blue(one) - Color.blue(two))
                    error += delta
                    if (delta > 120) significant++
                    if (abs(Color.red(one) - Color.red(background)) + abs(Color.green(one) - Color.green(background)) + abs(Color.blue(one) - Color.blue(background)) > 45) ink++
                    val darkA = Color.red(one) + Color.green(one) + Color.blue(one) < 435
                    val darkB = Color.red(two) + Color.green(two) + Color.blue(two) < 435
                    if (darkA || darkB) {
                        darkCount++
                        darkError += delta
                        if (darkA != darkB) darkMismatch++
                    }
                    count++
                }
            }
        }
        fun values() = doubleArrayOf(error.toDouble() / (count * 3), significant.toDouble() / count, ink.toDouble() / count,
            darkError.toDouble() / (darkCount.coerceAtLeast(1) * 3), darkMismatch.toDouble() / darkCount.coerceAtLeast(1))
    }

    private fun copy(uri: Uri, name: String): File = File(output, name).also { file ->
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun pendingSummary(count: Int, language: Language): String = when (language) {
        Language.ZH_CN -> "另有 $count 项待排课"
        Language.ZH_TW -> "另有 $count 項待排課"
        else -> "$count unscheduled class ${if (count == 1) "entry" else "entries"}"
    }

    private fun assertNoDetailList(text: String) {
        listOf("Course arrangements", "课程安排", "課程安排", "Unscheduled classes", "待排课程", "待排課程").forEach { title ->
            assertFalse("Exports contain no bottom detail-list heading: $title", text.contains(comparableText(title)))
        }
    }

    // Android PDF font subsets can map shared glyphs to radical code points. NFKC
    // covers Kangxi forms; Unicode's charts confirm the two observed supplement forms:
    // https://www.unicode.org/charts/PDF/U2E80.pdf (2ED4 -> 95E8; 2E92 -> 5DF3).
    // Keep all characters and their order; never strip source wording to make a test pass.
    private fun comparableText(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace('\u2ED4', '\u95E8').replace('\u2E92', '\u5DF3').replace(Regex("\\s+"), "")

    private fun fixturePlan(): Plan {
        fun recurring(name: String, day: Int, start: String, end: String, weeks: List<Int> = (1..18).toList()) =
            Course(name = name, credits = "3", lessons = listOf(Lesson(weeks = weeks, weekday = day, startTime = start, endTime = end,
                location = "Liberal Arts Building 402 / 励耘楼 402", teacher = "陈老师 / Dr Chen")))
        return Plan(semesterId = semester.id, name = "Export acceptance / 导出验收", courses = CourseColors.assign(listOf(
            recurring(longName, 1, "08:00", "08:45"),
            recurring("Applied statistics / 应用统计", 1, "09:00", "10:30"),
            recurring("Concurrent seminar / 同时段研讨", 1, "09:30", "10:15"),
            recurring("Past-week course / 非本周课程", 1, "14:00", "14:45", listOf(1)),
            recurring("Holiday laboratory / 假日实验", 4, "10:00", "11:30"),
            recurring("Rest-day seminar / 调休研讨", 5, "10:00", "10:45"),
            recurring("Weekend study / 周末课程", 6, "15:00", "15:45"),
        )))
    }
}
