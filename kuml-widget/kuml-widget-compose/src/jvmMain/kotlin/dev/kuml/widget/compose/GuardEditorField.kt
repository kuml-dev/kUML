package dev.kuml.widget.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kuml.runtime.defaultGuardScope

/**
 * Public, localizable inline OCL guard editor: syntax highlighting, a debounced live type check
 * against [defaultGuardScope], Save and Cancel. Input is limited to [MAX_GUARD_LENGTH] characters.
 *
 * Purely callback driven; applying the result (and the confirmation flow for protected
 * transitions) is the caller's job, see [changeGuard] and [resolveGuardEditAction].
 */
@Composable
public fun GuardEditorField(
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    labels: GuardEditorLabels = GuardEditorLabels(),
) {
    OclGuardEditor(
        initial = initial,
        scope = defaultGuardScope(),
        onSave = onSave,
        onCancel = onCancel,
        modifier = modifier,
        labels = labels,
    )
}
