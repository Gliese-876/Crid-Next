package cn.crid.next.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** A quiet surface tint keeps term and plan selection in the same tonal family. */
internal fun planCardContainer(scheme: ColorScheme, selected: Boolean): Color =
    if (selected) lerp(scheme.surfaceContainer, scheme.primaryContainer, .18f)
    else scheme.surfaceContainerLow

@Composable
internal fun PlanCard(selected: Boolean, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val container by animateColorAsState(planCardContainer(MaterialTheme.colorScheme, selected),
        AppMotion.pageSpring(), label = "Plan selection")
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = container,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
