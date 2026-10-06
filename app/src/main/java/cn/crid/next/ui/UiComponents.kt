package cn.crid.next.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable internal fun SectionTitle(title: String, subtitle: String = "", action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(alignByVisualCenter(Modifier.weight(1f))) {
            VisualCenterText(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) VisualCenterText(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        if (action != null) Box(alignByVisualCenter()) { action() }
    }
}

/** Shared container hierarchy across the application. */
internal enum class AppSurfaceRole {
    Reading, Supporting, Raised, Focused, Selected,
}

internal fun ColorScheme.appSurface(role: AppSurfaceRole): Color = when (role) {
    AppSurfaceRole.Reading -> surface
    AppSurfaceRole.Supporting -> surfaceContainerLow
    AppSurfaceRole.Raised -> surfaceContainer
    AppSurfaceRole.Focused -> surfaceContainerHigh
    AppSurfaceRole.Selected -> primaryContainer
}

internal fun ColorScheme.appOnSurface(role: AppSurfaceRole): Color = when (role) {
    AppSurfaceRole.Selected -> onPrimaryContainer
    else -> onSurface
}

@Composable internal fun SoftCard(
    modifier: Modifier = Modifier,
    role: AppSurfaceRole = AppSurfaceRole.Supporting,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.appSurface(role),
        contentColor = MaterialTheme.colorScheme.appOnSurface(role)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable internal fun EmptyPanel(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    illustration: IllustrationScene = IllustrationScene.REST,
    actionModifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIllustration(illustration, Modifier.widthIn(max = 360.dp).fillMaxWidth().height(152.dp))
            Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(body, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel.isNotEmpty()) FilledTonalButton(onClick = onAction, modifier = actionModifier) { VisualCenterText(actionLabel, modifier = Modifier.centerVisualText(), textAlign = TextAlign.Center) }
        }
    }
}

@Composable internal fun NavGlyph(route: String, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width / 24f
        fun p(x: Float, y: Float) = Offset(x*s,y*s)
        when(route) {
            "today" -> {
                drawCircle(tint, 5*s, p(12f,12f), style = Stroke(1.8f*s))
                repeat(8) { i -> val a = Math.PI*i/4; drawLine(tint,p((12+8.5*kotlin.math.cos(a)).toFloat(),(12+8.5*kotlin.math.sin(a)).toFloat()),p((12+10.5*kotlin.math.cos(a)).toFloat(),(12+10.5*kotlin.math.sin(a)).toFloat()),1.8f*s) }
            }
            "week" -> {
                drawRoundRect(tint,p(3f,4f),androidx.compose.ui.geometry.Size(18*s,17*s),CornerRadius(3*s),style=Stroke(1.8f*s))
                drawLine(tint,p(3f,9f),p(21f,9f),1.8f*s)
                drawLine(tint,p(8f,2f),p(8f,6f),1.8f*s); drawLine(tint,p(16f,2f),p(16f,6f),1.8f*s)
                drawRoundRect(tint,p(7f,12f),androidx.compose.ui.geometry.Size(4*s,5*s),CornerRadius(s))
            }
            "plans" -> {
                drawRoundRect(tint,p(6f,3f),androidx.compose.ui.geometry.Size(15*s,15*s),CornerRadius(3*s),style=Stroke(1.8f*s))
                drawRoundRect(tint,p(3f,7f),androidx.compose.ui.geometry.Size(14*s,14*s),CornerRadius(3*s),style=Stroke(1.8f*s))
            }
            else -> {
                listOf(6f,12f,18f).forEachIndexed { i,y -> drawLine(tint,p(3f,y),p(21f,y),1.8f*s); drawCircle(tint,2.5f*s,p(if(i==1)15f else 8f,y)) }
            }
        }
    }
}
