package cn.crid.next.platform

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import cn.crid.next.R
import cn.crid.next.core.Language
import cn.crid.next.data.AppRepository

data class ReminderDeliveryState(
    val notificationsAllowed: Boolean,
    val channelEnabled: Boolean,
    val channelSilent: Boolean,
    val exactAlarmsAllowed: Boolean,
    val backgroundRestricted: Boolean,
    val batteryOptimizationIgnored: Boolean,
)

enum class ReminderSettingsTarget { NOTIFICATIONS, CHANNEL, EXACT_ALARMS, BATTERY }

/** Public platform controls only: no vendor components or automatic battery exemptions. */
object ReminderDelivery {
    internal const val CHANNEL = "course-reminders"

    internal fun ensureChannel(context: Context, language: Language) {
        val localized = PlatformText.context(context, language)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, localized.getString(R.string.platform_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH))
    }

    internal fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    internal fun canDeliver(context: Context): Boolean = notificationsAllowed(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    fun read(context: Context): ReminderDeliveryState {
        ensureChannel(context, AppRepository.get(context).state.value.settings.language)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
        return ReminderDeliveryState(
            notificationsAllowed = notificationsAllowed(context),
            channelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            channelSilent = channel != null && (channel.importance < NotificationManager.IMPORTANCE_DEFAULT ||
                (channel.sound == null && !channel.shouldVibrate())),
            exactAlarmsAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    internal fun settingsIntents(context: Context, target: ReminderSettingsTarget): List<Intent> {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val requested = when (target) {
            ReminderSettingsTarget.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            ReminderSettingsTarget.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
            ReminderSettingsTarget.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"))
            ReminderSettingsTarget.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        return listOf(requested, details)
    }

    fun openSettings(context: Context, target: ReminderSettingsTarget): Boolean {
        if (target == ReminderSettingsTarget.CHANNEL)
            ensureChannel(context, AppRepository.get(context).state.value.settings.language)
        return settingsIntents(context, target).any { intent ->
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }

}
