package cn.crid.next

import android.animation.ValueAnimator
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.core.*
import cn.crid.next.data.AppRepository
import cn.crid.next.data.Defaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** The video shows a nearly full-height sheet oscillating long after the upward fling ends. */
class BottomSheetMotionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository
    private lateinit var previous: AppState

    @Before fun prepare() {
        repository = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = repository.state.value
        val first = ScheduleEngine.weekStart(LocalDate.now())
        val semester = Defaults.semester().copy(startDate = first.toString(), endDate = first.plusWeeks(18).minusDays(1).toString())
        val course = Course(name = "现代教育技术", lessons = listOf(
            Lesson(weeks = listOf(11, 12, 13), weekday = 0, unscheduled = true, teacher = "张老师", note = "11-13周 []"),
            Lesson(weeks = listOf(16), weekday = 0, unscheduled = true, teacher = "张老师", note = "16周 []"),
        ))
        val plan = Plan(semesterId = semester.id, name = "Sheet motion", courses = listOf(course))
        runBlocking { repository.update { AppState(listOf(semester), listOf(plan), semester.id, plan.id,
            Settings(language = Language.ZH_CN, theme = ThemeMode.LIGHT, holidaysEnabled = false)) } }
        compose.onNodeWithTag("nav_today").performClick()
        compose.waitForIdle()
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        runBlocking { repository.update { previous } }
    }

    @Test fun nearFullHeightPendingSheetSettlesAfterFastUpwardFling() {
        val previousSize = Regex("""Override size:\s*(\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1)
        val previousDensity = Regex("""Override density:\s*(\d+)""").find(shell("wm density"))?.groupValues?.get(1)
        val previousAnimatorScale = shell("settings get global animator_duration_scale").trim()
        require(previousAnimatorScale == "null" || previousAnimatorScale.toFloatOrNull() != null)
        val measurements = mutableListOf<String>()
        try {
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(5_000) { ValueAnimator.getDurationScale() == 1f }
            assertEquals("Real animation must be enabled for this regression", 1f, ValueAnimator.getDurationScale(), 0f)
            shell("wm density 320")
            // The content straddles the screen-height boundary as available height changes.
            for (height in listOf(1440, 1520, 1600, 1680)) {
                shell("wm size 720x$height")
                compose.waitForIdle()
                compose.onNodeWithTag("pending_lessons").performClick()
                compose.onNodeWithTag("pending_details").assertIsDisplayed()
                compose.waitForIdle()
                compose.mainClock.autoAdvance = false
                compose.onNode(isDialog()).performTouchInput {
                    swipe(Offset(width * .72f, this.height * .84f), Offset(width * .72f, this.height * .22f), durationMillis = 96)
                }
                compose.mainClock.advanceTimeBy(1_500)
                val handlePositions = mutableListOf<Float>()
                val contentPositions = mutableListOf<Float>()
                repeat(90) {
                    compose.mainClock.advanceTimeByFrame()
                    handlePositions += handle().fetchSemanticsNode().positionInRoot.y
                    contentPositions += compose.onNodeWithTag("pending_details").fetchSemanticsNode().positionInRoot.y
                }
                val handleMovement = handlePositions.max() - handlePositions.min()
                val contentMovement = contentPositions.max() - contentPositions.min()
                measurements += "$height: handleRange=$handleMovement, contentRange=$contentMovement"
                assertTrue("A released sheet must settle instead of repeatedly bouncing: $measurements", handleMovement <= 2f && contentMovement <= 2f)
                compose.mainClock.autoAdvance = true
                handle().performSemanticsAction(SemanticsActions.Dismiss) { it() }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("pending_details").fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            runCatching {
                if (compose.onAllNodesWithTag("pending_details").fetchSemanticsNodes().isNotEmpty()) {
                    handle().performSemanticsAction(SemanticsActions.Dismiss) { it() }
                    compose.mainClock.advanceTimeBy(1_500)
                }
            }
            compose.mainClock.autoAdvance = true
            shell("wm size ${previousSize ?: "reset"}")
            shell("wm density ${previousDensity ?: "reset"}")
            if (previousAnimatorScale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $previousAnimatorScale")
            compose.waitForIdle()
        }
    }

    @Test fun allCourseSheetsKeepScrollingAndNativeDragToDismiss() {
        val courses = (0..9).map { index ->
            Course(name = "Sheet class $index", lessons = listOf(Lesson(weeks = (1..18).toList(), weekday = 1,
                startPeriod = 1, endPeriod = 2, teacher = "Teacher $index", location = "Room $index")) +
                (1..8).map { week -> Lesson(weeks = listOf(week), weekday = 0, unscheduled = true,
                    teacher = "Teacher $week", note = "Independent arrangement $week") })
        }
        runBlocking { repository.update { state -> state.copy(plans = listOf(state.plan!!.copy(courses = CourseColors.assign(courses)))) } }
        val previousAnimatorScale = shell("settings get global animator_duration_scale").trim()
        require(previousAnimatorScale == "null" || previousAnimatorScale.toFloatOrNull() != null)
        try {
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(5_000) { ValueAnimator.getDurationScale() == 1f }
            compose.onNodeWithTag("nav_week").performClick()
            compose.waitForIdle()
            listOf("pending_details", "course_details", "course_manager", "course_group_details").forEach { contentTag ->
                when (contentTag) {
                    "pending_details" -> compose.onNodeWithTag("pending_lessons").performClick()
                    "course_group_details" -> compose.onNode(hasTestTag("course_tile_0_0") and hasAnyAncestor(hasTestTag("page_week")), useUnmergedTree = true).performClick()
                    else -> {
                        compose.onNodeWithTag("action_more").performClick()
                        compose.onNodeWithTag("action_manage_courses").performClick()
                        if (contentTag == "course_details") compose.onNodeWithTag("managed_course_0").performClick()
                    }
                }
                compose.onNodeWithTag(contentTag).assertIsDisplayed()
                if (handle().fetchSemanticsNode().config.contains(SemanticsActions.Expand)) {
                    handle().performSemanticsAction(SemanticsActions.Expand) { it() }
                }
                compose.waitForIdle()
                val content = compose.onNodeWithTag(contentTag)
                val before = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                content.performTouchInput { swipeUp(durationMillis = 450) }
                compose.waitForIdle()
                val after = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("$contentTag must still scroll its teaching records", after > before + 2f)
                handle().assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))

                val initial = handle().fetchSemanticsNode().boundsInRoot
                val dialog = compose.onNode(isDialog())
                compose.mainClock.autoAdvance = false
                dialog.performTouchInput {
                    down(initial.center)
                    moveTo(initial.center + Offset(0f, 96f), delayMillis = 150)
                }
                compose.mainClock.advanceTimeByFrame()
                assertTrue("$contentTag must follow a real downward drag", handle().fetchSemanticsNode().positionInRoot.y > initial.top + 12f)
                dialog.performTouchInput {
                    moveTo(Offset(initial.center.x, height * .96f), delayMillis = 180)
                    up()
                }
                compose.mainClock.advanceTimeBy(1_500)
                compose.mainClock.autoAdvance = true
                compose.waitUntil(5_000) { compose.onAllNodesWithTag(contentTag).fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            runCatching {
                if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) {
                    handle().performSemanticsAction(SemanticsActions.Dismiss) { it() }
                    compose.mainClock.advanceTimeBy(1_500)
                }
            }
            compose.mainClock.autoAdvance = true
            if (previousAnimatorScale == "null") shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $previousAnimatorScale")
            compose.waitForIdle()
        }
    }

    private fun handle() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and hasAnyAncestor(isDialog()))
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().toString(Charsets.UTF_8) }
}
