package cn.crid.next.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keeps the sheet's measured height independent of its animated vertical offset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // M3 derives the expanded anchor from measured height, and subtracts the current
    // sheet offset from content's top inset. Near full height this creates a feedback
    // loop after a fast fling. Reserve the top boundary outside that measurement;
    // the native sheet keeps its drag, nested scrolling, scrim and motion behavior.
    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    val contentInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.windowInsetsPadding(topInsets),
        sheetState = sheetState,
        contentWindowInsets = { contentInsets },
        content = content,
    )
}
