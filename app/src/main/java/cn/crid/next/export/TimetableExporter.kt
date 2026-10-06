package cn.crid.next.export

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import cn.crid.next.core.*
import cn.crid.next.ui.MinuteSpan
import cn.crid.next.ui.TodayAgendaScale
import cn.crid.next.ui.TodayPeriod
import cn.crid.next.ui.UiText
import cn.crid.next.ui.cridColorScheme
import cn.crid.next.ui.minuteOfDay
import cn.crid.next.ui.timetableGroups
import cn.crid.next.ui.todayAgendaHeights
import cn.crid.next.ui.todayPeriod
import cn.crid.next.ui.todayReferenceDuration
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ExportView { DAY, WEEK, MONTH, SEMESTER }
enum class ExportFormat { PNG, PDF }

data class ExportRequest(
    val semester: Semester,
    val plan: Plan,
    val settings: Settings,
    val calendar: HolidayCalendar,
    val view: ExportView,
    val from: LocalDate,
    val to: LocalDate,
    val format: ExportFormat,
    val pngScale: Int = 2,
)

/** One day/week/month/semester per page; PDF and PNG share every measured coordinate. */
class TimetableExporter(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val systemLocale = context.resources.configuration.locales[0]
    private val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var forceStreamPng = false
    internal constructor(context: Context, streamPng: Boolean) : this(context) { forceStreamPng = streamPng }

    fun estimate(request: ExportRequest): Int = estimate(listOf(request))
    fun estimate(requests: List<ExportRequest>, checkpoint: () -> Unit = {}): Int = documents(requests, checkpoint).sumOf { it.pages.size }

    suspend fun export(request: ExportRequest, treeUri: Uri): List<Uri> = export(listOf(request), treeUri)
    suspend fun export(requests: List<ExportRequest>, treeUri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val documents = documents(requests) { coroutineContext.ensureActive() }
        val words = documents.first().words
        val pages = documents.flatMap { document -> document.pages.map { document to it } }
        require(DocumentsContract.isTreeUri(treeUri)) { words.chooseFolder }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val created = mutableListOf<Uri>()
        val first = requests.first()
        val stem = (if (requests.size == 1) first.plan.name else "Crid Next").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(64).ifBlank { "Crid Next" }
        val datedStem = "$stem-${pages.first().second.unit.from}-${pages.last().second.unit.to}"
        try {
            if (first.format == ExportFormat.PDF) {
                currentCoroutineContext().ensureActive()
                val uri = DocumentsContract.createDocument(resolver, parent, "application/pdf", "$datedStem.pdf") ?: error(words.writeFailed)
                created += uri
                val pdf = PdfDocument()
                try {
                    pages.forEachIndexed { index, (document, page) ->
                        currentCoroutineContext().ensureActive()
                        val target = pdf.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create())
                        try { draw(target.canvas, document, page, index, pages.size) }
                        finally { pdf.finishPage(target) }
                    }
                    currentCoroutineContext().ensureActive()
                    resolver.openOutputStream(uri, "w")?.use(pdf::writeTo) ?: error(words.writeFailed)
                } finally { pdf.close() }
            } else {
                pages.forEachIndexed { index, (document, page) ->
                    currentCoroutineContext().ensureActive()
                    val uri = DocumentsContract.createDocument(resolver, parent, "image/png", "$datedStem-${(index + 1).toString().padStart(3, '0')}.png") ?: error(words.writeFailed)
                    created += uri
                    val pixelWidth = page.width * first.pngScale
                    val pixelHeight = page.height * first.pngScale
                    resolver.openOutputStream(uri, "w")?.use { output ->
                        if (!forceStreamPng && pixelWidth.toLong() * pixelHeight <= BITMAP_PIXELS) {
                            val bitmap = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(bitmap)
                                canvas.scale(first.pngScale.toFloat(), first.pngScale.toFloat())
                                draw(canvas, document, page, index, pages.size)
                                coroutineContext.ensureActive()
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { words.writeFailed }
                            } finally { bitmap.recycle() }
                        } else {
                            // Keep rounded-edge antialiasing away from the strip's clip boundary.
                            // Guard rows count toward the same bounded bitmap allocation.
                            val guard = minOf(BAND_GUARD_ROWS, ((BAND_PIXELS / pixelWidth - 1) / 2).coerceAtLeast(0))
                            val bandHeight = minOf(128, (BAND_PIXELS / pixelWidth - guard * 2).coerceAtLeast(1), pixelHeight)
                            val bitmap = Bitmap.createBitmap(pixelWidth, bandHeight + guard * 2, Bitmap.Config.ARGB_8888)
                            try {
                                val pixels = IntArray(pixelWidth)
                                StreamingPng.write(output, pixelWidth, pixelHeight) { appendRow ->
                                    var top = 0
                                    while (top < pixelHeight) {
                                        coroutineContext.ensureActive()
                                        val canvas = Canvas(bitmap)
                                        canvas.translate(0f, (guard - top).toFloat())
                                        canvas.scale(first.pngScale.toFloat(), first.pngScale.toFloat())
                                        draw(canvas, document, page, index, pages.size)
                                        val rows = minOf(bandHeight, pixelHeight - top)
                                        repeat(rows) { row ->
                                            if (row % 32 == 0) coroutineContext.ensureActive()
                                            bitmap.getPixels(pixels, 0, pixelWidth, 0, row + guard, pixelWidth, 1)
                                            appendRow(pixels)
                                        }
                                        top += rows
                                    }
                                }
                            } finally { bitmap.recycle() }
                        }
                    } ?: error(words.writeFailed)
                }
            }
            currentCoroutineContext().ensureActive()
            created.toList()
        } catch (failure: Throwable) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw failure
        }
    }

    private fun documents(requests: List<ExportRequest>, checkpoint: () -> Unit = {}): List<Document> {
        checkpoint()
        require(requests.isNotEmpty())
        require(requests.all { it.format == requests.first().format && it.pngScale == requests.first().pngScale })
        val result = requests.map { checkpoint(); layout(it, checkpoint) }
        require(result.sumOf { it.pages.size } <= MAX_PAGES) { result.first().words.tooManyPages }
        return result
    }

    private data class Line(val text: String, val size: Float, val bold: Boolean = false, val secondary: Boolean = false,
        val gapBefore: Float = 0f) {
        val height get() = size * 1.42f
    }
    private data class Card(val occurrence: Occurrence, val records: List<Occurrence>, val span: MinuteSpan,
        val lines: List<Line>, val height: Float, val padding: Float, val outOfWeek: Boolean, val holiday: Boolean, val swatch: CourseSwatch)
    private data class Day(val date: LocalDate?, val cards: List<Card>, val rest: Boolean)
    private data class CardPosition(val card: Card, val x: Float, val y: Float, val width: Float, val height: Float)
    private data class AgendaHeading(val period: TodayPeriod, val y: Float)
    private data class PeriodPosition(val period: Period, val top: Float, val bottom: Float)
    private data class Tile(val week: ExportWeek, val days: List<Day>, val periods: List<PeriodPosition>,
        val cards: List<CardPosition>, val headings: List<AgendaHeading>, val width: Float, val height: Float)
    private data class TilePosition(val tile: Tile, val x: Float, val y: Float)
    private data class Page(val unit: ExportUnit, val width: Int, val height: Int, val header: List<Line>, val headerHeight: Float,
        val tiles: List<TilePosition>, val pendingCount: Int)
    private data class Palette(val dark: Boolean) {
        private val scheme = cridColorScheme(dark)
        val background = scheme.surface.toArgb()
        val ink = scheme.onSurface.toArgb()
        val secondary = scheme.onSurfaceVariant.toArgb()
        val border = scheme.outlineVariant.copy(alpha = .35f).toArgb()
        val axis = scheme.outlineVariant.toArgb()
        val primary = scheme.primary.toArgb()
        val onPrimary = scheme.onPrimary.toArgb()
        val holidayShade = scheme.onSurface.copy(alpha = .03f).toArgb()
        val holidayHatch = scheme.onSurface.copy(alpha = .075f).toArgb()
    }
    private data class Document(val request: ExportRequest, val words: Words, val palette: Palette, val pages: List<Page>, val today: LocalDate = LocalDate.now())

    /** Structural evidence comes from the cards actually drawn in the main view. */
    internal data class CardBounds(val left: Float, val top: Float, val right: Float, val bottom: Float, val contentHeight: Float, val durationMinutes: Long)
    internal data class PageInspection(val unit: ExportUnit, val width: Int, val height: Int,
        val courseRecords: List<Pair<LocalDate, Lesson>>, val pendingCount: Int, val weekStarts: List<LocalDate>, val minimumFont: Float,
        val cardBounds: List<CardBounds>, val cardTexts: List<String>, val headerTexts: List<String>)
    internal fun inspect(request: ExportRequest): List<PageInspection> = layout(request).pages.map { page ->
        val cards = page.tiles.flatMap { it.tile.cards }
        PageInspection(page.unit, page.width, page.height,
            cards.flatMap { it.card.records.map { record -> record.date to record.lesson } },
            page.pendingCount, page.tiles.map { it.tile.week.start },
            (page.header + cards.flatMap { it.card.lines }).minOf { it.size },
            page.tiles.flatMap { tile -> tile.tile.cards.map { position ->
                CardBounds(tile.x + position.x, tile.y + position.y, tile.x + position.x + position.width,
                    tile.y + position.y + position.height, position.card.height,
                    (position.card.span.end - position.card.span.start).toLong())
            } }, cards.map { it.card.lines.joinToString("\n") { line -> line.text } }, page.header.map { it.text })
    }

    private fun layout(request: ExportRequest, checkpoint: () -> Unit = {}): Document {
        checkpoint()
        val words = Words(request.settings.language, systemLocale)
        val text = TextLayout()
        val palette = Palette(when (request.settings.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark })
        require(request.to >= request.from) { words.badDates }
        require(ChronoUnit.DAYS.between(request.from, request.to) < 732) { words.rangeTooLong }
        require(request.plan.semesterId == request.semester.id) { words.wrongPlan }
        if (request.format == ExportFormat.PNG) require(request.pngScale in 1..3) { words.badScale }
        val units = ExportUnits.pages(request.view, request.semester, request.from, request.to, request.settings.weekStartsSunday)
        val schedule = ScheduleEngine.prepare(request.semester, request.plan, request.settings, request.calendar)
        val pages = units.map { unit ->
            checkpoint()
            val prepared = unit.weeks.map { week -> week to week.days.map { date ->
                checkpoint()
                prepareDay(request, schedule, words, palette, text, date, checkpoint)
            } }
            val tiles = if (request.view == ExportView.DAY) {
                prepared.map { (week, days) -> measureAgenda(request, week, days) }
            } else {
                val axes = ExportTimeline.pagePositions(prepared.map { timelineInput(request, it.second) })
                prepared.mapIndexed { index, (week, days) -> measureGrid(request, week, days, axes[index]) }
            }
            val columns = when (request.view) { ExportView.MONTH -> 2; ExportView.SEMESTER -> if (tiles.size > 6) 3 else 2; else -> 1 }.coerceAtMost(tiles.size)
            val tileWidth = tiles.first().width
            val width = ceil(MARGIN * 2 + columns * tileWidth + (columns - 1) * TILE_GAP).toInt()
            val pending = pendingCount(request, unit)
            val header = text.wrap(request.plan.name, width - MARGIN * 2, 34f, true) +
                text.wrap("${request.semester.name} · ${words.view(request.view)}", width - MARGIN * 2, 20f, secondary = true) +
                text.wrap(if (request.view == ExportView.DAY) words.date(unit.from) else "${unit.from} — ${unit.to}", width - MARGIN * 2, 19f, secondary = true) +
                if (pending > 0) text.wrap(words.pendingCount(pending), width - MARGIN * 2, 19f, secondary = true) else emptyList()
            val headerHeight = MARGIN + header.sumOf { it.height.toDouble() }.toFloat() + 30f
            val positions = mutableListOf<TilePosition>()
            var y = headerHeight
            tiles.chunked(columns).forEach { row ->
                row.forEachIndexed { column, tile -> positions += TilePosition(tile, MARGIN + column * (tileWidth + TILE_GAP), y) }
                y += row.maxOf { it.height } + TILE_GAP
            }
            val height = ceil(y + FOOTER).toInt()
            require(width > 0 && height > 0) { words.tooMuchText }
            if (request.format == ExportFormat.PNG) require(
                width.toLong() * height * request.pngScale * request.pngScale <= MAX_PIXELS &&
                    width.toLong() * request.pngScale <= MAX_PNG_WIDTH && height.toLong() * request.pngScale <= MAX_PNG_HEIGHT
            ) { words.tooManyPixels }
            Page(unit, width, height, header, headerHeight, positions, pending)
        }
        return Document(request, words, palette, pages)
    }

    private fun prepareDay(request: ExportRequest, schedule: PreparedSchedule, words: Words, palette: Palette,
        text: TextLayout, date: LocalDate?, checkpoint: () -> Unit): Day {
        if (date == null) return Day(null, emptyList(), false)
        val agenda = request.view == ExportView.DAY
        val occurrences = schedule.occurrences(date).let { if (agenda) it.filter { occurrence -> occurrence.isActual } else it }
        val periodStarts = if (agenda) emptySet() else request.semester.periods.mapTo(HashSet()) { LocalTime.parse(it.start) }
        val periodEnds = if (agenda) emptySet() else request.semester.periods.mapTo(HashSet()) { LocalTime.parse(it.end) }
        val padding = if (agenda) DAY_CARD_PAD else CARD_PAD
        val textWidth = (if (agenda) DAY_CARD_WIDTH else CARD_WIDTH) - padding * 2
        fun card(item: Occurrence, records: List<Occurrence>, span: MinuteSpan, title: String, location: String, teacher: String,
            out: Boolean, holiday: Boolean, status: String, clockTimes: String = ""): Card {
            checkpoint()
            val lines = text.wrap(title, textWidth, if (agenda) 28f else 24f, true).toMutableList()
            fun field(value: String) {
                if (value.isBlank()) return
                val wrapped = text.wrap(value, textWidth, if (agenda) 24f else 20f, secondary = true)
                lines += wrapped.mapIndexed { index, line -> if (index == 0) line.copy(gapBefore = if (agenda) 8f else 5f) else line }
            }
            field(clockTimes)
            field(location)
            field(teacher)
            // The printed view retains status information that is also conveyed by color and hatching.
            if (!agenda) field(status)
            val height = lines.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat() + padding * 2
            val swatch = CourseColors.swatch(courseColor(item.course), dark = palette.dark, outOfWeek = out,
                holiday = holiday, surface = palette.background)
            return Card(item, records, span, lines, height, padding, out, holiday, swatch)
        }
        val cards = if (agenda) displayCourses(occurrences).map { display ->
            val item = display.representative
            card(item, display.occurrences.distinctBy { it.lesson }, MinuteSpan(item.start.minuteOfDay(), item.end.minuteOfDay()),
                courseNameSpacing(item.course.name),
                item.lesson.location.takeIf { it.isNotBlank() }?.let { words.location + courseLocationSpacing(it) }.orEmpty(),
                item.lesson.teacher.takeIf { it.isNotBlank() }?.let { words.teacher + courseTextSpacing(it) }.orEmpty(), false, false, "")
        } else timetableGroups(occurrences).map { group ->
            val items = group.courses.sortedBy { !it.representative.isActual }.map { it.representative }
            val out = group.occurrences.all { OccurrenceStatus.OUT_OF_WEEK in it.statuses }
            val holiday = group.occurrences.all { OccurrenceStatus.HOLIDAY in it.statuses }
            val status = (items.map(words::status).filter { it.isNotBlank() }.distinct() +
                if (group.hasConflict) listOf("${words.overlap} · ${group.conflictCourseCount}") else emptyList()).joinToString(" · ")
            // A custom endpoint has no matching period label. Keep one time range per title,
            // including standard courses in a mixed group, so the order stays unambiguous.
            val clockTimes = if (items.any { it.start !in periodStarts || it.end !in periodEnds })
                items.joinToString(" / ") { "${it.start.format(TIME)}–${it.end.format(TIME)}" } else ""
            val names = items.map { courseNameSpacing(it.course.name) }
            card(group.representative, group.courses.flatMap { it.occurrences.distinctBy { record -> record.lesson } }, group.span,
                (if (clockTimes.isEmpty()) names.distinct() else names).joinToString(" / "),
                items.map { courseLocationSpacing(it.lesson.location) }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                items.map { courseTextSpacing(it.lesson.teacher) }.filter { it.isNotBlank() }.distinct().joinToString(" / "), out, holiday, status, clockTimes)
        }
        val holiday = request.calendar.days.find { it.date == date.toString() }
        val rest = request.settings.holidaysEnabled && holiday?.let { it.statutory || it.extraRest && request.settings.makeupMode != MakeupMode.OFF } == true
        return Day(date, cards, rest)
    }

    private fun measureAgenda(request: ExportRequest, week: ExportWeek, days: List<Day>): Tile {
        val cards = days.single().cards
        val reference = todayReferenceDuration(request.semester.periods)
        val scale = TodayAgendaScale(reference, 2f, 1f)
        val content = cards.map { card ->
            ceil(card.height - card.padding * 2 + scale.verticalPaddingPixels(card.span.end - card.span.start) * 2).toInt()
        }
        val heights = todayAgendaHeights(cards.map { it.span }, content, 2f, 1f, reference)
        val headings = mutableListOf<AgendaHeading>()
        val positions = mutableListOf<CardPosition>()
        var y = 0f
        TodayPeriod.entries.forEach { period ->
            val indices = cards.indices.filter { todayPeriod(cards[it].occurrence.start) == period }
            if (indices.isEmpty()) return@forEach
            if (headings.isNotEmpty()) y += 24f
            headings += AgendaHeading(period, y)
            y += 48f
            indices.forEach { index ->
                val original = cards[index]
                val padding = scale.verticalPaddingPixels(original.span.end - original.span.start).toFloat()
                val card = original.copy(height = content[index].toFloat(), padding = padding)
                positions += CardPosition(card, DAY_AXIS_WIDTH + DAY_GAP, y, DAY_CARD_WIDTH, heights[index].toFloat())
                y += heights[index] + DAY_GAP
            }
        }
        return Tile(week, days, emptyList(), positions, headings, DAY_AXIS_WIDTH + DAY_GAP + DAY_CARD_WIDTH, maxOf(120f, y))
    }

    private fun timelineInput(request: ExportRequest, days: List<Day>): ExportTimeline.Input {
        val cards = days.flatMap { it.cards }
        if (cards.isEmpty()) return ExportTimeline.Input(emptyList(), emptyList())
        val periods = request.semester.periods.map { ExportTimeline.Span(LocalTime.parse(it.start).minuteOfDay(),
            LocalTime.parse(it.end).minuteOfDay(), PERIOD_HEIGHT) }
        val periodMinutes = periods.flatMap { listOf(it.start, it.end) }
        return ExportTimeline.Input((cards.flatMap { listOf(it.span.start, it.span.end) } + periodMinutes).distinct().sorted(), cards.map {
            ExportTimeline.Span(it.span.start, it.span.end, it.height + GAP)
        }, periods)
    }

    private fun measureGrid(request: ExportRequest, week: ExportWeek, days: List<Day>, axis: Map<Int, Float>): Tile {
        val cards = days.flatMapIndexed { index, day -> day.cards.map { card ->
            val top = axis.getValue(card.span.start)
            CardPosition(card, AXIS_WIDTH + GAP + index * (CARD_WIDTH + GAP), TILE_HEADER + top, CARD_WIDTH,
                axis.getValue(card.span.end) - top - GAP)
        } }
        val periods = if (axis.isEmpty()) emptyList() else request.semester.periods.map { period ->
            PeriodPosition(period, axis.getValue(LocalTime.parse(period.start).minuteOfDay()),
                axis.getValue(LocalTime.parse(period.end).minuteOfDay()))
        }
        return Tile(week, days, periods, cards, emptyList(), AXIS_WIDTH + days.size * (CARD_WIDTH + GAP),
            TILE_HEADER + maxOf(100f, axis.values.maxOrNull() ?: 0f) + 14f)
    }

    private fun pendingCount(request: ExportRequest, unit: ExportUnit): Int {
        val weeks = unit.dates.mapNotNull { ScheduleEngine.weekNumber(request.semester, it, request.settings.weekStartsSunday) }.toSet()
        return request.plan.courses.sumOf { course -> course.lessons.count { lesson -> lesson.unscheduled && lesson.weeks.any { it in weeks } } }
    }

    private fun draw(canvas: Canvas, document: Document, page: Page, pageIndex: Int, totalPages: Int) {
        val palette = document.palette
        val agenda = document.request.view == ExportView.DAY
        canvas.drawColor(palette.background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun line(value: Line, x: Float, top: Float) = drawLine(canvas, paint, value, x, top, if (value.secondary) palette.secondary else palette.ink)
        fun centered(value: Line, center: Float, top: Float, foreground: Int? = null) {
            paint.textSize = value.size
            paint.typeface = if (value.bold) BOLD_TYPEFACE else REGULAR_TYPEFACE
            drawLine(canvas, paint, value, center - paint.measureText(value.text) / 2, top,
                foreground ?: if (value.secondary) palette.secondary else palette.ink)
        }
        var y = MARGIN
        page.header.forEach { y = line(it, MARGIN, y) }
        canvas.drawLine(MARGIN, page.headerHeight - 16f, page.width - MARGIN, page.headerHeight - 16f, paint.apply { color = palette.border; strokeWidth = 1f })
        page.tiles.forEach { position ->
            val tile = position.tile
            if (canvas.quickReject(position.x, position.y, position.x + tile.width, position.y + tile.height + 16f)) return@forEach
            if (agenda) {
                tile.headings.forEach { line(Line(document.words.period(it.period), 26f, true), position.x + DAY_AXIS_WIDTH + DAY_GAP, position.y + it.y) }
                if (tile.cards.isEmpty()) line(Line(document.words.noClasses, 24f, secondary = true), position.x + DAY_AXIS_WIDTH + DAY_GAP, position.y + 24f)
            } else {
                val visibleDate = tile.days.firstNotNullOfOrNull { it.date }
                val week = visibleDate?.let { ScheduleEngine.weekNumber(document.request.semester, it, document.request.settings.weekStartsSunday) }
                line(Line("${week?.let(document.words::week) ?: document.words.outsideSemester} · ${tile.week.start} — ${tile.week.start.plusDays(6)}", 20f, true), position.x, position.y)
                val bodyY = position.y + TILE_HEADER
                centered(Line(document.words.periodLabel, 18f, secondary = true), position.x + AXIS_WIDTH / 2, position.y + SECTION_TITLE)
                tile.periods.forEach { period ->
                    val lineY = bodyY + period.top
                    paint.color = palette.border; paint.strokeWidth = 1f
                    canvas.drawLine(position.x + AXIS_WIDTH + GAP, lineY, position.x + tile.width, lineY, paint)
                    val labels = listOf(Line(period.period.number.toString(), 21f, true),
                        Line(period.period.start, 16f, secondary = true), Line(period.period.end, 16f, secondary = true))
                    var labelY = bodyY + (period.top + period.bottom - labels.sumOf { it.height.toDouble() }.toFloat()) / 2
                    labels.forEach { label ->
                        centered(label, position.x + AXIS_WIDTH / 2, labelY)
                        labelY += label.height
                    }
                }
                tile.days.forEachIndexed { index, day ->
                    val x = position.x + AXIS_WIDTH + GAP + index * (CARD_WIDTH + GAP)
                    if (day.rest) {
                        val rect = RectF(x, bodyY, x + CARD_WIDTH, position.y + tile.height)
                        paint.color = palette.holidayShade
                        canvas.drawRect(rect, paint)
                        shade(canvas, rect, paint, palette.holidayHatch)
                    }
                    day.date?.let { date ->
                        centered(Line(document.words.weekday(date), 19f, secondary = true), x + CARD_WIDTH / 2, position.y + SECTION_TITLE)
                        val today = date == document.today
                        if (today) {
                            paint.style = Paint.Style.FILL; paint.color = palette.primary
                            canvas.drawCircle(x + CARD_WIDTH / 2, position.y + SECTION_TITLE + 56f, 28f, paint)
                        }
                        centered(Line(date.dayOfMonth.toString(), 25f, true), x + CARD_WIDTH / 2, position.y + SECTION_TITLE + 38f,
                            if (today) palette.onPrimary else palette.ink)
                    }
                }
                if (tile.cards.isEmpty()) centered(Line(document.words.noClasses, 24f, secondary = true),
                    position.x + AXIS_WIDTH + (tile.width - AXIS_WIDTH) / 2, bodyY + 24f)
            }
            tile.cards.forEach cardLoop@ { cardPosition ->
                val x = position.x + cardPosition.x
                val top = position.y + cardPosition.y
                if (canvas.quickReject(position.x - 1f, top - 1f, x + cardPosition.width + 1f, top + cardPosition.height + 1f)) return@cardLoop
                if (agenda) {
                    val center = position.x + DAY_AXIS_WIDTH / 2
                    val bottom = top + cardPosition.height
                    paint.color = palette.axis; paint.strokeWidth = 2f
                    canvas.drawLine(center, top + 38f, center, bottom - 38f, paint)
                    centered(Line(cardPosition.card.occurrence.start.format(TIME), 24f, secondary = true), center, top)
                    centered(Line(cardPosition.card.occurrence.end.format(TIME), 24f, secondary = true), center, bottom - 34f)
                }
                drawCard(canvas, paint, cardPosition.card, x, top, cardPosition.width, cardPosition.height, agenda)
            }
        }
        line(Line("Crid Next", 17f, true, true), MARGIN, page.height - 42f)
        val footer = "${pageIndex + 1} / $totalPages"
        paint.textSize = 17f; paint.typeface = REGULAR_TYPEFACE
        line(Line(footer, 17f, secondary = true), page.width - MARGIN - paint.measureText(footer), page.height - 42f)
    }

    private fun drawCard(canvas: Canvas, paint: Paint, card: Card, x: Float, top: Float, width: Float, height: Float, agenda: Boolean) {
        if (canvas.quickReject(x - 1f, top - 1f, x + width + 1f, top + height + 1f)) return
        val swatch = card.swatch
        val rect = RectF(x, top, x + width, top + height)
        val radius = if (agenda) 28f else 14f
        paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = swatch.fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (card.holiday) {
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW) })
            val hatch = (swatch.hatch and 0x00ffffff) or (((swatch.hatch ushr 24) * .6f).roundToInt() shl 24)
            shade(canvas, rect, paint, hatch)
            canvas.restore()
        }
        if (card.outOfWeek) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.1f; paint.color = swatch.outline; paint.pathEffect = OUT_OF_WEEK_DASH
            canvas.drawRoundRect(RectF(x + 1.1f, top + 1.1f, x + width - 1.1f, top + height - 1.1f), radius, radius, paint)
        }
        paint.pathEffect = null; paint.style = Paint.Style.FILL
        var y = top + card.padding
        val horizontalPadding = if (agenda) DAY_CARD_PAD else CARD_PAD
        card.lines.forEach { y = drawLine(canvas, paint, it, x + horizontalPadding, y + it.gapBefore, swatch.content) }
    }

    private fun shade(canvas: Canvas, rect: RectF, paint: Paint, hatch: Int) {
        canvas.save()
        canvas.clipRect(rect)
        paint.color = hatch; paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.strokeWidth = 5.25f
        var x = rect.left - rect.height()
        while (x < rect.right) { canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, paint); x += 22.75f }
        canvas.restore()
    }

    private fun drawLine(canvas: Canvas, paint: Paint, line: Line, x: Float, top: Float, foreground: Int): Float {
        paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = foreground
        paint.textSize = line.size
        paint.typeface = if (line.bold) BOLD_TYPEFACE else REGULAR_TYPEFACE
        canvas.drawText(line.text, x, top - paint.fontMetrics.ascent, paint)
        return top + line.height
    }

    /** A document reuses identical course labels across weeks; release the cache after layout. */
    private class TextLayout {
        private data class Key(val text: String, val width: Float, val size: Float, val bold: Boolean, val secondary: Boolean)
        private val cache = HashMap<Key, List<Line>>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Prefer language-aware line opportunities; split long words at grapheme boundaries. */
        fun wrap(text: String, width: Float, size: Float, bold: Boolean = false, secondary: Boolean = false): List<Line> {
            val key = Key(text, width, size, bold, secondary)
            cache[key]?.let { return it }
            paint.textSize = size
            paint.typeface = if (bold) BOLD_TYPEFACE else REGULAR_TYPEFACE
            val result = text.replace("\r\n", "\n").split('\n').flatMap { paragraph ->
                if (paragraph.isEmpty()) return@flatMap listOf(Line("", size, bold, secondary))
                val characters = java.text.BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(paragraph) }
                val opportunities = java.text.BreakIterator.getLineInstance(Locale.ROOT).apply { setText(paragraph) }
                val lines = mutableListOf<Line>()
                var start = 0
                while (start < paragraph.length) {
                    var boundary = characters.following(start)
                    var fitted = start
                    while (boundary != java.text.BreakIterator.DONE) {
                        if (paint.measureText(paragraph, start, boundary) > width) {
                            if (fitted == start) fitted = boundary
                            break
                        }
                        fitted = boundary
                        boundary = characters.next()
                    }
                    val preferred = if (fitted == paragraph.length || opportunities.isBoundary(fitted)) fitted else opportunities.preceding(fitted)
                    val end = preferred.takeIf { it > start } ?: fitted
                    lines += Line(paragraph.substring(start, end).trimEnd(), size, bold, secondary)
                    start = end
                    while (start < paragraph.length && paragraph[start].isWhitespace()) start++
                }
                lines
            }
            if (cache.size < 1024) cache[key] = result
            return result
        }
    }

    private fun courseColor(course: Course): Int = course.color.takeIf { it != 0 } ?: CourseColors.colorFor(course.name)
    private class Words(language: Language, system: Locale) {
        private val uiText = UiText(language, system)
        private val english = language == Language.EN || language == Language.SYSTEM && system.language != "zh"
        private val traditional = language == Language.ZH_TW || language == Language.SYSTEM && (system.country in setOf("TW", "HK", "MO") || system.script == "Hant")
        private fun t(cn: String, en: String, tw: String = cn) = if (english) en else if (traditional) tw else cn
        val noClasses = t("无课程", "No classes", "無課程")
        val outsideSemester = t("学期外", "Outside semester", "學期外")
        val overlap = t("时间重叠", "Overlapping time", "時間重疊")
        val periodLabel = t("节次", "Period", "節次")
        fun pendingCount(count: Int) = t("另有 $count 项待排课", "$count unscheduled class ${if (count == 1) "entry" else "entries"}", "另有 $count 項待排課")
        val location = t("地点： ", "Location: ", "地點： ")
        val teacher = t("教师： ", "Teacher: ", "教師： ")
        fun period(period: TodayPeriod) = when (period) {
            TodayPeriod.MORNING -> t("上午", "Morning")
            TodayPeriod.AFTERNOON -> t("下午", "Afternoon")
            TodayPeriod.EVENING -> t("晚上", "Evening")
        }
        val chooseFolder = t("请选择保存文件夹", "Choose a destination folder", "請選擇儲存資料夾")
        val writeFailed = t("无法保存文件，请检查所选位置的写入权限和剩余空间", "Could not save. Check the destination permission and free space.", "無法儲存檔案，請檢查所選位置的寫入權限與剩餘空間")
        val badDates = t("结束日期不能早于起始日期", "The end date must not precede the start date.", "結束日期不能早於起始日期")
        val rangeTooLong = t("一次最多导出两年的日期范围", "Export up to two years at a time.", "一次最多匯出兩年的日期範圍")
        val wrongPlan = t("所选方案不属于此学期", "The selected plan does not belong to this semester.", "所選方案不屬於此學期")
        val badScale = t("请选择 1、2 或 3 倍图片分辨率", "Choose an image resolution of 1×, 2× or 3×.", "請選擇 1、2 或 3 倍圖片解析度")
        val tooManyPixels = t("此分辨率下图片过大，请降低分辨率或选择 PDF", "The image is too large at this resolution. Choose a lower resolution or PDF.", "此解析度下圖片過大，請降低解析度或選擇 PDF")
        val tooMuchText = t("部分课程文字过长，无法完整排入一页，请选择日视图或检查课程内容", "Some course text is too long for a page. Choose day view or check the course details.", "部分課程文字過長，無法完整排入一頁，請選擇日視圖或檢查課程內容")
        val tooManyPages = t("导出超过 750 页，请缩小日期范围", "The export exceeds 750 pages. Choose a shorter date range.", "匯出超過 750 頁，請縮小日期範圍")
        fun view(view: ExportView) = when(view) {
            ExportView.DAY -> t("日课表", "Daily timetable", "日課表")
            ExportView.WEEK -> t("周课表", "Weekly timetable", "週課表")
            ExportView.MONTH -> t("月课表", "Monthly timetable", "月課表")
            ExportView.SEMESTER -> t("学期课表", "Semester timetable", "學期課表")
        }
        fun week(week: Int) = t("第 $week 周", "Week $week", "第 $week 週")
        fun date(date: LocalDate): String = uiText.date(date)
        fun weekday(date: LocalDate): String = uiText.weekday(date)
        fun status(item: Occurrence): String = buildList {
            if (OccurrenceStatus.OUT_OF_WEEK in item.statuses) add(t("非本周", "Not this week", "非本週"))
            if (OccurrenceStatus.HOLIDAY in item.statuses) add(listOf(holidayName(item.holidayName.orEmpty()), t("休假停课", "Holiday · no class", "休假停課")).filter { it.isNotBlank() }.joinToString(" · "))
            if (OccurrenceStatus.MAKEUP in item.statuses) add(t("调休补课", "Make-up class", "調休補課"))
        }.joinToString(" · ")

        private fun holidayName(name: String) = when (name.trim().lowercase(Locale.ROOT)) {
            "元旦", "new year's day" -> t("元旦", "New Year's Day", "元旦")
            "春节", "春節", "spring festival", "chinese new year" -> t("春节", "Spring Festival", "春節")
            "清明节", "清明節", "qingming festival" -> t("清明节", "Qingming Festival", "清明節")
            "劳动节", "勞動節", "labour day", "labor day" -> t("劳动节", "Labour Day", "勞動節")
            "端午节", "端午節", "dragon boat festival" -> t("端午节", "Dragon Boat Festival", "端午節")
            "中秋节", "中秋節", "mid-autumn festival" -> t("中秋节", "Mid-Autumn Festival", "中秋節")
            "国庆节", "國慶節", "national day" -> t("国庆节", "National Day", "國慶節")
            else -> name
        }
    }

    private companion object {
        const val MARGIN = 48f
        const val GAP = 12f
        const val CARD_PAD = 9f
        const val DAY_CARD_PAD = 24f
        const val DAY_AXIS_WIDTH = 108f
        const val DAY_GAP = 16f
        const val SECTION_TITLE = 42f
        const val TILE_HEADER = 140f
        const val TILE_GAP = 36f
        const val AXIS_WIDTH = 84f
        // Three complete lines: ceil((21 + 16 + 16) * 1.42), without extra vertical padding.
        const val PERIOD_HEIGHT = 76f
        const val CARD_WIDTH = 240f
        const val DAY_CARD_WIDTH = 600f
        const val FOOTER = 76f
        const val BITMAP_PIXELS = 24_000_000L
        const val BAND_PIXELS = 1_048_576
        const val BAND_GUARD_ROWS = 4
        const val MAX_PIXELS = 192_000_000L
        const val MAX_PNG_WIDTH = 16_384
        const val MAX_PNG_HEIGHT = 65_535
        const val MAX_PAGES = 750
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val REGULAR_TYPEFACE: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val BOLD_TYPEFACE: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val OUT_OF_WEEK_DASH = DashPathEffect(floatArrayOf(8.75f, 5.25f), 0f)
    }
}
