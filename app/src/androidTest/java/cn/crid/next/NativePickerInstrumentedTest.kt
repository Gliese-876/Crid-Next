package cn.crid.next

import android.view.View
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.NumberPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.AppState
import cn.crid.next.core.Language
import cn.crid.next.core.Semester
import cn.crid.next.data.Defaults
import cn.crid.next.ui.NativeDatePicker
import cn.crid.next.ui.NativeMonthPicker
import cn.crid.next.ui.NativeTimePicker
import cn.crid.next.ui.PlansScreen
import cn.crid.next.ui.UiText
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Real Material calendar/clock gestures and platform spinner accessibility, without app-data writes. */
class NativePickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val text = UiText(Language.EN)

    @Test fun calendarDisablesOutsideBoundsAndReturnsSelectedNaturalDate() {
        var received: LocalDate? = null
        compose.setContent {
            MaterialTheme {
                NativeDatePicker("Start date", LocalDate.of(2026, 9, 7), text, {}, { received = it },
                    min = LocalDate.of(2026, 9, 7), max = LocalDate.of(2026, 9, 12))
            }
        }
        compose.onNodeWithTag("native_date_picker").assertIsDisplayed()
        compose.onNode(hasText("September 6, 2026", substring = true) and hasAnyAncestor(hasTestTag("native_date_picker"))).assertIsNotEnabled()
        compose.onNode(hasText("September 8, 2026", substring = true) and hasAnyAncestor(hasTestTag("native_date_picker"))).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil { received != null }
        assertEquals(LocalDate.of(2026, 9, 8), received)
    }

    @Test fun nativeClockChangesMinutesWithoutDateOrTimeTextInput() {
        var received: LocalTime? = null
        compose.setContent {
            MaterialTheme { NativeTimePicker("Period 1 · Start", LocalTime.of(8, 5), text, {}, { received = it }) }
        }
        compose.onNodeWithTag("native_time_picker").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select minutes", substring = true).performClick()
        try {
            compose.onNode(SemanticsMatcher("ContentDescription equals 30 minutes after removing bidi controls") { node ->
                node.config.contains(SemanticsProperties.ContentDescription) &&
                    node.config[SemanticsProperties.ContentDescription].any { withoutBidiControls(it) == "30 minutes" }
            }).performClick()
        } catch (failure: AssertionError) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val configuration = context.resources.configuration
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val tree = roots.fetchSemanticsNodes().indices.joinToString("\n") { index ->
                "Root $index\n${roots[index].printToString(maxDepth = Int.MAX_VALUE)}"
            }
            throw AssertionError("Native clock minute selection: SDK=${android.os.Build.VERSION.SDK_INT}, " +
                "screen=${configuration.screenWidthDp}x${configuration.screenHeightDp}dp, " +
                "fontScale=${configuration.fontScale}, locales=${configuration.locales.toLanguageTags()}, " +
                "is24Hour=${android.text.format.DateFormat.is24HourFormat(context)}\n$tree", failure)
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil { received != null }
        assertEquals(LocalTime.of(8, 30), received)
    }

    // Some Android resource versions wrap localized descriptions in directional formatting controls.
    // Preserve every visible character and compare exactly after removing only those controls.
    private fun withoutBidiControls(value: String): String = value.filterNot { character ->
        character.code == 0x061C || character.code in 0x200E..0x200F ||
            character.code in 0x202A..0x202E || character.code in 0x2066..0x2069
    }

    @Test fun confirmationKeepsClickedDateDuringReturnAnimation() {
        var received: LocalDate? = null
        compose.setContent {
            MaterialTheme {
                NativeDatePicker("Start date", LocalDate.of(2026, 9, 7), text, {}, { received = it })
            }
        }
        compose.onNodeWithTag("native_date_picker").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("OK").performClick()
            compose.mainClock.advanceTimeByFrame()
            assertNull(received)
            // Semantics actions model a late accessibility selection while the dialog travels back.
            compose.onNode(hasText("September 8, 2026", substring = true) and hasAnyAncestor(hasTestTag("native_date_picker"))).performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals(LocalDate.of(2026, 9, 7), received)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun inspectingLegacyPartialWeekDatesCannotSaveOrAlterThemWithoutCorrection() {
        val original = Semester(name = "Partial week term", startDate = "2026-09-07", endDate = "2026-09-19", weeks = 2, periods = Defaults.periods)
        var current by mutableStateOf(AppState(semesters = listOf(original), selectedSemesterId = original.id))
        compose.setContent { MaterialTheme { PlansScreen(current, text, { current = it }, {}) } }
        compose.onNodeWithContentDescription("Edit term").performClick()

        // Merely inspecting a calculated value must not make it one of the edited fields.
        compose.onNodeWithTag("semester_weeks").performScrollTo().performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("semester_start").performScrollTo().performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("semester_end").performScrollTo().performClick()
        compose.onNodeWithTag("native_picker_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("native_picker_cancel").performClick()
        compose.onNodeWithTag("semester_save").assertIsNotEnabled()
        compose.onNodeWithTag("semester_cancel").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("semester_start").fetchSemanticsNodes().isEmpty() }
        assertEquals(original.startDate, current.semesters.single().startDate)
        assertEquals(original.endDate, current.semesters.single().endDate)
        assertEquals(original.weeks, current.semesters.single().weeks)
    }

    @Test fun calendarChromeUsesAppLanguageInsteadOfSystemLocale() {
        val systemLanguage = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0].language
        val appText = UiText(if (systemLanguage == "zh") Language.EN else Language.ZH_CN)
        compose.setContent {
            MaterialTheme { NativeDatePicker(appText.t("起始日", "Start date"), LocalDate.of(2026, 9, 7), appText, {}, {}) }
        }
        compose.onNodeWithText(appText.t("2026年9月7日", "Sep 7, 2026")).assertIsDisplayed()
        compose.onNodeWithText(appText.t("2026年9月", "September 2026")).assertIsDisplayed()
        compose.onNodeWithContentDescription(appText.t("星期一", "Monday")).assertIsDisplayed()
        compose.onNodeWithText(appText.t("确定", "OK")).assertIsDisplayed()
        compose.onNodeWithText(appText.t("取消", "Cancel")).assertIsDisplayed()
    }

    @Test fun nativeMonthWheelsKeepSelectionInBoundsWhenCrossingYear() {
        var received: YearMonth? = null
        compose.setContent {
            MaterialTheme {
                NativeMonthPicker("End month", YearMonth.of(2026, 12), YearMonth.of(2026, 12), YearMonth.of(2027, 2),
                    text, {}, { received = it })
            }
        }
        compose.onNodeWithTag("native_month_picker").assertIsDisplayed()
        onView(withContentDescription("Year")).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "Advance the native year wheel with its accessibility action"
            override fun perform(uiController: UiController, view: View) {
                val wheel = view as NumberPicker
                val expected = wheel.value + 1
                check(wheel.accessibilityNodeProvider?.performAction(View.NO_ID, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) == true)
                val deadline = SystemClock.uptimeMillis() + 2_000
                while (wheel.value != expected && SystemClock.uptimeMillis() < deadline) uiController.loopMainThreadForAtLeast(16)
                check(wheel.value == expected)
                uiController.loopMainThreadUntilIdle()
            }
        })
        compose.waitForIdle()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil { received != null }
        // December cannot carry into the partial next year; February is the last allowed month.
        assertEquals(YearMonth.of(2027, 2), received)
    }
}
