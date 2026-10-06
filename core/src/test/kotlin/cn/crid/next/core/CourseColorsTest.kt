package cn.crid.next.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class CourseColorsTest {
    @Test fun `persisted identities survive theme changes and even existing shared colors`() {
        val old = listOf(Course(name = "Already blue", color = 0xff4263c7.toInt(), lessons = emptyList()),
            Course(name = "Also blue", color = 0xff4263c7.toInt(), lessons = emptyList()),
            Course(name = "Original pink", color = 0xff9b4e88.toInt(), lessons = emptyList()))
        val assigned = CourseColors.assign(old + Course(name = "New course", lessons = emptyList()))
        assertEquals(old.map { it.color }, assigned.take(old.size).map { it.color })
        assertFalse(old.map { it.color }.contains(assigned.last().color))
        assigned.forEach { course ->
            CourseColors.swatch(course.color, dark = false)
            CourseColors.swatch(course.color, dark = true, holiday = true)
        }
        assertEquals(old, assigned.take(old.size))
        assertEquals(assigned.associate { it.name to it.color }, CourseColors.assign(assigned.reversed()).associate { it.name to it.color })
    }

    @Test fun `new courses have varied soft colors in both themes`() {
        val courses = CourseColors.assign((1..32).map { Course(name = "Course $it", lessons = emptyList()) })
        assertEquals(32, courses.map { it.color }.distinct().size)
        for (dark in listOf(false, true)) {
            val swatches = courses.map { CourseColors.swatch(it.color, dark) }
            assertEquals("The usual fourteen-course plan retains separate fill colors", 14, swatches.take(14).map { it.fill }.distinct().size)
            assertEquals("Fallback colors must remain distinct after pastel rounding in either theme", 32, swatches.map { it.fill }.distinct().size)
            swatches.forEach { swatch ->
                val channels = listOf(16, 8, 0).map { swatch.fill ushr it and 255 }
                if (dark) assertTrue("Dark-mode fills stay muted rather than becoming bright saturated tiles", channels.max() <= 95)
                else assertTrue("Light-mode course fills remain pale", channels.min() >= 195)
                assertTrue("Course fills retain a gentle chroma", channels.max() - channels.min() <= 60)
                assertTrue("Soft fills retain readable text", contrast(swatch.content, swatch.fill) >= 4.5)
                assertEquals(0, swatch.hatch)
            }
        }
    }

    @Test fun `blue orange teal and purple stay separated in lightness as well as hue`() {
        val identities = listOf(0xff2864a0, 0xffc78932, 0xff2f8a84, 0xff8c65af).map { it.toInt() }
        val fills = identities.map { CourseColors.swatch(it).fill }
        for ((index, first) in fills.withIndex()) for (second in fills.drop(index + 1)) {
            val channelDifference = listOf(16, 8, 0).sumOf { shift ->
                val delta = (first ushr shift and 255) - (second ushr shift and 255)
                delta * delta
            }
            assertTrue("Typical palette colors must not collapse into nearly identical pastels", channelDifference >= 225)
            assertTrue("The main palette also has distinct brightness, without relying only on hue", contrast(first, second) >= 1.03)
        }
    }

    @Test fun `adding and reordering courses preserves existing assignments`() {
        val initial = CourseColors.assign((1..14).map { Course(name = "Course $it", lessons = emptyList()) })
        val reordered = CourseColors.assign(initial.reversed())
        assertEquals(initial.associate { it.name to it.color }, reordered.associate { it.name to it.color })
        val extended = CourseColors.assign(initial + Course(name = "Additional seminar", lessons = emptyList()))
        assertEquals(initial, extended.take(initial.size))
        assertFalse(initial.any { it.color == extended.last().color })
        val variants = CourseColors.assign(listOf(initial.first(), initial.first().copy(name = " ＣＯＵＲＳＥ １ ")))
        assertEquals(variants.first().color, variants.last().color)
    }

    @Test fun `text contrast survives both fill and holiday stripes across the color space`() {
        val channels = listOf(0, 34, 85, 136, 187, 221, 255)
        for (red in channels) for (green in channels) for (blue in channels) {
            val color = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
            for (dark in listOf(false, true)) for (out in listOf(false, true)) for (holiday in listOf(false, true)) {
                val swatch = CourseColors.swatch(color, dark, out, holiday)
                assertTrue("All course text must be readable against its fill", contrast(swatch.content, swatch.fill) >= 4.5)
                if (holiday) {
                    val alpha = (swatch.hatch ushr 24 and 255) / 255.0
                    val stripe = listOf(16, 8, 0).fold(0xff000000.toInt()) { value, shift ->
                        value or ((((swatch.fill ushr shift and 255) * (1 - alpha) +
                            (swatch.hatch ushr shift and 255) * alpha).roundToInt()) shl shift)
                    }
                    assertTrue("Holiday hatching must keep course text readable", contrast(swatch.content, stripe) >= 4.5)
                    assertTrue("Holiday hatching must have a visible alpha", (swatch.hatch ushr 24 and 255) >= 32)
                } else assertEquals(0, swatch.hatch)
            }
        }
    }

    @Test fun `out of week fills recede while retaining course identity and visible outlines`() {
        val color = 0xffba3788.toInt()
        for (dark in listOf(false, true)) {
            val normal = CourseColors.swatch(color, dark)
            val inactive = CourseColors.swatch(color, dark, outOfWeek = true)
            assertNotEquals(normal.fill, inactive.fill)
            assertEquals(normal.outline, inactive.outline)
            assertNotEquals(inactive.fill, inactive.outline)
            assertTrue(contrast(inactive.content, inactive.fill) >= 4.5)
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color ushr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + .05) / (min(a, b) + .05)
    }
}
