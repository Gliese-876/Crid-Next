package cn.crid.next

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.AppState
import cn.crid.next.core.Language
import cn.crid.next.core.Settings
import cn.crid.next.core.ThemeMode
import cn.crid.next.data.AppRepository
import cn.crid.next.platform.CourseWidgets
import cn.crid.next.platform.WidgetConfigurationActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WidgetConfigurationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(CourseWidgets.PREFS, Context.MODE_PRIVATE)
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    private val oldModes = mutableMapOf<Int, String?>()
    private val widgetId = 900016
    private val otherWidgetId = 900017

    @Before fun prepare() {
        repository = AppRepository.get(context)
        previous = repository.state.value
        listOf(widgetId, otherWidgetId).forEach { oldModes[it] = preferences.getString("theme:$it", null) }
        runBlocking { repository.update { AppState(settings = Settings(language = Language.EN, theme = ThemeMode.LIGHT)) } }
    }

    @After fun restore() {
        preferences.edit().apply {
            oldModes.forEach { (id, mode) -> if (mode == null) remove("theme:$id") else putString("theme:$id", mode) }
        }.commit()
        runBlocking { repository.update { previous } }
    }

    @Test fun selectionSurvivesRecreationAndOnlySaveChangesThisWidget() {
        preferences.edit().putString("theme:$widgetId", "LIGHT").putString("theme:$otherWidgetId", "APP").commit()
        ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent()).use { scenario ->
            compose.onNodeWithTag("widget_theme_LIGHT").assertIsSelected()
            choose("DARK")
            assertEquals("LIGHT", preferences.getString("theme:$widgetId", null))
            scenario.recreate()
            compose.onNodeWithTag("widget_theme_DARK").assertIsSelected()
            compose.onNodeWithTag("widget_theme_LIGHT").assertIsNotSelected()
            press("widget_configuration_save")
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(widgetId, requireNotNull(result.resultData).getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            assertEquals("DARK", preferences.getString("theme:$widgetId", null))
            assertEquals("APP", preferences.getString("theme:$otherWidgetId", null))
        }
        ActivityScenario.launch<WidgetConfigurationActivity>(intent()).use {
            compose.onNodeWithTag("widget_theme_DARK").assertIsSelected()
        }
    }

    @Test fun cancelAndSystemBackKeepTheStoredModeAndReturnTheWidgetId() {
        preferences.edit().putString("theme:$widgetId", "APP").commit()
        listOf(false, true).forEach { useBack ->
            ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(intent()).use { scenario ->
                compose.onNodeWithTag("widget_theme_APP").assertIsSelected()
                choose(if (useBack) "DARK" else "LIGHT")
                if (useBack) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                else press("widget_configuration_cancel")
                val result = scenario.result
                assertEquals(Activity.RESULT_CANCELED, result.resultCode)
                assertEquals(widgetId, requireNotNull(result.resultData).getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                assertEquals("APP", preferences.getString("theme:$widgetId", null))
            }
        }
    }

    @Test fun invalidWidgetIdFinishesWithoutCreatingOrSavingAConfiguration() {
        preferences.edit().putString("theme:$widgetId", "LIGHT").commit()
        ActivityScenario.launchActivityForResult<WidgetConfigurationActivity>(
            Intent(context, WidgetConfigurationActivity::class.java)
        ).use { scenario ->
            val result = scenario.result
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
            assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID,
                requireNotNull(result.resultData).getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            assertEquals("LIGHT", preferences.getString("theme:$widgetId", null))
        }
    }

    private fun intent() = Intent(context, WidgetConfigurationActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun choose(mode: String) {
        press("widget_theme_$mode")
        compose.onNodeWithTag("widget_theme_$mode").assertIsSelected()
    }

    private fun press(tag: String) {
        compose.onNodeWithTag("widget_configuration_scroll").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
}
