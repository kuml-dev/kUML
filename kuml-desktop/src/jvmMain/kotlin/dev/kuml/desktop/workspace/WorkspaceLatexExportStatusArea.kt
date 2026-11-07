package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kuml.cli.workspace.LatexReportLine
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.ui.ButtonAction
import dev.kuml.desktop.ui.IconLabel

/** The protocol shown in the banner is capped; "Copy report" carries every line. */
private const val MAX_SHOWN_LINES = 40

/** Presentation of a [LatexStatusView]: strings and callbacks only, no decisions. */
@Composable
internal fun WorkspaceLatexStatusArea(
    view: LatexStatusView,
    strings: Strings,
    canOpenFolder: Boolean,
    onShowFolder: () -> Unit,
    onCopyReport: () -> Unit,
    onOverwrite: () -> Unit,
    onKeepExisting: () -> Unit,
) {
    val s = strings.workspaceLatexExport
    when (view) {
        LatexStatusView.Idle ->
            Text(s.idleHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is LatexStatusView.Working ->
            Column {
                Text(if (view.writing) s.writing else s.preparing, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        LatexStatusView.Cancelled -> StatusBanner(tone = view.tone(), headline = s.cancelled)
        is LatexStatusView.Overwrite ->
            StatusBanner(
                tone = view.tone(),
                headline = s.overwriteHeadline.format(view.count, view.names.joinToString()),
            ) {
                Button(onClick = onOverwrite) { IconLabel(action = ButtonAction.PROCEED_RISKY, text = s.overwriteButton) }
                TextButton(onClick = onKeepExisting) { IconLabel(action = ButtonAction.CANCEL, text = s.keepExisting) }
            }
        is LatexStatusView.StrictBlocked ->
            StatusBanner(
                tone = view.tone(),
                headline = s.strictBlockedHeadline,
                details = shownLines(lines = view.lines, moreFormat = s.moreLines),
            )
        is LatexStatusView.NothingToExport ->
            StatusBanner(
                tone = view.tone(),
                headline = s.nothingToExport,
                details = shownLines(lines = view.lines, moreFormat = s.moreLines),
            )
        is LatexStatusView.Done ->
            StatusBanner(
                tone = view.tone(),
                headline = s.doneHeadline.format(view.outDir),
                details =
                    buildList {
                        addAll(view.summary)
                        view.openError?.let { add(s.openFolderFailed.format(it)) }
                        addAll(shownLines(lines = view.lines, moreFormat = s.moreLines))
                    },
            ) {
                if (canOpenFolder) {
                    Button(onClick = onShowFolder) { IconLabel(action = ButtonAction.BROWSE, text = s.showFolder) }
                }
                TextButton(onClick = onCopyReport) { IconLabel(action = ButtonAction.COPY, text = s.copyReport) }
            }
        is LatexStatusView.Failed ->
            StatusBanner(
                tone = view.tone(),
                headline =
                    when {
                        view.message == null -> s.busy
                        view.ioClass != null -> s.failedHeadline.format(s.ioError.format(view.ioClass))
                        else -> s.failedHeadline.format(view.message)
                    },
            )
    }
}

/** The protocol lines for the banner: at most [MAX_SHOWN_LINES], then a note how many are hidden. */
internal fun shownLines(
    lines: List<LatexReportLine>,
    moreFormat: String,
): List<String> {
    val shown = lines.take(MAX_SHOWN_LINES).map { it.text }
    val hidden = lines.size - shown.size
    return if (hidden > 0) shown + moreFormat.format(hidden) else shown
}
