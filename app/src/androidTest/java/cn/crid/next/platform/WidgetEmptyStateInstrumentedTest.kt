package cn.crid.next.platform

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.R
import cn.crid.next.core.AppState
import cn.crid.next.core.Course
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Language
import cn.crid.next.core.Lesson
import cn.crid.next.core.Plan
import cn.crid.next.core.Semester
import cn.crid.next.core.Settings
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

/** Empty scenes and pointer dispatch through a real RemoteViews host, including list whitespace. */
class WidgetEmptyStateInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val today = LocalDate.of(2026, 9, 23)
    private val square = SizeF(180f, 160f)
    private val wide = SizeF(360f, 160f)
    private val minimumWeek = SizeF(245f, 280f)
    private val roomyWeek = SizeF(480f, 400f)
    private val sizes = listOf(square to false, SizeF(160f, 180f) to false, SizeF(200f, 180f) to false,
        SizeF(140f, 140f) to false, SizeF(180f, 180f) to false, wide to false, minimumWeek to true, roomyWeek to true)

    @Test fun emptyIllustrationsAndMessagesFitAllSupportedProfilesAndLanguages() = onMain {
        for (language in listOf(Language.ZH_CN, Language.ZH_TW, Language.EN)) for (dark in listOf(false, true)) {
            for ((size, weekly) in sizes) {
                val empty = state(language = language)
                val root = inflate(views(size, weekly, empty, dark), size)
                val profile = "${if (weekly) "week" else "today"}-${size.width.toInt()}x${size.height.toInt()}-${language.name}-${if (dark) "dark" else "light"}"
                capture(root, "empty-$profile")
                assertEmpty(root, if (weekly) R.string.platform_no_week_classes else R.string.platform_no_today_classes, language)
                assertReadableEmpty(root, profile)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty_art).visibility)
                assertNotNull(root.findViewById<ImageView>(R.id.widget_empty_art).drawable)

                val otherStates = listOf(
                    empty.copy(plans = emptyList(), selectedPlanId = null) to R.string.platform_no_plan,
                    AppState(settings = empty.settings) to R.string.platform_no_semester,
                ) + if (weekly) emptyList() else listOf(
                    state(listOf(course("morning", today, "08:00", "09:00")), language) to R.string.platform_classes_finished)
                for ((missing, message) in otherStates) {
                    views(size, weekly, missing, dark).reapply(context, root)
                    measure(root, size)
                    val scene = when (message) {
                        R.string.platform_no_plan -> "no-plan"
                        R.string.platform_classes_finished -> "finished"
                        else -> "no-semester"
                    }
                    val missingProfile = "$scene-$profile"
                    capture(root, missingProfile)
                    assertEmpty(root, message, language)
                    assertReadableEmpty(root, missingProfile)
                }
            }
        }
    }

    @Test fun doubleFontGivesTheWholeMessagePriorityOverDecorativeArt() = withSystemFontScale(2f) { onMain {
        val large = context
        assertEquals("Widgets must use the actual system font setting", 2f, large.resources.configuration.fontScale, .001f)
        for (language in listOf(Language.ZH_CN, Language.ZH_TW, Language.EN)) {
            for ((size, weekly) in sizes + listOf(SizeF(245f, 560f) to true, SizeF(360f, 320f) to false)) {
                val root = inflate(views(size, weekly, state(language = language), renderContext = large), size, large)
                assertEquals("RemoteViews must inflate with the actual system font setting", 2f, root.resources.configuration.fontScale, .001f)
                val profile = "empty-font2-${if (weekly) "week" else "today"}-${size.width.toInt()}x${size.height.toInt()}-${language.name}"
                capture(root, profile)
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
                assertReadableEmpty(root, profile)
            }
            val finishedSize = SizeF(240f, 240f)
            val finished = state(listOf(course("morning", today, "08:00", "09:00")), language)
            val root = inflate(views(finishedSize, state = finished, renderContext = large), finishedSize, large)
            val profile = "finished-font2-240x240-${language.name}"
            capture(root, profile)
            assertEmpty(root, R.string.platform_classes_finished, language)
            assertReadableEmpty(root, profile)
        }
    } }

    @Test fun resizingFinishedTodayPreservesHistoryAndReapplyRestoresCourseContent() = onMain {
        val tall = SizeF(180f, 360f)
        for (language in listOf(Language.ZH_CN, Language.ZH_TW, Language.EN)) {
            val populated = state(listOf(course("morning", today, "08:00", "09:00")), language)
            val root = inflate(views(square, state = populated), square)
            val squareProfile = "finished-today-square-${language.name}"
            capture(root, squareProfile)
            assertEmpty(root, R.string.platform_classes_finished, language)
            assertReadableEmpty(root, squareProfile)
            views(tall, state = populated).reapply(context, root)
            measure(root, tall)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty_panel).visibility)
            assertEquals(listOf("morning"), courseNames(root))
            views(wide, state = populated).reapply(context, root)
            measure(root, wide)
            val wideProfile = "finished-today-wide-${language.name}"
            capture(root, wideProfile)
            assertEmpty(root, R.string.platform_classes_finished, language)
            assertReadableEmpty(root, wideProfile)
            val upcoming = state(listOf(course("next", today, "11:00", "12:00"), course("later", today, "16:00", "17:00")), language)
            views(wide, state = upcoming).reapply(context, root)
            measure(root, wide)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty_panel).visibility)
            assertEquals(listOf("next", "later"), courseNames(root))
            views(square, state = state(language = language)).reapply(context, root)
            measure(root, square)
            assertEmpty(root, R.string.platform_no_today_classes, language)
            assertTrue(courseNames(root).isEmpty())
        }
    }

    @Test fun emptyScenePaddingTitleArtAndTextDispatchToTheirWidgetRoute() = withActivity { scenario ->
        for ((size, weekly) in sizes) {
            lateinit var root: View
            scenario.onActivity { root = mount(it, size, weekly, state()) }
            val points = listOf<(View) -> Pair<Float, Float>>(
                { bounds(it, it.findViewById(R.id.widget_root)).let { rect -> rect.exactCenterX() to rect.top + 4f } },
                { center(it, R.id.widget_title) },
                { center(it, R.id.widget_empty_art) },
                { center(it, R.id.widget_empty_caption) },
                { bounds(it, it.findViewById(R.id.widget_root)).let { rect -> rect.exactCenterX() to rect.bottom - 4f } },
            )
            points.forEachIndexed { index, point ->
                touchAndExpect(scenario, root, if (weekly) "timetable" else "today", today, "empty-$size-$index", point)
            }
            scenario.onActivity { unmount(root) }
        }
    }

    @Test fun courseCardsGuttersAndShortListBlankSpaceRemainClickable() = withActivity { scenario ->
        lateinit var root: View
        val upcoming = state(listOf(course("next", today, "11:00", "12:00"), course("later", today, "16:00", "17:00")))
        scenario.onActivity { root = mount(it, wide, false, upcoming) }
        for (id in listOf(R.id.widget_title, R.id.widget_course_0, R.id.widget_course_1)) {
            touchAndExpect(scenario, root, "today", today, "compact-card-$id") { center(it, id) }
        }
        touchAndExpect(scenario, root, "today", today, "compact-gutter") {
            val first = bounds(it, it.findViewById(R.id.widget_column_first))
            val second = bounds(it, it.findViewById(R.id.widget_column_second))
            (first.right + second.left) / 2f to (first.top + first.bottom) / 2f
        }
        touchAndExpect(scenario, root, "today", today, "compact-outer-padding") { 4f to it.height / 2f }
        scenario.onActivity { unmount(root) }

        val tall = SizeF(300f, 600f)
        scenario.onActivity { root = mount(it, tall, false, state(listOf(course("only", today, "11:00", "12:00")))) }
        touchAndExpect(scenario, root, "today", today, "list-bottom-blank") {
            val list = it.findViewById<ListView>(R.id.widget_list)
            val listBounds = bounds(it, list)
            val footer = bounds(it, it.findViewById(R.id.widget_footer))
            val bottom = footer.top.takeIf { top -> top > listBounds.bottom } ?: (it.height - it.paddingBottom)
            assertTrue("Fixture needs free space below its short list", bottom - listBounds.bottom > 16)
            it.width / 2f to (listBounds.bottom + bottom) / 2f
        }
        scenario.onActivity { unmount(root) }

        val monday = today.minusDays(2)
        scenario.onActivity { root = mount(it, roomyWeek, true, state(listOf(
            course("monday", monday, "11:00", "12:00"), course("tuesday", monday.plusDays(1), "11:00", "12:00")))) }
        for ((id, date) in listOf(R.id.widget_day_0 to monday, R.id.widget_course_0 to monday,
            R.id.widget_day_1 to monday.plusDays(1), R.id.widget_course_1 to monday.plusDays(1))) {
            touchAndExpect(scenario, root, "timetable", date, "week-child-$id") { center(it, id) }
        }
        touchAndExpect(scenario, root, "timetable", today, "week-outer-padding") { 4f to it.height / 2f }
        scenario.onActivity { unmount(root) }
    }

    @Test fun scrollingAWeekListDoesNotTriggerItsDefaultClickOrBlockTheList() = withActivity { scenario ->
        lateinit var root: View
        var startPosition = 0
        var startTop = 0
        val busy = state(List(7) { day -> course("day-$day", today.minusDays(2).plusDays(day.toLong()), "11:00", "12:00") })
        scenario.onActivity {
            root = mount(it, minimumWeek, true, busy)
            it.intent = Intent()
            val list = root.findViewById<ListView>(R.id.widget_list)
            startPosition = list.firstVisiblePosition
            startTop = list.getChildAt(0).top
            val rect = bounds(root, list)
            val x = rect.centerX().toFloat()
            val start = rect.bottom - 12f
            val end = rect.top + 12f
            val down = SystemClock.uptimeMillis()
            dispatch(root, down, down, MotionEvent.ACTION_DOWN, x, start)
            repeat(8) { index -> dispatch(root, down, down + (index + 1) * 24L, MotionEvent.ACTION_MOVE,
                x, start + (end - start) * (index + 1) / 8f) }
            dispatch(root, down, down + 216, MotionEvent.ACTION_UP, x, end)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val list = root.findViewById<ListView>(R.id.widget_list)
            assertTrue("Drag must scroll the collection", list.firstVisiblePosition > startPosition || list.getChildAt(0).top < startTop)
            assertNull("A drag must not launch the default route", it.intent.getStringExtra("route"))
            unmount(root)
        }
    }

    private fun assertEmpty(root: View, message: Int, language: Language) {
        val illustrated = root.findViewById<View>(R.id.widget_empty_panel).visibility == View.VISIBLE
        assertEquals(if (illustrated) View.GONE else View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
        val text = root.findViewById<TextView>(if (illustrated) R.id.widget_empty_caption else R.id.widget_empty)
        assertEquals(View.VISIBLE, text.visibility)
        val expected = if (message == R.string.platform_classes_finished) when (language) {
            Language.ZH_CN -> "今日课程已结束"
            Language.ZH_TW -> "今日課程已結束"
            Language.EN -> "Today's classes are over"
            else -> PlatformText.context(context, language).getString(message)
        } else PlatformText.context(context, language).getString(message)
        assertEquals(expected, text.text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_compact).visibility)
        assertEquals(0, root.findViewById<ListView>(R.id.widget_list).adapter.count)
    }

    private fun assertReadableEmpty(root: View, profile: String = "unspecified") {
        val text = if (root.findViewById<View>(R.id.widget_empty_panel).visibility == View.VISIBLE)
            root.findViewById<TextView>(R.id.widget_empty_caption) else root.findViewById<TextView>(R.id.widget_empty)
        val rect = bounds(root, text)
        val surface = root.findViewById<View>(R.id.widget_root)
        val surfaceBounds = bounds(root, surface)
        val diagnostics = emptyDiagnostics(root, profile)
        assertTrue("Empty text escapes the widget: $rect\n$diagnostics", rect.left >= surfaceBounds.left + surface.paddingLeft &&
            rect.top >= surfaceBounds.top + surface.paddingTop && rect.right <= surfaceBounds.right - surface.paddingRight &&
            rect.bottom <= surfaceBounds.bottom - surface.paddingBottom)
        val layout = requireNotNull(text.layout)
        assertTrue(text.text.isNotBlank() && layout.lineCount > 0)
        assertTrue("Empty message is clipped\n$diagnostics", layout.getLineBottom(layout.lineCount - 1) <=
            text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        assertTrue("Empty message is ellipsized\n$diagnostics", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        val art = root.findViewById<View>(R.id.widget_empty_art)
        if (art?.visibility == View.VISIBLE) {
            val artRect = bounds(root, art)
            assertTrue(artRect.width() > 0 && artRect.height() > 0)
            assertFalse("Decorative art overlaps empty text\n$diagnostics", Rect.intersects(artRect, rect))
        }
    }

    private fun emptyDiagnostics(root: View, profile: String): String {
        val text = root.findViewById<TextView>(R.id.widget_empty_caption)
            ?: root.findViewById<TextView>(R.id.widget_empty)
        val layout = text.layout
        val body = root.findViewById<View>(R.id.widget_body)
        val scene = root.findViewById<View>(R.id.widget_empty_scene)
        val art = root.findViewById<View>(R.id.widget_empty_art)
        val surface = root.findViewById<View>(R.id.widget_root)
        return "profile=$profile\n" +
            "rootPx=${root.width}x${root.height};measuredPx=${root.measuredWidth}x${root.measuredHeight};layoutRequested=${root.isLayoutRequested}\n" +
            "surfaceBounds=${bounds(root, surface)};surfaceBackgroundBounds=${surface.background?.bounds}\n" +
            "surfacePaddingPx=${surface.paddingLeft},${surface.paddingTop},${surface.paddingRight},${surface.paddingBottom}\n" +
            "density=${root.resources.displayMetrics.density};fontScale=${root.resources.configuration.fontScale}\n" +
            "bodyPx=${body.width}x${body.height};scenePx=${scene?.width}x${scene?.height};artPx=${art?.width}x${art?.height}\n" +
            "text=${text.text};textBounds=${bounds(root, text)};measuredHeight=${text.measuredHeight};height=${text.height}\n" +
            "textSizePx=${text.textSize};lineHeight=${text.lineHeight};includeFontPadding=${text.includeFontPadding};" +
            "paddingTop=${text.compoundPaddingTop};paddingBottom=${text.compoundPaddingBottom}\n" +
            "layoutHeight=${layout?.height};lineCount=${layout?.lineCount};lastLineBottom=" +
            (if (layout != null && layout.lineCount > 0) layout.getLineBottom(layout.lineCount - 1) else null) + "\n"
    }

    private fun views(size: SizeF, weekly: Boolean = false, state: AppState = state(), dark: Boolean = false,
        renderContext: Context = context): RemoteViews = CourseWidgets.layout(renderContext,
        PlatformText.context(renderContext, state.settings.language), 971400, weekly, size, state,
        HolidayCalendar(), dark, today, LocalTime.of(10, 0))

    private fun inflate(remote: RemoteViews, size: SizeF, renderContext: Context = context): View {
        val host = AppWidgetHostView(renderContext)
        val root = remote.apply(renderContext, host)
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        measure(root, size)
        return root
    }

    private fun mount(activity: MainActivity, size: SizeF, weekly: Boolean, state: AppState): View {
        val root = inflate(views(size, weekly, state, renderContext = activity), size, activity)
        val density = activity.resources.displayMetrics.density
        activity.addContentView(root.parent as View, FrameLayout.LayoutParams((size.width * density).toInt(), (size.height * density).toInt()))
        measure(root, size)
        return root
    }

    private fun unmount(root: View) { val host = root.parent as View; (host.parent as? ViewGroup)?.removeView(host) }

    private fun measure(root: View, size: SizeF) {
        val density = root.resources.displayMetrics.density
        val host = root.parent as View
        val width = (size.width * density).toInt()
        val height = (size.height * density).toInt()
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
    }

    private fun courseNames(root: View): List<String> {
        fun collect(view: View): List<String> = buildList {
            if (view.visibility != View.VISIBLE) return@buildList
            if (view.id == R.id.widget_course_name && view is TextView) add(view.text.toString())
            if (view is ViewGroup) repeat(view.childCount) { addAll(collect(view.getChildAt(it))) }
        }
        return collect(root)
    }

    private fun bounds(root: View, child: View): Rect = Rect(0, 0, child.width, child.height).also {
        (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
    }

    private fun center(root: View, id: Int): Pair<Float, Float> = bounds(root, root.findViewById(id)).let {
        assertTrue("Tap target $id has no area", it.width() > 0 && it.height() > 0)
        it.exactCenterX() to it.exactCenterY()
    }

    private fun touchAndExpect(scenario: ActivityScenario<MainActivity>, root: View, route: String, date: LocalDate,
        label: String, point: (View) -> Pair<Float, Float>) {
        scenario.onActivity {
            it.intent = Intent()
            val (x, y) = point(root)
            val down = SystemClock.uptimeMillis()
            assertTrue("$label must receive pointer down", dispatch(root, down, down, MotionEvent.ACTION_DOWN, x, y))
            dispatch(root, down, down + 32, MotionEvent.ACTION_UP, x, y)
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actualRoute: String? = null
        var actualDate: String? = null
        do {
            instrumentation.waitForIdleSync()
            scenario.onActivity { actualRoute = it.intent.getStringExtra("route"); actualDate = it.intent.getStringExtra("date") }
            if (actualRoute == route && actualDate == date.toString()) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("$label route", route, actualRoute)
        assertEquals("$label date", date.toString(), actualDate)
    }

    private fun dispatch(root: View, down: Long, time: Long, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        return try { root.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun withActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            try { block(scenario) } finally { scenario.onActivity { it.intent = Intent(launch) } }
        }
    }

    private fun capture(root: View, name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa-v1.14.1/widgets")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(directory, "$name.txt").writeText(emptyDiagnostics(root, name))
        } finally { bitmap.recycle() }
    }

    private fun course(id: String, date: LocalDate, start: String, end: String) = Course(id, id, lessons = listOf(
        Lesson(date = date.toString(), startTime = start, endTime = end, teacher = "Teacher", location = "North building 201")))

    private fun state(courses: List<Course> = emptyList(), language: Language = Language.EN): AppState {
        val semester = Semester("empty-widget-semester", "Autumn", "2026-09-01", "2026-12-31", 18, emptyList())
        val plan = Plan("empty-widget-plan", semester.id, "Plan", courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id, Settings(language = language, holidaysEnabled = false))
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun withSystemFontScale(scale: Float, block: () -> Unit) {
        val previousSetting = shell("settings get system font_scale").trim()
        val previousScale = context.resources.configuration.fontScale
        try {
            shell("settings put system font_scale $scale")
            waitForFontScale(scale)
            block()
        } finally {
            if (previousSetting == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale ${requireNotNull(previousSetting.toFloatOrNull())}")
            waitForFontScale(previousScale)
        }
    }

    private fun waitForFontScale(scale: Float) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            instrumentation.waitForIdleSync()
            if (kotlin.math.abs(context.resources.configuration.fontScale - scale) < .001f) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Application resources must receive the system font setting", scale,
            context.resources.configuration.fontScale, .001f)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }
}
