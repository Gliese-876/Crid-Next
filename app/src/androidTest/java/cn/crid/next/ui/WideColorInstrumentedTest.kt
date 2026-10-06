package cn.crid.next.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as NativeColor
import android.graphics.ColorSpace as NativeColorSpace
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.crid.next.core.CourseColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the same ShaderBrush -> Compose Paint -> Android shader path used by UI backgrounds. */
@RunWith(AndroidJUnit4::class)
class WideColorInstrumentedTest {
    private val linearSrgb = NativeColorSpace.get(NativeColorSpace.Named.LINEAR_EXTENDED_SRGB)

    @Test fun nativeP3ShaderRetainsComponentsOutsideSrgbInAnF16Canvas() {
        val green = Color(0f, 1f, 0f, 1f, ColorSpaces.DisplayP3)
        val packed = nativeDisplayP3Color(green)
        assertEquals(NativeColorSpace.get(NativeColorSpace.Named.DISPLAY_P3), NativeColor.colorSpace(packed))
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.RGBA_F16, true, linearSrgb)
        try {
            val paint = Paint()
            wideColorBrushFor(green, wideColorSupported = true).applyTo(Size(24f, 24f), paint, 1f)
            assertNotNull("The wide color must reach Android as a native shader", paint.asFrameworkPaint().shader)
            Canvas(bitmap).drawRect(0f, 0f, 24f, 24f, paint.asFrameworkPaint())
            val rendered = bitmap.getColor(12, 12)
            assertEquals(linearSrgb, rendered.colorSpace)
            assertTrue("Display P3 green has negative linear sRGB red; ARGB clipping would lose it", rendered.red() < -.15f)
            assertTrue("Display P3 green exceeds linear sRGB green; ARGB clipping would cap it", rendered.green() > 1.02f)
            assertEquals(1f, rendered.alpha(), .001f)
        } finally { bitmap.recycle() }
    }

    @Test fun unsupportedDisplaysKeepTheExactExistingSdrPixelsAndAlpha() {
        val samples = listOf(0xffcedcea, 0xfff0e8dc, 0xffd8e8e9, 0xffe2dced, 0x804263c7).map { it.toInt() }
        for (sample in samples) {
            val color = Color(sample)
            val brush = wideColorBrushFor(color, wideColorSupported = false)
            assertEquals(SolidColor(color), brush)
            val expected = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            try {
                val expectedPaint = Paint().apply { this.color = color }
                val actualPaint = Paint()
                brush.applyTo(Size(24f, 24f), actualPaint, 1f)
                Canvas(expected).drawRect(0f, 0f, 24f, 24f, expectedPaint.asFrameworkPaint())
                Canvas(actual).drawRect(0f, 0f, 24f, 24f, actualPaint.asFrameworkPaint())
                assertEquals("Fallback rendering must match the original Compose color path", expected.getPixel(12, 12), actual.getPixel(12, 12))
                if (sample ushr 24 == 255) assertEquals(color.toArgb(), actual.getPixel(12, 12))
            } finally { expected.recycle(); actual.recycle() }
        }
    }

    @Test fun nativeP3CourseFillsKeepTheirSoftSdrAppearanceAndDoNotGainABorder() {
        val identities = listOf(0xff2864a0, 0xffc78932, 0xff2f8a84, 0xff8c65af).map { it.toInt() }
        for (identity in identities) {
            val swatch = CourseColors.swatch(identity)
            val color = Color(swatch.fill)
            val expected = NativeColor.valueOf(swatch.fill).convert(linearSrgb)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.RGBA_F16, true, linearSrgb)
            try {
                val paint = Paint()
                wideColorBrushFor(color, wideColorSupported = true).applyTo(Size(32f, 32f), paint, 1f)
                Canvas(bitmap).drawRoundRect(0f, 0f, 32f, 32f, 6f, 6f, paint.asFrameworkPaint())
                val middle = bitmap.getColor(16, 16)
                assertEquals("P3 conversion preserves soft red rather than reinterpreting its primary", expected.red(), middle.red(), .004f)
                assertEquals("P3 conversion preserves soft green rather than reinterpreting its primary", expected.green(), middle.green(), .004f)
                assertEquals("P3 conversion preserves soft blue rather than reinterpreting its primary", expected.blue(), middle.blue(), .004f)
                val edge = bitmap.getColor(1, 16)
                assertEquals("A normal course receives a fill, not an outline", middle.red(), edge.red(), .001f)
                assertEquals(middle.green(), edge.green(), .001f)
                assertEquals(middle.blue(), edge.blue(), .001f)
                assertEquals("The caller's rounded shape remains clipped", 0f, bitmap.getColor(0, 0).alpha(), .001f)
            } finally { bitmap.recycle() }
        }
    }
}
