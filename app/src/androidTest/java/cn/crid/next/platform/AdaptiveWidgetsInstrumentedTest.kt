package cn.crid.next.platform

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Parcel
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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

/** Real framework collection inflation, viewport checks and nested collection click dispatch. */
class AdaptiveWidgetsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val today = LocalDate.of(2026, 9, 23)
    private val now = LocalTime.of(10, 0)
    private val smallToday = SizeF(180f, 160f)
    private val wideToday = SizeF(360f, 180f)
    private val tallToday = SizeF(480f, 400f)
    private val smallWeek = SizeF(245f, 280f)
    private val wideWeek = SizeF(480f, 400f)

    @Test fun nearSquareTodayHostsHaveCenteredSquareBackgroundsAndVisibleCourseEssentials() = onMain {
        val hosts = listOf(SizeF(180f, 160f), SizeF(160f, 180f), SizeF(200f, 180f),
            SizeF(140f, 140f), SizeF(180f, 180f))
        for (dark in listOf(false, true)) for (size in hosts) {
            val frame = inflate(views(size, dark = dark), size)
            assertSurfaceShape(frame, size, square = true)
            val list = visibleViewport(frame)
            val card = requireNotNull(list.getChildAt(0)).findViewById<View>(R.id.widget_course_body)
            for (id in listOf(R.id.widget_course_time, R.id.widget_course_name, R.id.widget_course_location)) {
                val text = card.findViewById<TextView>(id)
                assertFullyVisible(list, text, allowEllipsis = id == R.id.widget_course_location)
            }
            capture(frame, "square-${size.width.toInt()}x${size.height.toInt()}-${theme(dark)}")
        }
    }

    @Test fun reportedSquareSizesAndReapplyKeepShapesAndRestoreWideAndTallLayouts() = onMain {
        val squares = listOf(SizeF(180f, 160f), SizeF(160f, 180f), SizeF(200f, 180f),
            SizeF(140f, 140f), SizeF(180f, 180f))
        val tall = SizeF(180f, 360f)
        val path = squares + listOf(wideToday, tall, squares.first())
        val options = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, ArrayList(path.distinct()))
        }
        val selected = CourseWidgets.responsiveSizes(options, weekly = false)
        assertTrue("Reported near-square host dimensions must be preserved: $selected", selected.containsAll(squares))
        assertTrue(selected.size <= 16)
        val frame = inflate(views(path.first()), path.first())
        val host = AppWidgetHostView(context)
        measure(host, path.first())
        host.updateAppWidget(RemoteViews(selected.associateWith { views(it) }))
        for (size in path) {
            views(size).reapply(context, frame)
            measure(frame.parent as View, size)
            measure(host, size)
            host.requestLayout()
            measure(host, size)
            val current = requireNotNull(host.findViewById<View>(R.id.widget_frame))
            val expectedNames = when (size) {
                wideToday -> listOf("Next course", "Later course")
                tall -> listOf("Finished course", "Next course", "Later course")
                else -> listOf("Next course")
            }
            for ((mode, rendered) in listOf("reapply" to frame, "responsive-map" to current)) {
                assertSurfaceShape(rendered, size, square = size in squares)
                assertEquals("$mode retained wrong content at $size", expectedNames, courseNames(allRows(rendered)))
                capture(rendered, "square-$mode-${size.width.toInt()}x${size.height.toInt()}")
            }
        }
        val week = inflate(views(smallWeek, weekly = true), smallWeek)
        assertSurfaceShape(week, smallWeek, square = false)
    }

    @Test fun narrowFourByTwoHostsRetainAReadableSingleColumn() = onMain {
        for (size in listOf(SizeF(245f, 140f), SizeF(280f, 160f))) {
            val root = inflate(views(size), size)
            assertEquals(listOf("Next course"), courseNames(allRows(root)))
            assertTrue(allRows(root).none { it.findViewById<View>(R.id.widget_columns) != null })
            capture(root, "today-${size.width.toInt()}x${size.height.toInt()}-narrow-host")
        }
    }

    @Test fun fourByTwoTodayShowsTwoCoursesWithReadableTitlesAcrossCommonHostSizes() = onMain {
        val normal = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 1f })
        val clippedNames = mutableListOf<String>()
        for (language in listOf(Language.ZH_CN, Language.EN)) {
            val names = if (language == Language.ZH_CN) listOf("现代教育技术与课堂实践", "量子物理导论与实验方法")
                else listOf("Advanced mathematics", "Modern education theory")
            val state = todayState().let { original -> original.copy(settings = original.settings.copy(language = language),
                plans = original.plans.map { plan -> plan.copy(courses = plan.courses.map { course -> when (course.id) {
                    "next" -> course.copy(name = names[0])
                    "later" -> course.copy(name = names[1])
                    else -> course
                } }) }) }
            for (size in listOf(SizeF(300f, 140f), SizeF(320f, 140f), SizeF(300f, 160f), SizeF(320f, 160f), SizeF(300f, 180f), wideToday)) {
                for (dark in listOf(false, true)) {
                    val root = inflate(views(size, state = state, dark = dark, renderContext = normal), size, normal)
                    capture(root, "today-${size.width.toInt()}x${size.height.toInt()}-${language.name.lowercase()}-${theme(dark)}-dense")
                    val title = root.findViewById<TextView>(R.id.widget_title)
                    assertEquals(20f, title.textSize / normal.resources.displayMetrics.scaledDensity, .01f)
                    assertTrue((0 until title.layout.lineCount).all { title.layout.getEllipsisCount(it) == 0 })
                    val list = visibleViewport(root)
                    val pair = requireNotNull(list.getChildAt(0))
                    assertColumns(pair)
                    assertEquals(names, courseNames(listOf(pair)))
                    for (columnId in listOf(R.id.widget_column_first, R.id.widget_column_second)) {
                        val column = pair.findViewById<ViewGroup>(columnId)
                        val time = column.findViewById<TextView>(R.id.widget_course_time)
                        val name = column.findViewById<TextView>(R.id.widget_course_name)
                        assertFullyVisible(list, time)
                        assertFullyVisible(list, name, allowEllipsis = true)
                        if (size.height >= 160f && (0 until name.layout.lineCount).any { name.layout.getEllipsisCount(it) != 0 }) {
                            clippedNames += "$size/${language.name}/${theme(dark)}: ${name.text}"
                        }
                        assertTrue("Long course names should use two or three readable lines", name.layout.lineCount in 2..3)
                        val body = column.getChildAt(0)
                        val bounds = Rect(0, 0, body.width, body.height)
                        list.offsetDescendantRectToMyCoords(body, bounds)
                        assertTrue("The full card must fit at $size: $bounds / ${list.height}", bounds.bottom <= list.height)
                        val content = body.findViewById<View>(R.id.widget_course_content)
                        assertEquals(((if (size.height < 160f) 6 else 8) * normal.resources.displayMetrics.density).toInt(), content.paddingTop)
                        assertEquals((12 * normal.resources.displayMetrics.density).toInt(), content.paddingLeft)
                        assertFullyVisible(list, column.findViewById(R.id.widget_course_location))
                        if (size.height >= 180f) {
                            assertFullyVisible(list, column.findViewById(R.id.widget_course_teacher))
                            assertTrue(column.findViewById<View>(R.id.widget_course_location).bottom <=
                                column.findViewById<View>(R.id.widget_course_teacher).top)
                        } else assertEquals(View.GONE, column.findViewById<View>(R.id.widget_course_teacher).visibility)
                    }
                }
            }
        }
        assertTrue("Course names are ellipsized: $clippedNames", clippedNames.isEmpty())
    }

    @Test fun twoHundredPercentFontAtFourByTwoShowsACompleteResizePromptThenRecovers() = onMain {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        for (size in listOf(SizeF(300f, 140f), SizeF(320f, 160f), wideToday)) {
            val root = inflate(views(size, renderContext = large), size, large)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
            val prompt = root.findViewById<TextView>(R.id.widget_empty)
            assertEquals(View.VISIBLE, prompt.visibility)
            assertTrue(prompt.text.isNotBlank())
            assertTrue("Resize guidance must fit at 200% font", prompt.layout.getLineBottom(prompt.layout.lineCount - 1) <=
                prompt.height - prompt.compoundPaddingTop - prompt.compoundPaddingBottom)
            capture(root, "today-${size.width.toInt()}x${size.height.toInt()}-font2-resize")
            val supported = SizeF(size.width, 280f)
            views(supported, renderContext = large).reapply(large, root)
            measure(root.parent as View, supported)
            val list = visibleViewport(root)
            assertEquals(View.VISIBLE, list.visibility)
            assertTrue(allRows(root).none { it.findViewById<View>(R.id.widget_columns) != null })
            val first = requireNotNull(list.getChildAt(0))
            listOf(R.id.widget_course_time, R.id.widget_course_name).forEach { id ->
                assertFullyVisible(list, first.findViewById(id))
            }
        }
    }

    @Test fun compactCardsFillTheActualHostBodyAndOneRemainingCourseUsesItsFullWidth() = onMain {
        val sizes = arrayListOf(SizeF(320f, 160f), SizeF(320f, 170f), SizeF(320f, 190f))
        val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) }
        val responsive = RemoteViews(CourseWidgets.responsiveSizes(options, weekly = false).associateWith { views(it) })
        val host = AppWidgetHostView(context)
        measure(host, sizes.first())
        host.updateAppWidget(responsive)
        for (size in sizes) {
            measure(host, size)
            host.requestLayout()
            measure(host, size)
            val root = requireNotNull(host.findViewById<View>(R.id.widget_root))
            val viewport = root.findViewById<ViewGroup>(R.id.widget_compact)
            assertEquals(View.VISIBLE, viewport.visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
            val cards = listOf(R.id.widget_course_0, R.id.widget_course_1).map { viewport.findViewById<View>(it) }
            assertEquals("Two compact cards must be equally tall", cards[0].height, cards[1].height)
            cards.forEach { card ->
                val bounds = Rect(0, 0, card.width, card.height)
                viewport.offsetDescendantRectToMyCoords(card, bounds)
                assertEquals("Card starts at the body top", 0, bounds.top)
                assertEquals("Card follows the actual host height, including intermediate sizes", viewport.height, bounds.bottom)
            }
            capture(root, "today-filled-map-${size.width.toInt()}x${size.height.toInt()}")
        }
        val singleState = todayState().let { state -> state.copy(plans = state.plans.map { plan ->
            plan.copy(courses = plan.courses.filterNot { it.id == "later" })
        }) }
        val size = SizeF(320f, 160f)
        val root = inflate(views(size, state = singleState), size)
        val viewport = root.findViewById<ViewGroup>(R.id.widget_compact)
        val card = viewport.findViewById<View>(R.id.widget_course_0)
        assertEquals(viewport.width, card.width)
        assertEquals(viewport.height, card.height)
        assertEquals(View.GONE, viewport.findViewById<View>(R.id.widget_column_second).visibility)
        assertEquals(listOf("Next course"), courseNames(allRows(root)))
        capture(root, "today-filled-single-320x160")
    }

    @Test fun compactWideTodayShowsTwoUpcomingCoursesFullyOnTheFirstScreen() = onMain {
        for (dark in listOf(false, true)) {
            val narrow = inflate(views(smallToday, dark = dark), smallToday)
            assertEquals(listOf("Next course"), courseNames(allRows(narrow)))
            assertNull(allRows(narrow).single().findViewById<View>(R.id.widget_columns))

            val root = inflate(views(wideToday, dark = dark), wideToday)
            val list = visibleViewport(root)
            val pair = requireNotNull(list.getChildAt(0))
            assertColumns(pair)
            assertEquals(listOf("Next course", "Later course"), courseNames(listOf(pair)))
            assertFalse(texts(pair).contains("Finished course"))
            for (columnId in listOf(R.id.widget_column_first, R.id.widget_column_second)) {
                val column = pair.findViewById<ViewGroup>(columnId)
                assertFullyVisible(list, column.findViewById(R.id.widget_course_time))
                assertFullyVisible(list, column.findViewById(R.id.widget_course_name))
            }
            capture(root, "today-360x180-${theme(dark)}")
        }
    }

    @Test fun compactWideTodayKeepsTwoLineNamesAndFullCourseDetailsInsideTheFirstScreen() = onMain {
        val names = listOf("Advanced mathematics", "Modern education theory")
        val state = todayState().let { original -> original.copy(plans = original.plans.map { plan ->
            plan.copy(courses = plan.courses.map { course -> when (course.id) {
                "next" -> course.copy(name = names[0])
                "later" -> course.copy(name = names[1])
                else -> course
            } })
        }) }
        val normal = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 1f })
        for (dark in listOf(false, true)) {
            val root = inflate(views(wideToday, state = state, dark = dark, renderContext = normal), wideToday, normal)
            capture(root, "today-360x180-two-line-details-${theme(dark)}")
            val list = visibleViewport(root)
            val pair = requireNotNull(list.getChildAt(0))
            assertColumns(pair)
            assertEquals(names, courseNames(listOf(pair)))
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_footer).visibility)
            listOf(R.id.widget_column_first to R.id.widget_course_0,
                R.id.widget_column_second to R.id.widget_course_1).forEachIndexed { index, (columnId, courseId) ->
                val column = pair.findViewById<ViewGroup>(columnId)
                val card = column.findViewById<View>(courseId)
                val time = card.findViewById<TextView>(R.id.widget_course_time)
                val name = card.findViewById<TextView>(R.id.widget_course_name)
                val teacher = card.findViewById<TextView>(R.id.widget_course_teacher)
                val location = card.findViewById<TextView>(R.id.widget_course_location)
                assertEquals(if (index == 0) "11:00–12:00" else "16:00–17:00", time.text.toString())
                assertEquals(names[index], name.text.toString())
                assertEquals("Both long names must exercise two rendered lines", 2, requireNotNull(name.layout).lineCount)
                assertEquals("Teacher", teacher.text.toString())
                assertEquals("North building 201", location.text.toString())
                assertTrue(location.bottom <= teacher.top)
                listOf(time, name, teacher, location).forEach { assertFullyVisible(list, it) }
                val bounds = Rect(0, 0, card.width, card.height)
                list.offsetDescendantRectToMyCoords(card, bounds)
                assertTrue("Full course card starts outside viewport: $bounds", bounds.top >= list.paddingTop)
                assertTrue("Course card bottom corners are clipped: $bounds / ${list.height}",
                    bounds.bottom <= list.height - list.paddingBottom)
            }
        }
    }

    @Test fun tallTodayShowsTheWholeDayAndKeepsTheUnpairedFinalCourseOnce() = onMain {
        for (dark in listOf(false, true)) {
            val root = inflate(views(tallToday, dark = dark), tallToday)
            val rows = allRows(root)
            assertEquals(2, rows.size)
            rows.forEach(::assertColumns)
            assertEquals(listOf("Finished course", "Next course", "Later course"), courseNames(rows))
            assertEquals(0, rows.last().findViewById<ViewGroup>(R.id.widget_column_second).childCount)
            val list = visibleViewport(root)
            assertEquals(3, descendants(list).filterIsInstance<TextView>().count { it.id == R.id.widget_course_name })
            descendants(list).filterIsInstance<TextView>().filter { it.id == R.id.widget_course_name }
                .forEach { assertFullyVisible(list, it) }
            assertLocationBeforeTeacher(rows)
            capture(root, "today-480x400-${theme(dark)}")
        }
        val narrowTall = SizeF(180f, 360f)
        val root = inflate(views(narrowTall), narrowTall)
        assertEquals(listOf("Finished course", "Next course", "Later course"), courseNames(allRows(root)))
        assertTrue(allRows(root).none { it.findViewById<View>(R.id.widget_columns) != null })
    }

    @Test fun wideWeekUsesTwoDayColumnsWithReadableFirstCardsAndUniqueDates() = onMain {
        for (size in listOf(wideWeek, SizeF(600f, 480f))) for (dark in listOf(false, true)) {
            val root = inflate(views(size, weekly = true, dark = dark), size)
            val rows = allRows(root)
            assertEquals(4, rows.size)
            rows.forEach(::assertColumns)
            assertTrue(rows.none { it.findViewById<View>(R.id.widget_week_grid) != null })
            assertEquals(dayNames, courseNames(rows))
            val headers = rows.flatMap(::descendants).filter { it.id in dayIds }
            assertEquals(7, headers.size)
            assertEquals(dayIds.toSet(), headers.map { it.id }.toSet())
            assertLocationBeforeTeacher(rows)
            val list = visibleViewport(root)
            val firstPair = requireNotNull(list.getChildAt(0))
            assertEquals(dayNames.take(2), courseNames(listOf(firstPair)))
            for (columnId in listOf(R.id.widget_column_first, R.id.widget_column_second)) {
                val column = firstPair.findViewById<ViewGroup>(columnId)
                for (id in listOf(R.id.widget_course_time, R.id.widget_course_name,
                    R.id.widget_course_teacher, R.id.widget_course_location)) {
                    assertFullyVisible(list, column.findViewById(id))
                }
            }
            capture(root, "week-${size.width.toInt()}x${size.height.toInt()}-${theme(dark)}")
        }
    }

    @Test fun busyWeekDaysDeclareHiddenClassesWithoutReducingTheWeekTotal() = onMain {
        val state = weekState(mondayCount = 5)
        val localized = PlatformText.context(context, Language.EN)
        for ((size, shown) in listOf(wideWeek to 3, SizeF(600f, 480f) to 4)) {
            val root = inflate(views(size, weekly = true, state = state), size)
            val firstPair = allRows(root).first()
            val monday = firstPair.findViewById<ViewGroup>(R.id.widget_column_first)
            val tuesday = firstPair.findViewById<ViewGroup>(R.id.widget_column_second)
            assertEquals(List(shown) { "Monday course ${it + 1}" }, courseNames(listOf(monday)))
            val overflow = monday.findViewById<TextView>(R.id.widget_day_more_0)
            assertEquals(localized.getString(R.string.platform_widget_more_day_classes, 5 - shown), overflow.text.toString())
            assertTrue(overflow.hasOnClickListeners())
            assertEquals(listOf("Tuesday course"), courseNames(listOf(tuesday)))
            assertNull(tuesday.findViewById<View>(R.id.widget_day_more_1))
            val footer = root.findViewById<TextView>(R.id.widget_footer)
            assertEquals(View.VISIBLE, footer.visibility)
            assertEquals(localized.getString(R.string.platform_widget_week_count, 11, 11), footer.text.toString())
        }
        val emptyDayState = weekState().let { it.copy(plans = it.plans.map { plan ->
            plan.copy(courses = plan.courses.filterNot { course -> course.id == "day-1" })
        }) }
        val root = inflate(views(wideWeek, weekly = true, state = emptyDayState), wideWeek)
        val freeDay = allRows(root).first().findViewById<TextView>(R.id.widget_day_1)
        assertTrue(freeDay.text.toString().contains(localized.getString(R.string.platform_widget_day_off)))
    }

    @Test fun doubledFontKeepsSingleColumnsUntilTwoReadableColumnsFit() = onMain {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        for ((size, weekly) in listOf(SizeF(360f, 280f) to false, SizeF(480f, 560f) to true)) {
            val root = inflate(views(size, weekly, renderContext = large), size, large)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_list).visibility)
            val rows = allRows(root)
            assertTrue(rows.none { it.findViewById<View>(R.id.widget_columns) != null })
            assertEquals(if (weekly) dayNames else listOf("Next course"), courseNames(rows))
            rows.flatMap(::descendants).filterIsInstance<TextView>()
                .filter { it.id == R.id.widget_course_name || it.id == R.id.widget_course_time }
                .forEach { assertTrue(it.measuredHeight > 0) }
        }
    }

    @Test fun responsiveMapAndReapplyRestoreSingleColumnsWithoutStalePairedCards() = onMain {
        for (weekly in listOf(false, true)) {
            val small = if (weekly) smallWeek else smallToday
            val wide = if (weekly) wideWeek else wideToday
            val root = inflate(views(small, weekly), small)
            val expectedSmall = courseNames(allRows(root))
            views(wide, weekly).reapply(context, root)
            measure(root.parent as View, wide)
            assertTrue(allRows(root).any { it.findViewById<View>(R.id.widget_columns) != null })
            views(small, weekly).reapply(context, root)
            measure(root.parent as View, small)
            assertEquals(expectedSmall, courseNames(allRows(root)))
            assertTrue(allRows(root).none { it.findViewById<View>(R.id.widget_columns) != null })

            val host = AppWidgetHostView(context)
            measure(host, small)
            val responsive = RemoteViews(linkedMapOf(small to views(small, weekly), wide to views(wide, weekly)))
            host.updateAppWidget(responsive)
            for ((size, paired) in listOf(small to false, wide to true, small to false)) {
                measure(host, size)
                // Layout may reinflate a size variant; measure its new child before viewport inspection.
                host.requestLayout()
                measure(host, size)
                val current = requireNotNull(host.findViewById<View>(R.id.widget_root))
                val rows = allRows(current)
                assertEquals("Framework map selected the wrong column count at $size", paired,
                    rows.any { it.findViewById<View>(R.id.widget_columns) != null })
                assertEquals(if (paired && !weekly) listOf("Next course", "Later course") else expectedSmall,
                    courseNames(rows))
            }
        }
    }

    @Test fun reportedHostSizesPreserveNarrowFullDayAndWideUpcomingLayoutsInTheRealMap() = onMain {
        val narrowTall = SizeF(160f, 500f)
        val reportedSizes = arrayListOf(SizeF(100f, 100f), SizeF(140f, 140f), narrowTall,
            wideToday, SizeF(1200f, 140f), SizeF(900f, 700f))
        for (reported in listOf(reportedSizes, ArrayList(reportedSizes +
            List(10) { index -> SizeF(245f + index * 40, 280f + index * 30) }))) {
            val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, reported) }
            val selected = CourseWidgets.responsiveSizes(options, weekly = false)
            val responsive = RemoteViews(selected.associateWith { views(it) })
            val host = AppWidgetHostView(context)
            measure(host, narrowTall)
            host.updateAppWidget(responsive)
            for ((size, expectedNames) in listOf(
                narrowTall to listOf("Finished course", "Next course", "Later course"),
                wideToday to listOf("Next course", "Later course"),
            )) {
                measure(host, size)
                host.requestLayout()
                measure(host, size)
                val root = requireNotNull(host.findViewById<View>(R.id.widget_root))
                capture(root, "today-reported-map-${reported.size}-${size.width.toInt()}x${size.height.toInt()}")
                assertEquals("Responsive map omitted courses for a reported host size $size; selected $selected",
                    expectedNames, courseNames(allRows(root)))
                val list = visibleViewport(root)
                assertEquals("All expected courses must be present on the first screen at $size",
                    expectedNames, courseNames(listOf(list)))
                descendants(list).filterIsInstance<TextView>().filter { it.id == R.id.widget_course_name }
                    .forEach { assertFullyVisible(list, it) }
                assertEquals("Reported wide layout must select two columns, while narrow remains one", size == wideToday,
                    allRows(root).any { it.findViewById<View>(R.id.widget_columns) != null })
            }
        }
    }

    @Test fun reportedWideSingleColumnWeekKeepsItsDayOverviewAlongsideCourseContent() = onMain {
        val size = SizeF(400f, 360f)
        val options = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(smallWeek, size, wideWeek))
        }
        val sizes = CourseWidgets.responsiveSizes(options, weekly = true)
        val host = AppWidgetHostView(context)
        measure(host, size)
        host.updateAppWidget(RemoteViews(sizes.associateWith { views(it, weekly = true) }))
        measure(host, size)
        host.requestLayout()
        measure(host, size)
        val root = requireNotNull(host.findViewById<View>(R.id.widget_root))
        val rows = allRows(root)
        assertNotNull(rows.first().findViewById<View>(R.id.widget_week_grid))
        assertEquals(dayNames, courseNames(rows))
        assertTrue(rows.none { it.findViewById<View>(R.id.widget_columns) != null })
        capture(root, "week-400x360-responsive-overview")
    }

    @Test fun bothCompactTodayCardsAndBothWeekColumnsDeliverTheirOwnRoutesAndDates() {
        val startingIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(startingIntent).use { scenario ->
            try {
                lateinit var todayRoot: View
                lateinit var weekRoot: View
                scenario.onActivity { activity ->
                    todayRoot = mount(activity, SizeF(300f, 140f), weekly = false, id = 970281)
                    weekRoot = mount(activity, wideWeek, weekly = true, id = 970282)
                }
                for (cardId in listOf(R.id.widget_course_0, R.id.widget_course_1)) {
                    scenario.onActivity {
                        val card = attachedFirstRow(todayRoot).findViewById<View>(cardId)
                        assertTrue(card.hasOnClickListeners())
                        assertTrue(card.performClick())
                    }
                    awaitIntent(scenario, "today", today)
                }
                for ((headerId, date) in listOf(R.id.widget_day_0 to today.minusDays(2), R.id.widget_day_1 to today.minusDays(1))) {
                    scenario.onActivity {
                        val day = attachedFirstRow(weekRoot).findViewById<View>(headerId)
                        assertTrue(day.hasOnClickListeners())
                        assertTrue(day.performClick())
                    }
                    awaitIntent(scenario, "timetable", date)
                }
                for ((cardId, date) in listOf(R.id.widget_course_0 to today.minusDays(2), R.id.widget_course_1 to today.minusDays(1))) {
                    scenario.onActivity {
                        val card = attachedFirstRow(weekRoot).findViewById<View>(cardId)
                        assertTrue(card.hasOnClickListeners())
                        assertTrue(card.performClick())
                    }
                    awaitIntent(scenario, "timetable", date)
                }
            } finally {
                scenario.onActivity { it.intent = Intent(startingIntent) }
            }
        }
    }

    @Test fun crowdedMixedSingleAndDoubleColumnVariantsStayWithinTheBinderBudget() = onMain {
        val measured = mutableMapOf<String, Int>()
        for (chinese in listOf(false, true)) {
            val crowded = weekState().let { original -> original.copy(plans = original.plans.map { plan ->
                plan.copy(courses = List(400) { index -> course("crowded-$index",
                    if (chinese) "课程 $index " + "现代教育技术".repeat(80) else "Course $index " + "long title ".repeat(80),
                    today.minusDays(2).plusDays((index % 7).toLong()), "11:00", "12:00",
                    teacher = (if (chinese) "教师姓名" else "Teacher ").repeat(60),
                    location = (if (chinese) "木铎楼教室" else "Location ").repeat(60)) })
            }) }
            val candidateSets = mapOf(
                "profiles" to listOf(SizeF(100f, 100f), SizeF(140f, 140f), SizeF(180f, 180f), SizeF(140f, 240f),
                    SizeF(140f, 320f), SizeF(360f, 140f), SizeF(360f, 180f), SizeF(360f, 240f), SizeF(360f, 320f),
                    SizeF(245f, 280f), SizeF(245f, 320f), SizeF(400f, 360f), SizeF(440f, 280f),
                    SizeF(440f, 320f), SizeF(440f, 480f), SizeF(440f, 640f)),
                "same-profile" to List(8) { index -> SizeF(600f + index * 10, 640f + index * 10) },
            )
            for (weekly in listOf(false, true)) for ((scenario, sizes) in candidateSets) {
                val selected = CourseWidgets.responsiveSizes(Bundle().apply {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, ArrayList(sizes))
                }, weekly)
                val remote = RemoteViews(selected.associateWith { views(it, weekly, state = crowded) })
                val parcel = Parcel.obtain()
                try {
                    remote.writeToParcel(parcel, 0)
                    val key = "${if (weekly) "week" else "today"}-${if (chinese) "zh" else "en"}-$scenario"
                    measured[key] = parcel.dataSize()
                    val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa-v1.14/widgets-adaptive")
                    assertTrue(directory.isDirectory || directory.mkdirs())
                    File(directory, "binder-$key-selected.txt").writeText("candidateVariants=${sizes.size}\n" +
                        "selectedVariants=${selected.size}\nparcelBytes=${parcel.dataSize()}\nlimitBytes=${900 * 1024}\n")
                    parcel.setDataPosition(0)
                    val host = AppWidgetHostView(context)
                    val largeSize = SizeF(600f, 640f)
                    measure(host, largeSize)
                    host.updateAppWidget(RemoteViews.CREATOR.createFromParcel(parcel))
                    measure(host, largeSize)
                    host.requestLayout()
                    measure(host, largeSize)
                    val rendered = requireNotNull(host.findViewById<View>(R.id.widget_root))
                    if (scenario == "same-profile") {
                        assertTrue("Equivalent large hosts must share a rich layout", selected.size <= 2)
                        assertTrue(allRows(rendered).all { it.findViewById<View>(R.id.widget_columns) != null })
                        assertEquals(if (weekly) 35 else 36, courseNames(allRows(rendered)).size)
                    }
                } finally { parcel.recycle() }
            }
        }
        assertTrue("Mixed widget parcel sizes: $measured", measured.values.all { it < 900 * 1024 })
    }

    private val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").map { "$it course" }
    private val dayIds = listOf(R.id.widget_day_0, R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3,
        R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6)

    private fun views(size: SizeF, weekly: Boolean = false, state: AppState = if (weekly) weekState() else todayState(),
        dark: Boolean = false, renderContext: Context = context, id: Int = 970280): RemoteViews = CourseWidgets.layout(
        renderContext, PlatformText.context(renderContext, state.settings.language), id, weekly, size, state,
        HolidayCalendar(), dark, today, now,
    )

    private fun inflate(remote: RemoteViews, size: SizeF, renderContext: Context = context): View {
        val host = AppWidgetHostView(renderContext)
        val root = remote.apply(renderContext, host)
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        measure(host, size)
        return root
    }

    private fun mount(activity: MainActivity, size: SizeF, weekly: Boolean, id: Int): View {
        val root = inflate(views(size, weekly, renderContext = activity, id = id), size, activity)
        val host = root.parent as AppWidgetHostView
        val density = activity.resources.displayMetrics.density
        activity.addContentView(host, FrameLayout.LayoutParams((size.width * density).toInt(), (size.height * density).toInt()))
        measure(host, size)
        return root
    }

    private fun measure(view: View, size: SizeF) {
        val density = view.resources.displayMetrics.density
        val width = (size.width * density).toInt()
        val height = (size.height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun visibleViewport(root: View): ViewGroup {
        val compact = root.findViewById<ViewGroup>(R.id.widget_compact)
        return if (compact.visibility == View.VISIBLE) compact else root.findViewById(R.id.widget_list)
    }

    private fun allRows(root: View): List<View> {
        val list = visibleViewport(root)
        if (list !is ListView) return List(list.childCount) { list.getChildAt(it) }
        val adapter = requireNotNull(list.adapter)
        return List(adapter.count) { index -> adapter.getView(index, null, list).also {
            it.measure(View.MeasureSpec.makeMeasureSpec(list.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
        } }
    }

    private fun attachedFirstRow(root: View): View {
        val list = visibleViewport(root)
        return requireNotNull(list.getChildAt(0)).also {
            assertTrue(it.isAttachedToWindow)
            assertSame(list, it.parent)
        }
    }

    private fun assertColumns(row: View) {
        val columns = requireNotNull(row.findViewById<LinearLayout>(R.id.widget_columns))
        assertEquals(LinearLayout.HORIZONTAL, columns.orientation)
        for (id in listOf(R.id.widget_column_first, R.id.widget_column_second)) {
            val column = columns.findViewById<LinearLayout>(id)
            assertEquals(LinearLayout.VERTICAL, column.orientation)
            assertTrue(column.width > 0)
        }
    }

    private fun assertSurfaceShape(frame: View, size: SizeF, square: Boolean) {
        assertEquals(R.id.widget_frame, frame.id)
        val surface = frame.findViewById<View>(R.id.widget_root)
        val density = frame.resources.displayMetrics.density
        val expectedWidth = ((if (square) minOf(size.width, size.height) else size.width) * density).toInt()
        val expectedHeight = ((if (square) minOf(size.width, size.height) else size.height) * density).toInt()
        assertEquals("Visible background width at $size", expectedWidth, surface.width)
        assertEquals("Visible background height at $size", expectedHeight, surface.height)
        assertTrue("Background must be centered horizontally at $size", kotlin.math.abs(surface.left - (frame.width - surface.width) / 2) <= 1)
        assertTrue("Background must be centered vertically at $size", kotlin.math.abs(surface.top - (frame.height - surface.height) / 2) <= 1)
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            frame.draw(Canvas(bitmap))
            // View assigns the background drawable's bounds lazily when it is drawn.
            assertEquals(Rect(0, 0, surface.width, surface.height), requireNotNull(surface.background).bounds)
            assertEquals("Rounded background corner must remain transparent", 0,
                Color.alpha(bitmap.getPixel(surface.left, surface.top)))
            assertEquals("The middle of the background edge must be opaque", 255,
                Color.alpha(bitmap.getPixel(surface.left + 1, surface.top + surface.height / 2)))
            if (surface.left > 0) assertEquals("Space outside the square must stay transparent", 0,
                Color.alpha(bitmap.getPixel(surface.left - 1, surface.top + surface.height / 2)))
            if (surface.top > 0) assertEquals("Space outside the square must stay transparent", 0,
                Color.alpha(bitmap.getPixel(surface.left + surface.width / 2, surface.top - 1)))
        } finally { bitmap.recycle() }
    }

    private fun assertLocationBeforeTeacher(rows: List<View>) {
        rows.flatMap(::descendants).filterIsInstance<TextView>().filter { it.id == R.id.widget_course_teacher }.forEach { teacher ->
            val location = (teacher.parent as ViewGroup).findViewById<TextView>(R.id.widget_course_location)
            assertEquals(View.VISIBLE, teacher.visibility)
            assertEquals(View.VISIBLE, location.visibility)
            assertTrue(location.bottom <= teacher.top)
            assertEquals("Teacher", teacher.text.toString())
            assertEquals("North building 201", location.text.toString())
        }
    }

    private fun assertFullyVisible(list: ViewGroup, text: TextView, allowEllipsis: Boolean = false) {
        assertEquals(View.VISIBLE, text.visibility)
        val bounds = Rect(0, 0, text.width, text.height)
        list.offsetDescendantRectToMyCoords(text, bounds)
        assertTrue("Text starts outside viewport: ${text.text}, $bounds", bounds.left >= 0 && bounds.top >= 0)
        assertTrue("Text ends outside viewport: ${text.text}, $bounds / ${list.width}×${list.height}",
            bounds.right <= list.width && bounds.bottom <= list.height)
        val layout = requireNotNull(text.layout)
        assertTrue(layout.lineCount > 0)
        if (!allowEllipsis) assertTrue("Text is ellipsized: ${text.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
    }

    private fun descendants(view: View): List<View> = buildList {
        if (view.visibility != View.VISIBLE) return@buildList
        add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(descendants(view.getChildAt(it))) }
    }

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun courseNames(rows: List<View>) = rows.flatMap(::descendants).filterIsInstance<TextView>()
        .filter { it.id == R.id.widget_course_name }.map { it.text.toString() }
    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private fun capture(root: View, name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa-v1.14/widgets-adaptive")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val list = visibleViewport(root)
            File(directory, "$name.txt").writeText("pixels=${root.width}x${root.height}\n" +
                "fontScale=${root.resources.configuration.fontScale}\ncollectionRows=${if (list is ListView) list.adapter.count else list.childCount}\n" +
                "headingHeightPx=${root.findViewById<View>(R.id.widget_heading).height};listHeightPx=${list.height}\n" +
                "visibleChildren=${list.childCount}\nvisibleText=${texts(list).joinToString(" | ")}\n" +
                descendants(list).filterIsInstance<TextView>().filter { it.id == R.id.widget_course_name }.joinToString("\n") {
                    "name=${it.text};widthPx=${it.width};heightPx=${it.height};lineHeightPx=${it.lineHeight};" +
                        "cardHeightPx=${(it.parent.parent as View).height};lines=${it.layout.lineCount};ellipsis=" +
                        (0 until it.layout.lineCount).sumOf { line -> it.layout.getEllipsisCount(line) }
                } + "\n")
        } finally { bitmap.recycle() }
    }

    private fun awaitIntent(scenario: ActivityScenario<MainActivity>, route: String, date: LocalDate) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actualRoute: String? = null
        var actualDate: String? = null
        do {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                actualRoute = it.intent.getStringExtra("route")
                actualDate = it.intent.getStringExtra("date")
            }
            if (actualRoute == route && actualDate == date.toString()) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("PendingIntent route", route, actualRoute)
        assertEquals("PendingIntent date", date.toString(), actualDate)
    }

    private fun course(id: String, name: String, date: LocalDate, start: String, end: String,
        teacher: String = "Teacher", location: String = "North building 201") = Course(id, name,
        lessons = listOf(Lesson(date = date.toString(), startTime = start, endTime = end, teacher = teacher, location = location)))

    private fun todayState() = state(listOf(
        course("finished", "Finished course", today, "08:00", "09:00"),
        course("next", "Next course", today, "11:00", "12:00"),
        course("later", "Later course", today, "16:00", "17:00"),
    ))

    private fun weekState(mondayCount: Int = 1) = state(buildList {
        repeat(7) { offset ->
            val date = today.minusDays(2).plusDays(offset.toLong())
            if (offset == 0 && mondayCount > 1) repeat(mondayCount) { index ->
                add(course("monday-$index", "Monday course ${index + 1}", date,
                    "%02d:00".format(8 + index * 2), "%02d:00".format(9 + index * 2)))
            } else add(course("day-$offset", dayNames[offset], date, "11:00", "12:00"))
        }
    })

    private fun state(courses: List<Course>): AppState {
        val semester = Semester("adaptive-widget-semester", "Autumn", "2026-09-01", "2026-12-31", 18, emptyList())
        val plan = Plan("adaptive-widget-plan", semester.id, "Test plan", courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false))
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
