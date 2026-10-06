package cn.crid.next.ui

import android.content.pm.ActivityInfo
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import cn.crid.next.MainActivity
import cn.crid.next.core.AppState
import cn.crid.next.core.HolidayCalendar
import cn.crid.next.core.Language
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WideColorDisplayInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun appearanceSettingsHaveNoRemovedHdrControlOrDescription() {
        compose.activity.setContent {
            MaterialTheme(colorScheme = cridColorScheme(false)) {
                SettingsScreen(AppState(), HolidayCalendar(), UiText(Language.EN), {})
            }
        }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithTag("hdr_controls").assertDoesNotExist()
        compose.onAllNodes(hasText("HDR", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test fun windowLeaseSwitchesP3WithSupportAndForegroundThenRestoresBaseline() {
        compose.activity.setContent { MaterialTheme { Text("Window policy") } }
        compose.waitForIdle()
        compose.runOnIdle {
            val window = compose.activity.window
            val oldMode = window.colorMode
            val oldBrightness = window.attributes.screenBrightness
            val leases = mutableListOf<WideColorWindowLease>()
            try {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                val first = WideColorWindowLease(window).also { leases += it }
                val p3 = WideColorDisplayState(supported = true)
                first.apply(p3)
                assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, window.colorMode)
                first.apply(p3.copy(foreground = false))
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
                first.apply(p3)
                first.apply(p3.copy(supported = false))
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
                first.apply(p3)
                val second = WideColorWindowLease(window).also { leases += it }
                second.apply(p3)
                first.restore()
                assertEquals(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, window.colorMode)
                second.restore()
                assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
                second.restore()
                second.apply(p3)
                assertEquals("Released consumers cannot mutate the window", ActivityInfo.COLOR_MODE_DEFAULT, window.colorMode)
                assertEquals(oldBrightness, window.attributes.screenBrightness, 0f)
            } finally {
                leases.asReversed().forEach { it.restore() }
                window.colorMode = oldMode
            }
        }
    }

    @Test fun displayProviderSelectsAutomaticP3FromTheRealRenderingCapabilities() {
        var observed: WideColorDisplayState? = null
        compose.activity.setContent {
            WideColorDisplayProvider {
                val state = currentWindowColorState()
                SideEffect { observed = state }
                MaterialTheme { Text("Display capability") }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val display = view.display
            val supported = display?.isValid == true && view.isHardwareAccelerated &&
                view.resources.configuration.isScreenWideColorGamut && display.isWideColorGamut
            assertNotNull(observed)
            assertEquals(supported, observed!!.supported)
            assertTrue(observed!!.foreground)
            assertEquals(display?.displayId, observed!!.displayId)
            assertEquals(if (supported) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT,
                compose.activity.window.colorMode)
        }
    }

    @Test fun dialogUsesItsOwnDisplayCapabilitiesAndRestoresPolicyAfterDismissal() {
        var showing by mutableStateOf(true)
        var foreground by mutableStateOf(true)
        var dialogWindow: Window? = null
        fun supportsP3(): Boolean {
            val view = dialogWindow!!.decorView
            val display = view.display
            return display?.isValid == true && view.isHardwareAccelerated &&
                view.resources.configuration.isScreenWideColorGamut && display.isWideColorGamut
        }
        compose.activity.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWideColorDisplay provides WideColorDisplayState(
                    supported = true, foreground = foreground)) {
                    if (showing) Dialog(onDismissRequest = { showing = false }) {
                        val view = LocalView.current
                        SideEffect {
                            dialogWindow = generateSequence(view.parent) { it.parent }
                                .filterIsInstance<DialogWindowProvider>().first().window
                        }
                        MatchDialogSystemBars()
                        MatchDialogSystemBars()
                        Surface { Text("Wide color window") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(if (supportsP3()) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT,
                dialogWindow!!.colorMode)
            foreground = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, dialogWindow!!.colorMode)
            foreground = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(if (supportsP3()) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT,
                dialogWindow!!.colorMode)
            showing = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, dialogWindow!!.colorMode) }
    }
}
