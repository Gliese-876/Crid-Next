package cn.crid.next.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.crid.next.MainActivity
import cn.crid.next.core.Language
import cn.crid.next.core.ThemeMode
import cn.crid.next.data.AppRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun glassCanBeTurnedOffWithoutChangingTheSelectedColorTheme() {
        val repository = AppRepository.get(compose.activity)
        val saved = repository.state.value.settings
        try {
            runBlocking {
                repository.update { it.copy(settings = saved.copy(language = Language.EN, theme = ThemeMode.LIGHT,
                    liquidGlassEnabled = false, hapticFeedbackEnabled = false)) }
            }
            compose.onNodeWithTag("nav_settings").performClick()
            val glass = compose.onNodeWithTag("liquid_glass_toggle")
            glass.assertIsOff().performClick()
            compose.waitUntil(5_000) {
                repository.state.value.settings.let { it.liquidGlassEnabled && it.theme == ThemeMode.LIGHT }
            }

            glass.assertIsOn()
            compose.onNodeWithText("Dark").performClick()
            compose.waitUntil(5_000) {
                repository.state.value.settings.let { it.liquidGlassEnabled && it.theme == ThemeMode.DARK }
            }

            glass.assertIsOn().performClick()
            compose.waitUntil(5_000) {
                repository.state.value.settings.let { !it.liquidGlassEnabled && it.theme == ThemeMode.DARK }
            }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("liquid_glass_toggle").assertIsOff()
        } finally {
            runBlocking { repository.update { it.copy(settings = saved) } }
        }
    }
}
