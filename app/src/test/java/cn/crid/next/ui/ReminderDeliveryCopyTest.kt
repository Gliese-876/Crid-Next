package cn.crid.next.ui

import cn.crid.next.core.Language
import cn.crid.next.platform.ReminderDeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReminderDeliveryCopyTest {
    private val ready = ReminderDeliveryState(
        notificationsAllowed = true, channelEnabled = true, channelSilent = false,
        exactAlarmsAllowed = true, backgroundRestricted = false, batteryOptimizationIgnored = false,
    )
    private val translations = listOf(
        Copy(Language.ZH_CN, "提醒已关闭", "通知未允许", "课程通知已关闭", "后台运行受限", "准时提醒未允许", "按当前设置提醒"),
        Copy(Language.ZH_TW, "提醒已關閉", "尚未允許通知", "課程通知已關閉", "背景執行受限", "尚未允許準時提醒", "依目前設定提醒"),
        Copy(Language.EN, "Reminders are off", "Notifications not allowed", "Class notifications are off",
            "Background activity restricted", "Timely reminders not allowed", "Using current settings"),
    )

    @Test fun turningRemindersOffTakesPriorityOverEverySystemRestriction() {
        val allBlocked = ready.copy(notificationsAllowed = false, channelEnabled = false,
            exactAlarmsAllowed = false, backgroundRestricted = true)
        translations.forEach { copy ->
            listOf(ready, allBlocked).forEach { state ->
                assertSummary(copy.off, state, copy.language, enabled = false)
            }
        }
    }

    @Test fun summaryNamesTheFirstActionableBlockerAsEarlierRestrictionsAreResolved() {
        val allBlocked = ready.copy(notificationsAllowed = false, channelEnabled = false,
            exactAlarmsAllowed = false, backgroundRestricted = true)
        translations.forEach { copy ->
            assertSummary(copy.notifications, allBlocked, copy.language)
            assertSummary(copy.channel, allBlocked.copy(notificationsAllowed = true), copy.language)
            assertSummary(copy.background, ready.copy(backgroundRestricted = true, exactAlarmsAllowed = false), copy.language)
            assertSummary(copy.timing, ready.copy(exactAlarmsAllowed = false), copy.language)
            assertSummary(copy.ready, ready, copy.language)
        }
    }

    @Test fun silentDeliveryAndOrdinaryBatteryOptimizationDoNotBecomePermissionWarnings() {
        translations.forEach { copy ->
            listOf(false, true).forEach { silent ->
                listOf(false, true).forEach { batteryExempt ->
                    assertSummary(copy.ready, ready.copy(channelSilent = silent,
                        batteryOptimizationIgnored = batteryExempt), copy.language)
                }
            }
        }
    }

    @Test fun alarmClockChoiceReportsMissingPermissionBeforeBackgroundRestriction() {
        val expected = mapOf(
            Language.ZH_CN to "闹钟级提醒尚未生效 · 可能延迟",
            Language.ZH_TW to "鬧鐘級提醒尚未生效 · 可能延遲",
            Language.EN to "Alarm-clock reminders inactive · May be delayed",
        )
        expected.forEach { (language, copy) ->
            listOf(false, true).forEach { backgroundRestricted ->
                val state = ready.copy(exactAlarmsAllowed = false, backgroundRestricted = backgroundRestricted)
                assertSummary(copy, state, language, alarmClockEnabled = true)
                assertEquals(copy, reminderTimingSummary(state, true, UiText(language), alarmClockEnabled = true))
            }
        }
    }

    @Test fun alarmClockChoiceNeverClaimsDeliveryWhenNotificationsOrRemindersAreOff() {
        translations.forEach { copy ->
            listOf(false, true).forEach { exactAllowed ->
                val state = ready.copy(exactAlarmsAllowed = exactAllowed)
                listOf(
                    Triple(copy.off, state, false),
                    Triple(copy.notifications, state.copy(notificationsAllowed = false), true),
                    Triple(copy.channel, state.copy(channelEnabled = false), true),
                ).forEach { (expected, snapshot, enabled) ->
                    assertSummary(expected, snapshot, copy.language, enabled, alarmClockEnabled = true)
                    assertEquals(expected, reminderTimingSummary(snapshot, enabled, UiText(copy.language), true))
                }
            }
        }
    }

    @Test fun alarmClockStatusRespectsSilentChannelsWithoutPromisingASound() {
        val expected = mapOf(
            Language.ZH_CN to "闹钟级提醒已开启",
            Language.ZH_TW to "鬧鐘級提醒已開啟",
            Language.EN to "Alarm-clock reminders are on",
        )
        expected.forEach { (language, copy) ->
            listOf(false, true).forEach { silent ->
                assertSummary(copy, ready.copy(channelSilent = silent), language, alarmClockEnabled = true)
            }
        }
    }

    private fun assertSummary(expected: String, state: ReminderDeliveryState, language: Language,
        enabled: Boolean = true, alarmClockEnabled: Boolean = false) {
        val actual = reminderDeliverySummary(state, enabled, UiText(language), alarmClockEnabled)
        assertEquals("Summary in $language", expected, actual)
        assertFalse("Summary ends without a full stop in $language", actual.endsWith('.') || actual.endsWith('。'))
    }

    private data class Copy(
        val language: Language, val off: String, val notifications: String, val channel: String,
        val background: String, val timing: String, val ready: String,
    )
}
