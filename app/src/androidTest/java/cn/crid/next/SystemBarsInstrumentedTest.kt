package cn.crid.next

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import cn.crid.next.platform.WidgetConfigurationActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run with both gesture and three-button navigation; no system bars are deliberately hidden. */
class SystemBarsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
    }

    @After fun restore() { runBlocking { repository.update { previous } } }

    @Test fun fullScreenAndFloatingDialogsUseTheirOwnThemeWithoutNavigationScrim() {
        listOf(ThemeMode.LIGHT,ThemeMode.DARK).forEach { theme ->
            runBlocking { repository.update { AppState(settings=Settings(language=Language.EN,theme=theme)) } }
            compose.waitForIdle()
            assertFocusedWindow(theme)

            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_entry"))
            compose.onNodeWithTag("about_entry").performClick()
            compose.onNodeWithTag("about_screen").assertIsDisplayed()
            assertFocusedWindow(theme)
            compose.onNodeWithTag("about_content").performScrollToNode(hasTestTag("about_license"))
            compose.onNodeWithTag("about_license").performClick()
            compose.onNodeWithTag("about_document").assertIsDisplayed()
            assertFocusedWindow(theme)
            compose.onNodeWithTag("about_document_back").performClick()
            compose.onNodeWithTag("about_back").performClick()

            compose.onNodeWithTag("nav_plans").performClick()
            compose.onNodeWithText("New term").performClick()
            compose.onNode(hasText("Save") and hasAnyAncestor(isDialog()),useUnmergedTree=true).assertIsDisplayed()
            assertFocusedWindow(theme,dimmed=true)
            compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog()),useUnmergedTree=true).performClick()

            compose.onNodeWithTag("action_import").performClick()
            compose.onNodeWithContentDescription("Close import").assertIsDisplayed()
            assertFocusedWindow(theme)
            compose.onNodeWithContentDescription("Close import").performClick()
        }
    }

    @Test fun nativeBottomSheetKeepsVisibleTransparentSystemBarsInBothThemes() {
        listOf(ThemeMode.LIGHT,ThemeMode.DARK).forEach { theme ->
            val semester=Defaults.semester()
            val plan=Plan(semesterId=semester.id,name="Bar test",courses=listOf(
                Course(name="Awaiting time",lessons=listOf(Lesson(weeks=listOf(1,2),weekday=0,unscheduled=true)))
            ))
            runBlocking { repository.update { AppState(listOf(semester),listOf(plan),semester.id,plan.id,Settings(language=Language.EN,theme=theme)) } }
            compose.onNodeWithTag("nav_today").performClick()
            compose.onNodeWithTag("pending_lessons").performClick()
            compose.onNodeWithTag("pending_details").assertIsDisplayed()
            assertFocusedWindow(theme)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
    }

    @Test fun widgetConfigurationFollowsApplicationThemeAndKeepsControlsInsideInsets() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        listOf(ThemeMode.LIGHT,ThemeMode.DARK).forEach { theme ->
            runBlocking { repository.update { AppState(settings=Settings(language=Language.EN,theme=theme)) } }
            val intent=Intent(context,WidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,900001)
            ActivityScenario.launch<WidgetConfigurationActivity>(intent).use { scenario ->
                var ready=false
                val deadline=SystemClock.uptimeMillis()+5_000
                while(!ready && SystemClock.uptimeMillis()<deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val decor=it.window.decorView
                        ready=decor.hasWindowFocus() && decor.height>0 && decor.rootWindowInsets!=null
                    }
                    if(!ready)SystemClock.sleep(16)
                }
                assertTrue("Widget configuration must be laid out and receive system insets",ready)
                compose.waitForIdle()
                var safe = android.graphics.Insets.NONE
                var windowWidth = 0
                var windowHeight = 0
                scenario.onActivity { activity ->
                    assertWindow(activity.window,theme)
                    val decor=activity.window.decorView
                    safe=decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    windowWidth=decor.width
                    windowHeight=decor.height
                }
                val surface=compose.onNodeWithTag("widget_configuration").fetchSemanticsNode().boundsInRoot
                assertEquals("The background extends behind both system bars",windowHeight.toFloat(),surface.height,1f)
                assertEquals(windowWidth.toFloat(),surface.width,1f)
                listOf("widget_configuration_title","widget_theme_APP","widget_theme_LIGHT","widget_theme_DARK",
                    "widget_configuration_cancel","widget_configuration_save").forEach { tag ->
                    compose.onNodeWithTag("widget_configuration_scroll").performScrollToNode(hasTestTag(tag))
                    val bounds=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("$tag stays below the status bar and cutout",bounds.top>=surface.top+safe.top-1f)
                    assertTrue("$tag stays above navigation controls",bounds.bottom<=surface.bottom-safe.bottom+1f)
                    assertTrue("$tag respects the left display cutout",bounds.left>=surface.left+safe.left-1f)
                    assertTrue("$tag respects the right display cutout",bounds.right<=surface.right-safe.right+1f)
                }
            }
        }
    }

    private fun assertFocusedWindow(theme:ThemeMode,dimmed:Boolean=false) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val root=WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            val window=if(root===compose.activity.window.decorView)compose.activity.window else requireNotNull(findDialogWindow(root))
            assertWindow(window,theme,dimmed)
        }
    }

    private fun findDialogWindow(view:View):Window? {
        if(view is DialogWindowProvider)return view.window
        if(view is ViewGroup)for(index in 0 until view.childCount)findDialogWindow(view.getChildAt(index))?.let {return it}
        return null
    }

    private fun assertWindow(window:Window,theme:ThemeMode,dimmed:Boolean=false) {
        assertFalse("Three-button navigation must not add a contrast scrim",window.isNavigationBarContrastEnforced)
        val appearance=window.decorView.windowInsetsController!!.systemBarsAppearance
        val base=if(theme==ThemeMode.LIGHT)Color.rgb(250,249,254)else Color.rgb(18,18,25)
        val background=if(dimmed)ColorUtils.compositeColors(Color.argb((255*window.attributes.dimAmount).toInt(),0,0,0),base)else base
        val expectedLight=ColorUtils.calculateContrast(Color.BLACK,background)>ColorUtils.calculateContrast(Color.WHITE,background)
        assertEquals("Status icon contrast follows this window",expectedLight,appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
        assertEquals("Navigation icon contrast follows this window",expectedLight,appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
        val insets=window.decorView.rootWindowInsets
        if(insets!=null) {
            assertTrue("Status bar stays visible",insets.isVisible(WindowInsets.Type.statusBars()))
            assertTrue("Navigation controls stay visible",insets.isVisible(WindowInsets.Type.navigationBars()))
        }
    }
}
