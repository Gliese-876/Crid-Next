package cn.crid.next.ui

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.NumberPicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import cn.crid.next.MainActivity
import cn.crid.next.core.AppState
import cn.crid.next.core.Settings
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matcher
import java.io.File

/** Uses MainActivity's real CridApp → applyStateChanges → repository pipeline, never a UI callback stub. */
class ReminderPreferencePersistenceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = AppRepository.get(compose.activity)
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val backup get() = File(compose.activity.filesDir, "reminder-preference-qa-backup.txt")
    private val json = Json { encodeDefaults = true }

    @Test fun actualAppSwitchPersistsBothDirectionsAndSurvivesActivityRecreation() {
        val saved = repository.state.value.settings
        try {
            initializeOff()
            openSwitch().assertIsOff().performClick()
            awaitPersisted(true)
            openSwitch().assertIsOn()
            Espresso.pressBack()
            compose.onNodeWithTag("reminder_delivery_panel").assertDoesNotExist()
            val minutes = if (saved.reminderMinutes == 1440) 1439 else saved.reminderMinutes + 1
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reminder_minutes"))
            compose.onNodeWithTag("reminder_minutes").performScrollTo().performClick()
            compose.onNodeWithTag("native_number_picker").assertIsDisplayed()
            moveMinuteWheel(forward = saved.reminderMinutes < 1440)
            compose.onNodeWithTag("native_picker_confirm").performClick()
            compose.waitUntil(5_000) {
                repository.state.value.settings.reminderMinutes == minutes && persistedSettings().reminderMinutes == minutes
            }
            compose.onNodeWithTag("reminder_toggle").performScrollTo().performClick()
            compose.waitUntil(5_000) { !repository.state.value.settings.remindersEnabled && !persistedSettings().remindersEnabled }
            compose.onNodeWithTag("reminder_alarm_clock").assertDoesNotExist()
            compose.onNodeWithTag("reminder_minutes").assertDoesNotExist()
            compose.onNodeWithTag("reminder_toggle").performClick()
            compose.waitUntil(5_000) { repository.state.value.settings.remindersEnabled && persistedSettings().remindersEnabled }
            compose.activityRule.scenario.recreate()
            openSwitch().assertIsOn().performClick()
            awaitPersisted(false)
            openSwitch().assertIsOff()
            assertEquals(saved.copy(remindersEnabled = true, reminderAlarmClock = false, reminderMinutes = minutes), repository.state.value.settings)
        } finally { restore(saved) }
    }

    /** QA runs this method, kills the app process, then runs verifyColdStartAndRestore. */
    @Test fun prepareColdStartFromActualSwitch() {
        assumeTrue(arguments.getString("preferenceScenario") == "prepare")
        assertFalse("Restore the earlier preference fixture first", backup.exists())
        val saved = repository.state.value.settings
        backup.writeText("${Process.myPid()}\n${json.encodeToString(saved)}")
        initializeOff()
        openSwitch().assertIsOff().performClick()
        awaitPersisted(true)
        openSwitch().assertIsOn()
        report("prepared")
    }

    @Test fun verifyColdStartAndRestore() {
        assumeTrue(arguments.getString("preferenceScenario") == "verify")
        assertTrue("Missing preference fixture", backup.exists())
        val contents = backup.readText().split('\n', limit = 2)
        val saved = json.decodeFromString<Settings>(contents[1])
        try {
            assertNotEquals("This check requires a new app process", contents[0].toInt(), Process.myPid())
            assertTrue(repository.state.value.settings.reminderAlarmClock)
            assertTrue(persistedSettings().reminderAlarmClock)
            openSwitch().assertIsOn().performClick()
            awaitPersisted(false)
            openSwitch().assertIsOff()
            report("cold-start verified")
        } finally {
            restore(saved)
            backup.delete()
        }
    }

    private fun initializeOff() = runBlocking {
        repository.update { it.copy(settings = it.settings.copy(remindersEnabled = true, reminderAlarmClock = false)) }
    }

    private fun restore(settings: Settings) = runBlocking {
        repository.update { it.copy(settings = settings) }
    }

    private fun openSwitch(): SemanticsNodeInteraction {
        if (compose.onAllNodesWithTag("reminder_delivery_panel").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reminder_status"))
            compose.onNodeWithTag("reminder_status").performClick()
        }
        return compose.onNodeWithTag("reminder_alarm_clock").performScrollTo().assertIsDisplayed()
    }

    private fun moveMinuteWheel(forward: Boolean) {
        onView(isAssignableFrom(NumberPicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "Change the native reminder minute wheel by one value"
            override fun perform(uiController: UiController, view: View) {
                val picker = view as NumberPicker
                val expected = picker.value + if (forward) 1 else -1
                val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                check(picker.accessibilityNodeProvider?.performAction(View.NO_ID, action, null) == true)
                val deadline = SystemClock.uptimeMillis() + 2_000
                while (picker.value != expected && SystemClock.uptimeMillis() < deadline) uiController.loopMainThreadForAtLeast(16)
                check(picker.value == expected)
                uiController.loopMainThreadUntilIdle()
            }
        })
        compose.waitForIdle()
    }

    private fun persistedSettings(): Settings = json.decodeFromString<AppState>(
        File(compose.activity.filesDir, "timetables-v1.json").readText()).settings

    private fun awaitPersisted(enabled: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            repository.state.value.settings.reminderAlarmClock == enabled &&
                runCatching { persistedSettings().reminderAlarmClock == enabled }.getOrDefault(false)
        }
    }

    private fun report(result: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("qaPreferenceResult", result); putInt("qaProcessId", Process.myPid())
            putBoolean("qaStoredAlarmClock", persistedSettings().reminderAlarmClock)
        })
    }
}
