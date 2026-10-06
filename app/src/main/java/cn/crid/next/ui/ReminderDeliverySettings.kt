package cn.crid.next.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.crid.next.platform.ReminderDeliveryState
import cn.crid.next.platform.ReminderSettingsTarget

internal fun reminderDeliverySummary(
    state: ReminderDeliveryState,
    enabled: Boolean,
    text: UiText,
    alarmClockEnabled: Boolean = false,
): String = when {
    !enabled -> text.t("提醒已关闭", "Reminders are off", "提醒已關閉")
    !state.notificationsAllowed -> text.t("通知未允许", "Notifications not allowed", "尚未允許通知")
    !state.channelEnabled -> text.t("课程通知已关闭", "Class notifications are off", "課程通知已關閉")
    alarmClockEnabled && !state.exactAlarmsAllowed -> text.t("闹钟级提醒尚未生效 · 可能延迟",
        "Alarm-clock reminders inactive · May be delayed", "鬧鐘級提醒尚未生效 · 可能延遲")
    state.backgroundRestricted -> text.t("后台运行受限", "Background activity restricted", "背景執行受限")
    !state.exactAlarmsAllowed -> text.t("准时提醒未允许", "Timely reminders not allowed", "尚未允許準時提醒")
    alarmClockEnabled -> text.t("闹钟级提醒已开启", "Alarm-clock reminders are on", "鬧鐘級提醒已開啟")
    else -> text.t("按当前设置提醒", "Using current settings", "依目前設定提醒")
}

internal fun reminderTimingSummary(
    state: ReminderDeliveryState,
    enabled: Boolean,
    text: UiText,
    alarmClockEnabled: Boolean = false,
): String = when {
    !enabled || !state.notificationsAllowed || !state.channelEnabled || alarmClockEnabled ->
        reminderDeliverySummary(state, enabled, text, alarmClockEnabled)
    !state.exactAlarmsAllowed -> text.t("可能延迟", "May be delayed", "可能延遲")
    else -> text.t("已允许", "Allowed", "已允許")
}

@Composable
internal fun ReminderDeliverySettingsDialog(
    state: ReminderDeliveryState,
    text: UiText,
    remindersEnabled: Boolean,
    alarmClockEnabled: Boolean,
    onAlarmClockEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: (ReminderSettingsTarget) -> Unit,
    openFailed: Boolean = false,
    origin: ModalOrigin? = null,
) {
    AnimatedAppDialog(onDismissRequest = onDismiss, origin = origin) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.appSurface(AppSurfaceRole.Raised),
            contentColor = MaterialTheme.colorScheme.appOnSurface(AppSurfaceRole.Raised)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).testTag("reminder_delivery_panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text.t("提醒设置", "Reminder settings", "提醒設定"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                if (remindersEnabled) {
                    SwitchRow(text.t("闹钟级提醒", "Alarm-clock reminders", "鬧鐘級提醒"),
                        text.t("待机时优先准时提醒，系统可能显示闹钟标记；声音仍遵循课程通知设置",
                            "Prioritize timely reminders while idle. The system may show an alarm icon; sound follows your class notification settings",
                            "待機時優先準時提醒，系統可能顯示鬧鐘標記；聲音仍遵循課程通知設定"),
                        alarmClockEnabled, modifier = Modifier.testTag("reminder_alarm_clock"), onChecked = onAlarmClockEnabledChange)
                }
                ReminderDeliveryRow(
                    text.t("通知权限", "Notification permission", "通知權限"),
                    if (state.notificationsAllowed) text.t("已允许", "Allowed", "已允許")
                    else text.t("未允许", "Not allowed", "未允許"),
                    text.t("打开通知设置", "Open notification settings", "開啟通知設定"),
                    "notification", "reminder_notifications", { onOpenSettings(ReminderSettingsTarget.NOTIFICATIONS) },
                )
                ReminderDeliveryRow(
                    text.t("课程通知", "Class notifications", "課程通知"),
                    when {
                        !state.channelEnabled -> text.t("已关闭", "Off", "已關閉")
                        state.channelSilent -> text.t("静默提醒", "Silent reminders", "靜默提醒")
                        else -> text.t("已开启", "On", "已開啟")
                    },
                    text.t("调整提醒样式", "Adjust notification style", "調整提醒樣式"),
                    "course", "reminder_channel", { onOpenSettings(ReminderSettingsTarget.CHANNEL) },
                )
                ReminderDeliveryRow(
                    text.t("准时提醒", "Timely reminders", "準時提醒"),
                    reminderTimingSummary(state, remindersEnabled, text, alarmClockEnabled),
                    text.t("打开准时提醒设置", "Open timely reminder settings", "開啟準時提醒設定"),
                    "time", "reminder_timing", { onOpenSettings(ReminderSettingsTarget.EXACT_ALARMS) },
                )
                ReminderDeliveryRow(
                    text.t("后台运行", "Background activity", "背景執行"),
                    when {
                        state.backgroundRestricted -> text.t("后台运行受限", "Background activity restricted", "背景執行受限")
                        state.batteryOptimizationIgnored -> text.t("已放宽省电限制", "Battery restrictions relaxed", "已放寬省電限制")
                        else -> text.t("系统默认", "System default", "系統預設")
                    },
                    text.t("打开电池设置", "Open battery settings", "開啟電池設定"),
                    "settings", "reminder_battery", { onOpenSettings(ReminderSettingsTarget.BATTERY) },
                )
                Text(text.t("提醒经常延迟时，可在电池设置中找到 Crid Next，检查省电限制和后台运行选项",
                    "If reminders are often late, find Crid Next in battery settings and check battery and background restrictions",
                    "提醒經常延遲時，可在電池設定中找到 Crid Next，檢查省電限制與背景執行選項"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ReminderManufacturerHelp(text)
                if (openFailed) Text(text.t("无法打开系统设置", "System settings are unavailable", "無法開啟系統設定"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("reminder_settings_error").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
private fun ReminderDeliveryRow(title: String, status: String, action: String, glyph: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
        .clickable(onClickLabel = action, role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppGlyph(glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = alignByVisualCenter(Modifier.size(24.dp)))
        Column(alignByVisualCenter(Modifier.weight(1f)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VisualCenterText(title, style = MaterialTheme.typography.bodyLarge)
            VisualCenterText(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppGlyph("open", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = alignByVisualCenter(Modifier.size(20.dp)))
    }
}
