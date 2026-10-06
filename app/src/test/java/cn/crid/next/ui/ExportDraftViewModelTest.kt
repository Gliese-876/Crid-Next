package cn.crid.next.ui

import cn.crid.next.core.AppState
import cn.crid.next.core.Period
import cn.crid.next.core.Plan
import cn.crid.next.core.Semester
import cn.crid.next.export.ExportView
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class ExportDraftViewModelTest {
    private val semester = Semester("autumn", "Autumn", "2026-09-09", "2027-01-12", 18,
        listOf(Period(1, "08:00", "08:45")))

    @Test fun initialWeekUsesActualCalendarBoundsInsteadOfDeclaredWeekCount() {
        val draft = ExportDraftViewModel()
        draft.prepare(semester, false, LocalDate.of(2027, 4, 1))
        assertEquals(LocalDate.of(2027, 1, 12), draft.fromDay.value)
        assertEquals(19, draft.fromWeek.intValue)
        assertEquals(YearMonth.of(2027, 1), draft.fromMonth.value)
        assertEquals(setOf(semester.id), draft.selectedSemesterIds.value)
    }

    @Test fun changingLayoutAndRecreatingUiKeepsEachIndependentRange() {
        val draft = ExportDraftViewModel()
        draft.prepare(semester, false, LocalDate.of(2026, 10, 1))
        draft.fromDay.value = LocalDate.of(2026, 9, 17)
        draft.toDay.value = LocalDate.of(2026, 9, 21)
        draft.fromWeek.intValue = 2
        draft.toWeek.intValue = 5
        draft.fromMonth.value = YearMonth.of(2026, 11)
        draft.toMonth.value = YearMonth.of(2027, 1)
        draft.view.value = ExportView.MONTH
        draft.prepare(semester, false, LocalDate.of(2026, 12, 1))
        assertEquals(LocalDate.of(2026, 9, 17), draft.fromDay.value)
        assertEquals(LocalDate.of(2026, 9, 21), draft.toDay.value)
        assertEquals(2, draft.fromWeek.intValue)
        assertEquals(5, draft.toWeek.intValue)
        assertEquals(YearMonth.of(2026, 11), draft.fromMonth.value)
        assertEquals(YearMonth.of(2027, 1), draft.toMonth.value)
        assertEquals(ExportView.MONTH, draft.view.value)
    }

    @Test fun changedSemesterBoundsCannotLeaveOutOfRangeSelections() {
        val draft = ExportDraftViewModel()
        draft.prepare(semester, false, LocalDate.of(2027, 1, 12))
        val shortened = semester.copy(endDate = "2026-12-01")
        draft.prepare(shortened, false, LocalDate.of(2027, 1, 12))
        assertEquals(LocalDate.of(2026, 12, 1), draft.fromDay.value)
        assertEquals(LocalDate.of(2026, 12, 1), draft.toDay.value)
        assertEquals(13, draft.fromWeek.intValue)
        assertEquals(YearMonth.of(2026, 12), draft.toMonth.value)
    }

    @Test fun semesterDefaultUsesActivePlanAndHonorsExplicitChoice() {
        val first = Plan("first", semester.id, "First plan", emptyList())
        val active = first.copy(id = "active", name = "Active plan")
        val state = AppState(listOf(semester), listOf(first, active), semester.id, active.id)
        assertEquals(active, exportPlanForSemester(state, semester.id, emptyMap()))
        assertEquals(first, exportPlanForSemester(state, semester.id, mapOf(semester.id to first.id)))
        assertNull(exportPlanForSemester(state, semester.id, mapOf(semester.id to "removed")))
    }

    @Test fun otherSemesterDoesNotBorrowCurrentPlan() {
        val second = semester.copy(id = "spring", name = "Spring")
        val autumnPlan = Plan("autumn-plan", semester.id, "Autumn plan", emptyList())
        val springPlan = Plan("spring-plan", second.id, "Spring plan", emptyList())
        val state = AppState(listOf(semester, second), listOf(autumnPlan, springPlan), semester.id, autumnPlan.id)
        assertEquals(springPlan, exportPlanForSemester(state, second.id, emptyMap()))
        assertNull(exportPlanForSemester(state.copy(plans = listOf(autumnPlan)), second.id, emptyMap()))
        assertNull(exportPlanForSemester(state, second.id, mapOf(second.id to autumnPlan.id)))
    }
}
