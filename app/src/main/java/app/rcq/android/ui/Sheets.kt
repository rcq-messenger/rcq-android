package app.rcq.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.rcq.android.R

/**
 * The app's own bottom sheet, and the reason there is one.
 *
 * A centred `AlertDialog` is a box that lands in the middle of the screen, in
 * Material's colours and Material's corners, fighting the keyboard for space
 * and looking like a system prompt rather than part of RCQ. Everything that
 * asks the user something is a SHEET here: it comes up from the thumb, it has
 * the app's surface colour, and it dismisses by dragging down. Founder asked
 * for exactly this ("центральные диалоги в шторки").
 *
 * [RcqSheet] is the bare surface for anything custom; [RcqAskSheet] is the
 * drop-in replacement for the title/text/buttons shape that most of the old
 * dialogs were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcqSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    val c = RcqTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.bgSecondary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            content()
        }
    }
}

/** One choice in [RcqAskSheet]. `destructive` paints it red, `dimmed` greys it
 *  down to the weight of a cancel. */
data class SheetAction(
    val label: String,
    val destructive: Boolean = false,
    val dimmed: Boolean = false,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * Ask something and offer choices, as a sheet.
 *
 * The actions are full-width rows, not a right-aligned button cluster: a row is
 * a thumb-sized target and reads in the order it is written, where three text
 * buttons stacked in a corner read as a puzzle. Cancel is always present and is
 * always last — it does not need to be passed in.
 */
@Composable
fun RcqAskSheet(
    onDismiss: () -> Unit,
    title: String,
    body: String? = null,
    actions: List<SheetAction>,
    cancelLabel: String? = null,
) {
    val c = RcqTheme.colors
    RcqSheet(onDismiss = onDismiss, title = title) {
        if (!body.isNullOrBlank()) {
            Text(body, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp))
        }
        actions.forEach { a ->
            SheetRow(a)
        }
        SheetRow(
            SheetAction(
                label = cancelLabel ?: stringResource(R.string.common_cancel),
                dimmed = true,
                onClick = onDismiss,
            ),
        )
    }
}

@Composable
private fun SheetRow(a: SheetAction) {
    val c = RcqTheme.colors
    val tint = when {
        a.destructive -> Color(0xFFE5484D)
        a.dimmed -> c.textSecondary
        else -> c.accent
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = a.onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (a.icon != null) Icon(a.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(a.label, color = tint, fontSize = 16.sp,
            fontWeight = if (a.dimmed) FontWeight.Normal else FontWeight.Medium)
    }
}

/**
 * The app's text field: a filled capsule, no outline, no floating label.
 *
 * ⚠ Either a fill or a border, never both (house rule). Material's outlined
 * field draws a border AND cuts a notch in it for a label that jumps around on
 * focus — three moving parts to type a nickname into. Here the label is a
 * placeholder that simply gets out of the way, and the only thing that changes
 * on focus is the accent underline, which is also the only thing worth saying.
 */
@Composable
fun RcqField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    /// Height floor for a multi-line field. A bug report or an About text that
    /// starts one line tall reads as "type a word here", not "tell me what
    /// happened", so the box has to look like the answer it wants.
    minLines: Int = 1,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
) {
    val c = RcqTheme.colors
    val fill = c.textPrimary.copy(alpha = 0.06f)
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let {
                { Text(it, color = c.textSecondary, fontSize = 15.sp) }
            },
            singleLine = singleLine,
            minLines = minLines,
            isError = isError,
            enabled = enabled,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            leadingIcon = leadingIcon,
            shape = RoundedCornerShape(14.dp),
            // ⚠ The fill is derived from the TEXT colour, not from a background
            // token. A field lives on the screen background (bgPrimary) in
            // Settings and on the sheet background (bgSecondary) inside a
            // sheet; painting it either of those makes it vanish into one of
            // the two, which is exactly what happened the first time — a
            // profile editor of floating placeholders with no boxes under them.
            // A 6% wash of the text colour is darker than a light background
            // and lighter than a dark one, so it reads as a field on both.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fill,
                unfocusedContainerColor = fill,
                disabledContainerColor = fill,
                errorContainerColor = fill,
                focusedTextColor = c.textPrimary,
                unfocusedTextColor = c.textPrimary,
                disabledTextColor = c.textSecondary,
                cursorColor = c.accent,
                // The fill IS the field, so the border only exists to say
                // "focused" and "wrong".
                focusedBorderColor = c.accent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color(0xFFE5484D),
            ),
        )
        if (!supportingText.isNullOrBlank()) {
            Text(
                supportingText,
                color = if (isError) Color(0xFFE5484D) else c.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

/** Spacer sized to the gap the sheet layout uses between blocks. */
@Composable
fun SheetGap(height: Int = 12) = Spacer(Modifier.height(height.dp))
