package cn.crid.next.ui

import cn.crid.next.core.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class StateChangesTest {
    private val term = Semester(id = "term", name = "Autumn", startDate = "2026-09-07", endDate = "2027-01-10", weeks = 18, periods = listOf(Period(1, "08:00", "08:45")))
    private val plan = Plan(id = "plan", semesterId = term.id, name = "Original", courses = emptyList())
    private val base = AppState(listOf(term), listOf(plan), term.id, plan.id)
    @Test fun reminderPreferenceAndConcurrentThemeChangeBothSurvive() {
        val live = base.copy(settings = base.settings.copy(theme = ThemeMode.DARK))
        val requested = base.copy(settings = base.settings.copy(remindersEnabled = true))
        val result = applyStateChanges(base, requested, live)
        assertTrue(result.settings.remindersEnabled)
        assertEquals(ThemeMode.DARK, result.settings.theme)
        val languageUpdate = base.copy(settings = base.settings.copy(language = Language.EN))
        assertTrue(applyStateChanges(base, languageUpdate, result).settings.remindersEnabled)
    }
    @Test fun alarmClockChoiceSurvivesMergeAndUnrelatedConcurrentSettings() {
        val live = base.copy(settings = base.settings.copy(theme = ThemeMode.DARK, reminderMinutes = 10,
            language = Language.EN, remindersEnabled = true))
        val requested = base.copy(settings = base.settings.copy(reminderAlarmClock = true))
        val enabled = applyStateChanges(base, requested, live)
        assertEquals(live.settings.copy(reminderAlarmClock = true), enabled.settings)
        val otherAction = base.copy(settings = base.settings.copy(showOutOfWeek = false))
        assertEquals(enabled.settings.copy(showOutOfWeek = false),
            applyStateChanges(base, otherAction, enabled).settings)
    }
    @Test fun alarmClockCanBeDisabledWithoutLosingAConcurrentLeadTimeChange() {
        val enabled = base.copy(settings = base.settings.copy(remindersEnabled = true, reminderAlarmClock = true))
        val live = enabled.copy(settings = enabled.settings.copy(reminderMinutes = 30))
        val requested = enabled.copy(settings = enabled.settings.copy(reminderAlarmClock = false))
        assertEquals(live.settings.copy(reminderAlarmClock = false), applyStateChanges(enabled, requested, live).settings)
    }
    @Test fun settingsActionDoesNotOverwriteConcurrentImport() {
        val added = Plan(semesterId = term.id, name = "Imported", courses = emptyList())
        val live = base.copy(plans = base.plans + added, selectedPlanId = added.id)
        val next = base.copy(settings = base.settings.copy(theme = ThemeMode.DARK))
        val result = applyStateChanges(base, next, live)
        assertEquals(live.plans, result.plans)
        assertEquals(added.id, result.selectedPlanId)
        assertEquals(ThemeMode.DARK, result.settings.theme)
    }
    @Test fun successiveDifferentSettingsPreserveBothChanges() {
        val live = base.copy(settings = base.settings.copy(theme = ThemeMode.DARK))
        val next = base.copy(settings = base.settings.copy(language = Language.EN))
        val result = applyStateChanges(base, next, live)
        assertEquals(ThemeMode.DARK, result.settings.theme)
        assertEquals(Language.EN, result.settings.language)
    }
    @Test fun renamePreservesConcurrentlyMergedCourses() {
        val course = Course(name = "Math", lessons = listOf(Lesson(weeks = listOf(1), startPeriod = 1, endPeriod = 1)))
        val live = base.copy(plans = listOf(plan.copy(courses = listOf(course))))
        val next = base.copy(plans = listOf(plan.copy(name = "Renamed")))
        val result = applyStateChanges(base, next, live)
        assertEquals("Renamed", result.plan!!.name)
        assertEquals(listOf(course), result.plan!!.courses)
    }
    @Test fun deletingTermCannotLeaveConcurrentOrphanPlan() {
        val live = base.copy(plans = base.plans + Plan(semesterId = term.id, name = "Concurrent", courses = emptyList()))
        val result = applyStateChanges(base, AppState(), live)
        assertTrue(result.plans.isEmpty()); assertTrue(result.semesters.isEmpty())
        assertNull(result.selectedPlanId); assertNull(result.selectedSemesterId)
    }
    @Test fun unsupportedSystemLocaleDoesNotOverrideExplicitLanguage() {
        val unsupported = Locale.FRENCH
        assertEquals("Settings", UiText(Language.SYSTEM, unsupported).t("设置", "Settings"))
        assertEquals("设置", UiText(Language.ZH_CN, unsupported).t("设置", "Settings"))
        assertEquals("刪除課程", UiText(Language.ZH_TW, unsupported).t("删除课程", "Delete course"))
        assertFalse(UiText(Language.SYSTEM, unsupported).systemSupported)
    }
}
