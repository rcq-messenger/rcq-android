package app.rcq.android.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.rcq.android.data.LocalStores

/**
 * ⚠⚠ EVERY NEW WINDOW LOSES THE APP'S TEXT SIZE, and this puts it back.
 *
 * The in-app text-size setting is applied by overriding `LocalDensity` once,
 * around the whole app (MainActivity). That covers everything drawn in the
 * activity's own window. A bottom sheet, a dialog and a popup are NOT drawn
 * there: each is its own window with its own `AndroidComposeView`, and that
 * view provides `LocalDensity` from the platform configuration inside the
 * subcomposition, which lands UNDER our provider and wins.
 *
 * So the largest text size made the whole app bigger and left every sheet at
 * the system size: the About sheet, the report and bug-report sheets, the
 * add-contact sheet, the attach sheet (report #957, vss, 08.09, verified on
 * the emulator before this was written by comparing the About sheet against
 * the settings list behind it).
 *
 * Wrap the CONTENT of any window-owning composable in [RcqScaledText], or use
 * [RcqModalBottomSheet], which does it for you. Reading `LocalDensity.current`
 * inside the new window gives the platform density, so the multiplier is
 * applied exactly once.
 */
@Composable
fun RcqScaledText(content: @Composable () -> Unit) {
    val scale by LocalStores.fontScale.collectAsState()
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * scale),
        content = content,
    )
}

/**
 * `ModalBottomSheet` with the app's text size applied inside it. Same
 * parameters, same defaults, plus two things every RCQ sheet needs:
 * [RcqScaledText] around the content, and Back that puts the keyboard away
 * before it closes the sheet ([RcqSheetProperties] and [SheetBackHandler],
 * report 867). Use this rather than Material's directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcqModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberRcqSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = containerColor,
        shape = shape,
        contentWindowInsets = contentWindowInsets,
        dragHandle = dragHandle,
        properties = RcqSheetProperties,
    ) {
        // Inside the content on purpose: only here is the sheet's own window
        // (and its back dispatcher) the one BackHandler finds.
        SheetBackHandler(sheetState, onDismissRequest)
        RcqScaledText { androidx.compose.foundation.layout.Column { content() } }
    }
}
