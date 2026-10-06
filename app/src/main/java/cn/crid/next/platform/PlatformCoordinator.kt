package cn.crid.next.platform

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import cn.crid.next.R
import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Coordinates current reminders, periodic jobs and widget refreshes. */
object PlatformCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { refreshNow(app) } }
    }

    suspend fun refreshNow(context: Context) = mutex.withLock {
        val repository = AppRepository.get(context)
        val revision = repository.reminderRevision
        val state = repository.state.value
        val holidays = repository.holidays.value
        ReminderScheduler.schedule(context, state, holidays, isCurrent = {
            !repository.reminderCommitPending && revision == repository.reminderRevision
        })
        // Repair system-discarded background jobs only after time-critical reminder registration.
        runCatching { PlatformJobs.ensureScheduled(context) }
        CourseWidgets.updateAll(context, state, holidays)
        scheduleWidgetRefresh(context, state, holidays)
    }

    private fun scheduleWidgetRefresh(context: Context, state: AppState, holidays: HolidayCalendar) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        val semester = state.semester
        val plan = state.plan
        val boundaries = if (semester == null || plan == null) emptyList() else
            ScheduleEngine.occurrences(semester, plan, today, state.settings, holidays)
                .filter { it.isActual }.flatMap { occurrence ->
                    listOf(occurrence.start, occurrence.end).map { today.atTime(it).atZone(zone).toInstant() }
                }
        val next = (boundaries + midnight).filter { it > now }.minOrNull() ?: midnight
        val pending = PendingIntent.getBroadcast(context, 8102,
            Intent(context, ScheduleRefreshReceiver::class.java).setAction(ACTION_DATE_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Reuse one non-waking, inexact alarm for today's current/next class and the date rollover.
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next.toEpochMilli(), pending)
    }

    internal fun receive(receiver: BroadcastReceiver, context: Context, block: suspend () -> Unit) {
        val pending = receiver.goAsync()
        scope.launch {
            try { runCatching { block() } } finally { pending.finish() }
        }
    }

    const val ACTION_DATE_REFRESH = "cn.crid.next.action.DATE_REFRESH"
}

class ScheduleRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowed = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            PlatformCoordinator.ACTION_DATE_REFRESH)
        if (intent.action !in allowed) return
        PlatformCoordinator.receive(this, context) {
            try { PlatformCoordinator.refreshNow(context.applicationContext) }
            finally {
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    runCatching { PlatformJobs.removeUnavailableServices(context.applicationContext) }
                }
            }
        }
    }
}

object ReminderScheduler {
    private const val REQUEST = 8101
    internal const val ACTION = "cn.crid.next.action.COURSE_REMINDER"
    internal const val DUE = "due"

    internal fun pending(context: Context, due: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, CourseReminderReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("crid-reminder://due/$due")).putExtra(DUE, due),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized
    fun schedule(context: Context, state: AppState, calendar: HolidayCalendar, now: Instant = Instant.now(),
        isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val manager = context.getSystemService(AlarmManager::class.java)
        ReminderDeviceStore.migrateUnlocked(context)
        val registered = ReminderDeviceStore.registered(context)
        ReminderDelivery.ensureChannel(context, state.settings.language)
        val enabled = state.settings.remindersEnabled && ReminderDelivery.canDeliver(context)
        val zone = ZoneId.systemDefault()
        val planned = if (enabled) ReminderSnapshot.create(state, calendar, now, zone) else ReminderSnapshot()
        val snapshot = planned.copy(notices = planned.remaining(now, zone, ReminderDeviceStore.delivered(context)))
        ReminderDeviceStore.save(context, snapshot)
        val next = snapshot.notices.map { it.due(zone) }.toSet()

        // Re-register even unchanged entries: boot, package replacement and permission changes can
        // erase the system alarms while this small on-disk index survives. Stable identities replace
        // existing entries without first cancelling a reminder that is just about to fire.
        next.forEach { due -> register(context, due, snapshot.alarmClock) }
        if (enabled) registered.filter { it <= now.toEpochMilli() }.sorted().forEach { due ->
            deliverCurrent(context, state, calendar, due, now)
        }
        (registered - next).forEach { old ->
            val alarm = pending(context, old)
            manager.cancel(alarm)
            alarm.cancel()
        }
        ReminderDeviceStore.setRegistered(context, next)
        removeObsoleteNotifications(context, state, calendar, now)
        pruneDeliveries(context, now)

        // Migrate the pre-1.9 single-alarm identity after its independent replacements are ready.
        val legacy = PendingIntent.getBroadcast(context, REQUEST,
            Intent(context, CourseReminderReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (legacy != null) { manager.cancel(legacy); legacy.cancel() }
    }

    internal fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, REQUEST,
        launchIntent(context, "today"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal enum class Registration { ALARM_CLOCK, EXACT, INEXACT }

    internal fun register(context: Context, due: Long, alarmClock: Boolean,
        exactAllowed: Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()): Registration {
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = pending(context, due)
        try {
            if (exactAllowed) {
                if (alarmClock) manager.setAlarmClock(AlarmManager.AlarmClockInfo(due, showIntent(context)), alarm)
                else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarm)
                return if (alarmClock) Registration.ALARM_CLOCK else Registration.EXACT
            }
        } catch (_: SecurityException) {
            // Access can be revoked between the permission check and the platform call.
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarm)
        return Registration.INEXACT
    }

    @Synchronized
    internal fun deliver(context: Context, due: Long) {
        val repository = AppRepository.get(context)
        if (repository.reminderCommitPending) return
        ReminderDeviceStore.migrateUnlocked(context)
        deliverCurrent(context, repository.state.value, repository.holidays.value, due, Instant.now())
    }

    internal fun registeredDues(context: Context): Set<Long> = ReminderDeviceStore.registered(context)

    /** Called under this monitor before a repository commit can expose a changed plan. */
    internal fun invalidateSnapshot(context: Context) = ReminderDeviceStore.invalidate(context)

    /** This path only touches device-protected data, AlarmManager and NotificationManager. */
    @Synchronized
    internal fun restoreLocked(context: Context, now: Instant = Instant.now()) {
        val snapshot = ReminderDeviceStore.snapshot(context)
        val zone = ZoneId.systemDefault()
        ReminderDelivery.ensureChannel(context, snapshot.language)
        val remaining = snapshot.remaining(now, zone, ReminderDeviceStore.delivered(context))
        val future = remaining.map { it.due(zone) }.filter { it > now.toEpochMilli() }.distinct().sorted()
            .take(ReminderPlanner.MAX_PENDING).toSet()
        val next = if (ReminderDelivery.canDeliver(context)) future else emptySet()
        next.forEach { register(context, it, snapshot.alarmClock) }
        val manager = context.getSystemService(AlarmManager::class.java)
        (ReminderDeviceStore.registered(context) - next).forEach { due ->
            pending(context, due).also { manager.cancel(it); it.cancel() }
        }
        ReminderDeviceStore.setRegistered(context, next)
        remaining.filter { it.due(zone) <= now.toEpochMilli() }.forEach {
            postNotice(context, it, snapshot.language, now, zone)
        }
        ReminderDeviceStore.save(context, snapshot.copy(notices = snapshot.remaining(now, zone, ReminderDeviceStore.delivered(context))))
        pruneDeliveries(context, now)
    }

    @Synchronized
    internal fun deliverLocked(context: Context, due: Long, now: Instant = Instant.now()) {
        val snapshot = ReminderDeviceStore.snapshot(context)
        val zone = ZoneId.systemDefault()
        snapshot.notices.filter { it.isDue(due, now, zone) }.forEach {
            postNotice(context, it, snapshot.language, now, zone)
        }
        ReminderDeviceStore.save(context, snapshot.copy(notices = snapshot.remaining(now, zone, ReminderDeviceStore.delivered(context))))
    }

    private fun deliverCurrent(context: Context, state: AppState, calendar: HolidayCalendar, due: Long, now: Instant) {
        if (due <= 0 || !state.settings.remindersEnabled || !ReminderDelivery.canDeliver(context)) return
        val zone = ZoneId.systemDefault()
        // Re-evaluate the current plan and holidays, so deleted/moved/rest-day classes never leak through.
        val occurrences = ReminderPlanner.dueOccurrences(state, calendar, due, now, zone)
        if (occurrences.isEmpty()) return
        occurrences.forEach { occurrence ->
            postNotice(context, ReminderNotice.from(state, occurrence), state.settings.language, now, zone)
        }
    }

    private fun postNotice(context: Context, notice: ReminderNotice, language: cn.crid.next.core.Language,
        now: Instant, zone: ZoneId) {
        ReminderDelivery.ensureChannel(context, language)
        if (!ReminderDelivery.canDeliver(context) || notice.id in ReminderDeviceStore.delivered(context) || notice.end(zone) <= now) return
        val localized = PlatformText.context(context, language)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(localized, ReminderDelivery.CHANNEL)
            .setSmallIcon(R.drawable.ic_course_notification)
            .setContentTitle(notice.title).setContentText(notice.details)
            .setStyle(Notification.BigTextStyle().bigText(notice.details))
            .setContentIntent(showIntent(context)).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(true)
            .setWhen(notice.start(zone).toEpochMilli())
            .setTimeoutAfter(java.time.Duration.between(now, notice.end(zone)).toMillis())
            .build()
        try {
            manager.notify(notice.id, 0, notification)
            ReminderDeviceStore.setDelivered(context, ReminderDeviceStore.delivered(context) + notice.id)
        } catch (_: SecurityException) { /* Permission revoked; do not mark an undelivered notice. */ }
    }

    private fun pruneDeliveries(context: Context, now: Instant) {
        val cutoff = now.atZone(ZoneId.systemDefault()).toLocalDate().minusDays(7)
        val retained = ReminderDeviceStore.delivered(context).filter {
            runCatching { LocalDate.parse(it.substringBefore(':')) }.getOrNull()?.let { date -> !date.isBefore(cutoff) } == true
        }.toSet()
        ReminderDeviceStore.setDelivered(context, retained)
    }

    private fun removeObsoleteNotifications(context: Context, state: AppState, calendar: HolidayCalendar, now: Instant) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val semester = state.semester
        val plan = state.plan
        val zone = ZoneId.systemDefault()
        val validByDate = mutableMapOf<LocalDate, Set<String>>()
        manager.activeNotifications.filter { it.notification.channelId == ReminderDelivery.CHANNEL }.forEach { posted ->
            val date = runCatching { LocalDate.parse(posted.tag.orEmpty().substringBefore(':')) }.getOrNull()
            val valid = if (!state.settings.remindersEnabled || semester == null || plan == null || date == null) false
                else posted.tag in validByDate.getOrPut(date) {
                    ScheduleEngine.occurrences(semester, plan, date, state.settings, calendar)
                        .filter { it.isActual && date.atTime(it.end).atZone(zone).toInstant() > now }
                        .map { ReminderIdentity.deliveryId(state, it) }.toSet()
                }
            if (!valid) manager.cancel(posted.tag, posted.id)
        }
    }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION) return
        val due = intent.getLongExtra(ReminderScheduler.DUE, 0)
        PlatformCoordinator.receive(this, context) {
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                ReminderScheduler.deliverLocked(context.applicationContext, due)
                return@receive
            }
            try { ReminderScheduler.deliver(context.applicationContext, due) }
            finally { PlatformCoordinator.refreshNow(context.applicationContext) }
        }
    }
}

/** Kept separate from the unlocked maintenance receiver to avoid initializing CE-only components. */
class DirectBootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        if (context.getSystemService(UserManager::class.java).isUserUnlocked) return
        PlatformCoordinator.receive(this, context) { ReminderScheduler.restoreLocked(context.applicationContext) }
    }
}

internal fun launchIntent(context: Context, route: String): Intent = Intent()
    .setClassName(context.packageName, "cn.crid.next.MainActivity")
    .setAction(Intent.ACTION_VIEW).putExtra("route", route)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
