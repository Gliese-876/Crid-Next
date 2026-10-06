package cn.crid.next.ui

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Each modal window owns its system bars; the Activity's flags do not carry over. */
@Composable
internal fun MatchDialogSystemBars(
    statusBarBackground: Color = MaterialTheme.colorScheme.surface,
    navigationBarBackground: Color = statusBarBackground,
    dimmedBackground: Boolean = false,
) {
    val view=LocalView.current
    val dialogWindow=generateSequence(view.parent) {it.parent}.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    MatchWideColorWindow(dialogWindow)
    SideEffect {
        val provider=generateSequence(view.parent) {it.parent}.filterIsInstance<DialogWindowProvider>().firstOrNull()
        provider?.window?.let {window ->
            WindowCompat.setDecorFitsSystemWindows(window,false)
            val dim=if(dimmedBackground)window.attributes.dimAmount.coerceIn(0f,1f)else 0f
            val scrim=Color.Black.copy(alpha=dim)
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=useDarkSystemBarIcons(scrim.compositeOver(statusBarBackground))
                isAppearanceLightNavigationBars=useDarkSystemBarIcons(scrim.compositeOver(navigationBarBackground))
            }
            window.isNavigationBarContrastEnforced=false
        }
    }
}

/** Status icons sit above the page scrim; navigation icons sit above the sheet container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchBottomSheetSystemBars() {
    MatchDialogSystemBars(
        statusBarBackground=BottomSheetDefaults.ScrimColor.compositeOver(MaterialTheme.colorScheme.surface),
        navigationBarBackground=BottomSheetDefaults.ContainerColor,
    )
}

// The crossover where black and white have equal contrast against an opaque background.
internal fun useDarkSystemBarIcons(background:Color):Boolean=background.luminance()>.17913f
