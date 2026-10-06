package cn.crid.next.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Presentation colors; the persisted course identity is never rewritten for a theme. */
data class CourseSwatch(val fill: Int, val content: Int, val outline: Int, val hatch: Int)

/** Persisted ARGB identities. Assignment is deterministic and never depends on view or theme. */
object CourseColors {
    private val palette = listOf(0xff2864a0, 0xffc78932, 0xff2f8a84, 0xff8c65af,
        0xff173b66, 0xffe2b85d, 0xff599ebb, 0xffb59bcb,
        0xff93623b, 0xffb67594, 0xff9ba371, 0xff607c92,
        0xffd89987, 0xff4c416f).map { it.toInt() }
    fun colorFor(name: String): Int = palette[Math.floorMod(courseKey(name).hashCode(), palette.size)]
    fun assign(courses: List<Course>): List<Course> {
        val byIdentity = linkedMapOf<String, Int>()
        val used = mutableSetOf<Int>()
        val lightFills = mutableSetOf<Int>()
        val darkFills = mutableSetOf<Int>()
        fun remember(color: Int) {
            used += color
            lightFills += swatch(color).fill
            darkFills += swatch(color, dark = true).fill
        }
        fun collides(color: Int) = color in used || swatch(color).fill in lightFills || swatch(color, dark = true).fill in darkFills
        // Existing identities win, so appending courses cannot recolor the current scheme.
        courses.forEach { course ->
            val key = courseKey(course.name)
            if (course.color != 0 && key !in byIdentity) {
                byIdentity[key] = course.color
                remember(course.color)
            }
        }
        courses.map { courseKey(it.name) }.distinct().sorted().forEach { key ->
            if (key !in byIdentity) {
                val first = Math.floorMod(key.hashCode(), palette.size)
                val available = palette.indices.map { palette[(first + it) % palette.size] }.filterNot(::collides)
                // Prefer a visibly separated unused hue/tone over the next adjacent
                // palette entry. Existing stored identities always stay untouched.
                var candidate = if (used.isEmpty()) colorFor(key) else available.maxByOrNull { color ->
                    used.minOf { separation(color, it) }
                } ?: colorFor(key)
                var attempt = 0
                while (collides(candidate)) {
                    attempt++
                    candidate = hueColor(Math.floorMod(key.hashCode(), 360).toDouble() + attempt * 137.507764, attempt)
                }
                byIdentity[key] = candidate
                remember(candidate)
            }
        }
        return courses.map { it.copy(color = byIdentity.getValue(courseKey(it.name))) }
    }

    /** Soft course fills shared by Compose, widgets and export, with readable text over hatching. */
    fun swatch(argb: Int, dark: Boolean = false, outOfWeek: Boolean = false, holiday: Boolean = false,
        surface: Int = if (dark) 0xff181b24.toInt() else 0xfff8fafc.toInt()): CourseSwatch {
        val identity = argb or 0xff000000.toInt()
        var fill = when {
            outOfWeek -> mix(surface, identity, if (dark) .09 else .06)
            dark -> mix(surface, mix(identity, WHITE, .20), .24)
            else -> mix(surface, identity, if (luminance(identity) < .18) .20 else .16)
        }
        val hatch = if (holiday) (if (dark) 0x22ffffff else 0x30000000) else 0
        fun leastContrast(text: Int, background: Int): Double = min(contrast(text, background),
            if (holiday) contrast(text, composite(hatch, background)) else Double.MAX_VALUE)
        var content = if (leastContrast(BLACK, fill) >= leastContrast(WHITE, fill)) BLACK else WHITE
        // Some middle tones straddle both text colors under a stripe. Move the fill
        // a little toward the chosen text's opposite, preserving its original hue.
        repeat(20) {
            if (leastContrast(content, fill) < 4.5) fill = mix(fill, if (content == BLACK) WHITE else BLACK, .04)
        }
        val ink = 0xff111820.toInt()
        if (content == BLACK && leastContrast(ink, fill) >= 4.5) content = ink
        val outline = if (dark) mix(identity, WHITE, .38) else mix(identity, BLACK, .12)
        return CourseSwatch(fill, content, outline, hatch)
    }

    private fun mix(a: Int, b: Int, amount: Double): Int {
        fun channel(shift: Int) = (((a ushr shift and 255) * (1.0 - amount) + (b ushr shift and 255) * amount).roundToInt()).coerceIn(0, 255)
        return BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun composite(foreground: Int, background: Int): Int = mix(background, foreground, (foreground ushr 24 and 255) / 255.0)

    private fun contrast(a: Int, b: Int): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + .05) / (min(first, second) + .05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = (color ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    /** Hue and lightness both matter; a red/green axis alone does not decide assignment. */
    private fun separation(a: Int, b: Int): Double {
        val red = (a ushr 16 and 255) - (b ushr 16 and 255)
        val green = (a ushr 8 and 255) - (b ushr 8 and 255)
        val blue = (a and 255) - (b and 255)
        val lightness = .2126 * red + .7152 * green + .0722 * blue
        return red * red + green * green + blue * blue + 2 * lightness * lightness
    }

    private const val BLACK: Int = -0x1000000
    private const val WHITE: Int = -1

    private fun hueColor(degrees: Double, variant: Int): Int {
        val hue = (degrees % 360.0) / 60.0
        // Vary tone as well as hue so a large plan does not exhaust the small
        // number of distinct pastel pixels available on one fixed color ring.
        val chroma = .32 + (variant / 4 % 5) * .06
        val minimum = .14 + (variant % 4) * .09
        val x = chroma * (1.0 - abs(hue % 2.0 - 1.0))
        val channels = when (hue.toInt()) {
            0 -> doubleArrayOf(chroma, x, 0.0)
            1 -> doubleArrayOf(x, chroma, 0.0)
            2 -> doubleArrayOf(0.0, chroma, x)
            3 -> doubleArrayOf(0.0, x, chroma)
            4 -> doubleArrayOf(x, 0.0, chroma)
            else -> doubleArrayOf(chroma, 0.0, x)
        }.map { ((it + minimum) * 255.0).roundToInt().coerceIn(0, 255) }
        return (0xff shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }
}
