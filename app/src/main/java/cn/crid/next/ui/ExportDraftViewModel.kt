package cn.crid.next.ui

import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import cn.crid.next.core.Plan
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.Semester
import cn.crid.next.export.ExportRequest
import cn.crid.next.export.ExportUnits
import cn.crid.next.export.ExportView
import kotlinx.coroutines.Job
import java.time.LocalDate
import java.time.YearMonth

internal enum class ExportKind { PNG, PDF, DATA }
/** Keeps each view's selection, the destination request, and ongoing work through rotation. */
internal class ExportDraftViewModel : ViewModel() {
    private var selectionKey: String? = null
    val kind = mutableStateOf(ExportKind.PDF)
    val view = mutableStateOf(ExportView.WEEK)
    val fromDay = mutableStateOf(LocalDate.ofEpochDay(0))
    val toDay = mutableStateOf(LocalDate.ofEpochDay(0))
    val fromWeek = mutableIntStateOf(1)
    val toWeek = mutableIntStateOf(1)
    val fromMonth = mutableStateOf(YearMonth.of(1970, 1))
    val toMonth = mutableStateOf(YearMonth.of(1970, 1))
    val selectedSemesterIds = mutableStateOf<Set<String>>(emptySet())
    val semesterPlanIds = mutableStateOf<Map<String, String>>(emptyMap())
    val scale = mutableIntStateOf(2)
    val busy = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val results = mutableStateOf<List<Uri>>(emptyList())
    val resultMime = mutableStateOf("application/pdf")
    val exportJob = mutableStateOf<Job?>(null)
    val pendingRequests = mutableStateOf<List<ExportRequest>>(emptyList())
    val pendingPlan = mutableStateOf<Plan?>(null)
    val pendingKind = mutableStateOf(ExportKind.PDF)

    fun prepare(semester: Semester, weekStartsSunday: Boolean, today: LocalDate = LocalDate.now()) {
        val key = "${semester.id}|${semester.startDate}|${semester.endDate}|$weekStartsSunday"
        if (selectionKey == key) return
        val start = LocalDate.parse(semester.startDate)
        val end = LocalDate.parse(semester.endDate)
        val day = today.coerceIn(start, end)
        val week = (ScheduleEngine.weekNumber(semester, day, weekStartsSunday) ?: 1)
            .coerceIn(1, ExportUnits.weekCount(semester, weekStartsSunday))
        fromDay.value = day
        toDay.value = day
        fromWeek.intValue = week
        toWeek.intValue = week
        fromMonth.value = YearMonth.from(day)
        toMonth.value = YearMonth.from(day)
        if (selectionKey == null) selectedSemesterIds.value = setOf(semester.id)
        selectionKey = key
    }

    fun clear() {
        selectionKey = null
        kind.value = ExportKind.PDF
        view.value = ExportView.WEEK
        selectedSemesterIds.value = emptySet()
        semesterPlanIds.value = emptyMap()
        scale.intValue = 2
        busy.value = false
        error.value = null
        results.value = emptyList()
        resultMime.value = "application/pdf"
        exportJob.value = null
        pendingRequests.value = emptyList()
        pendingPlan.value = null
        pendingKind.value = ExportKind.PDF
    }
}
