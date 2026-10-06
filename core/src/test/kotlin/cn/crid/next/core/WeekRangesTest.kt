package cn.crid.next.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WeekRangesTest {
    @Test fun consecutiveWeeksUseOneRange() {
        assertEquals("1–4", formatWeekRanges(listOf(1, 2, 3, 4)))
    }
    @Test fun gapsAndOddWeeksRemainDistinct() {
        assertEquals("1–4, 6, 8–10", formatWeekRanges(listOf(1, 2, 3, 4, 6, 8, 9, 10)))
        assertEquals("1, 3, 5", formatWeekRanges(listOf(1, 3, 5)))
    }
    @Test fun unorderedDuplicateWeeksPreserveTheirSet() {
        assertEquals("1–3、5", formatWeekRanges(listOf(5, 2, 1, 3, 2), "、"))
        assertEquals("", formatWeekRanges(emptyList()))
        assertEquals("16", formatWeekRanges(listOf(16, 16)))
    }
}
