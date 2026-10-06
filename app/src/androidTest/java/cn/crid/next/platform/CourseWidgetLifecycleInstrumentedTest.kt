package cn.crid.next.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ListView
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
import java.time.LocalDate
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CourseWidgetLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(CourseWidgets.PREFS, Context.MODE_PRIVATE)
    private val ids = listOf(970171, 970172, 970173, 970174, 970175)
    private val previousThemes = mutableMapOf<Int, String?>()
    private val today = LocalDate.of(2026, 9, 23)

    @Before fun savePreferences() {
        ids.forEach { previousThemes[it] = preferences.getString("theme:$it", null) }
    }

    @After fun restorePreferences() {
        preferences.edit().apply {
            previousThemes.forEach { (id, theme) ->
                if (theme == null) remove("theme:$id") else putString("theme:$id", theme)
            }
        }.commit()
    }

    @Test fun widgetIntentsUseAnExplicitActivityAndIndependentRouteAndDateIdentities() {
        val todayIntent = CourseWidgets.widgetLaunchIntent(context, "today", today)
        val tomorrowIntent = CourseWidgets.widgetLaunchIntent(context, "today", today.plusDays(1))
        val timetableIntent = CourseWidgets.widgetLaunchIntent(context, "timetable", today)
        val headingIntent = CourseWidgets.widgetLaunchIntent(context, "timetable")
        for (intent in listOf(todayIntent, tomorrowIntent, timetableIntent, headingIntent)) {
            assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
            assertNotNull(intent.data)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        assertEquals("today", todayIntent.getStringExtra("route"))
        assertEquals(today.toString(), todayIntent.getStringExtra("date"))
        assertEquals("timetable", timetableIntent.getStringExtra("route"))
        assertFalse(headingIntent.hasExtra("date"))
        assertFalse(todayIntent.filterEquals(tomorrowIntent))
        assertFalse(todayIntent.filterEquals(timetableIntent))
        assertFalse(timetableIntent.filterEquals(headingIntent))
        assertTrue(todayIntent.filterEquals(CourseWidgets.widgetLaunchIntent(context, "today", today)))
    }

    @Test fun compactCourseCardAndEachWeekdayClickDeliverTheCorrectDateToMainActivity() {
        val startingIntent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(startingIntent).use { scenario ->
            try {
                lateinit var todayRoot: View
                lateinit var weekRoot: View
                scenario.onActivity { activity ->
                    todayRoot = mountWidget(activity, ids[0], weekly = false, SizeF(180f, 160f))
                    weekRoot = mountWidget(activity, ids[1], weekly = true, SizeF(400f, 360f))
                    val card = requireNotNull(todayRoot.findViewById<View>(R.id.widget_course_body))
                    assertTrue(card.isAttachedToWindow)
                    val bounds = android.graphics.Rect(0, 0, card.width, card.height)
                    (todayRoot as ViewGroup).offsetDescendantRectToMyCoords(card, bounds)
                    val downTime = SystemClock.uptimeMillis()
                    for ((action, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 40)) {
                        val event = MotionEvent.obtain(downTime, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        try { assertTrue("The compact course must handle touch", todayRoot.dispatchTouchEvent(event)) }
                        finally { event.recycle() }
                    }
                }
                awaitIntent(scenario, "today", today)

                repeat(7) { offset ->
                    scenario.onActivity {
                        val list = weekRoot.findViewById<ListView>(R.id.widget_list)
                        val row = requireNotNull(list.getChildAt(0)) { "Week overview has no attached row" }
                        assertTrue(row.isAttachedToWindow)
                        assertSame(list, row.parent)
                        val grid = row.findViewById<ViewGroup>(R.id.widget_week_grid)
                        assertEquals(7, grid.childCount)
                        assertEquals(7, (0 until grid.childCount).map { grid.getChildAt(it).id }.distinct().size)
                        val day = grid.getChildAt(offset)
                        assertTrue("Weekday $offset must have a framework click listener", day.hasOnClickListeners())
                        assertTrue("Weekday $offset must open its own date", day.performClick())
                    }
                    awaitIntent(scenario, "timetable", today.minusDays(2).plusDays(offset.toLong()))
                }

                scenario.onActivity { assertTrue(todayRoot.findViewById<View>(R.id.widget_heading).performClick()) }
                awaitIntent(scenario, "today", today)
            } finally {
                // ActivityScenario matches lifecycle events against its original launch identity.
                scenario.onActivity { it.intent = Intent(startingIntent) }
            }
        }
    }

    private fun mountWidget(activity: MainActivity, id: Int, weekly: Boolean, size: SizeF): View {
        val host = AppWidgetHostView(activity)
        val root = CourseWidgets.layout(activity, PlatformText.context(activity, Language.EN), id,
            weekly, size, fixture(), HolidayCalendar(), false, today, LocalTime.of(10, 0)).apply(activity, host)
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val density = activity.resources.displayMetrics.density
        val width = (size.width * density).toInt()
        val height = (size.height * density).toInt()
        activity.addContentView(host, FrameLayout.LayoutParams(width, height))
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        return root
    }

    @Test fun deletingOneWidgetRemovesOnlyItsStoredTheme() {
        preferences.edit().putString("theme:${ids[0]}", "DARK").putString("theme:${ids[1]}", "LIGHT").commit()
        CourseWidgetProvider().onDeleted(context, intArrayOf(ids[0]))
        assertFalse(preferences.contains("theme:${ids[0]}"))
        assertEquals("LIGHT", preferences.getString("theme:${ids[1]}", null))
    }

    @Test fun restoreMovesThemesBeforeUpdatingAndPreservesOtherWidgetInstances() {
        fun assertRestore(oldIds: IntArray, newIds: IntArray, expected: Map<Int, String>) {
            var updatedIds: IntArray? = null
            var updates = 0
            fun assertStoredThemes() {
                expected.forEach { (id, theme) -> assertEquals(theme, preferences.getString("theme:$id", null)) }
                oldIds.filter { it !in newIds }.forEach { assertFalse(preferences.contains("theme:$it")) }
            }
            val provider = object : CourseWidgetProvider() {
                override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
                    updates++
                    updatedIds = ids.copyOf()
                    // The actual callback must already see the entire restored mapping.
                    assertStoredThemes()
                }
            }
            provider.onRestored(context, oldIds, newIds)
            assertEquals(1, updates)
            assertArrayEquals(newIds, updatedIds)
            assertStoredThemes()
        }

        preferences.edit().putString("theme:${ids[0]}", "DARK").remove("theme:${ids[1]}")
            .putString("theme:${ids[4]}", "LIGHT").commit()
        assertRestore(intArrayOf(ids[0], ids[1]), intArrayOf(ids[2], ids[3]),
            mapOf(ids[2] to "DARK", ids[3] to "APP", ids[4] to "LIGHT"))

        // An old ID is also an earlier destination: neither its source value nor the destination may be lost.
        preferences.edit().putString("theme:${ids[0]}", "DARK").putString("theme:${ids[1]}", "LIGHT")
            .putString("theme:${ids[2]}", "APP").commit()
        assertRestore(intArrayOf(ids[0], ids[1]), intArrayOf(ids[1], ids[2]),
            mapOf(ids[1] to "DARK", ids[2] to "LIGHT", ids[4] to "LIGHT"))

        // A launcher may preserve IDs; restoring those IDs must retain their saved modes.
        assertRestore(intArrayOf(ids[1], ids[2]), intArrayOf(ids[1], ids[2]),
            mapOf(ids[1] to "DARK", ids[2] to "LIGHT", ids[4] to "LIGHT"))
    }

    private fun awaitIntent(scenario: ActivityScenario<MainActivity>, route: String, date: LocalDate?) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actualRoute: String? = null
        var actualDate: String? = null
        do {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                actualRoute = it.intent.getStringExtra("route")
                actualDate = it.intent.getStringExtra("date")
            }
            if (actualRoute == route && actualDate == date?.toString()) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("PendingIntent route", route, actualRoute)
        assertEquals("PendingIntent date", date?.toString(), actualDate)
    }

    private fun fixture(): AppState {
        val semester = Semester("widget-click-semester", "Autumn", "2026-09-01", "2026-12-31", 18, emptyList())
        val courses = List(7) { index ->
            Course("widget-click-course-$index", "Course $index", lessons = listOf(Lesson(
                date = today.minusDays(2).plusDays(index.toLong()).toString(), startTime = "11:00", endTime = "12:00",
            )))
        }
        val plan = Plan("widget-click-plan", semester.id, "Test plan", courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false))
    }
}
