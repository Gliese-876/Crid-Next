package cn.crid.next.ui

import org.junit.Assert.*
import org.junit.Test

class CourseHeightCurveTest {
    @Test fun weeklySoftMinimumPreservesTwoPeriodsAndApproachesProportionalHeight() {
        val expected = mapOf(30 to 65.015f, 45 to 74.506f, 60 to 96.145f, 100 to 160f, 180 to 288f)
        expected.forEach { (duration, height) ->
            assertEquals("$duration minutes", height, readableCourseHeight(duration, 64f, 1.6f, 8f), .001f)
        }
        (60..1_440).forEach { duration ->
            assertEquals(duration * 1.6f, readableCourseHeight(duration, 64f, 1.6f, 8f), .15f)
        }
    }

    @Test fun todayUsesAShortReadableFloorAndTheExistingTwoPeriodHeight() {
        val expected = mapOf(30 to 76f, 45 to 76f, 60 to 76f, 100 to 84.036f, 180 to 151.2f)
        expected.forEach { (duration, height) ->
            assertEquals("$duration minutes", height, readableCourseHeight(duration, 76f, .84f, 2f), .001f)
        }
        (100..1_440).forEach { duration ->
            assertEquals(duration * .84f, readableCourseHeight(duration, 76f, .84f, 2f), .04f)
        }
    }

    @Test fun theCurveIsStableForShortLessonsAndAWholeDay() {
        listOf(Triple(64f, 1.6f, 8f), Triple(76f, .84f, 2f)).forEach { (minimum, slope, smoothing) ->
            val heights = (1..1_440).map { readableCourseHeight(it, minimum, slope, smoothing) }
            assertTrue(heights.all { it.isFinite() && it >= minimum })
            // Floating-point rounding can flatten the far-left tail; layouts preserve a visible step.
            assertTrue(heights.zipWithNext().all { (shorter, longer) -> longer >= shorter })
            assertTrue(heights.last() > heights.first())
        }
    }

    @Test fun scalingAllThreeParametersPreservesTheShapeForLargerText() {
        listOf(30, 45, 60, 100, 180).forEach { duration ->
            assertEquals(readableCourseHeight(duration, 64f, 1.6f, 8f) * 2,
                readableCourseHeight(duration, 128f, 3.2f, 16f), .001f)
        }
    }
}
