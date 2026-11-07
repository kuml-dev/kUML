package dev.kuml.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * What a dialog button does. The icon is chosen from the action, never passed in directly, so
 * the same action always carries the same symbol (design review: one meaning, one symbol).
 *
 * The exhaustive `when` in [buttonIcon] forces every new value to get an icon. The dialog-button
 * coverage test checks the whole desktop module (parts b2a and b2b).
 */
enum class ButtonAction {
    CLOSE,
    CANCEL,
    DECLINE,
    DISMISS,
    CONFIRM,
    PROCEED_RISKY,
    COPY,
    REGENERATE,
    RELOAD,
    OPEN_LICENSES,
    USE_A3,
    EXPORT_PDF,
    EXPORT_LATEX,
    BROWSE,
    CREATE_WORKSPACE,
    SAVE,
    UNDO,
    ACCEPT,
    REJECT,
    ACKNOWLEDGE,
    MANAGE_PROVIDERS,
    NEW_SESSION,
    INSPECT,
    EDIT,
    DELETE,
    SEND,
    ADD,
}

/** The symbol for [action]. */
fun buttonIcon(action: ButtonAction): ImageVector =
    when (action) {
        ButtonAction.CLOSE, ButtonAction.CANCEL, ButtonAction.DECLINE, ButtonAction.DISMISS -> KumlIcons.Close
        ButtonAction.CONFIRM -> KumlIcons.Check
        ButtonAction.PROCEED_RISKY -> KumlIcons.Warning
        ButtonAction.COPY -> KumlIcons.Copy
        ButtonAction.REGENERATE, ButtonAction.RELOAD -> KumlIcons.Refresh
        ButtonAction.OPEN_LICENSES -> KumlIcons.Document
        ButtonAction.USE_A3 -> KumlIcons.PageLandscape
        ButtonAction.EXPORT_PDF -> KumlIcons.PdfDocument
        ButtonAction.EXPORT_LATEX -> KumlIcons.SaveTex
        ButtonAction.BROWSE -> KumlIcons.FolderOpen
        ButtonAction.CREATE_WORKSPACE -> KumlIcons.WorkspaceNew
        ButtonAction.SAVE -> KumlIcons.Save
        ButtonAction.UNDO -> KumlIcons.Undo
        ButtonAction.ACCEPT, ButtonAction.ACKNOWLEDGE -> KumlIcons.Check
        ButtonAction.REJECT -> KumlIcons.Reject
        ButtonAction.MANAGE_PROVIDERS -> KumlIcons.Settings
        ButtonAction.NEW_SESSION -> KumlIcons.ChatNew
        ButtonAction.INSPECT -> KumlIcons.Search
        ButtonAction.EDIT -> KumlIcons.Edit
        ButtonAction.DELETE -> KumlIcons.Delete
        ButtonAction.SEND -> KumlIcons.Send
        ButtonAction.ADD -> KumlIcons.Add
    }

/**
 * Reasons a dialog button may legitimately have no icon. A button without [IconLabel] must carry a
 * `// no-icon: <NAME>` comment directly above it, where NAME is one of these values.
 */
enum class ButtonIconException(
    val reason: String,
) {
    CHOICE_SAME_KIND("Both buttons would get the same symbol and could no longer be told apart"),
    DYNAMIC_EVENT("Dynamic event names from the model, no stable symbol"),
    STEPPER("Counter minus/plus: the sign is already the symbol"),
    DISCLOSURE("Disclosure triangle: the state is already in the text"),
    MENU_TRIGGER("Opens a menu or list in place; the shown value or ellipsis is the affordance"),
    ROW_INLINE("Repeated in every row of a dense 11 sp list; an icon per row adds noise, not meaning"),
}

/**
 * Tint for the icon. Material 3 [Icon] applies no tint at all for [Color.Unspecified] (the default
 * from `LocalContentColor` only applies when the parameter is omitted), so an unspecified
 * [contentColor] must be resolved to the ambient [localContentColor] here.
 */
internal fun resolveIconTint(
    contentColor: Color,
    localContentColor: Color,
): Color = if (contentColor == Color.Unspecified) localContentColor else contentColor

/**
 * Button content: icon before the label. The tint comes from `LocalContentColor`, so disabled
 * buttons stay dimmed. The icon is decorative (`contentDescription = null`); the text is the label.
 * With [showAsDone] a [ButtonAction.COPY] button shows a check mark instead of the copy symbol.
 * [contentColor] stays unspecified in Material 3 buttons; inside a Material 2 button (which does not
 * feed the Material 3 `LocalContentColor`) the caller passes the button's own content colour.
 */
@Composable
fun IconLabel(
    action: ButtonAction,
    text: String,
    compact: Boolean = false,
    showAsDone: Boolean = false,
    fontSize: TextUnit = TextUnit.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val icon = if (showAsDone && action == ButtonAction.COPY) KumlIcons.Check else buttonIcon(action)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
            tint = resolveIconTint(contentColor = contentColor, localContentColor = LocalContentColor.current),
        )
        Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = fontSize, color = contentColor)
    }
}
