package cn.crid.next.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

internal enum class IllustrationScene { PLANNING, REST, IMPORT }

/** Decorative scenes use the same theme roles as the surrounding interface. */
@Composable
internal fun AppIllustration(scene: IllustrationScene, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val palette = IllustrationPalette(
        isDark = dark,
        paper = scheme.surfaceContainerLowest,
        backdrop = scheme.surfaceContainerHighest,
        ink = scheme.onSurfaceVariant,
        primary = scheme.primary,
        primarySoft = scheme.primaryContainer,
        secondary = scheme.secondary,
        secondarySoft = scheme.secondaryContainer,
        accent = scheme.tertiary,
        accentSoft = scheme.tertiaryContainer,
        skin = if (dark) Color(0xFFE7B998) else Color(0xFFC48664),
        hair = if (dark) Color(0xFF65534C) else Color(0xFF453A36),
    )
    Canvas(modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics { }) {
        val factor = minOf(size.width / 360f, size.height / 180f)
        withTransform({
            translate((size.width - 360f * factor) / 2f, (size.height - 180f * factor) / 2f)
            scale(factor, factor, Offset.Zero)
        }) {
            when (scene) {
                IllustrationScene.PLANNING -> drawPlanning(palette)
                IllustrationScene.REST -> drawRest(palette)
                IllustrationScene.IMPORT -> drawImport(palette)
            }
        }
    }
}

private data class IllustrationPalette(
    val isDark: Boolean,
    val paper: Color,
    val backdrop: Color,
    val ink: Color,
    val primary: Color,
    val primarySoft: Color,
    val secondary: Color,
    val secondarySoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val skin: Color,
    val hair: Color,
)

private fun DrawScope.block(color: Color, x: Float, y: Float, width: Float, height: Float, radius: Float = 6f) =
    drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(radius))

private fun DrawScope.line(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 3f) =
    drawLine(color, Offset(x1, y1), Offset(x2, y2), width, StrokeCap.Round)

private fun shape(build: Path.() -> Unit) = Path().apply(build)

private fun DrawScope.drawPlanning(p: IllustrationPalette) {
    // A student moves one last card into the week before settling down to work.
    drawOval(p.backdrop.copy(alpha = .55f), Offset(56f, 19f), Size(246f, 137f))
    line(p.backdrop, 40f, 166f, 318f, 166f, 2f)

    block(p.secondarySoft, 172f, 22f, 131f, 108f, 11f)
    block(p.paper, 179f, 30f, 117f, 91f, 6f)
    repeat(3) { column ->
        block(p.secondary, 188f + column * 34f, 39f, 23f, 4f, 2f)
        repeat(3) { row ->
            block(p.backdrop.copy(alpha = .55f), 187f + column * 34f, 51f + row * 21f, 27f, 17f, 3f)
        }
    }
    block(p.primary, 187f, 51f, 27f, 17f, 3f)
    block(p.accent, 255f, 51f, 27f, 17f, 3f)
    block(p.secondary, 221f, 72f, 27f, 17f, 3f)
    block(p.accentSoft, 187f, 93f, 27f, 17f, 3f)
    block(p.primary, 255f, 93f, 27f, 17f, 3f)

    block(p.secondarySoft, 68f, 112f, 53f, 28f, 10f)
    line(p.secondary, 78f, 137f, 70f, 164f, 5f)
    line(p.secondary, 112f, 137f, 120f, 164f, 5f)
    // Seated silhouette: a soft shirt, bent legs and a hand reaching to the board.
    drawPath(shape {
        moveTo(87f, 113f); cubicTo(112f, 105f, 130f, 114f, 128f, 131f)
        lineTo(119f, 153f); lineTo(105f, 153f); lineTo(108f, 130f)
        lineTo(88f, 130f); close()
    }, p.ink)
    block(p.hair, 101f, 152f, 23f, 8f, 4f)
    drawPath(shape {
        moveTo(85f, 88f); cubicTo(99f, 82f, 114f, 85f, 118f, 103f)
        lineTo(123f, 119f); cubicTo(111f, 128f, 92f, 126f, 80f, 120f)
        lineTo(82f, 100f); close()
    }, p.primary)
    line(p.skin, 110f, 99f, 135f, 105f, 11f)
    line(p.skin, 135f, 105f, 158f, 83f, 10f)
    rotate(-12f, Offset(164f, 78f)) { block(p.accent, 152f, 69f, 27f, 17f, 3f) }
    drawCircle(p.skin, 5f, Offset(157f, 85f))
    block(p.skin, 91f, 77f, 12f, 14f, 5f)
    drawCircle(p.skin, 14f, Offset(97f, 68f))
    drawPath(shape {
        moveTo(83f, 70f); cubicTo(74f, 47f, 109f, 44f, 111f, 64f)
        cubicTo(103f, 66f, 96f, 60f, 91f, 58f)
        lineTo(91f, 72f); close()
    }, p.hair)

    block(p.secondary, 142f, 131f, 168f, 7f, 3f)
    line(p.secondary, 155f, 137f, 155f, 165f, 5f)
    line(p.secondary, 297f, 137f, 297f, 165f, 5f)
    block(p.primarySoft, 204f, 121f, 39f, 10f, 2f)
    block(p.paper, 208f, 123f, 33f, 5f, 1f)
    block(p.accentSoft, 272f, 115f, 15f, 16f, 4f)
}

private fun DrawScope.drawRest(p: IllustrationPalette) {
    // Reading beside the window, with the chair supporting the seated figure.
    drawOval(p.backdrop.copy(alpha = .5f), Offset(54f, 24f), Size(256f, 133f))
    line(p.backdrop, 43f, 165f, 316f, 165f, 2f)
    block(p.secondarySoft, 148f, 20f, 106f, 102f, 13f)
    block(if (p.isDark) Color(0xFF293A50) else Color(0xFFDDE9F5), 155f, 27f, 92f, 87f, 8f)
    if (p.isDark) {
        drawPath(shape {
            moveTo(229f, 35f); cubicTo(217f, 32f, 209f, 42f, 213f, 52f)
            cubicTo(217f, 63f, 231f, 65f, 238f, 54f)
            cubicTo(230f, 58f, 223f, 52f, 222f, 45f)
            cubicTo(221f, 41f, 224f, 37f, 229f, 35f); close()
        }, Color(0xFFD6DDBF))
        drawCircle(Color(0xFFAABBD4), 1.5f, Offset(168f, 41f))
        drawCircle(Color(0xFFAABBD4), 1.5f, Offset(186f, 56f))
    } else {
        drawCircle(Color(0xFFE9B77F), 11f, Offset(225f, 48f))
    }
    drawPath(shape {
        moveTo(155f, 93f); cubicTo(173f, 62f, 187f, 78f, 204f, 89f)
        cubicTo(219f, 78f, 235f, 84f, 247f, 92f)
        lineTo(247f, 114f); lineTo(155f, 114f); close()
    }, if (p.isDark) Color(0xFF425A70) else Color(0xFFC2D5E8))
    line(p.secondarySoft, 201f, 27f, 201f, 114f, 5f)
    line(p.secondarySoft, 153f, 69f, 248f, 69f, 4f)
    block(p.secondary, 141f, 118f, 120f, 5f, 2f)

    // The backrest stays behind the torso; the shallow seat supports the hips.
    line(p.accent, 78f, 138f, 72f, 163f, 4f)
    line(p.accent, 120f, 138f, 124f, 163f, 4f)
    drawPath(shape {
        moveTo(72f, 139f); lineTo(63f, 98f)
        cubicTo(61f, 89f, 73f, 85f, 78f, 94f)
        lineTo(92f, 132f); lineTo(91f, 139f); close()
    }, p.accentSoft)
    block(p.accentSoft, 73f, 132f, 65f, 11f, 5f)

    // Bent legs start at the seated pelvis; both soles meet the ground.
    drawPath(shape {
        moveTo(98f, 123f); lineTo(123f, 122f)
        quadraticTo(133f, 122f, 135f, 133f)
        lineTo(136f, 159f); lineTo(125f, 159f); lineTo(123f, 137f)
        lineTo(103f, 137f); quadraticTo(95f, 135f, 95f, 128f); close()
    }, p.ink.copy(alpha = .75f))
    block(p.hair, 123f, 157f, 17f, 8f, 4f)
    drawPath(shape {
        moveTo(94f, 123f); cubicTo(110f, 120f, 125f, 122f, 136f, 123f)
        quadraticTo(145f, 123f, 147f, 133f)
        lineTo(154f, 159f); lineTo(142f, 159f); lineTo(136f, 137f)
        lineTo(107f, 136f); quadraticTo(96f, 135f, 94f, 128f); close()
    }, p.ink)
    block(p.hair, 144f, 157f, 24f, 8f, 4f)
    drawPath(shape {
        moveTo(83f, 91f); cubicTo(93f, 83f, 106f, 89f, 110f, 101f)
        lineTo(118f, 126f); cubicTo(108f, 133f, 86f, 132f, 77f, 125f)
        close()
    }, p.primary)
    line(p.skin, 103f, 105f, 122f, 117f, 9f)
    line(p.skin, 122f, 117f, 145f, 111f, 8f)
    block(p.skin, 89f, 80f, 11f, 13f, 4f)
    drawCircle(p.skin, 13f, Offset(94f, 73f))
    drawPath(shape {
        moveTo(80f, 76f); cubicTo(71f, 53f, 105f, 49f, 107f, 69f)
        cubicTo(98f, 70f, 93f, 64f, 89f, 63f)
        lineTo(88f, 78f); close()
    }, p.hair)
    // An open book on the window ledge, within easy reach.
    drawPath(shape {
        moveTo(138f, 106f); lineTo(151f, 109f); lineTo(164f, 106f)
        lineTo(164f, 117f); lineTo(151f, 120f); lineTo(138f, 117f); close()
    }, p.accent)
    line(p.paper, 151f, 111f, 151f, 117f, 2f)

    line(p.secondary, 282f, 93f, 282f, 140f, 3f)
    drawPath(shape {
        moveTo(281f, 115f); cubicTo(264f, 118f, 258f, 103f, 260f, 96f)
        cubicTo(275f, 95f, 281f, 104f, 281f, 115f); close()
    }, p.secondary)
    drawPath(shape {
        moveTo(283f, 104f); cubicTo(283f, 86f, 293f, 79f, 305f, 81f)
        cubicTo(303f, 96f, 295f, 104f, 283f, 104f); close()
    }, p.primary)
    drawPath(shape {
        moveTo(280f, 95f); cubicTo(266f, 88f, 267f, 75f, 274f, 68f)
        cubicTo(285f, 74f, 288f, 85f, 280f, 95f); close()
    }, p.secondarySoft)
    drawPath(shape {
        moveTo(267f, 136f); lineTo(297f, 136f); lineTo(292f, 162f)
        lineTo(272f, 162f); close()
    }, p.accentSoft)
    block(p.accent, 265f, 133f, 34f, 6f, 3f)
}

private fun DrawScope.drawImport(p: IllustrationPalette) {
    // Loose sheets travel out of a folder; a hand makes space for the new week.
    drawOval(p.backdrop.copy(alpha = .5f), Offset(49f, 23f), Size(264f, 130f))
    line(p.backdrop, 40f, 166f, 321f, 166f, 2f)
    block(p.secondary, 53f, 85f, 86f, 65f, 8f)
    block(p.secondary, 57f, 76f, 35f, 21f, 6f)
    rotate(-7f, Offset(96f, 98f)) {
        block(p.paper, 67f, 63f, 55f, 69f, 5f)
        block(p.primarySoft, 75f, 74f, 26f, 5f, 2f)
        repeat(3) { line(p.backdrop, 75f, 89f + it * 11f, 111f, 89f + it * 11f, 4f) }
    }
    drawPath(shape {
        moveTo(47f, 103f); quadraticTo(46f, 97f, 53f, 97f)
        lineTo(135f, 97f); quadraticTo(143f, 97f, 142f, 105f)
        lineTo(136f, 143f); quadraticTo(135f, 152f, 126f, 152f)
        lineTo(61f, 152f); quadraticTo(52f, 152f, 51f, 143f); close()
    }, p.secondarySoft)
    block(p.secondary, 72f, 116f, 35f, 5f, 2f)

    rotate(11f, Offset(169f, 64f)) {
        block(p.primarySoft, 145f, 27f, 55f, 70f, 6f)
        block(p.paper, 152f, 35f, 41f, 54f, 3f)
        block(p.accent, 158f, 42f, 18f, 5f, 2f)
        block(p.primary, 158f, 53f, 11f, 13f, 2f)
        block(p.secondarySoft, 174f, 53f, 12f, 13f, 2f)
        block(p.accentSoft, 158f, 71f, 28f, 9f, 2f)
    }
    line(p.accent, 127f, 47f, 133f, 51f, 3f)
    line(p.accent, 137f, 31f, 140f, 37f, 3f)

    block(p.primary, 212f, 63f, 94f, 90f, 10f)
    block(p.paper, 219f, 76f, 80f, 69f, 5f)
    line(p.paper, 235f, 57f, 235f, 68f, 5f)
    line(p.paper, 284f, 57f, 284f, 68f, 5f)
    repeat(3) { col ->
        block(p.backdrop, 227f + col * 23f, 83f, 16f, 4f, 2f)
        repeat(2) { row -> block(p.primarySoft, 226f + col * 23f, 94f + row * 23f, 18f, 18f, 3f) }
    }
    block(p.accent, 272f, 94f, 18f, 18f, 3f)
    block(p.secondary, 249f, 117f, 18f, 18f, 3f)
    // The incoming card remains connected to the hand instead of acting as a button.
    block(p.accentSoft, 218f, 112f, 23f, 20f, 3f)
    drawPath(shape {
        moveTo(164f, 159f); lineTo(190f, 132f)
        cubicTo(194f, 128f, 201f, 130f, 206f, 126f)
        lineTo(223f, 117f); cubicTo(228f, 114f, 232f, 120f, 226f, 124f)
        lineTo(215f, 132f); cubicTo(223f, 135f, 221f, 141f, 214f, 143f)
        lineTo(201f, 144f); lineTo(178f, 168f); close()
    }, p.skin)
    drawPath(shape {
        moveTo(143f, 166f); lineTo(166f, 143f); lineTo(188f, 163f)
        lineTo(184f, 168f); lineTo(143f, 168f); close()
    }, p.accent)
}
