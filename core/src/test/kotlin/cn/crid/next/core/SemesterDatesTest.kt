package cn.crid.next.core

import org.junit.Assert.*
import org.junit.Test

class SemesterDatesTest {
    @Test fun `start and weeks include both ends without rounding to a calendar week`() {
        val result = SemesterDates.resolve("2026-09-02", null, 1)
        assertEquals("2026-09-02", result.startDate)
        assertEquals("2026-09-08", result.endDate)
        assertEquals(1, result.weeks)
    }

    @Test fun `end and weeks derive an exact seven day interval`() {
        val result = SemesterDates.resolve(null, "2026-09-08", 1)
        assertEquals("2026-09-02", result.startDate)
        assertEquals("2026-09-08", result.endDate)
        assertEquals(1, result.weeks)
    }

    @Test fun `eight supplied days round weeks up but keep the original dates`() {
        val result = SemesterDates.resolve("2026-09-02", "2026-09-09", null)
        assertEquals(2, result.weeks)
        assertEquals("2026-09-02", result.startDate)
        assertEquals("2026-09-09", result.endDate)
    }

    @Test fun `same day is a valid one week semester`() {
        val result = SemesterDates.resolve("2026-09-02", "2026-09-02", null)
        assertEquals(1, result.weeks)
        assertEquals(result.startDate, result.endDate)
    }

    @Test fun `date arithmetic handles leap day and year boundaries`() {
        assertEquals("2028-03-02", SemesterDates.resolve("2028-02-25", null, 1).endDate)
        assertEquals("2027-01-05", SemesterDates.resolve("2026-12-30", null, 1).endDate)
    }

    @Test fun `a valid third field can confirm an incomplete final week`() {
        val result = SemesterDates.resolve("2026-09-02", "2026-09-09", 2)
        assertEquals("2026-09-09", result.endDate)
        assertEquals(2, result.weeks)
    }

    @Test fun `invalid or insufficient inputs fail rather than becoming a semester`() {
        rejects { SemesterDates.resolve("2026-09-02", null, null) }
        rejects { SemesterDates.resolve(null, null, 1) }
        rejects { SemesterDates.resolve("2026-09-03", "2026-09-02", null) }
        rejects { SemesterDates.resolve("2026-09-02", null, 0) }
        rejects { SemesterDates.resolve(null, "2026-09-02", -1) }
        rejects { SemesterDates.resolve("2026-02-30", null, 1) }
        rejects { SemesterDates.resolve("2026-09-02", "2026-09-09", 1) }
    }

    @Test fun `editor does not save before two valid manual inputs`() {
        val empty = SemesterEditor()
        assertNull(empty.result)
        assertNull(empty.editStart("2026-09-02").result)
        val complete = empty.editStart("2026-09-02").editWeeks(1)
        assertEquals(SemesterField.END, complete.derivedField)
        assertEquals("2026-09-08", complete.result!!.endDate)
        assertTrue(complete.errors.isEmpty())
    }

    @Test fun `editor keeps the latest two manually edited fields`() {
        val first = SemesterEditor().editStart("2026-09-02").editWeeks(1)
        val second = first.editEnd("2026-09-15")
        assertEquals(SemesterField.START, second.derivedField)
        assertEquals("2026-09-09", second.result!!.startDate)
        assertEquals(1, second.result!!.weeks)

        val third = second.editStart("2026-09-02")
        assertEquals(SemesterField.WEEKS, third.derivedField)
        assertEquals("2026-09-15", third.result!!.endDate)
        assertEquals(2, third.result!!.weeks)
        // A derived update must not alter the previous immutable editor.
        assertEquals("2026-09-08", first.result!!.endDate)
    }

    @Test fun `editing a field again updates its manual priority`() {
        val result = SemesterEditor()
            .editStart("2026-09-02")
            .editWeeks(1)
            .editStart("2026-09-03")
            .editEnd("2026-09-10")
        assertEquals(SemesterField.WEEKS, result.derivedField)
        assertEquals("2026-09-03", result.result!!.startDate)
        assertEquals("2026-09-10", result.result!!.endDate)
        assertEquals(2, result.result!!.weeks)
    }

    @Test fun `invalid newest edit is surfaced instead of silently keeping a saveable result`() {
        val valid = SemesterEditor().editStart("2026-09-02").editWeeks(1)
        val invalidDate = valid.editEnd("not-a-date")
        assertNull(invalidDate.result)
        assertFalse(invalidDate.errors.isEmpty())
        val invalidWeeks = valid.editWeeks(0)
        assertNull(invalidWeeks.result)
        assertFalse(invalidWeeks.errors.isEmpty())
    }

    @Test fun `opening an existing partial week semester preserves both dates`() {
        val editor = SemesterEditor(start = "2026-09-02", end = "2026-09-09", weeks = 2)
        assertEquals("2026-09-02", editor.result!!.startDate)
        assertEquals("2026-09-09", editor.result!!.endDate)
        assertEquals(2, editor.result!!.weeks)
        assertEquals(SemesterField.WEEKS, editor.derivedField)
    }

    @Test fun `editing an existing partial week semester can preserve its authoritative date bounds`() {
        val editor = SemesterEditor("2026-09-02", "2026-09-09", 2, manualOrder = listOf(SemesterField.START, SemesterField.END))
        assertEquals("2026-09-02", editor.result!!.startDate)
        assertEquals("2026-09-09", editor.result!!.endDate)
        assertEquals(2, editor.result!!.weeks)
        assertEquals(SemesterField.WEEKS, editor.derivedField)
    }

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Expected an invalid semester to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation outcome.
        }
    }
}
