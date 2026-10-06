package cn.crid.next.data

import cn.crid.next.core.Period
import cn.crid.next.core.Semester

object Defaults {
    val periods = listOf("08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40", "13:30" to "14:15", "14:25" to "15:10", "15:30" to "16:15", "16:25" to "17:10", "18:00" to "18:45", "18:55" to "19:40", "19:50" to "20:35", "20:45" to "21:30").mapIndexed { i, p -> Period(i + 1, p.first, p.second) }
    fun semester() = Semester(name = "2026–2027 秋季学期", startDate = "2026-09-07", endDate = "2027-01-10", weeks = 18, periods = periods)
}
