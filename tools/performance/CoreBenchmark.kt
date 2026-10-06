package cn.crid.next.benchmark

import cn.crid.next.core.*
import cn.crid.next.platform.ReminderSnapshot
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/** Deterministic JVM computation workloads; this does not measure Android rendering. */
private object Sink { @Volatile var value: Any? = null }

private class Fixture(courseCount: Int) {
    val first = LocalDate.of(2026, 9, 7)
    val dates = List(112) { first.plusDays(it.toLong()) }
    val semester = Semester("benchmark-semester", "2026秋", first.toString(), dates.last().toString(), 16,
        List(12) { index ->
            val start = LocalTime.of(8, 0).plusHours(index.toLong())
            Period(index + 1, start.toString(), start.plusMinutes(45).toString())
        })
    val courses = List(courseCount) { index ->
        val weekday = index % 7 + 1
        val period = (index % 6) * 2 + 1
        val weeks = (1..16).filter { index % 3 == 0 || it % 2 == index % 2 }
        val otherWeeks = (1..16).filter { it !in weeks }.ifEmpty { listOf(16) }
        val regular = Lesson(weeks, weekday, period, period, location = "教学楼 ${index % 20 + 1}", teacher = "教师 ${index % 37}")
        val fixed = first.plusDays((index % 112).toLong())
        Course(id = "course-$index", name = "课程 ${index.toString().padStart(3, '0')}", credits = "2", lessons = listOf(
            regular,
            regular.copy(startPeriod = period + 1, endPeriod = period + 1),
            regular.copy(date = fixed.toString(), weekday = fixed.dayOfWeek.value, weeks = emptyList(), note = "单次安排"),
            regular.copy(weekday = weekday % 7 + 1, startPeriod = null, endPeriod = null, startTime = "20:00", endTime = "20:45"),
            Lesson(weeks = weeks, weekday = 0, teacher = "待排课教师", unscheduled = true),
            regular.copy(weeks = otherWeeks, teacher = "另一教师 ${index % 29}"),
        ))
    }
    val plan = Plan("benchmark-plan", semester.id, "benchmark", courses)
    val settings = Settings(makeupMode = MakeupMode.ON, remindersEnabled = true)
    val calendar = HolidayCalendar(days = listOf(
        HolidayDay(dates[24].toString(), "假日", statutory = true),
        HolidayDay(dates[25].toString(), "休息", extraRest = true),
        HolidayDay(dates[26].toString(), "调课", workday = true, teachingDate = dates[24].toString()),
        HolidayDay(dates[26].toString(), "重复日期保持首条", statutory = true),
        HolidayDay(dates[40].toString(), "无映射", workday = true, teachingDate = "2027-09-01"),
    ))
    val state = AppState(listOf(semester), listOf(plan), semester.id, plan.id, settings)

    init {
        check(DataValidator.validateSemester(semester).isEmpty())
        check(DataValidator.validateCourses(courses, semester).isEmpty())
    }
}

private fun occurrenceText(o: Occurrence): String = listOf(o.course.id, o.course.name, o.lesson.toString(),
    o.date, o.start, o.end, o.week, o.statuses.sortedBy { it.name }, o.holidayName).joinToString("|")

private fun digest(lines: Sequence<String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    lines.forEach { hash.update(it.toByteArray(Charsets.UTF_8)); hash.update(10.toByte()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

private fun cardText(card: DisplayCourse): Sequence<String> =
    sequenceOf("card|${occurrenceText(card.representative)}|${card.weeks}|${card.isActual}") +
        card.occurrences.asSequence().map(::occurrenceText)

/** Fixed-seed cases exercise equal slots, adjacent periods and overlapping other-week cards. */
private fun randomizedProjectionDigest(fixture: Fixture): String {
    val random = Random(20261002)
    val cases = List(128) { trial ->
        val occurrences = List(40) { index ->
            val period = random.nextInt(1, 12)
            val date = fixture.first.plusDays(random.nextInt(3).toLong())
            val weekParity = random.nextInt(3)
            val weeks = (1..16).filter { weekParity == 2 || it % 2 == weekParity }
            val lesson = Lesson(weeks, date.dayOfWeek.value, period, period,
                date = date.toString().takeIf { random.nextInt(7) == 0 },
                teacher = "教师${random.nextInt(3)}", location = "教室${random.nextInt(3)}")
            val course = Course("random-$trial-$index", "课程 ${random.nextInt(6)}", lessons = listOf(lesson))
            val statuses = buildSet {
                if (random.nextBoolean()) add(OccurrenceStatus.OUT_OF_WEEK)
                if (random.nextInt(5) == 0) add(OccurrenceStatus.HOLIDAY)
                if (random.nextInt(7) == 0) add(OccurrenceStatus.MAKEUP)
            }
            val start = LocalTime.parse(fixture.semester.periods[period - 1].start)
            val base = Occurrence(course, lesson, date, start, start.plusMinutes(45), 1, statuses)
            listOf(base, base.copy(lesson = lesson.copy(startPeriod = period + 1, endPeriod = period + 1),
                start = start.plusHours(1), end = start.plusHours(1).plusMinutes(45)))
        }.flatten().shuffled(random)
        displayCourses(occurrences)
    }
    return digest(cases.asSequence().flatMap { it.asSequence() }.flatMap(::cardText))
}

private fun measure(name: String, warmups: Int, samples: Int, maxBatchSize: Int = 64, run: () -> Any) {
    val allocationBean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
    allocationBean?.isThreadAllocatedMemoryEnabled = true
    val threadId = Thread.currentThread().id
    repeat(warmups) { Sink.value = run() }
    val calibrationStart = System.nanoTime()
    Sink.value = run()
    val calibrationNanos = (System.nanoTime() - calibrationStart).coerceAtLeast(1)
    val batchSize = ceil(100_000_000.0 / calibrationNanos).roundToInt().coerceIn(1, maxBatchSize)
    val allocations = mutableListOf<Double>()
    val durations = List(samples) {
        val bytesBefore = allocationBean?.getThreadAllocatedBytes(threadId) ?: -1
        val start = System.nanoTime()
        repeat(batchSize) { Sink.value = run() }
        val duration = (System.nanoTime() - start).toDouble() / batchSize / 1_000_000.0
        val bytesAfter = allocationBean?.getThreadAllocatedBytes(threadId) ?: -1
        allocations += if (bytesBefore >= 0 && bytesAfter >= 0) (bytesAfter - bytesBefore).toDouble() / batchSize else -1.0
        duration
    }
    println("TIMING\t$name\t$batchSize\t${durations.joinToString(",") { "%.6f".format(Locale.ROOT, it) }}\t${allocations.joinToString(",") { "%.3f".format(Locale.ROOT, it) }}")
}

private fun singleDayProbe(fixture: Fixture, warmups: Int, samples: Int, verifyOnly: Boolean) {
    val date = fixture.dates[20]
    val outside = fixture.first.minusDays(1)
    val insideRun = { ScheduleEngine.occurrences(fixture.semester, fixture.plan, date, fixture.settings, fixture.calendar) }
    val outsideRun = { ScheduleEngine.occurrences(fixture.semester, fixture.plan, outside, fixture.settings, fixture.calendar) }
    val insideResult = insideRun()
    val outsideResult = outsideRun()
    check(insideResult.isNotEmpty())
    check(outsideResult.isEmpty())
    println("CHECK\tsingle_day_3000_lessons\t${digest(insideResult.asSequence().map(::occurrenceText))}")
    println("CHECK\toutside_semester_guard\t${digest(outsideResult.asSequence().map(::occurrenceText))}")
    println("DETAIL\tsingle_day_occurrences\t${insideResult.size}")
    if (verifyOnly) return
    measure("single_day_3000_lessons", maxOf(warmups, 100), samples, maxBatchSize = 256, run = insideRun)
    // The early return is very short; larger batches and extra warm-up resolve sub-microsecond work.
    measure("outside_semester_guard", maxOf(warmups, 10_000), samples, maxBatchSize = 1_000_000, run = outsideRun)
}

fun main(args: Array<String>) {
    val warmups = args.getOrNull(0)?.toInt() ?: 8
    val samples = args.getOrNull(1)?.toInt() ?: 9
    val mode = args.getOrNull(2) ?: "measure"
    val verifyOnly = mode.startsWith("verify")
    val dense = Fixture(500) // Exactly the supported limit: 500 courses / 3,000 lessons.
    if (mode.endsWith("probe")) {
        singleDayProbe(dense, warmups, samples, verifyOnly)
        return
    }
    val display = Fixture(120)
    val import = Fixture(180)
    val scheduleRun = {
        benchmarkOccurrences(dense.semester, dense.plan, dense.settings, dense.calendar, dense.dates)
    }
    val scheduled = scheduleRun()
    println("CHECK\tschedule_112_days_3000_lessons\t${digest(scheduled.asSequence().flatMap { it.asSequence() }.map(::occurrenceText))}")
    println("DETAIL\tschedule_occurrences\t${scheduled.sumOf { it.size }}")

    // Isolate card projection: scheduling is deliberately outside this timed workload.
    val displayInput = benchmarkOccurrences(display.semester, display.plan, display.settings, display.calendar, display.dates)
    val displayRun = { displayInput.map(::displayCourses) }
    val cards = displayRun()
    println("CHECK\tdisplay_112_days_720_lessons\t${digest(cards.asSequence().flatMap { it.asSequence() }.flatMap(::cardText))}")
    println("DETAIL\tdisplay_cards\t${cards.sumOf { it.size }}")
    println("CHECK\tdisplay_randomized_128_cases\t${randomizedProjectionDigest(display)}")

    val importResult = ParseResult("benchmark", import.courses + import.courses.take(40).map { course ->
        course.copy(lessons = course.lessons + course.lessons.first().copy(note = "新增说明"))
    })
    val importCurrent = import.plan.copy(courses = import.courses.take(40))
    val importRun = { ImportEngine.preview(importResult, import.semester, importCurrent, ImportMode.MERGE) }
    val preview = importRun()
    check(preview.valid) { preview.errors.toString() }
    println("CHECK\timport_merge_conflicts\t${digest(sequenceOf(preview.toString()))}")
    println("DETAIL\timport_conflicts_duplicates_warnings\t${preview.conflicts},${preview.duplicates},${preview.warnings.size}")

    val zone = ZoneId.of("Asia/Shanghai")
    val now = Instant.parse("2026-09-06T16:00:00Z")
    val reminderRun = { ReminderSnapshot.create(display.state, display.calendar, now, zone) }
    val snapshot = reminderRun()
    println("CHECK\treminder_snapshot_64_alarms\t${digest(sequenceOf(snapshot.toString()))}")
    println("DETAIL\treminder_notices\t${snapshot.notices.size}")
    if (verifyOnly) return

    measure("schedule_112_days_3000_lessons", warmups, samples, run = scheduleRun)
    measure("display_112_days_720_lessons", warmups, samples, run = displayRun)
    measure("import_merge_conflicts", warmups, samples, run = importRun)
    measure("reminder_snapshot_64_alarms", warmups, samples, run = reminderRun)
}
