package cn.crid.next.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
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
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Exercises the actual Plans page, including both selection levels and rendered card fills. */
class PlansAppearanceInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState
    private val first = Defaults.semester().copy(id = "appearance-autumn", name = "2026 秋季学期")
    private val second = Defaults.semester().copy(id = "appearance-spring", name = "2027 春季学期",
        startDate = "2027-02-22", endDate = "2027-06-27", weeks = 18)
    private val firstPlan = Plan(id = "appearance-main", semesterId = first.id, name = "本学期课表",
        courses = listOf(Course(name = "数学分析", lessons = listOf(Lesson(weeks = listOf(1, 2),
            startPeriod = 1, endPeriod = 2, teacher = "张老师", location = "木铎楼 A101")))))
    private val secondPlan = firstPlan.copy(id = "appearance-alternative", name = "备用安排")

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        runBlocking {
            repository.update { AppState(listOf(first, second), listOf(firstPlan, secondPlan), first.id, firstPlan.id,
                Settings(language = Language.ZH_CN, holidaysEnabled = false)) }
        }
        compose.onNodeWithTag("nav_plans").performClick()
        compose.waitForIdle()
    }

    @After fun restore() { runBlocking { repository.update { previous } } }

    @Test fun lightAndDarkCardsKeepBothSelectionLevelsReadableAndVisuallyDistinct() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            runBlocking { repository.update { it.copy(settings = it.settings.copy(theme = theme)) } }
            compose.waitForIdle()
            val dark = theme == ThemeMode.DARK
            val scheme = cridColorScheme(dark)
            val termSelected = fillOf("term_card_${first.id}")
            selection("term_card_${first.id}").assertIsSelected()
            val termIdle = fillOf("term_card_${second.id}")
            selection("term_card_${second.id}").assertIsNotSelected()
            capture("plans-terms-${if (dark) "dark" else "light"}")
            val planSelected = fillOf("plan_card_${firstPlan.id}")
            selection("plan_card_${firstPlan.id}").assertIsSelected()
            val planIdle = fillOf("plan_card_${secondPlan.id}")
            selection("plan_card_${secondPlan.id}").assertIsNotSelected()
            capture("plans-schedules-${if (dark) "dark" else "light"}")

            assertNotEquals("The selected term remains distinguishable from the other term", termSelected, termIdle)
            assertNotEquals("The selected plan remains distinguishable from the other plan", planSelected, planIdle)
            assertEquals("Both kinds of selected panel use the same visual treatment", termSelected, planSelected)
            assertEquals("Both kinds of unselected panel use the same visual treatment", termIdle, planIdle)
            for (fill in listOf(termSelected, termIdle, planSelected, planIdle)) {
                assertEquals("Card fills remain opaque", 255, fill ushr 24)
                assertTrue("Panel titles retain normal-text contrast", contrast(fill, scheme.onSurface.toArgb()) >= 4.5)
                assertTrue("Panel metadata retains normal-text contrast", contrast(fill, scheme.onSurfaceVariant.toArgb()) >= 4.5)
            }
            compose.onNodeWithTag("nav_plans").assertIsDisplayed()
        }
    }

    @Test fun changingPlansAndTermsKeepsRadioSelectionAndStoredTargetsInSync() {
        card("plan_card_${secondPlan.id}")
        selection("plan_card_${secondPlan.id}").performClick()
        compose.waitUntil(5_000) { repository.state.value.selectedPlanId == secondPlan.id }
        selection("plan_card_${secondPlan.id}").assertIsSelected()
        card("plan_card_${firstPlan.id}")
        selection("plan_card_${firstPlan.id}").assertIsNotSelected()
        card("term_card_${second.id}")
        selection("term_card_${second.id}").performClick()
        compose.waitUntil(5_000) { repository.state.value.selectedSemesterId == second.id }
        selection("term_card_${second.id}").assertIsSelected()
        compose.runOnIdle { assertNull(repository.state.value.selectedPlanId) }
        card("term_card_${first.id}")
        selection("term_card_${first.id}").assertIsNotSelected().performClick()
        compose.waitUntil(5_000) { repository.state.value.selectedPlanId == firstPlan.id }
        selection("term_card_${first.id}").assertIsSelected()
        card("plan_card_${firstPlan.id}")
        selection("plan_card_${firstPlan.id}").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(first, second), repository.state.value.semesters)
            assertEquals(listOf(firstPlan, secondPlan), repository.state.value.plans)
        }
    }

    private fun card(tag: String): SemanticsNodeInteraction {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex) and
            hasAnyAncestor(hasTestTag("page_plans"))).performScrollToNode(hasTestTag(tag))
        return compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("page_plans")), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun selection(tag: String) = compose.onNode(isSelectable() and hasAnyAncestor(hasTestTag(tag)),
        useUnmergedTree = true)

    private fun fillOf(tag: String): Int {
        val bitmap = card(tag).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        try {
            // Top-centre is inside the rounded surface and above the 16 dp content padding.
            val y = (compose.activity.resources.displayMetrics.density * 6f).toInt().coerceAtMost(bitmap.height - 1)
            return bitmap.getPixel(bitmap.width / 2, y)
        } finally { bitmap.recycle() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(compose.activity.getExternalFilesDir(null), "qa-v1.11/plans").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(directory, "native-$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun linear(component: Int): Double {
                val value = component / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return linear(color shr 16 and 255) * .2126 + linear(color shr 8 and 255) * .7152 + linear(color and 255) * .0722
        }
        val one = luminance(first)
        val two = luminance(second)
        return (max(one, two) + .05) / (min(one, two) + .05)
    }
}
