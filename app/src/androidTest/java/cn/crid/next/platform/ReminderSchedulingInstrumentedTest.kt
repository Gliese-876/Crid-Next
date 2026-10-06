package cn.crid.next.platform

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.AppState
import cn.crid.next.core.Course
import cn.crid.next.core.DataValidator
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Language
import cn.crid.next.core.Lesson
import cn.crid.next.core.Period
import cn.crid.next.core.Plan
import cn.crid.next.core.ScheduleEngine
import cn.crid.next.core.Semester
import cn.crid.next.core.Settings
import cn.crid.next.data.AppRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Run on the dedicated QA emulator with exact alarm access granted externally and notification
 * permission granted on API 33+.
 * Revoking a runtime permission from inside instrumentation can kill its target process, so these
 * tests deliberately preserve permission and notification-channel choices.
 */
class ReminderSchedulingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = AppRepository.get(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val preferences get() = ReminderDeviceStore.preferences(context)
    private val zone get() = ZoneId.systemDefault()
    private val calendar = HolidayCalendar()
    private val fixtureId = UUID.randomUUID().toString()
    private lateinit var previousState: AppState
    private var previousPreferences: Map<String, Any?> = emptyMap()

    @Before fun isolateTestSchedule() {
        previousState = repository.state.value
        previousPreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        replaceRepository(AppState(settings = Settings(language = Language.EN)))
        preferences.edit().clear().commit()
        cancelTestNotifications()
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals("QA must grant notification permission before running this class",
                PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        ReminderDelivery.ensureChannel(context, Language.EN)
        assertTrue("QA must leave the existing reminder channel enabled", ReminderDelivery.canDeliver(context))
    }

    @After fun restoreScheduleAndPreferences() {
        if (!::previousState.isInitialized) return
        try {
            // Cancels only alarms tracked by the test registration index, never unrelated alarms.
            replaceRepository(AppState(settings = Settings(language = Language.EN)))
            cancelTestNotifications()
        } finally {
            restorePreferences(preferences, previousPreferences)
            replaceRepository(previousState)
            // Keep the original persisted delivery history even if the clock advanced during a test.
            // refreshNow above has already restored the real state's desired platform alarms.
            restorePreferences(preferences, previousPreferences)
        }
    }

    @Test fun sixtyFourUpcomingTimesHaveIndependentStablePlatformPendingIntents() {
        val now = Instant.now()
        val firstDate = now.atZone(zone).toLocalDate().plusDays(1)
        val courses = (0 until 100).map { index ->
            course("future-$index", firstDate.plusDays(index.toLong()), "08:00", "08:45")
        }
        val state = fixture(courses)
        replaceRepository(state)
        ReminderScheduler.schedule(context, state, calendar, now)
        val dues = ReminderScheduler.registeredDues(context).sorted()
        assertEquals(64, dues.size)
        assertEquals(firstDate.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), dues.first())
        assertEquals(firstDate.plusDays(63).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), dues.last())
        val originals = dues.map { due -> requireNotNull(findPending(due)) { "Missing independent alarm for $due" } }
        assertEquals("One missed receiver must not erase subsequent identities", 64, originals.toSet().size)
        assertTrue(originals.all { it.isImmutable && it.creatorPackage == context.packageName })

        ReminderScheduler.schedule(context, state, calendar, now)
        assertEquals(dues.toSet(), ReminderScheduler.registeredDues(context))
        assertEquals("Refreshing must replace, not multiply, platform identities", originals, dues.map(::findPending))
        assertNull(findPending(firstDate.plusDays(64).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()))
    }

    @Test fun simultaneousCoursesShareOneAlarmAndDisablingRemindersCancelsIt() {
        val now = Instant.now()
        val date = now.atZone(zone).toLocalDate().plusDays(1)
        val state = fixture(listOf(course("first", date, "09:00", "10:00"),
            course("second", date, "09:00", "11:00")))
        replaceRepository(state)
        ReminderScheduler.schedule(context, state, calendar, now)
        val due = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(setOf(due), ReminderScheduler.registeredDues(context))
        val pending = requireNotNull(findPending(due))
        assertEquals(pending, ReminderScheduler.pending(context, due))

        val disabled = state.copy(settings = state.settings.copy(remindersEnabled = false))
        replaceRepository(disabled)
        ReminderScheduler.schedule(context, disabled, calendar, now)
        assertTrue(ReminderScheduler.registeredDues(context).isEmpty())
        assertNull("Disabling must remove the actual PendingIntent token", findPending(due))
        assertThrows(PendingIntent.CanceledException::class.java) { pending.send() }
    }

    @Test fun missedRegisteredReminderCatchesUpAndDismissalDoesNotPermitRedelivery() {
        val fixture = currentReminder("catch-up")
        replaceRepository(fixture.state)
        preferences.edit().putStringSet(REGISTERED, setOf(fixture.due.toString())).commit()
        ReminderScheduler.schedule(context, fixture.state, calendar, Instant.now())
        await("Current registered reminder was not recovered") { testNotifications().size == 1 }
        val first = testNotifications().single()
        assertEquals(fixture.course.name, first.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val delivered = preferences.getStringSet(DELIVERED, emptySet()).orEmpty().toSet()
        assertEquals("Successful delivery must be recorded on disk", 1, delivered.size)
        assertTrue(delivered.single().startsWith("${fixture.course.lessons.single().date}:"))
        notifications.cancel(first.tag, first.id)
        await("Dismissed test notification was not removed") { testNotifications().isEmpty() }

        // A surviving stale registration after boot/refresh must not recreate a dismissed notice.
        preferences.edit().putStringSet(REGISTERED, setOf(fixture.due.toString())).commit()
        ReminderScheduler.schedule(context, fixture.state, calendar, Instant.now())
        ReminderScheduler.deliver(context, fixture.due)
        SystemClock.sleep(250)
        assertTrue(testNotifications().isEmpty())
        assertEquals(delivered, preferences.getStringSet(DELIVERED, emptySet()))
    }

    @Test fun movedAndDeletedCoursesRejectTheirPreviouslyRegisteredTrigger() {
        val fixture = currentReminder("stale")
        val originalPlan = requireNotNull(fixture.state.plan)
        val moved = fixture.course.copy(lessons = fixture.course.lessons.map {
            it.copy(date = LocalDate.parse(requireNotNull(it.date)).plusDays(1).toString())
        })
        val states = listOf(
            fixture.state.copy(plans = listOf(originalPlan.copy(courses = listOf(moved)))),
            fixture.state.copy(plans = listOf(originalPlan.copy(courses = emptyList()))),
        )
        states.forEach { state ->
            replaceRepository(state)
            preferences.edit().putStringSet(REGISTERED,
                ReminderScheduler.registeredDues(context).map(Long::toString).toSet() + fixture.due.toString()).commit()
            ReminderScheduler.schedule(context, state, calendar, Instant.now())
            ReminderScheduler.deliver(context, fixture.due)
            assertFalse(ReminderScheduler.registeredDues(context).contains(fixture.due))
            assertTrue("An old trigger must re-read the edited/deleted course", testNotifications().isEmpty())
            assertTrue(preferences.getStringSet(DELIVERED, emptySet()).orEmpty().isEmpty())
        }
    }

    @Test fun disablingOrSwitchingPlansRemovesVisibleOldCourseNotices() {
        val fixture = currentReminder("visible-old")
        replaceRepository(fixture.state)
        preferences.edit().putStringSet(REGISTERED, setOf(fixture.due.toString())).commit()
        ReminderScheduler.schedule(context, fixture.state, calendar)
        await("Expected current notice") { testNotifications().size == 1 }
        val disabled = fixture.state.copy(settings = fixture.state.settings.copy(remindersEnabled = false))
        replaceRepository(disabled)
        await("Disabled reminder must remove its visible notice") { testNotifications().isEmpty() }

        preferences.edit().remove(DELIVERED).commit()
        replaceRepository(fixture.state)
        preferences.edit().putStringSet(REGISTERED, setOf(fixture.due.toString())).commit()
        ReminderScheduler.schedule(context, fixture.state, calendar)
        await("Expected restored current notice") { testNotifications().size == 1 }
        val nextPlan = requireNotNull(fixture.state.plan).copy(id = "another-$fixtureId", courses = emptyList())
        replaceRepository(fixture.state.copy(plans = fixture.state.plans + nextPlan, selectedPlanId = nextPlan.id))
        await("Switching plans must remove the old plan's notice") { testNotifications().isEmpty() }
    }

    @Test fun publicSettingsLinksTargetOnlyThisAppAndPreserveExistingChannelChoices() {
        val original = requireNotNull(notifications.getNotificationChannel(ReminderDelivery.CHANNEL))
        val originalImportance = original.importance
        val originalSound = original.sound
        val originalVibration = original.shouldVibrate()
        val originalBypass = original.canBypassDnd()
        val delivery = ReminderDelivery.read(context)
        assertTrue(delivery.notificationsAllowed)
        assertTrue(delivery.channelEnabled)
        val allowedActions = setOf(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS,
            AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
            AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        ReminderSettingsTarget.entries.forEach { target ->
            val intents = ReminderDelivery.settingsIntents(context, target)
            assertTrue(intents.isNotEmpty())
            intents.forEach { intent ->
                assertTrue(intent.action in allowedActions)
                assertNull("No private OEM component may be hard-coded", intent.component)
                if (intent.action == AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS ||
                    intent.action == AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) {
                    assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
                } else if (intent.action != AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                    assertEquals(context.packageName, intent.getStringExtra(AndroidSettings.EXTRA_APP_PACKAGE))
                }
                if (intent.action == AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    assertEquals(ReminderDelivery.CHANNEL, intent.getStringExtra(AndroidSettings.EXTRA_CHANNEL_ID))
            }
            assertEquals(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
            if (target == ReminderSettingsTarget.BATTERY)
                assertEquals(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents.first().action)
        }
        val channel = requireNotNull(notifications.getNotificationChannel(ReminderDelivery.CHANNEL))
        assertEquals(originalImportance, channel.importance)
        assertEquals(originalSound, channel.sound)
        assertEquals(originalVibration, channel.shouldVibrate())
        assertEquals(originalBypass, channel.canBypassDnd())
    }

    @Test fun realAlarmManagerDeliveryPostsCurrentCourseThroughManifestReceiver() {
        assertTrue("QA must grant exact alarms before testing an immediate real alarm", alarms.canScheduleExactAlarms())
        val fixture = currentReminder("alarm-manager")
        replaceRepository(fixture.state)
        assertTrue(testNotifications().isEmpty())
        assertTrue(preferences.getStringSet(DELIVERED, emptySet()).orEmpty().isEmpty())
        // Keep the catch-up index empty so queued maintenance cannot produce a false positive.
        // Only the real AlarmManager can send this production PendingIntent to the manifest receiver.
        assertTrue(ReminderScheduler.registeredDues(context).isEmpty())
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
            ReminderScheduler.pending(context, fixture.due))
        await("AlarmManager did not deliver through CourseReminderReceiver", timeoutMillis = 15_000) {
            testNotifications().size == 1 && preferences.getStringSet(DELIVERED, emptySet()).orEmpty().size == 1
        }
        val notification = testNotifications().single().notification
        assertEquals(fixture.course.name, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(ReminderDelivery.CHANNEL, notification.channelId)
        assertEquals(Notification.CATEGORY_EVENT, notification.category)
        val lesson = fixture.course.lessons.single()
        val start = LocalDate.parse(requireNotNull(lesson.date)).atTime(LocalTime.parse(lesson.startTime))
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(start, notification.`when`)
        val details = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(details.contains("Teaching Block A203"))
        assertTrue(details.contains("QA Teacher"))
        assertNotNull(notification.smallIcon)
        assertNotNull(notification.contentIntent)
        assertEquals(context.packageName, notification.contentIntent.creatorPackage)
        assertTrue(notification.contentIntent.isImmutable)
        assertEquals(1, preferences.getStringSet(DELIVERED, emptySet()).orEmpty().size)
    }

    @Test fun alarmClockModeUsesVisibleAlarmInfoAndSwitchesBackWithoutDuplicateIdentity() {
        assertTrue(alarms.canScheduleExactAlarms())
        val date = LocalDate.now(zone).plusDays(1)
        val standard = fixture(listOf(course("clock-mode", date, "08:00", "09:00")))
        val clock = standard.copy(settings = standard.settings.copy(reminderAlarmClock = true))
        replaceRepository(clock)
        val due = ReminderScheduler.registeredDues(context).single()
        val original = requireNotNull(findPending(due))
        await("Alarm clock info was not exposed") { alarms.nextAlarmClock?.triggerTime == due }
        val info = requireNotNull(alarms.nextAlarmClock)
        assertEquals(ReminderScheduler.showIntent(context), info.showIntent)
        assertTrue(info.showIntent.isImmutable)
        assertEquals(context.packageName, info.showIntent.creatorPackage)
        replaceRepository(standard)
        assertEquals(original, findPending(due))
        await("Ordinary reminder must remove its alarm-clock marker") { alarms.nextAlarmClock?.triggerTime != due }
        assertFalse(ReminderDeviceStore.snapshot(context).alarmClock)
    }

    @Test fun deniedExactAccessUsesInexactRegistrationWithoutClockMarker() {
        val date = LocalDate.now(zone).plusDays(1)
        val state = fixture(listOf(course("fallback", date, "09:00", "10:00")))
        replaceRepository(state)
        val due = ReminderScheduler.registeredDues(context).single()
        assertEquals(ReminderScheduler.Registration.INEXACT,
            ReminderScheduler.register(context, due, alarmClock = true, exactAllowed = false))
        assertNotNull(findPending(due))
        assertNotEquals(due, alarms.nextAlarmClock?.triggerTime)
    }

    @Test fun deviceSnapshotRestoresAfterAlarmLossAndRejectsInvalidatedOrCorruptData() {
        val date = LocalDate.now(zone).plusDays(1)
        val state = fixture(listOf(course("snapshot", date, "08:00", "09:00")))
        replaceRepository(state)
        val due = ReminderScheduler.registeredDues(context).single()
        val alarm = requireNotNull(findPending(due))
        alarms.cancel(alarm)
        alarm.cancel()
        ReminderScheduler.restoreLocked(context)
        assertNotNull(findPending(due))
        synchronized(ReminderScheduler) { ReminderScheduler.invalidateSnapshot(context) }
        ReminderScheduler.restoreLocked(context)
        assertNull(findPending(due))
        assertTrue(ReminderDeviceStore.snapshot(context).notices.isEmpty())

        val corrupt = """{"language":"EN","alarmClock":true,"notices":[{"id":"bad","advanceMinutes":15,"startLocal":"broken","endLocal":"2026-10-01T09:00","title":"Bad","details":"Bad"}]}"""
        preferences.edit().putString("snapshot", corrupt).commit()
        ReminderScheduler.restoreLocked(context)
        assertTrue(ReminderDeviceStore.snapshot(context).notices.isEmpty())
        assertTrue(ReminderScheduler.registeredDues(context).isEmpty())
    }

    @Test fun lockedDeliverySharesHistoryWithUnlockedDeliveryAndMigratesOldRecords() {
        val fixture = currentReminder("locked-dedup")
        replaceRepository(fixture.state)
        val occurrence = ReminderPlanner.dueOccurrences(fixture.state, calendar, fixture.due, Instant.now(), zone).single()
        val notice = ReminderNotice.from(fixture.state, occurrence)
        // The device store shares the scheduler monitor; queued unlocked maintenance must not
        // overwrite this artificial locked-boot snapshot between its installation and delivery.
        synchronized(ReminderScheduler) {
            ReminderDeviceStore.save(context, ReminderSnapshot(Language.EN, true, listOf(notice)))
            assertEquals(listOf(notice), ReminderDeviceStore.snapshot(context).notices)
            ReminderScheduler.deliverLocked(context, fixture.due)
        }
        await("Device snapshot did not produce a reminder") { testNotifications().size == 1 }
        val posted = testNotifications().single()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
        notifications.cancel(posted.tag, posted.id)
        // NotificationManager cancellation is asynchronous, including on the unchanged baseline.
        await("The original notification was not dismissed") { testNotifications().isEmpty() }
        ReminderScheduler.deliver(context, fixture.due)
        assertTrue(testNotifications().isEmpty())
        assertEquals(setOf(notice.id), ReminderDeviceStore.delivered(context))

        preferences.edit().remove(DELIVERED).commit()
        val legacy = context.getSharedPreferences(ReminderDeviceStore.LEGACY_STORE, Context.MODE_PRIVATE)
        legacy.edit().putStringSet(DELIVERED, setOf(notice.id)).commit()
        ReminderScheduler.deliver(context, fixture.due)
        assertTrue(testNotifications().isEmpty())
        assertEquals(setOf(notice.id), ReminderDeviceStore.delivered(context))
        assertFalse(legacy.contains(DELIVERED))
    }

    @Test fun staleRefreshRevisionCannotRepublishSnapshotAfterRepositoryEdit() {
        val date = LocalDate.now(zone).plusDays(1)
        val state = fixture(listOf(course("revision", date, "08:00", "09:00")))
        replaceRepository(state)
        val revision = repository.reminderRevision
        replaceRepository(state.copy(settings = state.settings.copy(remindersEnabled = false)))
        assertTrue(ReminderDeviceStore.snapshot(context).notices.isEmpty())
        ReminderScheduler.schedule(context, state, calendar, isCurrent = {
            !repository.reminderCommitPending && revision == repository.reminderRevision
        })
        assertTrue(ReminderDeviceStore.snapshot(context).notices.isEmpty())
        assertTrue(ReminderScheduler.registeredDues(context).isEmpty())
    }

    private fun replaceRepository(state: AppState) = runBlocking {
        repository.update { state }
        PlatformCoordinator.refreshNow(context)
    }

    private fun fixture(courses: List<Course>, reminderMinutes: Int = 0): AppState {
        val start = ScheduleEngine.weekStart(LocalDate.now(zone))
        val semester = Semester("reminder-qa-semester-$fixtureId", "Reminder QA semester", start.toString(),
            start.plusWeeks(16).minusDays(1).toString(), 16, listOf(Period(1, "08:00", "08:45")))
        assertTrue(DataValidator.validateSemester(semester).isEmpty())
        assertTrue(DataValidator.validateCourses(courses, semester).isEmpty())
        val plan = Plan("reminder-qa-plan-$fixtureId", semester.id, "Reminder QA plan", courses)
        return AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false, remindersEnabled = true,
                reminderMinutes = reminderMinutes))
    }

    private fun course(id: String, date: LocalDate, start: String, end: String) = Course(
        "reminder-qa-$fixtureId-$id", "$TITLE_PREFIX $id", lessons = listOf(Lesson(
            date = date.toString(), startTime = start, endTime = end,
            location = "Teaching Block A203", teacher = "QA Teacher")))

    private data class CurrentReminder(val state: AppState, val course: Course, val due: Long)

    private fun currentReminder(id: String): CurrentReminder {
        val currentMinute = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES)
        // At midnight, use the next day's first class and a matching lead time; lessons cannot span days.
        val start = if (currentMinute.toLocalTime() >= LocalTime.of(23, 50))
            currentMinute.toLocalDate().plusDays(1).atStartOfDay() else currentMinute
        val end = start.plusMinutes(10)
        val lead = Duration.between(currentMinute, start).toMinutes().toInt()
        val course = course(id, start.toLocalDate(), start.toLocalTime().toString(), end.toLocalTime().toString())
        val state = fixture(listOf(course), lead)
        return CurrentReminder(state, course, currentMinute.atZone(zone).toInstant().toEpochMilli())
    }

    private fun findPending(due: Long): PendingIntent? = PendingIntent.getBroadcast(context, 8101,
        Intent(context, CourseReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
            .setData(Uri.parse("crid-reminder://due/$due")),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun testNotifications() = notifications.activeNotifications.filter {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.startsWith(TITLE_PREFIX) == true
    }

    private fun cancelTestNotifications() {
        testNotifications().forEach { notifications.cancel(it.tag, it.id) }
    }

    private fun await(message: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        assertTrue(message, condition())
    }

    private fun restorePreferences(target: SharedPreferences, values: Map<String, Any?>) {
        target.edit().clear().apply {
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    private companion object {
        const val STORE = "course-reminder-registration-v2"
        const val REGISTERED = "registered"
        const val DELIVERED = "delivered"
        const val TITLE_PREFIX = "Reminder QA"
    }
}
