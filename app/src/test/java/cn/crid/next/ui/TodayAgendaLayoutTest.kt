package cn.crid.next.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import cn.crid.next.core.Period
import cn.crid.next.data.Defaults
import kotlin.math.roundToInt
import kotlin.math.abs

class TodayAgendaLayoutTest {
    @Test fun daySectionsUseStartTimeWithExactNoonAndEveningBoundaries() {
        listOf("00:00", "08:00", "11:59:59.999999999").forEach {
            assertEquals(TodayPeriod.MORNING, todayPeriod(LocalTime.parse(it)))
        }
        listOf("12:00", "14:00", "17:59:59.999999999").forEach {
            assertEquals(TodayPeriod.AFTERNOON, todayPeriod(LocalTime.parse(it)))
        }
        listOf("18:00", "20:00", "23:59:59.999999999").forEach {
            assertEquals(TodayPeriod.EVENING, todayPeriod(LocalTime.parse(it)))
        }
        assertEquals(listOf(TodayPeriod.MORNING, TodayPeriod.AFTERNOON, TodayPeriod.EVENING), TodayPeriod.entries)
    }

    @Test fun longerDurationsRemainStrictlyTallerEvenWhenAShortCourseNeedsMoreTextSpace() {
        val spans = listOf(MinuteSpan(480, 600), MinuteSpan(660, 690), MinuteSpan(780, 825), MinuteSpan(1080, 1140))
        val durations = spans.map { it.end - it.start }
        val heights = todayAgendaHeights(spans, listOf(84, 190, 94, 88), 1f, 1f)
        assertTrue(heights[1] >= 190)
        durations.indices.forEach { shorter -> durations.indices.forEach { longer ->
            if (durations[shorter] < durations[longer]) assertTrue(heights[shorter] < heights[longer])
        } }
    }

    @Test fun equalDurationsShareEnoughHeightAndAOneMinuteDifferenceRemainsVisible() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(780, 825), MinuteSpan(960, 1006), MinuteSpan(1080, 1200))
        val heights = todayAgendaHeights(spans, listOf(84, 190, 84, 84), 1f, 1f)
        assertTrue(heights[0] >= 190)
        assertEquals(heights[0], heights[1])
        assertTrue(heights[2] >= heights[1] + 1)
        assertTrue(heights[3] > heights[2])
        assertTrue(todayAgendaHeights(emptyList(), emptyList(), 1f, 1f).isEmpty())
    }

    @Test fun calibratedCardsShareTheTimetableDurationCurveAcrossDensityAndFontScale() {
        val spans = listOf(MinuteSpan(480, 525), MinuteSpan(720, 820), MinuteSpan(1080, 1260))
        listOf(1f, 2f, 3f).forEach { density -> listOf(1f, 1.8f).forEach { fontScale ->
            val axis = TimetableAxis(emptyList(), spans, (24f + 60f * fontScale) / 100f,
                minimumLessonHeight = 16f + 60f * fontScale, heightSmoothing = 2f * fontScale,
                minimumHeightStep = 1f / density)
            val today = todayAgendaHeights(spans, List(spans.size) { 0 }, density, fontScale)
            assertEquals(spans.map { (axis.lessonHeight(it) * density).roundToInt() }, today)
            assertTrue(abs(today[1] / density - (24f + 60f * fontScale)) < 1f)
        } }
    }

    @Test fun twoPeriodsKeepTheirOriginalHeightAloneAndAmongShorterAndLongerClasses() {
        val reference = todayReferenceDuration(Defaults.periods)
        assertEquals(100, reference)
        val anchor = MinuteSpan(480, 580)
        val mixed = listOf(MinuteSpan(480, 525), MinuteSpan(720, 820), MinuteSpan(1080, 1260))
        listOf(1f, 2f, 3f).forEach { density ->
            val sizing = TodayAgendaScale(reference, density, 1f)
            val plainContent = mixed.map { (60f * density).roundToInt() + sizing.verticalPaddingPixels(it.end - it.start) * 2 }
            val single = todayAgendaHeights(listOf(anchor), listOf((84f * density).roundToInt()), density, 1f, reference).single()
            val heights = todayAgendaHeights(mixed, plainContent, density, 1f, reference)
            assertEquals((84f * density).roundToInt(), single)
            assertEquals(single, heights[1])
            assertTrue(heights[0] < heights[1])
            assertTrue(heights[1] < heights[2])
            assertEquals((12f * density).roundToInt(), sizing.verticalPaddingPixels(reference))
        }
    }

    @Test fun adjacentMinuteRoundingKeepsTheAnchorWhileTextNeedsMayExpandIt() {
        val spans = listOf(MinuteSpan(480, 579), MinuteSpan(720, 820), MinuteSpan(1080, 1181))
        val sizing = TodayAgendaScale(100, 1f, 1f)
        val plainContent = spans.map { 60 + sizing.verticalPaddingPixels(it.end - it.start) * 2 }
        val ordinary = todayAgendaHeights(spans, plainContent, 1f, 1f)
        assertEquals(listOf(83, 84, 85), ordinary)
        val expanded = todayAgendaHeights(spans, listOf(190, 84, 84), 1f, 1f)
        assertTrue(expanded[0] >= 190)
        assertTrue(expanded[0] < expanded[1] && expanded[1] < expanded[2])
    }

    @Test fun shortSoftplusTailGetsOnlyTheNecessaryPixelSteps() {
        val durations = listOf(30, 45, 60, 100, 180)
        val spans = durations.map { MinuteSpan(480, 480 + it) }
        listOf(1f, 2f, 3f).forEach { density ->
            val heights = todayAgendaHeights(spans, List(spans.size) { 0 }, density, 1f)
            assertTrue(heights.zipWithNext().all { (shorter, longer) -> longer > shorter })
            assertEquals((84f * density).roundToInt(), heights[3])
            assertEquals((151.2f * density).roundToInt(), heights[4])
        }
    }

    @Test fun theReferenceUsesTheSemestersActualPeriodPairsAndIgnoresLunch() {
        val custom = listOf(Period(1, "08:00", "08:40"), Period(2, "08:45", "09:25"),
            Period(3, "13:00", "13:40"), Period(4, "13:45", "14:25"))
        assertEquals(85, todayReferenceDuration(custom.reversed()))
        val spans = listOf(MinuteSpan(480, 520), MinuteSpan(780, 865), MinuteSpan(1080, 1210))
        assertEquals(84, todayAgendaHeights(spans, listOf(76, 84, 84), 1f, 1f, todayReferenceDuration(custom))[1])
        assertEquals(80, todayReferenceDuration(custom.take(1)))
        assertEquals(100, todayReferenceDuration(emptyList()))
    }

    @Test fun movingClassesAcrossTheDayDoesNotInsertClockGapsOrChangeTheirHeights() {
        val adjacent = listOf(MinuteSpan(480, 510), MinuteSpan(510, 555), MinuteSpan(555, 675))
        val separated = listOf(MinuteSpan(480, 510), MinuteSpan(720, 765), MinuteSpan(1140, 1260))
        val content = listOf(84, 94, 88)
        assertEquals(todayAgendaHeights(adjacent, content, 2f, 1f), todayAgendaHeights(separated, content, 2f, 1f))
    }

    @Test fun overlappingCardsWithEqualDurationsRemainEqualAndLongMetadataDoesNotDominateTheDay() {
        val spans = listOf(MinuteSpan(480, 510), MinuteSpan(481, 511), MinuteSpan(720, 751), MinuteSpan(1140, 1260))
        val heights = todayAgendaHeights(spans, listOf(190, 84, 84, 84), 1f, 1f)
        assertEquals(heights[0], heights[1])
        assertTrue(heights[0] >= 190)
        assertTrue(heights[2] > heights[0])
        assertTrue(heights[3] > heights[2])
        assertTrue("The full two-hour course stays compact after fitting the short course's text", heights[3] < 300)
    }
}
