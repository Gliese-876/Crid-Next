package cn.crid.next.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.os.BundleCompat
import cn.crid.next.R
import cn.crid.next.core.AppState
import cn.crid.next.core.CourseColors
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Language
import cn.crid.next.core.Occurrence
import cn.crid.next.core.OccurrenceStatus
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.ThemeMode
import cn.crid.next.core.courseNameSpacing
import cn.crid.next.core.courseTextSpacing
import cn.crid.next.core.courseLocationSpacing
import cn.crid.next.data.AppRepository
import cn.crid.next.ui.WideColorDisplayProvider
import cn.crid.next.ui.UiText
import cn.crid.next.ui.VisualCenterText
import cn.crid.next.ui.alignByVisualCenter
import cn.crid.next.ui.cridColorScheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class TodayWidgetProvider : CourseWidgetProvider()
class WeekWidgetProvider : CourseWidgetProvider()

open class CourseWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED ->
                PlatformCoordinator.receive(this, context) { PlatformJobs.ensureScheduled(context.applicationContext) }
            AppWidgetManager.ACTION_APPWIDGET_ENABLED ->
                PlatformCoordinator.receive(this, context) { PlatformCoordinator.refreshNow(context.applicationContext) }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repository = AppRepository.get(context)
        CourseWidgets.update(context, ids, repository.state.value, repository.holidays.value)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences(CourseWidgets.PREFS, Context.MODE_PRIVATE).edit().apply {
            ids.forEach { remove("theme:$it") }
        }.apply()
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        val preferences = context.getSharedPreferences(CourseWidgets.PREFS, Context.MODE_PRIVATE)
        val restoredThemes = oldIds.zip(newIds).map { (old, new) ->
            new to preferences.getString("theme:$old", "APP")
        }
        preferences.edit().apply {
            oldIds.forEach { remove("theme:$it") }
            restoredThemes.forEach { (new, theme) -> putString("theme:$new", theme) }
        }.apply()
        onUpdate(context, AppWidgetManager.getInstance(context), newIds)
    }
}

object CourseWidgets {
    internal const val PREFS = "widget-preferences"
    private const val MAX_VISIBLE_CLASSES = 36
    private const val MAX_SIZE_VARIANTS = 16 // Native limit; equivalent information layouts share one variant.
    internal const val TODAY_MIN_SIZE_DP = 140
    // The minimum leaves room for a date and a complete course card with a two-line name.
    internal const val WEEK_MIN_WIDTH_DP = 245
    internal const val WEEK_MIN_HEIGHT_DP = 280
    private val weekdayViewIds = intArrayOf(R.id.widget_day_0, R.id.widget_day_1, R.id.widget_day_2,
        R.id.widget_day_3, R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6)
    private val moreDayViewIds = intArrayOf(R.id.widget_day_more_0, R.id.widget_day_more_1, R.id.widget_day_more_2, R.id.widget_day_more_3, R.id.widget_day_more_4, R.id.widget_day_more_5, R.id.widget_day_more_6)
    private val courseViewIds = intArrayOf(
        R.id.widget_course_0, R.id.widget_course_1, R.id.widget_course_2, R.id.widget_course_3, R.id.widget_course_4, R.id.widget_course_5,
        R.id.widget_course_6, R.id.widget_course_7, R.id.widget_course_8, R.id.widget_course_9, R.id.widget_course_10, R.id.widget_course_11,
        R.id.widget_course_12, R.id.widget_course_13, R.id.widget_course_14, R.id.widget_course_15, R.id.widget_course_16, R.id.widget_course_17,
        R.id.widget_course_18, R.id.widget_course_19, R.id.widget_course_20, R.id.widget_course_21, R.id.widget_course_22, R.id.widget_course_23,
        R.id.widget_course_24, R.id.widget_course_25, R.id.widget_course_26, R.id.widget_course_27, R.id.widget_course_28, R.id.widget_course_29,
        R.id.widget_course_30, R.id.widget_course_31, R.id.widget_course_32, R.id.widget_course_33, R.id.widget_course_34, R.id.widget_course_35,
    )
    private val columnViewIds = intArrayOf(R.id.widget_column_first, R.id.widget_column_second)

    private fun minimumWeekHeight(fontScale: Float) = WEEK_MIN_HEIGHT_DP + (160 * (fontScale - 1f).coerceAtLeast(0f)).toInt()
    private fun minimumTodayHeight(fontScale: Float) = TODAY_MIN_SIZE_DP + (100 * (fontScale - 1f).coerceAtLeast(0f)).toInt()
    private fun threeLineNameMinimumHeight(fontScale: Float) = 160f + 110f * (fontScale - 1f).coerceAtLeast(0f)
    private fun detailsMinimumHeight(fontScale: Float) = 180f + 110f * (fontScale - 1f).coerceAtLeast(0f)

    private fun twoColumnWidth(fontScale: Float, weekly: Boolean): Float =
        40f + 2f * (if (weekly) 200f else 130f) * fontScale.coerceAtLeast(1f)

    private fun twoColumns(size: SizeF, fontScale: Float, weekly: Boolean): Boolean =
        size.width >= twoColumnWidth(fontScale, weekly)

    private fun compactToday(size: SizeF, fontScale: Float) = size.height < if (fontScale >= 1.5f) 320f else 240f

    // Hosts report dp dimensions rather than grid spans. Keep only the compact, near-square
    // Today category square; wide summaries, tall agendas and week widgets retain their shape.
    private fun squareToday(size: SizeF, fontScale: Float, weekly: Boolean): Boolean =
        !weekly && compactToday(size, fontScale) &&
            size != SizeF(TODAY_MIN_SIZE_DP - 1f, minimumTodayHeight(fontScale) - 1f) &&
            maxOf(size.width, size.height) <= minOf(size.width, size.height) * 1.25f

    private fun informationLayout(size: SizeF, fontScale: Float, weekly: Boolean): SizeF {
        val paired = twoColumns(size, fontScale, weekly)
        val width = if (paired) twoColumnWidth(fontScale, weekly)
            else (if (weekly) WEEK_MIN_WIDTH_DP else TODAY_MIN_SIZE_DP).toFloat()
        if (weekly) {
            if (!paired && size.width >= 368f && size.height >= 360f && fontScale < 1.5f) {
                return SizeF(368f, 360f)
            }
            val courseHeight = if (!paired) 0f else when {
                size.height >= 640f -> 640f
                size.height >= 480f -> 480f
                else -> 0f
            }
            return SizeF(width, maxOf(minimumWeekHeight(fontScale).toFloat(), courseHeight))
        }
        val fullDayHeight = if (fontScale >= 1.5f) 320f else 240f
        val canonical = SizeF(width, maxOf(minimumTodayHeight(fontScale).toFloat(),
            if (size.height >= fullDayHeight) fullDayHeight else 0f,
            if (size.height >= threeLineNameMinimumHeight(fontScale)) threeLineNameMinimumHeight(fontScale) else 0f,
            if (size.height >= detailsMinimumHeight(fontScale)) detailsMinimumHeight(fontScale) else 0f))
        if (!squareToday(size, fontScale, weekly) && squareToday(canonical, fontScale, weekly)) {
            // A rectangular compact host must not become square merely because its information
            // breakpoint was narrower. Keep the key on the same side of the shape boundary.
            return if (size.width > size.height) SizeF(minOf(size.width, canonical.height * 1.25f + 1f), canonical.height)
                else SizeF(canonical.width, minOf(size.height, canonical.width * 1.25f + 1f))
        }
        return canonical
    }

    fun updateAll(context: Context, state: AppState, calendar: HolidayCalendar) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java)) +
            manager.getAppWidgetIds(ComponentName(context, WeekWidgetProvider::class.java))
        update(context, ids, state, calendar)
    }

    fun update(context: Context, id: Int, state: AppState, calendar: HolidayCalendar) =
        update(context, intArrayOf(id), state, calendar)

    internal fun update(context: Context, ids: IntArray, state: AppState, calendar: HolidayCalendar) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val localized = PlatformText.context(context, state.settings.language)
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val schedule = WidgetSchedule(state, calendar)
        val now = LocalDateTime.now()
        ids.forEach { id ->
            val info = manager.getAppWidgetInfo(id) ?: return@forEach
            val weekly = info.provider.className == WeekWidgetProvider::class.java.name
            val dark = when (preferences.getString("theme:$id", "APP")) {
                "LIGHT" -> false
                "DARK" -> true
                else -> when (state.settings.theme) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                }
            }
            val sizes = responsiveSizes(manager.getAppWidgetOptions(id), weekly, context.resources.configuration.fontScale)
            manager.updateAppWidget(id, RemoteViews(sizes.associateWith { size ->
                layout(context, localized, id, weekly, size, state, calendar, dark,
                    now.toLocalDate(), now.toLocalTime(), schedule)
            }))
        }
    }

    /** Share a canonical RemoteViews variant for every distinct information layout the host needs. */
    internal fun responsiveSizes(options: Bundle, weekly: Boolean, fontScale: Float = 1f): List<SizeF> {
        val minimumWidth = if (weekly) WEEK_MIN_WIDTH_DP else TODAY_MIN_SIZE_DP
        val minimumHeight = if (weekly) minimumWeekHeight(fontScale) else minimumTodayHeight(fontScale)
        val resizeLayout = SizeF((minimumWidth - 1).toFloat(), (minimumHeight - 1).toFloat())
        val supplied = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct()
        val sizes = supplied.ifEmpty {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minimumWidth).coerceAtLeast(80)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minimumHeight).coerceAtLeast(80)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth).coerceAtLeast(minWidth)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight).coerceAtLeast(minHeight)
            listOf(SizeF(minWidth.toFloat(), maxHeight.toFloat()), SizeF(maxWidth.toFloat(), minHeight.toFloat())).distinct()
        }
        val supported = sizes.filter { it.width >= minimumWidth && it.height >= minimumHeight }
        val squares = sizes.filter { squareToday(it, fontScale, weekly) }
        // Square panels need the host's real short side, including a large-font resize prompt.
        // Keep canonical information breakpoints first so a long list of sizes cannot remove
        // the wide summaries or full-day layouts. Extra square sizes share the remaining slots.
        val required = (listOfNotNull(
            resizeLayout.takeIf { sizes.any { it !in supported && it !in squares } },
            SizeF(minimumWidth.toFloat(), minimumHeight.toFloat()).takeIf { supported.isNotEmpty() }) +
            supported.filterNot { it in squares }.map { informationLayout(it, fontScale, weekly) }
                .sortedBy { it.width * it.height }).distinct().take(MAX_SIZE_VARIANTS)
        if (weekly) return required.ifEmpty { listOf(resizeLayout) }
        val remaining = squares.filterNot { it in required }.sortedWith(
            compareBy<SizeF> { minOf(it.width, it.height) }.thenBy { it.width }.thenBy { it.height })
        val regularHosts = supported.filterNot { it in squares }.sortedBy { it.width * it.height }
        var slots = minOf(remaining.size, MAX_SIZE_VARIANTS - required.size)
        while (true) {
            val selectedSquares = when {
                slots == remaining.size -> remaining
                slots == 0 -> emptyList()
                slots == 1 -> listOf(remaining.first())
                else -> List(slots) { index -> remaining[index * (remaining.size - 1) / (slots - 1)] }
            }
            val layouts = (required + selectedSquares).toMutableList()
            regularHosts.forEach { host ->
                val best = layouts.filter { it.width <= host.width && it.height <= host.height }
                    .maxByOrNull { it.width * it.height }
                if (best == null || squareToday(best, fontScale, weekly) ||
                    informationLayout(best, fontScale, weekly) != informationLayout(host, fontScale, weekly)) {
                    // RemoteViews picks the largest fitting area. Guard only an actual host whose
                    // square or other information category would otherwise win that selection.
                    if (host !in layouts) layouts.add(host)
                }
            }
            if (layouts.size <= MAX_SIZE_VARIANTS) return layouts.ifEmpty { listOf(resizeLayout) }
            if (slots == 0) return required.ifEmpty { listOf(resizeLayout) }
            slots = (slots - (layouts.size - MAX_SIZE_VARIANTS)).coerceAtLeast(0)
        }
    }

    internal fun widgetLaunchIntent(context: Context, route: String, date: LocalDate? = null): Intent =
        launchIntent(context, route).setData(Uri.Builder().scheme("crid").authority("widget")
            .appendPath(route).apply { date?.let { appendPath(it.toString()) } }.build())
            .apply { date?.let { putExtra("date", it.toString()) } }

    internal fun layout(context: Context, textContext: Context, id: Int, weekly: Boolean, size: SizeF,
        state: AppState, calendar: HolidayCalendar, dark: Boolean, today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(), schedule: WidgetSchedule = WidgetSchedule(state, calendar)): RemoteViews {
        val root = RemoteViews(context.packageName, R.layout.widget_courses)
        val fontScale = context.resources.configuration.fontScale
        val square = squareToday(size, fontScale, weekly)
        val side = minOf(size.width, size.height)
        val contentSize = if (square) SizeF(side, side) else size
        val bodyWidth = ((contentSize.width * context.resources.displayMetrics.density).toInt() - 2 * dp(context, 16)).coerceAtLeast(1)
        root.setViewLayoutWidth(R.id.widget_root,
            if (square) side else ViewGroup.LayoutParams.MATCH_PARENT.toFloat(),
            if (square) TypedValue.COMPLEX_UNIT_DIP else TypedValue.COMPLEX_UNIT_PX)
        root.setViewLayoutHeight(R.id.widget_root,
            if (square) side else ViewGroup.LayoutParams.MATCH_PARENT.toFloat(),
            if (square) TypedValue.COMPLEX_UNIT_DIP else TypedValue.COMPLEX_UNIT_PX)
        val foreground = if (dark) Color.rgb(238, 238, 244) else Color.rgb(28, 31, 39)
        val secondary = if (dark) Color.rgb(186, 190, 201) else Color.rgb(86, 94, 110)
        val background = if (dark) Color.rgb(24, 27, 36) else Color.rgb(248, 249, 255)
        root.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(background))
        listOf(R.id.widget_title, R.id.widget_empty, R.id.widget_footer).forEach { root.setTextColor(it, foreground) }
        root.setTextColor(R.id.widget_subtitle, secondary)
        root.setViewVisibility(R.id.widget_heading, View.VISIBLE)
        root.removeAllViews(R.id.widget_compact)
        root.setViewVisibility(R.id.widget_compact, View.GONE)
        root.removeAllViews(R.id.widget_empty_panel)
        root.setViewVisibility(R.id.widget_empty_panel, View.GONE)
        root.setViewVisibility(R.id.widget_empty, View.GONE)
        root.setInt(R.id.widget_empty, "setGravity", Gravity.CENTER_VERTICAL)
        val locale = textContext.resources.configuration.locales[0]
        val undersized = if (weekly) contentSize.width < WEEK_MIN_WIDTH_DP || contentSize.height < minimumWeekHeight(fontScale)
            else contentSize.width < TODAY_MIN_SIZE_DP || contentSize.height < minimumTodayHeight(fontScale)
        val compact = !weekly && compactToday(contentSize, fontScale)
        val paired = twoColumns(contentSize, fontScale, weekly)
        val weekOverview = weekly && !paired && !undersized && contentSize.width >= 368f && contentSize.height >= 360f && fontScale < 1.5f
        val padding = 16
        root.setViewPadding(R.id.widget_root, dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
        root.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, if (compact) 20f else 22f)
        val first = if (weekly) ScheduleEngine.weekStart(today, state.settings.weekStartsSunday) else today
        val dates = List(if (weekly) 7 else 1) { first.plusDays(it.toLong()) }
        val dateLabel = if (weekly) "${first.format(DateTimeFormatter.ofPattern("M/d", locale))}–${dates.last().format(DateTimeFormatter.ofPattern("M/d", locale))}"
            else today.format(DateTimeFormatter.ofPattern("M/d EEE", locale))
        val headingDate = if (!weekly && contentSize.width < twoColumnWidth(fontScale, weekly))
            today.format(DateTimeFormatter.ofPattern("M/d", locale)) else dateLabel
        root.setTextViewText(R.id.widget_title, headingDate)
        val planLabel = listOfNotNull(state.semester?.name, state.plan?.name).joinToString(" · ").take(240)
        val defaultSubtitle = textContext.getString(if (weekly) R.string.platform_week else R.string.platform_today)
        root.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
        val route = if (weekly) "timetable" else "today"
        val open = openPendingIntent(context, id, route, today)
        listOf(R.id.widget_frame, R.id.widget_root, R.id.widget_body, R.id.widget_compact, R.id.widget_heading,
            R.id.widget_empty, R.id.widget_empty_panel, R.id.widget_footer).forEach {
            root.setOnClickPendingIntent(it, open)
        }
        // Collection fill-in intents require a mutable template; the component is explicitly fixed.
        root.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, id + 100000,
            widgetLaunchIntent(context, route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        // ListView is supported by RemoteViews; even a single compact card can scroll at large fonts.
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(false).setViewTypeCount(4)
        if (undersized) {
            root.setRemoteAdapter(R.id.widget_list, items.build())
            val message = textContext.getString(R.string.platform_widget_week_resize)
            root.setTextViewText(R.id.widget_empty, message)
            alignEmptyText(context, root, message, bodyWidth,
                (contentSize.height * context.resources.displayMetrics.density).toInt() - 2 * dp(context, 16), Gravity.CENTER_VERTICAL)
            root.setViewVisibility(R.id.widget_list, View.GONE)
            root.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            root.setViewVisibility(R.id.widget_heading, View.GONE)
            root.setViewVisibility(R.id.widget_subtitle, View.GONE)
            root.setViewVisibility(R.id.widget_footer, View.GONE)
            return root
        }
        var rowIndex = 0L
        val semester = state.semester
        val plan = state.plan
        val coursesByDay = dates.associateWith(schedule::courses)
        val total = coursesByDay.values.sumOf { it.size }
        val actual = coursesByDay.values.sumOf { courses -> courses.count { it.isActual } }
        val ready = semester != null && plan != null
        val summary = !weekly && compact && ready
        val focusedCourses = if (summary) coursesByDay.getValue(today).filter { it.isActual && it.end > now } else emptyList()
        val focus = focusedCourses.firstOrNull()
        val subtitle = if (focus != null) textContext.getString(if (focus.start <= now) R.string.platform_class_now else R.string.platform_class_next)
            else defaultSubtitle
        root.setTextViewText(R.id.widget_subtitle, subtitle)
        val headingHeight = alignWidgetHeading(context, root, bodyWidth, headingDate, if (compact) 20f else 22f, subtitle)
        val emptyMessage = when {
            semester == null -> R.string.platform_no_semester
            plan == null -> R.string.platform_no_plan
            summary && focusedCourses.isEmpty() && actual > 0 -> R.string.platform_classes_finished
            summary && focusedCourses.isEmpty() -> R.string.platform_no_today_classes
            total == 0 && weekly -> R.string.platform_no_week_classes
            total == 0 -> R.string.platform_no_today_classes
            else -> null
        }
        root.setContentDescription(R.id.widget_heading,
            "${textContext.getString(if (weekly) R.string.platform_week else R.string.platform_today)} · $dateLabel · $planLabel")
        if (emptyMessage != null) {
            root.setRemoteAdapter(R.id.widget_list, items.build())
            root.setViewVisibility(R.id.widget_list, View.GONE)
            root.setViewVisibility(R.id.widget_footer, View.GONE)
            showEmptyScene(context, root, contentSize, headingHeight,
                textContext.getString(emptyMessage), foreground, dark, open,
                when {
                    !ready -> EmptyScene.PREPARE
                    emptyMessage == R.string.platform_classes_finished -> EmptyScene.FINISHED
                    else -> EmptyScene.REST
                })
            return root
        }
        var displayed = 0
        var compactCardsVisible = false
        if (summary) {
            val upcoming = focusedCourses
            if (focus != null) {
                val visible = upcoming.take(if (paired) 2 else 1)
                val fillBody = (paired || square) && fontScale <= 1.3f
                val card = if (paired) coursePair(context, textContext, visible, background, dark, contentSize,
                    compactWidgetId = id.takeIf { fillBody })
                    else courseRow(context, textContext, focus, background, dark, contentSize,
                        essentialsOnly = contentSize.height < detailsMinimumHeight(fontScale),
                        nested = fillBody, fillHeight = fillBody,
                        maximumNameLines = 2.takeIf { square && fillBody && contentSize.height < detailsMinimumHeight(fontScale) })
                if (fillBody) {
                    if (!paired) card.setOnClickPendingIntent(R.id.widget_course_body,
                        openPendingIntent(context, id, "today", focus.date))
                    root.addView(R.id.widget_compact, card)
                    root.setViewVisibility(R.id.widget_compact, View.VISIBLE)
                    compactCardsVisible = true
                } else {
                    items.addItem(rowIndex++, card)
                }
                displayed = visible.size
            }
            root.setTextViewText(R.id.widget_footer, textContext.getString(
                if (total > displayed) R.string.platform_widget_other_courses else R.string.platform_widget_view_day,
                (total - displayed).coerceAtLeast(0)))
        } else if (ready && paired) {
            if (weekly) {
                val perDay = when {
                    contentSize.height >= 640f -> 5
                    contentSize.height >= 480f -> 4
                    else -> 3
                }
                dates.chunked(2).forEach { days ->
                    val pair = columnsRow(context)
                    pair.setOnClickFillInIntent(R.id.widget_columns, Intent().putExtra("date", days.first().toString()))
                    days.forEachIndexed { column, date ->
                        pair.setOnClickFillInIntent(columnViewIds[column], Intent().putExtra("date", date.toString()))
                        val dayIndex = dates.indexOf(date)
                        val courses = coursesByDay.getValue(date)
                        val dateId = weekdayViewIds[dayIndex]
                        val label = date.format(DateTimeFormatter.ofPattern("M/d EEEE", locale)) +
                            if (courses.isEmpty()) " · ${textContext.getString(R.string.platform_widget_day_off)}" else ""
                        val columnsWidth = bodyWidth - dp(context, 8)
                        val dayWidth = if (column == 0) columnsWidth / 2 else columnsWidth - columnsWidth / 2
                        pair.addView(columnViewIds[column], dayHeader(context, label, secondary, date, dayWidth, dateId, nested = true))
                        pair.setOnClickFillInIntent(dateId, Intent().putExtra("date", date.toString()))
                        courses.take(perDay).forEach { course ->
                            val courseId = courseViewIds[displayed++]
                            val card = courseRow(context, textContext, course, background, dark,
                                SizeF((contentSize.width - 40f) / 2f, contentSize.height), rootId = courseId, nested = true)
                            pair.addView(columnViewIds[column], card)
                            // API 31 only marks the top-level collection item as a collection child.
                            pair.setOnClickFillInIntent(courseId, Intent().putExtra("date", date.toString()))
                        }
                        if (courses.size > perDay) {
                            val moreId = moreDayViewIds[dayIndex]
                            pair.addView(columnViewIds[column], dayHeader(context,
                                textContext.getString(R.string.platform_widget_more_day_classes, courses.size - perDay),
                                secondary, date, dayWidth, moreId, nested = true))
                            pair.setOnClickFillInIntent(moreId, Intent().putExtra("date", date.toString()))
                        }
                    }
                    items.addItem(rowIndex++, pair)
                }
                root.setTextViewText(R.id.widget_footer, textContext.getString(R.string.platform_widget_week_count, actual, total))
            } else {
                val courses = coursesByDay.getValue(today).take(MAX_VISIBLE_CLASSES)
                courses.chunked(2).forEach { pair ->
                    items.addItem(rowIndex++, coursePair(context, textContext, pair, background, dark, contentSize))
                }
                displayed = courses.size
                root.setTextViewText(R.id.widget_footer, textContext.getString(
                    if (total > displayed) R.string.platform_more_courses else R.string.platform_widget_total_count,
                    if (total > displayed) total - displayed else total))
            }
        } else if (ready) {
            if (weekOverview) {
                val overview = RemoteViews(context.packageName, R.layout.widget_week_overview)
                overview.removeAllViews(R.id.widget_week_grid)
                overview.setOnClickFillInIntent(R.id.widget_week_grid, Intent().putExtra("date", today.toString()))
                dates.forEachIndexed { index, date ->
                    val courses = coursesByDay.getValue(date)
                    val count = courses.count { it.isActual }
                    val dayId = weekdayViewIds[index]
                    val day = RemoteViews(context.packageName, R.layout.widget_day_summary, dayId)
                    day.setTextViewText(R.id.widget_day_weekday, date.format(DateTimeFormatter.ofPattern("EEEEE", locale)))
                    day.setTextViewText(R.id.widget_day_number, date.dayOfMonth.toString())
                    day.setTextViewText(R.id.widget_day_count, if (count == 0) textContext.getString(R.string.platform_widget_day_off) else count.toString())
                    day.setTextViewText(R.id.widget_day_extra, if (courses.size > count) textContext.getString(R.string.platform_widget_day_entries, courses.size) else "")
                    day.setViewVisibility(R.id.widget_day_extra, if (courses.size > count) View.VISIBLE else View.GONE)
                    listOf(R.id.widget_day_weekday, R.id.widget_day_count, R.id.widget_day_extra).forEach { day.setTextColor(it, secondary) }
                    day.setTextColor(R.id.widget_day_number, foreground)
                    day.setInt(dayId, "setBackgroundResource", if (date == today) R.drawable.widget_course_fill else 0)
                    if (date == today) day.setColorStateList(dayId, "setBackgroundTintList", ColorStateList.valueOf(if (dark) Color.rgb(43, 47, 61) else Color.rgb(231, 235, 246)))
                    day.setContentDescription(dayId, date.format(DateTimeFormatter.ofPattern("M/d EEEE", locale)) + " · " +
                        textContext.getString(R.string.platform_widget_actual_count, count) + " · " + textContext.getString(R.string.platform_widget_total_count, courses.size))
                    overview.addView(R.id.widget_week_grid, day)
                    // Keep the action on the collection item: API 31 does not propagate its collection
                    // flag to nested RemoteViews. Unique day roots remain ordinary clickable descendants.
                    overview.setOnClickFillInIntent(dayId, Intent().putExtra("date", date.toString()))
                }
                items.addItem(rowIndex++, overview)
            }
            coursesByDay.forEach { (date, courses) ->
                if (weekly && displayed < MAX_VISIBLE_CLASSES) {
                    val label = date.format(DateTimeFormatter.ofPattern("M/d EEEE", locale)) +
                        if (courses.isEmpty()) " · ${textContext.getString(R.string.platform_widget_day_off)}" else ""
                    items.addItem(rowIndex++, dayHeader(context, label, secondary, date, bodyWidth))
                }
                courses.forEach { course ->
                    if (displayed < MAX_VISIBLE_CLASSES) {
                        items.addItem(rowIndex++, courseRow(context, textContext, course, background, dark, contentSize))
                        displayed++
                    }
                }
            }
            root.setTextViewText(R.id.widget_footer, if (weekly && total == displayed)
                textContext.getString(R.string.platform_widget_week_count, actual, total)
            else textContext.getString(
                if (total > displayed) R.string.platform_more_courses else R.string.platform_widget_total_count,
                if (total > displayed) total - displayed else total))
        }
        root.setRemoteAdapter(R.id.widget_list, items.build())
        val showList = ready && !compactCardsVisible
        root.setViewVisibility(R.id.widget_list, if (showList) View.VISIBLE else View.GONE)
        val footerFits = if (weekly) contentSize.height >= 320f else !compact ||
            contentSize.height >= (if (paired) 220f else 180f) + 100f * (fontScale - 1f).coerceAtLeast(0f)
        root.setViewVisibility(R.id.widget_footer, if (ready && footerFits && !square) View.VISIBLE else View.GONE)
        return root
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun measureWidgetView(view: View, widthSpec: Int,
        heightSpec: Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)) {
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun alignWidgetHeading(context: Context, root: RemoteViews, width: Int,
        title: String, titleSp: Float, subtitle: String): Int {
        val heading = LayoutInflater.from(context).inflate(R.layout.widget_courses, null)
            .findViewById<View>(R.id.widget_heading)
        heading.findViewById<TextView>(R.id.widget_title).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
        }
        heading.findViewById<TextView>(R.id.widget_subtitle).text = subtitle
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        measureWidgetView(heading, widthSpec)
        listOf(R.id.widget_title, R.id.widget_subtitle).forEach { id ->
            val label = heading.findViewById<TextView>(id)
            centerWidgetText(label)
            root.setViewPadding(id, label.paddingLeft, label.paddingTop, label.paddingRight, label.paddingBottom)
        }
        measureWidgetView(heading, widthSpec)
        return heading.measuredHeight
    }

    private fun alignEmptyText(context: Context, root: RemoteViews, message: String, width: Int, height: Int, gravity: Int) {
        if (width <= 0 || height <= 0) return
        val label = LayoutInflater.from(context).inflate(R.layout.widget_courses, null)
            .findViewById<TextView>(R.id.widget_empty).apply { text = message; this.gravity = gravity }
        measureWidgetView(label, View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        centerWidgetText(label, fixedHeight = true)
        root.setViewPadding(R.id.widget_empty, label.paddingLeft, label.paddingTop, label.paddingRight, label.paddingBottom)
    }

    private enum class EmptyScene { REST, FINISHED, PREPARE }

    private fun showEmptyScene(context: Context, root: RemoteViews, size: SizeF, headingHeight: Int,
        message: String, foreground: Int, dark: Boolean, open: PendingIntent, scene: EmptyScene) {
        val fontScale = context.resources.configuration.fontScale
        val wide = size.width >= 280f && size.width / size.height >= 1.5f
        val density = context.resources.displayMetrics.density
        val bodyWidth = ((size.width * density).toInt() - 2 * dp(context, 16)).coerceAtLeast(1)
        val resource = if (wide) R.layout.widget_empty_wide else R.layout.widget_empty_stacked
        val inflater = LayoutInflater.from(context)
        val bodyHeight = (size.height * density).toInt() - 2 * dp(context, 16) - headingHeight
        // Measure the resource TextView itself so its font fallback, line spacing and padding
        // match the launcher. CJK fallback lines can be taller than a bare StaticLayout.
        val caption = inflater.inflate(resource, null).findViewById<TextView>(R.id.widget_empty_caption)
        caption.text = message
        val wideArtHeight = minOf(dp(context, 128), bodyHeight * 3 / 4, bodyWidth * 2 / 5 * 2 / 3)
        val wideArtWidth = wideArtHeight * 3 / 2
        val captionWidth = if (wide) (bodyWidth - wideArtWidth).coerceAtLeast(1) else bodyWidth
        val captionWidthSpec = View.MeasureSpec.makeMeasureSpec(captionWidth,
            if (wide) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY)
        measureWidgetView(caption, captionWidthSpec)
        centerWidgetText(caption)
        measureWidgetView(caption, captionWidthSpec)
        val illustrationHeight = if (wide) wideArtHeight else
            minOf(dp(context, 160), bodyWidth * 2 / 3, bodyHeight * 3 / 5, bodyHeight - caption.measuredHeight)
        // Text remains complete at large font sizes; decoration uses only the remaining space.
        if (fontScale > 1.3f || caption.measuredHeight > bodyHeight || illustrationHeight < dp(context, 28)) {
            root.setTextViewText(R.id.widget_empty, message)
            root.setInt(R.id.widget_empty, "setGravity", Gravity.CENTER)
            alignEmptyText(context, root, message, bodyWidth, bodyHeight, Gravity.CENTER)
            root.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            return
        }
        val view = RemoteViews(context.packageName, resource)
        val illustration = when (scene) {
            EmptyScene.REST -> if (dark) R.drawable.widget_scene_rest_dark else R.drawable.widget_scene_rest_light
            EmptyScene.FINISHED -> if (dark) R.drawable.widget_scene_finished_dark else R.drawable.widget_scene_finished_light
            EmptyScene.PREPARE -> if (dark) R.drawable.widget_scene_prepare_dark else R.drawable.widget_scene_prepare_light
        }
        view.setImageViewResource(R.id.widget_empty_art, illustration)
        view.setViewLayoutHeight(R.id.widget_empty_art, illustrationHeight.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        if (wide) {
            view.setViewLayoutWidth(R.id.widget_empty_art, wideArtWidth.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            view.setViewLayoutWidth(R.id.widget_empty_caption, caption.measuredWidth.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }
        view.setTextViewText(R.id.widget_empty_caption, message)
        view.setViewPadding(R.id.widget_empty_caption, caption.paddingLeft, caption.paddingTop, caption.paddingRight, caption.paddingBottom)
        view.setTextColor(R.id.widget_empty_caption, foreground)
        view.setOnClickPendingIntent(R.id.widget_empty_scene, open)
        root.addView(R.id.widget_empty_panel, view)
        root.setViewVisibility(R.id.widget_empty_panel, View.VISIBLE)
    }

    private fun openPendingIntent(context: Context, id: Int, route: String, date: LocalDate): PendingIntent =
        PendingIntent.getActivity(context, id, widgetLaunchIntent(context, route, date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun columnsRow(context: Context) = RemoteViews(context.packageName, R.layout.widget_columns).apply {
        columnViewIds.forEach(::removeAllViews)
    }

    private fun coursePair(context: Context, textContext: Context, courses: List<Occurrence>,
        background: Int, dark: Boolean, size: SizeF, compactWidgetId: Int? = null): RemoteViews = columnsRow(context).apply {
        if (compactWidgetId == null) {
            setOnClickFillInIntent(R.id.widget_columns, Intent().putExtra("date", courses.first().date.toString()))
        } else {
            setOnClickPendingIntent(R.id.widget_columns, openPendingIntent(context, compactWidgetId, "today", courses.first().date))
        }
        if (compactWidgetId != null) {
            listOf(R.id.widget_columns, *columnViewIds.toTypedArray()).forEach {
                setViewLayoutHeight(it, ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            }
            if (courses.size == 1) {
                setViewVisibility(R.id.widget_column_second, View.GONE)
                setViewLayoutMargin(R.id.widget_column_first, RemoteViews.MARGIN_END, 0f, TypedValue.COMPLEX_UNIT_DIP)
            }
        }
        courses.forEachIndexed { index, course ->
            val courseId = courseViewIds[index]
            addView(columnViewIds[index], courseRow(context, textContext, course, background, dark,
                SizeF(if (compactWidgetId != null && courses.size == 1) size.width - 32f else (size.width - 40f) / 2f, size.height),
                essentialsOnly = size.height < detailsMinimumHeight(context.resources.configuration.fontScale),
                rootId = courseId, nested = true, fillHeight = compactWidgetId != null))
            if (compactWidgetId == null) {
                setOnClickFillInIntent(columnViewIds[index], Intent().putExtra("date", course.date.toString()))
                setOnClickFillInIntent(courseId, Intent().putExtra("date", course.date.toString()))
            } else {
                setOnClickPendingIntent(columnViewIds[index], openPendingIntent(context, compactWidgetId, "today", course.date))
                setOnClickPendingIntent(courseId, openPendingIntent(context, compactWidgetId, "today", course.date))
            }
        }
    }

    private fun dayHeader(context: Context, label: String, color: Int, date: LocalDate, width: Int,
        rootId: Int = R.id.widget_day, nested: Boolean = false): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_day_header, rootId).apply {
            val view = (LayoutInflater.from(context).inflate(R.layout.widget_day_header, null) as TextView).apply { text = label }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
            measureWidgetView(view, widthSpec)
            centerWidgetText(view, fixedHeight = view.height <= view.minHeight)
            setTextViewText(rootId, label)
            setViewPadding(rootId, view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            setTextColor(rootId, color)
            if (!nested) setOnClickFillInIntent(rootId, Intent().putExtra("date", date.toString()))
        }

    private fun courseRow(context: Context, textContext: Context, occurrence: Occurrence,
        background: Int, dark: Boolean, size: SizeF, essentialsOnly: Boolean = false,
        rootId: Int = R.id.widget_course_body, nested: Boolean = false, fillHeight: Boolean = false,
        maximumNameLines: Int? = null): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_course_row, rootId)
        if (fillHeight) {
            row.setViewPadding(rootId, 0, 0, 0, 0)
            listOf(rootId, R.id.widget_course_surface, R.id.widget_course_content).forEach {
                row.setViewLayoutHeight(it, ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            }
            row.setInt(R.id.widget_course_content, "setGravity", Gravity.CENTER_VERTICAL)
        }
        val fontScale = context.resources.configuration.fontScale
        val fullDayHeight = if (fontScale >= 1.5f) 320f else 240f
        val shortCard = size.height < threeLineNameMinimumHeight(fontScale)
        val verticalPadding = when {
            shortCard -> 6
            size.height < fullDayHeight -> 8
            else -> 12
        }
        row.setViewPadding(R.id.widget_course_content, dp(context, 12), dp(context, verticalPadding),
            dp(context, 12), dp(context, verticalPadding))
        row.setViewPadding(R.id.widget_course_location, 0, dp(context, if (shortCard) 0 else 2), 0, 0)
        val accent = occurrence.course.color.takeIf { it != 0 } ?: CourseColors.colorFor(occurrence.course.name)
        val outOfWeek = OccurrenceStatus.OUT_OF_WEEK in occurrence.statuses
        val holiday = OccurrenceStatus.HOLIDAY in occurrence.statuses
        val swatch = CourseColors.swatch(accent, dark, outOfWeek, holiday, background)
        val status = buildList {
            if (outOfWeek) add(textContext.getString(R.string.platform_out_of_week))
            if (holiday) add(textContext.getString(R.string.platform_no_class))
            if (OccurrenceStatus.MAKEUP in occurrence.statuses) add(textContext.getString(R.string.platform_makeup))
        }.joinToString(" · ")
        val name = courseNameSpacing(occurrence.course.name).take(240)
        val teacher = courseTextSpacing(occurrence.lesson.teacher).take(160)
        val location = courseLocationSpacing(occurrence.lesson.location).take(160)
        val detail = listOf(location, teacher, status).filter { it.isNotBlank() }.joinToString("\n")
        row.setTextViewText(R.id.widget_course_name, name)
        row.setTextViewText(R.id.widget_course_time, "${occurrence.start}–${occurrence.end}")
        row.setTextViewText(R.id.widget_course_teacher, teacher)
        row.setTextViewText(R.id.widget_course_location, location)
        row.setTextViewText(R.id.widget_course_detail, status)
        listOf(R.id.widget_course_name, R.id.widget_course_time, R.id.widget_course_teacher,
            R.id.widget_course_location, R.id.widget_course_detail).forEach { row.setTextColor(it, swatch.content) }
        row.setViewVisibility(R.id.widget_course_teacher, if (!essentialsOnly && teacher.isNotBlank()) View.VISIBLE else View.GONE)
        row.setViewVisibility(R.id.widget_course_location, if (location.isNotBlank()) View.VISIBLE else View.GONE)
        row.setViewVisibility(R.id.widget_course_detail, if (!essentialsOnly && status.isNotBlank()) View.VISIBLE else View.GONE)
        row.setInt(R.id.widget_course_time, "setMaxLines", if (fontScale >= 1.5f) 2 else 1)
        row.setInt(R.id.widget_course_name, "setMaxLines",
            maximumNameLines ?: if (location.isNotBlank() && shortCard) 2 else 3)
        listOf(R.id.widget_course_teacher, R.id.widget_course_location).forEach {
            row.setInt(it, "setMaxLines", if (size.height >= fullDayHeight) 2 else 1)
        }
        row.setInt(R.id.widget_course_surface, "setBackgroundResource", if (outOfWeek) R.drawable.widget_course_outline else R.drawable.widget_course_fill)
        val fill = if (outOfWeek) swatch.outline else swatch.fill
        row.setColorStateList(R.id.widget_course_surface, "setBackgroundTintList", ColorStateList.valueOf(fill))
        row.setViewVisibility(R.id.widget_course_holiday, if (holiday) View.VISIBLE else View.GONE)
        row.setColorStateList(R.id.widget_course_holiday, "setImageTintList", ColorStateList.valueOf(swatch.hatch))
        row.setInt(R.id.widget_course_accent, "setBackgroundColor", swatch.outline)
        row.setViewVisibility(R.id.widget_course_accent, View.GONE)
        // Agenda rows grow with text (including large system fonts), not lesson duration.
        row.setContentDescription(rootId,
            "$name, ${occurrence.start}–${occurrence.end}, $detail")
        if (!nested) row.setOnClickFillInIntent(rootId, Intent().putExtra("date", occurrence.date.toString()))
        return row
    }

}

/** The same configuration activity serves both providers and each widget stores its own theme. */
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AppRepository.get(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val preferences = getSharedPreferences(CourseWidgets.PREFS, MODE_PRIVATE)
        val modes = listOf("APP", "LIGHT", "DARK")
        val labels = listOf(R.string.platform_follow_app, R.string.platform_light, R.string.platform_dark)
        setContent {
            val state by repository.state.collectAsState()
            val configuration = LocalConfiguration.current
            val dark = when (state.settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val textContext = remember(state.settings.language, configuration) {
                PlatformText.context(this, state.settings.language)
            }
            val text = UiText(state.settings.language, configuration.locales[0])
            var mode by rememberSaveable(id) {
                mutableStateOf(preferences.getString("theme:$id", "APP").takeIf { it in modes } ?: "APP")
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                window.isNavigationBarContrastEnforced = false
            }
            WideColorDisplayProvider {
            MaterialTheme(colorScheme = cridColorScheme(dark), typography = Typography()) {
                    Surface(Modifier.fillMaxSize().testTag("widget_configuration"), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .testTag("widget_configuration_scroll")
                                .padding(horizontal = 24.dp).padding(top = 26.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                Text(textContext.getString(R.string.platform_widget_theme),
                                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("widget_configuration_title"))
                                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.selectableGroup().padding(vertical = 8.dp)) {
                                        modes.forEachIndexed { index, choice ->
                                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                                .selectable(selected = mode == choice, role = Role.RadioButton, onClick = { mode = choice })
                                                .testTag("widget_theme_$choice").padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                RadioButton(selected = mode == choice, onClick = null, modifier = alignByVisualCenter())
                                                VisualCenterText(textContext.getString(labels[index]), style = MaterialTheme.typography.bodyLarge,
                                                    modifier = alignByVisualCenter())
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                                    TextButton(onClick = { finish() }, modifier = alignByVisualCenter(Modifier.testTag("widget_configuration_cancel"))) {
                                        VisualCenterText(text.t("取消", "Cancel"))
                                    }
                                    Button(onClick = {
                                        preferences.edit().putString("theme:$id", mode).apply()
                                        CourseWidgets.update(this@WidgetConfigurationActivity, id, repository.state.value, repository.holidays.value)
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                        finish()
                                    }, modifier = alignByVisualCenter(Modifier.testTag("widget_configuration_save"))) {
                                        VisualCenterText(textContext.getString(R.string.platform_save))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object PlatformText {
    fun context(context: Context, language: Language): Context {
        val locale = when (language) {
            Language.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            Language.ZH_TW -> Locale.TRADITIONAL_CHINESE
            Language.EN -> Locale.ENGLISH
            Language.SYSTEM -> context.resources.configuration.locales[0].takeIf { it.language in setOf("zh", "en") } ?: Locale.ENGLISH
        }
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
    }
}
