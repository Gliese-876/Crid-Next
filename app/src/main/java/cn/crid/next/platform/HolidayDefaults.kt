package cn.crid.next.platform

import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.HolidayDay
import java.time.LocalDate

/**
 * Reviewed 2026 dates, kept available without a network connection.
 *
 * The State Council notice supplies the complete rest periods and working days:
 * https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
 * The statutory dates follow the amended holiday regulation, cross-checked with
 * BNU's dated 2026 statutory-holiday list:
 * https://jwb.bnuzh.edu.cn/docs/2025-12/b6185fa96f524b8896dcac37c0b8cb79.pdf
 *
 * extraRest denotes other dates in the announced rest period, including weekends;
 * it does not claim that every such date was exchanged for a working day.
 * A national working day does not identify which university classes to teach.
 */
object HolidayDefaults {
    const val SOURCE = "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm"

    fun calendar(): HolidayCalendar {
        val statutory = setOf(
            "2026-01-01",
            "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
            "2026-04-05", "2026-05-01", "2026-05-02", "2026-06-19",
            "2026-09-25", "2026-10-01", "2026-10-02", "2026-10-03",
        )
        val days = buildList {
            fun rest(name: String, start: String, end: String) {
                var date = LocalDate.parse(start)
                val last = LocalDate.parse(end)
                while (!date.isAfter(last)) {
                    val value = date.toString()
                    add(HolidayDay(value, name, statutory = value in statutory, extraRest = value !in statutory))
                    date = date.plusDays(1)
                }
            }
            rest("元旦", "2026-01-01", "2026-01-03")
            rest("春节", "2026-02-15", "2026-02-23")
            rest("清明节", "2026-04-04", "2026-04-06")
            rest("劳动节", "2026-05-01", "2026-05-05")
            rest("端午节", "2026-06-19", "2026-06-21")
            rest("中秋节", "2026-09-25", "2026-09-27")
            rest("国庆节", "2026-10-01", "2026-10-07")
            listOf(
                "2026-01-04" to "元旦", "2026-02-14" to "春节", "2026-02-28" to "春节",
                "2026-05-09" to "劳动节", "2026-09-20" to "国庆节", "2026-10-10" to "国庆节",
            ).forEach { (date, name) -> add(HolidayDay(date, name, workday = true)) }
        }
        return HolidayCalendar(year = 2026, days = days.sortedBy { it.date }, source = SOURCE)
    }
}
