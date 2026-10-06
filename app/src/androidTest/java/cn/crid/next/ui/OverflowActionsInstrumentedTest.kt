package cn.crid.next.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The next window must not cover the overflow menu's return animation. */
class OverflowActionsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        val semester = Defaults.semester()
        val plan = Plan(semesterId = semester.id, name = "Menu transitions", courses = emptyList())
        runBlocking {
            repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
                Settings(language = Language.EN, holidaysEnabled = false)) }
        }
        compose.onNodeWithTag("nav_today").performClick()
        compose.waitForIdle()
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        runBlocking { repository.update { previous } }
    }

    @Test fun importWaitsForTheMenuToFinishClosing() {
        selectAfterClose("action_import")
        compose.onNodeWithTag("import_surface").assertIsDisplayed()
    }

    @Test fun exportWaitsForTheMenuToFinishClosing() {
        selectAfterClose("action_export")
        compose.onNodeWithContentDescription("Close export").assertIsDisplayed()
    }

    @Test fun courseManagerWaitsForTheMenuToFinishClosing() {
        selectAfterClose("action_manage_courses")
        compose.onNodeWithTag("course_manager").assertIsDisplayed()
    }

    private fun selectAfterClose(action: String) {
        compose.onNodeWithTag("action_more").performClick()
        compose.waitForIdle()
        val opened = menuItemScreenBounds()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(action).performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("more_menu_today").assertExists()
        val closing = menuItemScreenBounds()
        assertTrue("The menu must still be visible while retracting", closing.width > opened.width * .5f)
        assertTrue("Closing visibly returns toward the anchor before opening the destination", closing.width < opened.width - 1f)
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("more_menu_today").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        // The destination owns a new Android window. Its first layout establishes native
        // sheet anchors after the popup has gone; let that independent entrance finish.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun menuItemScreenBounds(): Rect {
        // The Surface's own layout bounds precede its graphics layer. Measure a menu item
        // inside that layer so the assertion observes the visible shrinking surface.
        val coordinates = compose.onNodeWithTag("action_manage_courses", useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo.coordinates
        return Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(
            Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }
}
