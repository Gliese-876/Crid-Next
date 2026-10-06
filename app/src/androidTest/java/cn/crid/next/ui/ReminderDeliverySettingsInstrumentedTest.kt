package cn.crid.next.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import cn.crid.next.MainActivity
import cn.crid.next.core.Language
import cn.crid.next.data.AppRepository
import cn.crid.next.platform.ReminderDeliveryState
import cn.crid.next.platform.ReminderSettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises snapshots and the public entry point without launching Android settings. */
class ReminderDeliverySettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val ready = ReminderDeliveryState(
        notificationsAllowed = true, channelEnabled = true, channelSilent = false,
        exactAlarmsAllowed = true, backgroundRestricted = false, batteryOptimizationIgnored = false,
    )
    private val blocked = ready.copy(
        notificationsAllowed = false, channelEnabled = false, channelSilent = true,
        exactAlarmsAllowed = false, backgroundRestricted = true, batteryOptimizationIgnored = true,
    )
    private val tags = listOf("reminder_notifications", "reminder_channel", "reminder_timing", "reminder_battery")

    @Test fun everyStatusHasAnAccessibleTouchTargetAndOpensOnlyItsRequestedSettings() {
        val fixture = install(Fixture(blocked))
        val savedSettings = compose.runOnIdle { AppRepository.get(compose.activity).state.value.settings }
        val expected = listOf(
            RowCopy("Notification permission", "Not allowed", "Open notification settings", ReminderSettingsTarget.NOTIFICATIONS),
            RowCopy("Class notifications", "Off", "Adjust notification style", ReminderSettingsTarget.CHANNEL),
            RowCopy("Timely reminders", "Notifications not allowed", "Open timely reminder settings", ReminderSettingsTarget.EXACT_ALARMS),
            RowCopy("Background activity", "Background activity restricted", "Open battery settings", ReminderSettingsTarget.BATTERY),
        )

        expected.forEachIndexed { index, copy ->
            row(tags[index], copy).performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(expected.take(index + 1).map { it.target }, fixture.opened)
                assertEquals("A settings shortcut must leave app preferences intact", savedSettings,
                    AppRepository.get(compose.activity).state.value.settings)
                assertEquals(0, fixture.dismissals)
                assertEquals(blocked, fixture.state)
            }
            compose.onNodeWithTag("reminder_delivery_panel").assertExists()
        }
    }

    @Test fun silentChannelsAndDefaultBatteryPolicyStayUsableWhileRestrictionTakesPriority() {
        val fixture = install(Fixture(ready.copy(channelSilent = true)))
        compose.onNodeWithTag("reminder_channel").performScrollTo().assertTextContains("Silent reminders")
        compose.onNodeWithTag("reminder_battery").performScrollTo().assertTextContains("System default")
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Using current settings")
        assertNeutralStatus("Silent reminders", "reminder_channel")
        assertNeutralStatus("System default", "reminder_battery")
        compose.onNodeWithTag("reminder_settings_error").assertDoesNotExist()

        compose.runOnIdle { fixture.state = fixture.state.copy(batteryOptimizationIgnored = true) }
        compose.onNodeWithTag("reminder_battery").performScrollTo().assertTextContains("Battery restrictions relaxed")
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Using current settings")
        assertNeutralStatus("Battery restrictions relaxed", "reminder_battery")

        compose.runOnIdle {
            fixture.state = fixture.state.copy(backgroundRestricted = true, exactAlarmsAllowed = false)
        }
        compose.onNodeWithTag("reminder_battery").performScrollTo()
            .assertTextEquals("Background activity", "Background activity restricted")
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Background activity restricted")
        compose.runOnIdle { assertTrue(fixture.opened.isEmpty()) }
    }

    @Test fun replacingTheSnapshotRefreshesEveryRowAndSummaryWithoutReopeningTheDialog() {
        val fixture = install(Fixture(blocked))
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Notifications not allowed")

        compose.runOnIdle { fixture.state = ready }

        listOf(
            "Notification permission" to "Allowed",
            "Class notifications" to "On",
            "Timely reminders" to "Allowed",
            "Background activity" to "System default",
        ).forEachIndexed { index, (title, status) ->
            compose.onNodeWithTag(tags[index]).performScrollTo().assertIsDisplayed().assertTextEquals(title, status)
        }
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Using current settings")

        compose.runOnIdle { fixture.enabled = false }
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Reminders are off")
        compose.onNodeWithTag("reminder_alarm_clock").assertDoesNotExist()
        compose.onNodeWithTag("reminder_notifications").performScrollTo().assertTextContains("Allowed")
        tags.forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed().assertHasClickAction().assertIsEnabled() }
        compose.runOnIdle {
            assertEquals(0, fixture.dismissals)
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun simplifiedTraditionalAndEnglishCopyHasLocalizedActionsAndNoTrailingFullStops() {
        val fixture = install(Fixture(ready))
        val translations = listOf(
            Language.ZH_CN to listOf(
                RowCopy("通知权限", "已允许", "打开通知设置", ReminderSettingsTarget.NOTIFICATIONS),
                RowCopy("课程通知", "已开启", "调整提醒样式", ReminderSettingsTarget.CHANNEL),
                RowCopy("准时提醒", "已允许", "打开准时提醒设置", ReminderSettingsTarget.EXACT_ALARMS),
                RowCopy("后台运行", "系统默认", "打开电池设置", ReminderSettingsTarget.BATTERY),
            ),
            Language.ZH_TW to listOf(
                RowCopy("通知權限", "已允許", "開啟通知設定", ReminderSettingsTarget.NOTIFICATIONS),
                RowCopy("課程通知", "已開啟", "調整提醒樣式", ReminderSettingsTarget.CHANNEL),
                RowCopy("準時提醒", "已允許", "開啟準時提醒設定", ReminderSettingsTarget.EXACT_ALARMS),
                RowCopy("背景執行", "系統預設", "開啟電池設定", ReminderSettingsTarget.BATTERY),
            ),
            Language.EN to listOf(
                RowCopy("Notification permission", "Allowed", "Open notification settings", ReminderSettingsTarget.NOTIFICATIONS),
                RowCopy("Class notifications", "On", "Adjust notification style", ReminderSettingsTarget.CHANNEL),
                RowCopy("Timely reminders", "Allowed", "Open timely reminder settings", ReminderSettingsTarget.EXACT_ALARMS),
                RowCopy("Background activity", "System default", "Open battery settings", ReminderSettingsTarget.BATTERY),
            ),
        )
        translations.forEach { (language, rows) ->
            compose.runOnIdle { fixture.language = language; fixture.state = ready }
            rows.forEachIndexed { index, copy -> row(tags[index], copy) }
            // These snapshots cover every status branch, plus the optional settings error.
            listOf(ready, ready.copy(channelSilent = true, batteryOptimizationIgnored = true), blocked).forEach { snapshot ->
                compose.runOnIdle { fixture.state = snapshot; fixture.openFailed = true }
                val rendered = compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
                ).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
                assertTrue("$language must render the panel copy", rendered.size >= 10)
                rendered.forEach { value -> assertShortCopy(language, value) }
                tags.forEach { tag ->
                    val action = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick]
                    assertShortCopy(language, requireNotNull(action.label))
                }
            }
        }
    }

    @Test fun unavailableSystemSettingsAreAnnouncedAndCanClearAfterANewAttempt() {
        val fixture = install(Fixture(ready))
        compose.onNodeWithTag("reminder_settings_error").assertDoesNotExist()

        compose.runOnIdle { fixture.openFailed = true }
        compose.onNodeWithTag("reminder_settings_error").performScrollTo().assertIsDisplayed()
            .assertTextEquals("System settings are unavailable")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("reminder_battery").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ReminderSettingsTarget.BATTERY), fixture.opened)
            fixture.openFailed = false
        }
        compose.onNodeWithTag("reminder_settings_error").assertDoesNotExist()
        compose.onNodeWithTag("reminder_delivery_panel").assertExists()
    }

    @Test fun settingsEntryOpensThePanelAndBackReturnsWithoutChangingPreferences() {
        val repository = AppRepository.get(compose.activity)
        val savedState = repository.state.value
        val calendar = repository.holidays.value
        var preferenceUpdates = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    SettingsScreen(
                        state = savedState.copy(settings = savedState.settings.copy(remindersEnabled = true)), calendar = calendar,
                        text = UiText(Language.EN), onUpdate = { preferenceUpdates++ },
                    )
                }
            }
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reminder_status"))
        compose.onNodeWithText("Reminder settings").assertExists()
        compose.onNodeWithTag("reminder_toggle").performScrollTo().assertIsOn()
        compose.onNodeWithTag("reminder_minutes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reminder_alarm_clock").assertDoesNotExist()
        compose.onNodeWithTag("reminder_status").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("reminder_delivery_panel").assertExists()
        listOf("reminder_toggle", "reminder_minutes").forEach { tag ->
            compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("reminder_delivery_panel"))).assertCountEquals(0)
        }
        compose.onNodeWithTag("reminder_alarm_clock").performScrollTo().assertIsDisplayed()
        tags.forEach { tag -> compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }

        Espresso.pressBack()

        compose.onNodeWithTag("reminder_delivery_panel").assertDoesNotExist()
        compose.onNodeWithTag("reminder_status").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, preferenceUpdates)
            assertEquals(savedState.settings, repository.state.value.settings)
        }
    }

    @Test fun alarmClockStatusUpdatesAsPermissionChangesAndNotificationsAreDisabled() {
        val fixture = install(Fixture(ready.copy(exactAlarmsAllowed = false)).apply { alarmClockEnabled = true })
        compose.onNodeWithTag("reminder_timing").performScrollTo()
            .assertTextContains("Alarm-clock reminders inactive · May be delayed")
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Alarm-clock reminders inactive · May be delayed")

        compose.runOnIdle { fixture.state = ready.copy(channelSilent = true) }
        compose.onNodeWithTag("reminder_timing").performScrollTo().assertTextContains("Alarm-clock reminders are on")
        compose.onNodeWithTag("reminder_channel").performScrollTo().assertTextContains("Silent reminders")

        compose.runOnIdle { fixture.state = ready.copy(channelEnabled = false) }
        compose.onNodeWithTag("reminder_timing").performScrollTo().assertTextContains("Class notifications are off")
        compose.onNodeWithTag("delivery_summary").assertTextEquals("Class notifications are off")
        compose.runOnIdle { fixture.enabled = false }
        compose.onNodeWithTag("reminder_alarm_clock").assertDoesNotExist()
        compose.onNodeWithTag("reminder_timing").performScrollTo().assertTextContains("Reminders are off")
            .assertHasClickAction().assertIsEnabled()
        compose.runOnIdle { assertTrue(fixture.opened.isEmpty()) }
    }

    @Test fun alarmClockSwitchIsAnExplicitReversibleChoiceAndKeepsOtherPreferences() {
        val repository = AppRepository.get(compose.activity)
        val savedState = repository.state.value
        val savedHolidays = repository.holidays.value
        val initial = savedState.copy(settings = savedState.settings.copy(remindersEnabled = true, reminderAlarmClock = false))
        var displayedState by mutableStateOf(initial)
        var preferenceUpdates = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    SettingsScreen(displayedState, savedHolidays, UiText(Language.EN),
                        onUpdate = { displayedState = it; preferenceUpdates++ })
                }
            }
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reminder_status"))
        compose.onNodeWithTag("reminder_status").performClick()
        compose.onNodeWithTag("reminder_alarm_clock").performScrollTo().assertIsDisplayed()
            .assertIsOff().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertTextContains("The system may show an alarm icon; sound follows your class notification settings", substring = true)
            .performClick()
        compose.onNodeWithTag("reminder_alarm_clock").assertIsOn()
        compose.runOnIdle {
            assertEquals(initial.copy(settings = initial.settings.copy(reminderAlarmClock = true)), displayedState)
            assertEquals(1, preferenceUpdates)
        }
        compose.onNodeWithTag("reminder_alarm_clock").performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(initial, displayedState)
            assertEquals(2, preferenceUpdates)
            displayedState = displayedState.copy(settings = displayedState.settings.copy(remindersEnabled = false))
        }
        compose.onNodeWithTag("reminder_alarm_clock").assertDoesNotExist()
        compose.runOnIdle { assertEquals(savedState, repository.state.value) }
    }

    private fun row(tag: String, copy: RowCopy): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assertHasClickAction().assertIsEnabled().assertTextEquals(copy.title, copy.status)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .also { node ->
                assertEquals(copy.action, node.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            }

    private fun assertShortCopy(language: Language, value: String) {
        assertTrue("$language has nonempty copy", value.isNotBlank())
        assertFalse("$language copy ends without a full stop: $value", value.trimEnd().endsWith('.') || value.trimEnd().endsWith('。'))
    }

    private fun assertNeutralStatus(value: String, rowTag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$value uses the normal status color", cridColorScheme(false).onSurfaceVariant.toArgb(),
            layouts.single().layoutInput.style.color.toArgb())
    }

    private fun install(fixture: Fixture): Fixture {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme(colorScheme = cridColorScheme(false)) {
                    val text = UiText(fixture.language)
                    Text(reminderDeliverySummary(fixture.state, fixture.enabled, text, fixture.alarmClockEnabled),
                        Modifier.testTag("delivery_summary"))
                    ReminderDeliverySettingsDialog(
                        state = fixture.state, text = text,
                        onDismiss = { fixture.dismissals++ },
                        onOpenSettings = { fixture.opened += it },
                        openFailed = fixture.openFailed,
                        remindersEnabled = fixture.enabled,
                        alarmClockEnabled = fixture.alarmClockEnabled,
                        onAlarmClockEnabledChange = { fixture.alarmClockEnabled = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private data class RowCopy(val title: String, val status: String, val action: String, val target: ReminderSettingsTarget)

    private class Fixture(initialState: ReminderDeliveryState) {
        var state by mutableStateOf(initialState)
        var language by mutableStateOf(Language.EN)
        var enabled by mutableStateOf(true)
        var alarmClockEnabled by mutableStateOf(false)
        var openFailed by mutableStateOf(false)
        var dismissals = 0
        val opened = mutableListOf<ReminderSettingsTarget>()
    }
}
