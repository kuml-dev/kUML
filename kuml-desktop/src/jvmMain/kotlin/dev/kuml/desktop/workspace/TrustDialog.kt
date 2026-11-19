package dev.kuml.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel
import java.io.File

/**
 * "Trust this workspace?" modal (V3.6.4, decision 2).
 *
 * Shown whenever a workspace root's canonical path is not yet in
 * [dev.kuml.desktop.AppState.trustedWorkspaces], **before** any document is
 * selected/rendered — i.e. before any ` ```kuml ` block in the workspace is
 * evaluated. [onDecline] must leave the workspace unopened (return to the
 * previous single-file view); only [onTrust] proceeds to open it.
 *
 * [onTrust] renders as a filled [Button] rather than a [TextButton]: this is a
 * consequential, security-relevant choice (it gates arbitrary script
 * execution), so it gets the same visual weight Material reserves for a
 * primary affirmative action instead of looking equal to [onDecline].
 */
@Composable
fun TrustDialog(
    root: File,
    strings: Strings,
    onTrust: () -> Unit,
    onDecline: () -> Unit,
    requestedByMcp: Boolean = false,
) {
    WindowAlertDialog(
        title = strings.workspaceTrustTitle,
        onDismissRequest = onDecline,
        height = if (requestedByMcp) 380.dp else 300.dp,
        content = {
            // The path is shown sanitised, in its own monospace field, so a hostile directory name can
            // never pose as part of the warning text.
            val template = strings.workspaceTrustMessage
            val lead = (if (requestedByMcp) strings.mcpHint.workspaceTrustRequestedByMcp + "\n\n" else "") + template.substringBefore("%s")
            Column {
                Text(lead.trimEnd())
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    Text(text = sanitizeForDisplay(root.absolutePath), fontFamily = FontFamily.Monospace)
                }
                template.substringAfter("%s", "").takeIf { it.isNotBlank() }?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(onClick = onTrust) { IconLabel(action = ButtonAction.CONFIRM, text = strings.workspaceTrustAccept) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { IconLabel(action = ButtonAction.DECLINE, text = strings.workspaceTrustDecline) }
        },
    )
}

/**
 * True for code points that must never reach a security dialog verbatim: ISO control characters, Unicode
 * format characters (bidi overrides, zero-width joiners, supplementary tag characters U+E0000..) and the
 * line / paragraph separators U+2028 / U+2029 (hard line breaks in text layout). Evaluated per code point,
 * so supplementary-plane characters (whose UTF-16 halves are surrogates) are covered.
 */
internal fun isUnsafeDisplayCodePoint(cp: Int): Boolean {
    if (Character.isISOControl(cp)) return true
    val type = Character.getType(cp)
    return type == Character.FORMAT.toInt() ||
        type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt()
}

/** True when [text] contains at least one code point for which [isUnsafeDisplayCodePoint] holds. */
internal fun hasUnsafeDisplayChars(text: String): Boolean = text.codePoints().anyMatch { isUnsafeDisplayCodePoint(it) }

/**
 * Makes [text] safe to show in a security dialog: unsafe code points (see [isUnsafeDisplayCodePoint]) are
 * replaced by a visible `\uXXXX` escape (`\u{XXXXX}` above the BMP), so the text can neither break the
 * layout nor reorder itself.
 */
internal fun sanitizeForDisplay(text: String): String =
    buildString {
        text.codePoints().forEach { cp ->
            if (isUnsafeDisplayCodePoint(cp)) {
                val hex = cp.toString(16).uppercase()
                if (cp <= 0xFFFF) append("\\u").append(hex.padStart(4, '0')) else append("\\u{").append(hex).append('}')
            } else {
                appendCodePoint(cp)
            }
        }
    }
