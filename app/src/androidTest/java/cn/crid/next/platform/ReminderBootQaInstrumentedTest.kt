package cn.crid.next.platform

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Opt-in fixture preparation only; the QA driver owns reboot, first unlock, Doze and cleanup. */
class ReminderBootQaInstrumentedTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val repository get() = AppRepository.get(context)
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val backup get() = File(context.filesDir, "reminder-boot-qa-backup.json")
    private val json = Json { encodeDefaults = true }

    @Test fun prepareRealDelivery() = runBlocking {
        assumeTrue(arguments.getString("deliveryScenario") == "prepare")
        assertFalse("Restore the earlier fixture before seeding another", backup.exists())
        backup.writeText(json.encodeToString(repository.state.value))
        val zone = ZoneId.systemDefault()
        val delay = arguments.getString("fireAfterMinutes")?.toLongOrNull()?.coerceIn(2, 15) ?: 4L
        val alarmClock = arguments.getString("alarmClock")?.toBooleanStrictOrNull() ?: true
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(delay)
        val end = start.plusMinutes(15)
        require(start.toLocalDate() == end.toLocalDate()) { "Run this fixture away from midnight" }
        val semesterStart = ScheduleEngine.weekStart(start.toLocalDate())
        val semester = Semester("boot-qa-semester", "Reminder QA", semesterStart.toString(),
            semesterStart.plusWeeks(2).minusDays(1).toString(), 2, listOf(Period(1, "08:00", "08:45")))
        val course = Course("boot-qa-course", "Reminder QA direct boot", lessons = listOf(
            Lesson(date = start.toLocalDate().toString(), startTime = start.toLocalTime().toString(),
                endTime = end.toLocalTime().toString(), teacher = "QA Teacher", location = "QA Room A203")))
        val plan = Plan("boot-qa-plan", semester.id, "Reminder QA", listOf(course))
        repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.EN, holidaysEnabled = false, remindersEnabled = true,
                reminderMinutes = 0, reminderAlarmClock = alarmClock)) }
        PlatformCoordinator.refreshNow(context)
        val notice = ReminderDeviceStore.snapshot(context).notices.single()
        assertEquals(setOf(notice.due(zone)), ReminderScheduler.registeredDues(context))
        instrument.sendStatus(0, Bundle().apply {
            putLong("qaDue", notice.due(zone)); putString("qaDeliveryId", notice.id)
            putString("qaTitle", notice.title); putString("qaLocalStart", notice.startLocal)
            putBoolean("qaAlarmClock", alarmClock)
        })
    }

    @Test fun inspectAndRestoreRealDelivery() = runBlocking {
        assumeTrue(arguments.getString("deliveryScenario") == "restore")
        assertTrue("Missing fixture backup", backup.exists())
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val expected = arguments.getString("expectedDeliveryId")
            if (expected != null) {
                assertTrue("The locked/idle delivery was not recorded", expected in ReminderDeviceStore.delivered(context))
                val before = manager.activeNotifications.filter { it.tag == expected }
                assertEquals(1, before.size)
                val timestamp = before.single().postTime
                PlatformCoordinator.refreshNow(context)
                val after = manager.activeNotifications.filter { it.tag == expected }
                assertEquals(1, after.size)
                assertEquals("Unlock refresh must not repost", timestamp, after.single().postTime)
                instrument.sendStatus(0, Bundle().apply {
                    putLong("qaPostTime", timestamp); putLong("qaRestoreTime", System.currentTimeMillis())
                    putString("qaDeliveryId", expected)
                })
            }
        } finally {
            repository.update { json.decodeFromString<AppState>(backup.readText()) }
            PlatformCoordinator.refreshNow(context)
            manager.activeNotifications.filter {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Reminder QA direct boot"
            }.forEach { manager.cancel(it.tag, it.id) }
            backup.delete()
        }
    }
}
