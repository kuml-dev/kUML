package dev.kuml.desktop.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.IconTooltipButton
import dev.kuml.desktop.ui.KumlIcons

/**
 * Inline incremental find bar (P8, design review) — a slim [Row] BELOW the editor, not an
 * overlay on top of it: [dev.kuml.desktop.editor.EditorPane]'s `SwingPanel` is heavyweight AWT
 * and always paints over lightweight Compose layers, so a Compose overlay would be invisible
 * (same reasoning as `MainWindow`'s StatusBar comment).
 *
 * No dialog, no modal — the editor stays interactive and visible while searching, matching
 * every mainstream code editor's find-in-file behaviour rather than the CLI-era Swing
 * `FindDialog` (which this codebase deliberately does not use — it isn't even present in this
 * project's `rsyntaxtextarea` artifact; see the plan's note).
 *
 * V3.7.5 (review fix): the prev/next/close buttons are [IconTooltipButton] (kUML's shared
 * compact-icon-button component, [KumlIcons.ChevronUp]/[KumlIcons.ChevronDown]/[KumlIcons.Close])
 * instead of a second, parallel `TooltipArea` + un-sized `IconButton` implementation that never
 * actually got the 32/18 compact sizing the V3.7.4 CHANGELOG entry claimed for this bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FindBar(
    actions: EditorActions?,
    strings: Strings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var noMatch by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // V3.7.5 (review fix) — `advance` distinguishes a pure query/flag refinement (typing,
    // match-case toggle: re-search from the fixed anchor, never move it) from an explicit
    // next/previous navigation (Enter/Shift+Enter, the buttons below: search from the
    // CURRENTLY highlighted match's own boundary). See EditorFindController's KDoc — conflating
    // the two used to both skip matches while typing and make "previous" never move backward.
    fun runFind(
        forward: Boolean,
        advance: Boolean,
    ) {
        val found = actions?.find?.invoke(query, forward, matchCase, advance) ?: true
        noMatch = query.isNotEmpty() && !found
    }

    LaunchedEffect(Unit) {
        actions?.beginFind?.invoke()
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { newQuery ->
                query = newQuery
                if (newQuery.isEmpty()) {
                    noMatch = false
                } else {
                    runFind(forward = true, advance = false)
                }
            },
            placeholder = { Text(strings.findPlaceholder) },
            singleLine = true,
            isError = noMatch,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("kuml-find-field")
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Enter && event.isShiftPressed -> {
                                runFind(forward = false, advance = true)
                                true
                            }
                            event.key == Key.Enter -> {
                                runFind(forward = true, advance = true)
                                true
                            }
                            event.key == Key.Escape -> {
                                actions?.endFind?.invoke()
                                onClose()
                                true
                            }
                            else -> false
                        }
                    },
            colors =
                if (noMatch) {
                    OutlinedTextFieldDefaults.colors(
                        errorContainerColor = MaterialTheme.colorScheme.errorContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        focusedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                },
        )
        Spacer(Modifier.width(4.dp))
        IconTooltipButton(
            icon = KumlIcons.ChevronUp,
            description = strings.findPrevious,
            onClick = { runFind(forward = false, advance = true) },
        )
        IconTooltipButton(
            icon = KumlIcons.ChevronDown,
            description = strings.findNext,
            onClick = { runFind(forward = true, advance = true) },
        )
        Spacer(Modifier.width(4.dp))
        Checkbox(checked = matchCase, onCheckedChange = {
            matchCase = it
            runFind(forward = true, advance = false)
        })
        Text(strings.findMatchCase, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        IconTooltipButton(
            icon = KumlIcons.Close,
            description = strings.findClose,
            onClick = {
                actions?.endFind?.invoke()
                onClose()
            },
        )
        if (noMatch) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = strings.findNoMatch,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
