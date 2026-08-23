package app.rcq.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.data.Sections
import app.rcq.android.security.PanicPinService

/**
 * Chat-list sections: the sheets. Founder item 1 of 23.08, built to
 * `RCQ/docs/sections-design-2026-08-23.md`; the list itself is in
 * [HomeScreen], the format and the merge in [app.rcq.android.data.Sections].
 *
 * ⚠⚠ The PIN copy in here says exactly one thing and must go on saying it:
 * **the flag syncs, the protection is local**. A section PIN encrypts nothing.
 * Anyone holding the account (a linked device, the recovery phrase, a compelled
 * handset) derives `identity_priv`, fetches the blob and reads every section
 * name and every member uin, and, for up to 90 days, the member keys of the
 * chats that have LEFT it. So: never "protects", never "encrypts", never a
 * padlock-with-a-key glyph that reads as encryption. The key glyph iOS already
 * uses on Archive, and one sentence.
 */

/** One candidate row in [SectionPickerSheet]: a contact, a cross-island peer or
 *  a group, already keyed the way the slot keys them (host and all). */
internal data class SectionCandidate(
    val key: String,
    val title: String,
    val subtitle: String,
    val group: Boolean,
    val avatar: @Composable () -> Unit,
)

/** What the long-press menu is being opened on. */
internal data class SectionMenuTarget(
    val id: String,
    val title: String,
    val user: Boolean,
    val pinned: Boolean,
)

/**
 * The long-press menu on a section header.
 *
 * ⚠⚠ Never offered on a LOCKED section, and the caller is what enforces that.
 * This menu carries "stop asking for a PIN" and "delete section", neither of
 * which asks for the PIN: on a locked header they turn the gate off in two
 * taps, with no verify call, no failure counter and no cooldown, and then sync
 * `p:0` (or the section tombstone) to every other device, where the section
 * stops being gated too. Unlock first, then the menu.
 */
@Composable
internal fun SectionMenuSheet(
    target: SectionMenuTarget,
    /// A real PIN this device can check against. Without one the flag cannot be
    /// honoured here, so it is not offered here either (§3).
    canPin: Boolean,
    onReorder: () -> Unit,
    onTogglePin: () -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        add(SheetAction(stringResource(R.string.sections_menu_reorder), icon = Icons.Filled.SwapVert) { onDismiss(); onReorder() })
        if (canPin) {
            add(
                SheetAction(
                    stringResource(if (target.pinned) R.string.sections_menu_pin_off else R.string.sections_menu_pin),
                    icon = Icons.Filled.Key,
                ) { onDismiss(); onTogglePin() },
            )
        }
        add(SheetAction(stringResource(R.string.sections_menu_new), icon = Icons.Filled.Add) { onDismiss(); onNew() })
        if (target.user) {
            add(SheetAction(stringResource(R.string.sections_menu_rename), icon = Icons.Filled.Edit) { onDismiss(); onRename() })
            add(SheetAction(stringResource(R.string.sections_menu_delete), destructive = true, icon = Icons.Filled.Delete) { onDismiss(); onDelete() })
        }
    }
    RcqSheet(onDismiss = onDismiss, title = target.title) {
        // The one sentence, always in front of the toggle rather than behind a
        // confirmation: a user who reads only the menu still reads it.
        if (canPin) {
            Text(
                stringResource(R.string.sections_pin_note),
                color = RcqTheme.colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        actions.forEach { a -> SectionSheetRow(a) }
        SectionSheetRow(SheetAction(stringResource(R.string.common_cancel), dimmed = true, onClick = onDismiss))
    }
}

/** [SheetAction] rendered the way `Sheets.kt` renders one; that helper is
 *  private to its file and this is the same row, not a second look. */
@Composable
private fun SectionSheetRow(a: SheetAction) {
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
        if (a.icon != null) Icon(a.icon!!, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(a.label, color = tint, fontSize = 16.sp)
    }
}

/** Create a section, or rename one. Clamped to 32 Unicode SCALARS on entry:
 *  the pinned-message 422 of 22.08 was a slot measured in one unit and filled
 *  in another, on all three clients at once. */
@Composable
internal fun SectionNameSheet(
    title: String,
    initial: String,
    saveLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    RcqSheet(onDismiss = onDismiss, title = title) {
        RcqField(
            value = name,
            // ⚠ limitName, not clampName: the trim belongs on save. Trimming
            // per keystroke eats the space as it is typed and a two-word
            // section name becomes impossible to enter.
            onValueChange = { name = Sections.limitName(it) },
            placeholder = stringResource(R.string.sections_new_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        SectionSheetRow(
            SheetAction(saveLabel, icon = Icons.Filled.Check) {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) onSave(trimmed)
                onDismiss()
            },
        )
        SectionSheetRow(SheetAction(stringResource(R.string.common_cancel), dimmed = true, onClick = onDismiss))
    }
}

/**
 * The PIN gate in front of a section.
 *
 * Uses the side-effect-free real-PIN verify ([PanicPinService.verifyRealPin]):
 * no dataKey swap, no decoy routing, no wipe. A device with no PIN configured
 * cannot honour the flag another device set, so it says so in one line and
 * offers to open anyway rather than pretending to check something.
 */
@Composable
internal fun SectionPinSheet(
    title: String,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    val context = LocalContext.current
    val configured = remember { PanicPinService.isConfigured(context) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    fun submit() {
        if (PanicPinService.verifyRealPin(context, pin)) {
            onUnlocked()
            onDismiss()
        } else {
            wrong = true
            pin = ""
        }
    }

    RcqSheet(onDismiss = onDismiss, title = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Key, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.sections_locked_title), color = c.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (!configured) {
            Text(stringResource(R.string.sections_locked_nopin), color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            SectionSheetRow(
                SheetAction(stringResource(R.string.sections_locked_open_anyway)) { onUnlocked(); onDismiss() },
            )
        } else {
            RcqField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12); wrong = false },
                placeholder = stringResource(R.string.sections_locked_enter),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sections_locked_wrong), color = Color(0xFFE5484D), fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            SectionSheetRow(SheetAction(stringResource(R.string.sections_locked_open), icon = Icons.Filled.Check) { submit() })
        }
        SectionSheetRow(SheetAction(stringResource(R.string.common_cancel), dimmed = true, onClick = onDismiss))
    }
}

/**
 * The plus button's sheet: tick the chats that belong in this section.
 *
 * ⚠ It hands back what the USER did, the keys they ticked and the keys they
 * unticked, both relative to the membership the sheet OPENED on, and never
 * "the membership is now exactly this list". Its checkboxes are seeded once and
 * the tree moves under an open sheet (the desktop files a chat into the same
 * section, the nudge folds it into the cache): diffing the sheet's list against
 * the tree as it stands on save turns a row the user never touched into a
 * removal, with a tombstone newer than the other device's add, and the merge
 * then keeps the undo without telling anybody.
 */
@Composable
internal fun SectionPickerSheet(
    sectionName: String,
    candidates: List<SectionCandidate>,
    /// Membership as it was when the sheet opened. Seeded once, on purpose.
    initial: Set<String>,
    onSave: (added: List<String>, removed: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RcqTheme.colors
    // ⚠ The BASELINE is frozen with the checkboxes, not read again on save.
    // Both have to be the same snapshot or the diff below stops describing what
    // the user did: if the tree moves under an open sheet, a row nobody touched
    // shows up as a removal.
    val baseline = remember { initial }
    var picked by remember { mutableStateOf(initial) }
    RcqSheet(onDismiss = onDismiss, title = stringResource(R.string.sections_picker_title, sectionName)) {
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.sections_picker_nobody), color = c.textSecondary, fontSize = 14.sp)
        } else {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { cand ->
                    val on = cand.key in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { picked = if (on) picked - cand.key else picked + cand.key }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        cand.avatar()
                        Column(Modifier.weight(1f)) {
                            Text(cand.title, color = c.textPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(cand.subtitle, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(if (on) c.accent else c.bgPrimary),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionSheetRow(
            SheetAction(stringResource(R.string.sections_picker_save), icon = Icons.Filled.Check) {
                onSave((picked - baseline).toList(), (baseline - picked).toList())
                onDismiss()
            },
        )
        SectionSheetRow(SheetAction(stringResource(R.string.common_cancel), dimmed = true, onClick = onDismiss))
    }
}

/** The empty hint under a user section. An empty user section still renders,
 *  header, plus button and all: the user made it on purpose. This is where it
 *  differs from Archive and Favorites, which hide when empty. */
@Composable
internal fun SectionEmptyHint() {
    Text(
        stringResource(R.string.sections_empty),
        color = RcqTheme.colors.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** The bar that says the list is in reorder mode, with the way out. */
@Composable
internal fun SectionReorderBar(onDone: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SwapVert, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.sections_reorder_hint), color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.sections_reorder_done),
            color = c.accent,
            fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The generic group glyph, for a picker row that has no avatar of its own. */
@Composable
internal fun SectionGroupGlyph() {
    val c = RcqTheme.colors
    Box(Modifier.size(30.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}
