package cn.crid.next.platform

import cn.crid.next.core.HolidayDay
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidaySyncTest {
    @Test fun bundledDatesSeparateStatutoryRestAndWorkdays() {
        val calendar = HolidayDefaults.calendar()
        assertEquals(2026, calendar.year)
        assertEquals(39, calendar.days.size)
        assertEquals(39, calendar.days.map { it.date }.distinct().size)
        assertEquals(13, calendar.days.count { it.statutory })
        assertEquals(20, calendar.days.count { it.extraRest })
        assertEquals(6, calendar.days.count { it.workday })
        assertTrue(calendar.days.all { listOf(it.statutory, it.extraRest, it.workday).count { flag -> flag } == 1 })
        assertTrue(calendar.days.all { it.teachingDate == null })
        assertNull(calendar.syncedAt)
        assertTrue(calendar.days.single { it.date == "2026-02-16" }.statutory)
        assertTrue(calendar.days.single { it.date == "2026-02-15" }.extraRest)
        assertTrue(calendar.days.single { it.date == "2026-09-20" }.workday)
    }

    @Test fun completeNoticeIsRequired() {
        assertTrue(HolidaySync.isVerifiedNotice(notice))
        assertFalse(HolidaySync.isVerifiedNotice("<h1>国务院办公厅关于2026年部分节假日安排的通知</h1>"))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("10月10日", "10月11日")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("9月20日（周日）、10月10日（周六）上班。", "9月20日（周日）、10月10日（周六）上班")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("国办发明电〔2025〕7号", "")))
        assertFalse(HolidaySync.isVerifiedNotice("<script>$notice</script>"))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("四、劳动节", "四、其他节日")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("2026年部分", "2027年部分")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("2025年11月4日", "2025年99月99日")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("国务院办公厅 2025年11月4日", "")))
        assertFalse(HolidaySync.isVerifiedNotice(notice.replace("1月1日（周四）", "1月1日（周四周）")))
    }

    @Test fun markupWhitespaceAndOfficialTraditionalChineseAreAccepted() {
        val wrapped = notice.replace("一、", "<p>一、").replace("二、", "</p>\n<p>二、")
            .replace(" ", "&#160;")
        assertTrue(HolidaySync.isVerifiedNotice(wrapped))
        var traditional = notice
        "国务办厅关于节发电号周农历腊劳动庆调".zip("國務辦廳關於節發電號週農曆臘勞動慶調")
            .forEach { (from, to) -> traditional = traditional.replace(from, to) }
        assertTrue(HolidaySync.isVerifiedNotice(traditional))
    }

    @Test fun refreshPreservesOnlyExistingConfirmedWorkdayMappings() {
        val current = HolidayDefaults.calendar().copy(days = listOf(
            HolidayDay("2026-09-20", "国庆节", workday = true, teachingDate = "2026-10-06"),
            HolidayDay("2026-10-10", "国庆节", workday = false, teachingDate = "2026-10-07"),
            HolidayDay("2026-10-01", "国庆节", statutory = true, teachingDate = "2026-10-07"),
            HolidayDay("2026-05-09", "劳动节", workday = true, teachingDate = "invalid"),
        ))
        val now = Instant.parse("2026-09-27T00:00:00Z")
        val refreshed = HolidaySync.refreshed(current, HolidaySync.parseNotice(notice)!!, HolidayDefaults.SOURCE, now)
        assertEquals("2026-10-06", refreshed.days.single { it.date == "2026-09-20" }.teachingDate)
        assertNull(refreshed.days.single { it.date == "2026-10-10" }.teachingDate)
        assertNull(refreshed.days.single { it.date == "2026-10-01" }.teachingDate)
        assertNull(refreshed.days.single { it.date == "2026-05-09" }.teachingDate)
        assertEquals(now.toString(), refreshed.syncedAt)
        assertEquals(39, refreshed.days.size)
    }

    @Test fun conflictingStoredMappingsAreNotChosenArbitrarily() {
        val current = HolidayDefaults.calendar().copy(days = listOf(
            HolidayDay("2026-09-20", "国庆节", workday = true, teachingDate = "2026-10-06"),
            HolidayDay("2026-09-20", "国庆节", workday = true, teachingDate = "2026-10-07"),
        ))
        assertNull(HolidaySync.refreshed(current, HolidaySync.parseNotice(notice)!!, HolidayDefaults.SOURCE, Instant.EPOCH)
            .days.single { it.date == "2026-09-20" }.teachingDate)
    }

    @Test fun validOfficialAmendmentChangesActualDatesInsteadOfOnlyTimestamp() {
        val amended = notice.replace("1月1日（周四）至3日（周六）放假调休，共3天。1月4日（周日）上班。",
            "1月1日（周四）至4日（周日）放假调休，共4天。1月10日（周六）上班。")
        val parsed = HolidaySync.parseNotice(amended)!!
        val refreshed = HolidaySync.refreshed(HolidayDefaults.calendar(), parsed, HolidayDefaults.SOURCE, Instant.EPOCH)
        assertEquals(40, refreshed.days.size)
        assertTrue(refreshed.days.single { it.date == "2026-01-04" }.extraRest)
        assertFalse(refreshed.days.single { it.date == "2026-01-04" }.workday)
        assertTrue(refreshed.days.single { it.date == "2026-01-10" }.workday)
        assertNull(refreshed.days.single { it.date == "2026-01-10" }.teachingDate)
        assertEquals(13, refreshed.days.count { it.statutory })
    }

    @Test fun validWorkdayRemovalAndMoveAreAcceptedWithoutAssumingFixedDayCounts() {
        val removed = HolidaySync.parseNotice(notice.replace("9月20日（周日）、", ""))!!
        assertFalse(removed.days.any { it.date == "2026-09-20" })
        assertEquals(5, removed.days.count { it.workday })
        val moved = HolidaySync.parseNotice(notice.replace("10月10日（周六）", "10月11日（周日）"))!!
        assertFalse(moved.days.any { it.date == "2026-10-10" })
        assertTrue(moved.days.single { it.date == "2026-10-11" }.workday)
    }

    @Test fun rejectsInconsistentCountsOverlapsDatesAndStatutoryOmissions() {
        assertNull(HolidaySync.parseNotice(notice.replace("至3日（周六）放假调休，共3天", "至3日（周六）放假调休，共4天")))
        assertNull(HolidaySync.parseNotice(notice.replace("1月4日（周日）上班", "1月3日（周六）上班")))
        assertNull(HolidaySync.parseNotice(notice.replace("2月28日（周六）上班", "2月30日上班")))
        assertNull(HolidaySync.parseNotice(notice.replace("10月10日（周六）", "9月20日（周日）")))
        assertNull(HolidaySync.parseNotice(notice.replace("10月10日（周六）", "2027年10月10日")))
        assertNull(HolidaySync.parseNotice(notice.replace("10月10日（周六）", "12月10日（周四）")))
        assertNull(HolidaySync.parseNotice(notice.replace("5月1日（周五）至5日（周二）放假调休，共5天", "5月2日（周六）至5日（周二）放假调休，共4天")))
    }

    @Test fun generalOfficialClosingProseAndSignatureAreNotCalendarDays() {
        val closing = notice.replace("国务院办公厅 2025年11月4日",
            "鼓励单位和个人结合落实带薪年休假等制度，实际形成较长假期，推动错峰出行。" +
                "节假日期间，各地区、各部门要妥善安排好值班和安全工作。国务院办公厅 2025年11月4日")
        val parsed = HolidaySync.parseNotice(closing)!!
        assertEquals(39, parsed.days.size)
        assertFalse(parsed.days.any { it.date.startsWith("2025") })
        assertNull(HolidaySync.parseNotice(closing.replace("鼓励单位", "10月12日补班。鼓励单位")))
    }

    @Test fun staleMappingsAreRemovedWhenAmendmentMovesAWorkday() {
        val current = HolidayDefaults.calendar().let { calendar -> calendar.copy(days = calendar.days.map {
            if (it.date == "2026-10-10") it.copy(teachingDate = "2026-10-07") else it
        }) }
        val parsed = HolidaySync.parseNotice(notice.replace("10月10日（周六）", "10月11日（周日）"))!!
        val refreshed = HolidaySync.refreshed(current, parsed, HolidayDefaults.SOURCE, Instant.EPOCH)
        assertFalse(refreshed.days.any { it.date == "2026-10-10" })
        assertNull(refreshed.days.single { it.date == "2026-10-11" }.teachingDate)
    }

    private val notice = """
        <html><head><title>国务院办公厅关于2026年部分节假日安排的通知</title></head><body>
        国办发明电〔2025〕7号
        一、元旦：1月1日（周四）至3日（周六）放假调休，共3天。1月4日（周日）上班。
        二、春节：2月15日（农历腊月二十八、周日）至23日（农历正月初七、周一）放假调休，共9天。2月14日（周六）、2月28日（周六）上班。
        三、清明节：4月4日（周六）至6日（周一）放假，共3天。
        四、劳动节：5月1日（周五）至5日（周二）放假调休，共5天。5月9日（周六）上班。
        五、端午节：6月19日（周五）至21日（周日）放假，共3天。
        六、中秋节：9月25日（周五）至27日（周日）放假，共3天。
        七、国庆节：10月1日（周四）至7日（周三）放假调休，共7天。9月20日（周日）、10月10日（周六）上班。
        国务院办公厅 2025年11月4日
        </body></html>
    """.trimIndent()
}
