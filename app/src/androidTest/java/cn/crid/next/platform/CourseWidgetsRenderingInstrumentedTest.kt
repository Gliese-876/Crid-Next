package cn.crid.next.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.R
import cn.crid.next.core.AppState
import cn.crid.next.core.Course
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.HolidayDay
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

/** Uses framework RemoteViews inflation and collection adapters, including reuse after a resize. */
class CourseWidgetsRenderingInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val today = LocalDate.of(2026, 9, 23)
    private val now = LocalTime.of(10, 0)
    private val calendar = HolidayCalendar()
    private val square = SizeF(180f, 160f)
    private val wide = SizeF(360f, 160f)
    private val tall = SizeF(180f, 360f)
    private val weekSquare = SizeF(400f, 360f)
    private val minimumWeek = SizeF(245f, 280f)

    @Test fun todayLayoutsApplyAndReapplyBetweenTwoByTwoFourByTwoAndTwoByFour() = onMain {
        val state = state()
        val root = apply(views(square, state = state), square)
        assertEquals(View.VISIBLE, visibleViewport(root).visibility)
        assertEquals(1, rows(root).size)
        assertTrue(rows(root).flatMap(::texts).contains("Next course"))

        views(wide, state = state).reapply(context, root)
        measure(root, wide)
        assertEquals(1, rows(root).size)
        assertTrue(rows(root).flatMap(::texts).contains("Next course"))

        views(tall, state = state).reapply(context, root)
        measure(root, tall)
        val list = visibleViewport(root)
        assertEquals(View.VISIBLE, list.visibility)
        val names = collectionRows(list).flatMap(::texts)
        assertTrue(names.containsAll(listOf("Finished course", "Next course", "Later course")))

        views(square, state = state).reapply(context, root)
        measure(root, square)
        assertEquals(1, rows(root).size)
        assertTrue(rows(root).flatMap(::texts).contains("Next course"))
        for (dark in listOf(false, true)) {
            views(square, state = state, dark = dark).reapply(context, root)
            measure(root, square)
            capture(root, "today-180x160dp-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun compactTodayChangesFromCurrentToNextAtTheActualEndBoundary() = onMain {
        val state = state()
        val root = apply(views(square, state = state, time = LocalTime.of(11, 0)), square)
        assertTrue(rows(root).flatMap(::texts).contains("Next course"))
        views(square, state = state, time = LocalTime.of(12, 0)).reapply(context, root)
        measure(root, square)
        assertTrue(rows(root).flatMap(::texts).contains("Later course"))
        assertFalse(rows(root).flatMap(::texts).contains("Next course"))
        views(square, state = state, time = LocalTime.of(17, 0)).reapply(context, root)
        measure(root, square)
        assertEquals(0, rows(root).size)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty_panel).visibility)
        assertEquals(PlatformText.context(context, Language.EN).getString(R.string.platform_classes_finished),
            root.findViewById<TextView>(R.id.widget_empty_caption).text.toString())
    }

    @Test fun twoByTwoTodayKeepsCompleteTimeAndRecognizableNameOnTheFirstScreen() = onMain {
        for (size in listOf(SizeF(140f, 140f), SizeF(180f, 180f))) for (dark in listOf(false, true)) {
            val root = apply(views(size, dark = dark), size)
            val list = visibleViewport(root)
            val visibleCard = requireNotNull(list.getChildAt(0)).findViewById<ViewGroup>(R.id.widget_course_body)
            val time = visibleCard.findViewById<TextView>(R.id.widget_course_time)
            val name = visibleCard.findViewById<TextView>(R.id.widget_course_name)
            assertEquals("11:00–12:00", time.text.toString())
            assertEquals("Next course", name.text.toString())
            for (text in listOf(time, name)) {
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                list.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Core text must start inside the first screen: $bounds", bounds.top >= 0)
                assertTrue("Core text must be completely visible at $size: $bounds / ${list.height}", bounds.bottom <= list.height)
                assertTrue(text.layout.lineCount > 0)
                assertTrue("Core text must not be ellipsized at $size", (0 until text.layout.lineCount).all {
                    text.layout.getEllipsisCount(it) == 0
                })
            }
            if (size.height < 180f) {
                assertEquals(View.GONE, visibleCard.findViewById<View>(R.id.widget_course_teacher).visibility)
                assertEquals(View.VISIBLE, visibleCard.findViewById<View>(R.id.widget_course_location).visibility)
                assertTrue(visibleCard.contentDescription.toString().contains("Teacher"))
                assertTrue(visibleCard.contentDescription.toString().contains("North building 201"))
            }
            capture(root, "today-${size.width.toInt()}x${size.height.toInt()}dp-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun minimumTodayAtDoubleFontKeepsCoreContentOrAnEntireResizePromptVisible() = onMain {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val size = SizeF(140f, 140f)
        val root = apply(views(size, renderContext = large), size, large)
        capture(root, "today-140x140dp-font2-first-screen")
        val list = visibleViewport(root)
        if (list.visibility == View.GONE) {
            val prompt = root.findViewById<TextView>(R.id.widget_empty)
            assertEquals(View.VISIBLE, prompt.visibility)
            assertTrue(prompt.text.isNotBlank())
            assertTrue(prompt.layout.lineCount > 0)
            assertTrue("The entire resize instruction must fit at 200% font",
                prompt.layout.getLineBottom(prompt.layout.lineCount - 1) <=
                    prompt.height - prompt.compoundPaddingTop - prompt.compoundPaddingBottom)
        } else {
            val card = requireNotNull(list.getChildAt(0)).findViewById<ViewGroup>(R.id.widget_course_body)
            for (id in listOf(R.id.widget_course_time, R.id.widget_course_name)) {
                val text = card.findViewById<TextView>(id)
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                list.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Core content must remain on the first screen at 200% font: ${text.text}, $bounds / ${list.height}",
                    bounds.top >= 0 && bounds.bottom <= list.height)
            }
        }
    }

    @Test fun supportedWeekShowsAgendaAndRoomySingleColumnAddsAccessibleDayShortcuts() = onMain {
        val state = state(everyDay = true)
        val root = apply(views(weekSquare, weekly = true, state = state), weekSquare)
        val grid = weekGrid(root)
        assertEquals(View.VISIBLE, grid.visibility)
        assertEquals(7, grid.childCount)
        assertEquals(7, (0 until grid.childCount).map { grid.getChildAt(it).id }.distinct().size)
        repeat(7) { index ->
            val day = grid.getChildAt(index)
            assertNotEquals(View.NO_ID, day.id)
            assertTrue("Day $index must be clickable", day.hasOnClickListeners())
            assertTrue("Day $index has no readable summary", texts(day).any(String::isNotBlank))
            assertTrue("Day $index must retain a 48 dp touch target", day.width >= 48 * context.resources.displayMetrics.density - 1)
        }
        assertEquals(View.VISIBLE, visibleViewport(root).visibility)
        assertEquals(15, rows(root).size)
        assertTrue(rows(root).flatMap(::texts).containsAll(listOf("Monday course", "Sunday course")))

        views(minimumWeek, weekly = true, state = state).reapply(context, root)
        measure(root, minimumWeek)
        assertTrue(rows(root).none { it.findViewById<View>(R.id.widget_week_grid) != null })
        val list = visibleViewport(root)
        assertEquals(View.VISIBLE, list.visibility)
        assertTrue(collectionRows(list).flatMap(::texts).contains("Monday course"))
        assertTrue(collectionRows(list).flatMap(::texts).contains("Sunday course"))

        views(weekSquare, weekly = true, state = state).reapply(context, root)
        measure(root, weekSquare)
        assertEquals(7, weekGrid(root).childCount)
        for (size in listOf(minimumWeek, weekSquare, SizeF(600f, 480f))) for (dark in listOf(false, true)) {
            views(size, weekly = true, state = state, dark = dark).reapply(context, root)
            measure(root, size)
            capture(root, "week-${size.width.toInt()}x${size.height.toInt()}dp-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun oldUndersizedWeekPlacementsShowResizeMessageAndRecoverWithoutStaleRows() = onMain {
        val state = state(everyDay = true)
        val root = apply(views(weekSquare, weekly = true, state = state), weekSquare)
        for (size in listOf(square, wide, tall, SizeF(244f, 400f), SizeF(400f, 279f))) {
            views(size, weekly = true, state = state).reapply(context, root)
            measure(root, size)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
            assertEquals(0, rows(root).size)
            val message = root.findViewById<TextView>(R.id.widget_empty)
            assertEquals(View.VISIBLE, message.visibility)
            assertEquals(PlatformText.context(context, Language.EN).getString(R.string.platform_widget_week_resize),
                message.text.toString())
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_footer).visibility)
            capture(root, "week-resize-${size.width.toInt()}x${size.height.toInt()}dp")
            views(minimumWeek, weekly = true, state = state).reapply(context, root)
            measure(root, minimumWeek)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals(View.VISIBLE, visibleViewport(root).visibility)
            assertTrue(rows(root).flatMap(::texts).contains("Monday course"))
        }
    }

    @Test fun minimumWeekShowsTheFirstCompleteCourseCardBeforeScrolling() = onMain {
        for (dark in listOf(false, true)) {
            val state = state(everyDay = true).let { original -> original.copy(plans = original.plans.map { plan ->
                plan.copy(courses = plan.courses.mapIndexed { index, course ->
                    if (index == 0) course.copy(name = "Introduction to modern education") else course
                })
            }) }
            val root = apply(views(minimumWeek, weekly = true, state = state, dark = dark), minimumWeek)
            val list = visibleViewport(root)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_footer).visibility)
            val day = requireNotNull(list.getChildAt(0)).findViewById<TextView>(R.id.widget_day)
            assertTrue("Date must retain its 48 dp target", day.height >= 48 * context.resources.displayMetrics.density - 1)
            val card = requireNotNull(list.getChildAt(1)).findViewById<ViewGroup>(R.id.widget_course_body)
            val name = card.findViewById<TextView>(R.id.widget_course_name)
            val location = card.findViewById<TextView>(R.id.widget_course_location)
            assertEquals("Introduction to modern education", name.text.toString())
            assertEquals("The minimum must accommodate a two-line name", 2, name.layout.lineCount)
            val time = card.findViewById<TextView>(R.id.widget_course_time)
            assertEquals("11:00–12:00", time.text.toString())
            assertEquals("North building 201", location.text.toString())
            for (text in listOf(time, name, location)) {
                assertEquals(View.VISIBLE, text.visibility)
                assertTrue("Course name and place must not be ellipsized", (0 until text.layout.lineCount).all {
                    text.layout.getEllipsisCount(it) == 0
                })
            }
            val bounds = android.graphics.Rect(0, 0, card.width, card.height)
            list.offsetDescendantRectToMyCoords(card, bounds)
            assertTrue("First card must start inside the viewport", bounds.top >= 0)
            assertTrue("The full card, including its bottom corners, must fit: $bounds / ${list.height}",
                bounds.bottom <= list.height - list.paddingBottom)
            capture(root, "week-245x280dp-long-name-${if (dark) "dark" else "light"}")
            views(weekSquare, weekly = true, state = state, dark = dark).reapply(context, root)
            measure(root, weekSquare)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_footer).visibility)
        }
    }

    @Test fun providerUsesReadableWeekMinimumAndDefaultWhileTodayRemainsTwoByTwo() {
        val providers = AppWidgetManager.getInstance(context).installedProviders
        val week = providers.single { it.provider.className == WeekWidgetProvider::class.java.name }
        val today = providers.single { it.provider.className == TodayWidgetProvider::class.java.name }
        assertEquals(4, week.targetCellWidth)
        assertEquals(5, week.targetCellHeight)
        val minWidth = CourseWidgets.WEEK_MIN_WIDTH_DP * context.resources.displayMetrics.density
        val minHeight = CourseWidgets.WEEK_MIN_HEIGHT_DP * context.resources.displayMetrics.density
        assertTrue(week.minWidth >= minWidth - 1)
        assertTrue(week.minHeight >= minHeight - 1)
        assertTrue(week.minResizeWidth >= minWidth - 1)
        assertTrue(week.minResizeHeight >= minHeight - 1)
        assertEquals(2, today.targetCellWidth)
        assertEquals(2, today.targetCellHeight)
        val todayMinimum = CourseWidgets.TODAY_MIN_SIZE_DP * context.resources.displayMetrics.density
        assertTrue(today.minWidth >= todayMinimum - 1)
        assertTrue(today.minHeight >= todayMinimum - 1)
        assertTrue(today.minResizeWidth >= todayMinimum - 1)
        assertTrue(today.minResizeHeight >= todayMinimum - 1)
        assertEquals(listOf(minimumWeek), CourseWidgets.responsiveSizes(Bundle(), weekly = true))
    }

    @Test fun oldTodayPlacementsBelowReadableMinimumAskToEnlargeAndRecover() = onMain {
        val minimum = SizeF(140f, 140f)
        val root = apply(views(minimum), minimum)
        for (size in listOf(SizeF(110f, 110f), SizeF(139f, 180f), SizeF(180f, 139f))) {
            views(size).reapply(context, root)
            measure(root, size)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals(0, rows(root).size)
            views(minimum).reapply(context, root)
            measure(root, minimum)
            assertEquals(View.VISIBLE, visibleViewport(root).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
            assertTrue(rows(root).flatMap(::texts).contains("Next course"))
        }
    }

    @Test fun largeFontsRequireEnoughHeightForCoursesInsteadOfRenderingOnlyCalendarChrome() = onMain {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val tooShort = SizeF(400f, 400f)
        val supported = SizeF(400f, 560f)
        val root = apply(views(tooShort, weekly = true, state = state(everyDay = true), renderContext = large), tooShort, large)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty).visibility)
        views(supported, weekly = true, state = state(everyDay = true), renderContext = large).reapply(large, root)
        measure(root, supported)
        assertEquals(View.VISIBLE, visibleViewport(root).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
        assertTrue(rows(root).flatMap(::texts).contains("Monday course"))
        assertTrue(rows(root).none { it.findViewById<View>(R.id.widget_week_grid) != null })
    }

    @Test fun widgetAndCourseTextKeepReadableInsetsAtSmallAndLargeSizes() = onMain {
        val density = context.resources.displayMetrics.density
        for ((size, weekly) in listOf(square to false, SizeF(320f, 320f) to false, minimumWeek to true, weekSquare to true)) {
            val root = apply(views(size, weekly = weekly, state = state(everyDay = weekly)), size) as ViewGroup
            val surface = root.findViewById<ViewGroup>(R.id.widget_root)
            val inset = (16 * density).toInt()
            assertEquals(inset, surface.paddingLeft)
            assertEquals(inset, surface.paddingTop)
            assertEquals(inset, surface.paddingRight)
            assertEquals(inset, surface.paddingBottom)
            val title = root.findViewById<TextView>(R.id.widget_title)
            val titleBounds = android.graphics.Rect(0, 0, title.width, title.height)
            surface.offsetDescendantRectToMyCoords(title, titleBounds)
            assertTrue(titleBounds.left >= inset && titleBounds.top >= inset)
            for (row in rows(root)) {
                val body = row.findViewById<ViewGroup>(R.id.widget_course_body) ?: continue
                val text = body.findViewById<TextView>(R.id.widget_course_time)
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                body.offsetDescendantRectToMyCoords(text, bounds)
                val cardInset = (12 * density).toInt()
                assertTrue(bounds.left >= cardInset && bounds.top >= (if (!weekly && size.height < 240f) 8 else 12) * density)
                assertTrue(bounds.right <= body.width - cardInset)
            }
        }
    }

    @Test fun weekOverviewRetainsHolidayEntriesAndUsesTheConfiguredSundayBoundary() = onMain {
        val state = state(everyDay = true).let { it.copy(settings = it.settings.copy(holidaysEnabled = true)) }
        val holiday = HolidayCalendar(days = listOf(HolidayDay(today.toString(), "Rest day", statutory = true)))
        val root = apply(views(weekSquare, weekly = true, state = state, holidays = holiday), weekSquare)
        val grid = weekGrid(root)
        val wednesday = grid.getChildAt(2)
        assertEquals("23", wednesday.findViewById<TextView>(R.id.widget_day_number).text.toString())
        assertEquals(PlatformText.context(context, Language.EN).getString(R.string.platform_widget_day_off),
            wednesday.findViewById<TextView>(R.id.widget_day_count).text.toString())
        assertEquals(View.VISIBLE, wednesday.findViewById<TextView>(R.id.widget_day_extra).visibility)
        assertTrue(wednesday.findViewById<TextView>(R.id.widget_day_extra).text.toString().contains("1"))
        assertTrue(wednesday.contentDescription.toString().contains("9/23"))

        views(weekSquare, weekly = true, state = state.copy(settings = state.settings.copy(weekStartsSunday = true)),
            holidays = holiday).reapply(context, root)
        measure(root, weekSquare)
        val sundayFirst = weekGrid(root)
        assertEquals("20", sundayFirst.getChildAt(0).findViewById<TextView>(R.id.widget_day_number).text.toString())
        assertEquals("26", sundayFirst.getChildAt(6).findViewById<TextView>(R.id.widget_day_number).text.toString())
    }

    @Test fun emptyStatesReplacePreviouslyAppliedCardsWithoutLeavingStaleContentVisible() = onMain {
        for (weekly in listOf(false, true)) {
            val size = if (weekly) weekSquare else square
            val populated = state(everyDay = weekly)
            val root = apply(views(size, weekly, populated), size)
            val emptyStates = listOf(
                AppState(settings = populated.settings) to R.string.platform_no_semester,
                populated.copy(plans = emptyList(), selectedPlanId = null) to R.string.platform_no_plan,
            )
            for ((empty, expectedText) in emptyStates) {
                views(size, weekly, empty).reapply(context, root)
                measure(root, size)
                val message = root.findViewById<TextView>(if (root.findViewById<View>(R.id.widget_empty_panel).visibility == View.VISIBLE)
                    R.id.widget_empty_caption else R.id.widget_empty)
                assertEquals(View.VISIBLE, message.visibility)
                assertEquals(PlatformText.context(context, Language.EN).getString(expectedText), message.text.toString())
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
                assertEquals(0, rows(root).size)
            }
            val noCourses = populated.copy(plans = populated.plans.map { it.copy(courses = emptyList()) })
            views(size, weekly, noCourses).reapply(context, root)
            measure(root, size)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_empty_panel).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_list).visibility)
            assertEquals(0, rows(root).size)
            assertEquals(PlatformText.context(context, Language.EN).getString(
                if (weekly) R.string.platform_no_week_classes else R.string.platform_no_today_classes),
                root.findViewById<TextView>(R.id.widget_empty_caption).text.toString())
            views(size, weekly, populated).reapply(context, root)
            measure(root, size)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
            assertEquals(View.VISIBLE, visibleViewport(root).visibility)
            assertEquals(if (weekly) 15 else 1, rows(root).size)
        }
    }

    @Test fun largeFontLongNamesRetainVisibleTimeAndNameInsideTheCompactCard() = onMain {
        val large = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = 2f })
        val longName = "Long advanced mathematics course with laboratory practice ".repeat(16)
        val state = state().let { original ->
            original.copy(plans = original.plans.map { plan ->
                plan.copy(courses = plan.courses.map { it.copy(name = longName) })
            })
        }
        for (size in listOf(SizeF(180f, 240f), SizeF(360f, 240f), SizeF(180f, 300f))) {
            val root = apply(views(size, state = state, renderContext = large), size, large)
            assertEquals(View.VISIBLE, visibleViewport(root).visibility)
            val body = rows(root).single().findViewById<ViewGroup>(R.id.widget_course_body)
            val time = body.findViewById<TextView>(R.id.widget_course_time)
            val name = body.findViewById<TextView>(R.id.widget_course_name)
            val teacher = body.findViewById<TextView>(R.id.widget_course_teacher)
            val location = body.findViewById<TextView>(R.id.widget_course_location)
            assertTrue(time.text.toString().contains("11:00"))
            assertTrue(name.text.toString().startsWith("Long advanced mathematics"))
            assertEquals("Teacher", teacher.text.toString())
            assertEquals("North building 201", location.text.toString())
            val essentialsOnly = size.height < 290f
            if (essentialsOnly) {
                assertEquals(View.GONE, teacher.visibility)
                assertEquals(View.VISIBLE, location.visibility)
            } else assertTrue(location.top < teacher.top)
            for (text in if (essentialsOnly) listOf(time, name, location) else listOf(time, name, location, teacher)) {
                assertEquals(View.VISIBLE, text.visibility)
                assertTrue("Text must have a measured height", text.measuredHeight > 0)
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                body.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Text extends above its card: $bounds", bounds.top >= 0)
                assertTrue("Text extends below its card: $bounds / ${body.height}", bounds.bottom <= body.height)
            }
        }
    }

    @Test fun themeReapplyUpdatesBothTheWidgetSurfaceAndVisibleCourseText() = onMain {
        val root = apply(views(square, state = state()), square)
        val surface = root.findViewById<View>(R.id.widget_root)
        val lightBackground = surface.backgroundTintList?.defaultColor
        val lightText = rows(root).single().findViewById<TextView>(R.id.widget_course_name).currentTextColor
        views(square, state = state(), dark = true).reapply(context, root)
        measure(root, square)
        assertNotEquals(lightBackground, surface.backgroundTintList?.defaultColor)
        assertNotEquals(lightText, rows(root).single().findViewById<TextView>(R.id.widget_course_name).currentTextColor)
    }

    @Test fun hostSizesAreDeduplicatedFiniteBoundedAndIncludeDistinctOrientations() {
        val supplied = arrayListOf(square, wide, tall, SizeF(360f, 360f), square)
        val options = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, supplied) }
        for (weekly in listOf(false, true)) {
            val sizes = CourseWidgets.responsiveSizes(options, weekly)
            assertTrue(sizes.size in 1..16)
            assertEquals(sizes.distinct(), sizes)
            if (weekly) assertTrue(sizes.contains(minimumWeek))
            else {
                assertTrue(sizes.contains(SizeF(140f, 140f)))
                assertTrue(sizes.contains(SizeF(300f, 160f)))
                assertTrue(sizes.any { it.width <= 180f && it.height in 240f..360f })
            }
            assertTrue(sizes.all { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 })
            assertTrue(CourseWidgets.responsiveSizes(Bundle(), weekly).size in 1..4)
        }
    }

    @Test fun hostSizesSelectInformationLayoutsAndPreserveBothLegacyOrientations() {
        val sizes = arrayListOf(SizeF(120f, 120f), SizeF(160f, 220f), SizeF(180f, 300f),
            SizeF(320f, 200f), SizeF(600f, 100f), SizeF(100f, 600f), SizeF(600f, 600f), SizeF(-1f, 160f))
        val supplied = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) }
        val selected = CourseWidgets.responsiveSizes(supplied, weekly = false)
        assertTrue(selected.size in 1..16)
        assertTrue(selected.contains(SizeF(139f, 139f)))
        assertTrue(selected.contains(SizeF(140f, 140f)))
        assertTrue(selected.contains(SizeF(140f, 180f)))
        assertTrue(selected.any { it.width <= 180f && it.height >= 240f })
        assertTrue(selected.any { it.width >= 300f && it.height >= 240f })
        assertTrue(selected.all { it.width > 0 && it.height > 0 })
        val legacy = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 360)
        }
        val legacyLayouts = CourseWidgets.responsiveSizes(legacy, weekly = false)
        assertTrue(legacyLayouts.any { it.width <= tall.width && it.height in 240f..tall.height })
        assertTrue(legacyLayouts.contains(SizeF(300f, 160f)))
        val largeFontOptions = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
            ArrayList(listOf(SizeF(100f, 100f), SizeF(140f, 700f), SizeF(800f, 800f)) +
                List(16) { index -> SizeF(300f + index * 10, 300f + index * 10) })) }
        assertTrue(CourseWidgets.responsiveSizes(largeFontOptions, weekly = false, fontScale = 2f)
            .contains(SizeF(140f, 240f)))
    }

    @Test fun manyWeekSizesKeepTheSmallestSupportedVariantAlongsideOldUndersizedHosts() {
        val sizes = arrayListOf(square, wide, tall, minimumWeek, weekSquare, SizeF(600f, 600f))
        val supplied = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes) }
        val selected = CourseWidgets.responsiveSizes(supplied, weekly = true)
        assertTrue(selected.size in 1..16)
        assertTrue(selected.contains(SizeF(244f, 279f)))
        assertTrue(selected.contains(minimumWeek))
        assertTrue(selected.any { it.width >= 440f && it.height in 480f..600f })
        val narrowTall = ArrayList(listOf(SizeF(100f, 100f), SizeF(245f, 1000f), SizeF(600f, 300f), SizeF(700f, 700f)) +
            List(16) { index -> SizeF(300f + index * 10, 300f + index * 10) })
        val selectedTall = CourseWidgets.responsiveSizes(Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, narrowTall)
        }, weekly = true)
        assertTrue(selectedTall.size in 1..16)
        assertTrue("A valid narrow/tall host must have a fitting supported variant", selectedTall.any {
            it.width in 245f..245f && it.height in 280f..1000f
        })
        val veryWideInvalid = CourseWidgets.responsiveSizes(Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                arrayListOf(SizeF(2000f, 100f), SizeF(2000f, 500f)))
        }, weekly = false)
        assertTrue(veryWideInvalid.contains(SizeF(139f, 139f)))
        assertFalse(veryWideInvalid.contains(SizeF(2000f, 100f)))
        assertTrue(veryWideInvalid.any { it.width == 300f && it.height >= 240f })
    }

    @Test fun crowdedResponsiveWidgetsStayBelowTheBinderTransactionBudget() = onMain {
        val crowded = state().let { original ->
            original.copy(plans = original.plans.map { plan -> plan.copy(courses = List(400) { index ->
                Course(id = "crowded-$index", name = "Course $index " + "long title ".repeat(80), lessons = listOf(
                    Lesson(date = today.toString(), startTime = "11:00", endTime = "12:00",
                        teacher = "Teacher ".repeat(60), location = "Location ".repeat(60)),
                ))
            }) })
        }
        for (weekly in listOf(false, true)) for (fixture in listOf(crowded,
            crowded.copy(plans = crowded.plans.map { it.copy(courses = emptyList()) }))) {
            val responsive = RemoteViews(listOf(square, wide, tall, SizeF(360f, 360f), SizeF(600f, 480f)).associateWith {
                views(it, weekly, fixture)
            })
            val parcel = Parcel.obtain()
            try {
                responsive.writeToParcel(parcel, 0)
                assertTrue("RemoteViews parcel is ${parcel.dataSize()} bytes", parcel.dataSize() < 900 * 1024)
                parcel.setDataPosition(0)
                val restored = RemoteViews.CREATOR.createFromParcel(parcel)
                assertNotNull(apply(restored, square))
            } finally { parcel.recycle() }
        }
    }

    private fun views(size: SizeF, weekly: Boolean = false, state: AppState = state(), dark: Boolean = false,
        time: LocalTime = now, renderContext: Context = context, holidays: HolidayCalendar = calendar): RemoteViews = CourseWidgets.layout(
        renderContext, PlatformText.context(renderContext, Language.EN), 970017, weekly, size, state, holidays,
        dark, today, time,
    )

    private fun apply(views: RemoteViews, size: SizeF, renderContext: Context = context): View {
        // RemoteCollectionItems is installed only when the action's root parent is a widget host.
        // Keeping the root attached also supplies that same host to RemoteViews.reapply.
        val host = AppWidgetHostView(renderContext)
        val root = views.apply(renderContext, host)
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        measure(root, size)
        return root
    }

    private fun measure(root: View, size: SizeF) {
        val density = root.resources.displayMetrics.density
        val width = (size.width * density).toInt()
        val height = (size.height * density).toInt()
        val host = (root.parent as? AppWidgetHostView) ?: root
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        assertEquals(width, root.measuredWidth)
        assertEquals(height, root.measuredHeight)
    }

    private fun visibleViewport(root: View): ViewGroup {
        val compact = root.findViewById<ViewGroup>(R.id.widget_compact)
        return if (compact.visibility == View.VISIBLE) compact else root.findViewById(R.id.widget_list)
    }

    private fun collectionRows(list: ViewGroup): List<View> {
        if (list !is ListView) return List(list.childCount) { list.getChildAt(it) }
        val adapter = requireNotNull(list.adapter) { "RemoteCollectionItems did not install an adapter" }
        return List(adapter.count) { index ->
            adapter.getView(index, null, list).also { row ->
                row.measure(View.MeasureSpec.makeMeasureSpec((list.width - list.paddingLeft - list.paddingRight)
                    .coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            }
        }
    }

    private fun rows(root: View): List<View> = collectionRows(visibleViewport(root))

    private fun weekGrid(root: View): ViewGroup = rows(root).first().findViewById(R.id.widget_week_grid)

    private fun capture(root: View, name: String) {
        val list = visibleViewport(root)
        assertTrue("Screenshot must include a laid-out collection child or resize message", list.visibility == View.GONE || list.childCount > 0)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "qa-v1.14/widgets")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(directory, "$name.txt").writeText(
                "pixels=${root.width}x${root.height}\ndensity=${root.resources.displayMetrics.density}\n" +
                    "fontScale=${root.resources.configuration.fontScale}\ncollectionRows=${if (list is ListView) list.adapter.count else list.childCount}\n" +
                    "visibleChildren=${list.childCount}\nvisibleText=${texts(list).joinToString(" | ")}\n",
            )
        } finally { bitmap.recycle() }
    }

    private fun texts(view: View): List<String> = buildList {
        if (view is TextView && view.visibility == View.VISIBLE) add(view.text.toString())
        if (view is ViewGroup) repeat(view.childCount) { addAll(texts(view.getChildAt(it))) }
    }

    private fun state(everyDay: Boolean = false): AppState {
        val semester = Semester("widget-semester", "Autumn", "2026-09-01", "2026-12-31", 18, emptyList())
        fun course(id: String, name: String, day: LocalDate, start: String, end: String) = Course(
            id = id, name = name, lessons = listOf(Lesson(date = day.toString(), startTime = start,
                endTime = end, teacher = "Teacher", location = "North building 201")),
        )
        val courses = if (everyDay) List(7) { offset ->
            val day = today.minusDays(2).plusDays(offset.toLong())
            course("day-$offset", day.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase) + " course", day, "11:00", "12:00")
        } else listOf(
            course("finished", "Finished course", today, "08:00", "09:00"),
            course("next", "Next course", today, "11:00", "12:00"),
            course("later", "Later course", today, "16:00", "17:00"),
        )
        val plan = Plan("widget-plan", semester.id, "Test plan", courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false))
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
