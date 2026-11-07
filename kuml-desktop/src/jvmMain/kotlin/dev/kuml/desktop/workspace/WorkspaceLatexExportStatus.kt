package dev.kuml.desktop.workspace

import dev.kuml.cli.workspace.LatexReportLine
import dev.kuml.cli.workspace.LatexRunCounts
import java.io.File

/** Where the "Export Workspace > As LaTeX…" dialog is in its flow. Pure data: no strings, no Compose. */
internal sealed interface LatexUiState {
    data object Idle : LatexUiState

    data object Preparing : LatexUiState

    /** The files are being written; cancelling is no longer possible. */
    data object Writing : LatexUiState

    /** The shared evaluation gate was not free in time (an MCP render runs); transient, not an error. */
    data object Busy : LatexUiState

    data object Cancelled : LatexUiState

    /** [existing] are the relative paths that exist and would be replaced. */
    data class ConfirmOverwrite(
        val existing: List<String>,
    ) : LatexUiState

    /** `--strict` found something and refused; [lines] is the issue protocol. */
    data class StrictBlocked(
        val lines: List<LatexReportLine>,
        val counts: LatexRunCounts,
    ) : LatexUiState

    /** Not a single figure could be produced. */
    data class NothingToExport(
        val lines: List<LatexReportLine>,
        val counts: LatexRunCounts,
    ) : LatexUiState

    /** [summary] are the two lines the CLI prints after a write; [openError] is set when "Show folder" failed. */
    data class Done(
        val outDir: File,
        val summary: List<String>,
        val lines: List<LatexReportLine>,
        val counts: LatexRunCounts,
        val openError: String? = null,
    ) : LatexUiState

    /** [message] is already terminal-safe; [ioClass] is set for an I/O error, whose text is built from the class name. */
    data class Failed(
        val message: String,
        val ioClass: String? = null,
    ) : LatexUiState
}

/** The status area's content; pure data, strings are added by the composable. */
internal sealed interface LatexStatusView {
    data object Idle : LatexStatusView

    data class Working(
        val writing: Boolean,
    ) : LatexStatusView

    data class Overwrite(
        val count: Int,
        val names: List<String>,
    ) : LatexStatusView

    data class StrictBlocked(
        val lines: List<LatexReportLine>,
        val counts: LatexRunCounts,
    ) : LatexStatusView

    data class NothingToExport(
        val lines: List<LatexReportLine>,
    ) : LatexStatusView

    data class Done(
        val outDir: String,
        val summary: List<String>,
        val lines: List<LatexReportLine>,
        val counts: LatexRunCounts,
        val openError: String?,
    ) : LatexStatusView

    /** [message] is null for the transient busy case. */
    data class Failed(
        val message: String?,
        val ioClass: String?,
    ) : LatexStatusView

    data object Cancelled : LatexStatusView

    fun tone(): StatusTone =
        when (this) {
            Idle, is Working, Cancelled -> StatusTone.NEUTRAL
            is Overwrite -> StatusTone.WARNING
            is StrictBlocked, is NothingToExport -> StatusTone.ERROR
            is Done -> doneTone(counts)
            is Failed -> if (message == null) StatusTone.WARNING else StatusTone.ERROR
        }

    /** Whether the main button must wait because the status asks for a decision first. */
    fun blocksPrimary(): Boolean = this is Overwrite || this is Working

    /** A cancel button is useful only while preparing, never while writing. */
    fun canCancel(): Boolean = this is Working && !writing
}

/** Green only if nothing but NOTEs was reported; amber for skipped/excluded/warnings; red if a diagram failed. */
internal fun doneTone(counts: LatexRunCounts): StatusTone =
    when {
        counts.failed > 0 -> StatusTone.ERROR
        counts.skipped > 0 || counts.excluded > 0 || (counts.warnings ?: 0) > 0 -> StatusTone.WARNING
        else -> StatusTone.SUCCESS
    }

private const val OVERWRITE_SAMPLE_SIZE = 5

internal fun latexStatusView(ui: LatexUiState): LatexStatusView =
    when (ui) {
        LatexUiState.Idle -> LatexStatusView.Idle
        LatexUiState.Preparing -> LatexStatusView.Working(writing = false)
        LatexUiState.Writing -> LatexStatusView.Working(writing = true)
        LatexUiState.Busy -> LatexStatusView.Failed(message = null, ioClass = null)
        LatexUiState.Cancelled -> LatexStatusView.Cancelled
        is LatexUiState.ConfirmOverwrite ->
            LatexStatusView.Overwrite(count = ui.existing.size, names = ui.existing.take(OVERWRITE_SAMPLE_SIZE))
        is LatexUiState.StrictBlocked -> LatexStatusView.StrictBlocked(lines = ui.lines, counts = ui.counts)
        is LatexUiState.NothingToExport -> LatexStatusView.NothingToExport(lines = ui.lines)
        is LatexUiState.Done ->
            LatexStatusView.Done(
                outDir =
                    ui.outDir.path
                        .map { if (Character.isISOControl(it)) ' ' else it }
                        .joinToString(""),
                summary = ui.summary,
                lines = ui.lines,
                counts = ui.counts,
                openError = ui.openError,
            )
        is LatexUiState.Failed -> LatexStatusView.Failed(message = ui.message, ioClass = ui.ioClass)
    }

/** The text "Copy report" puts on the clipboard: the summary lines, then the issue protocol. */
internal fun latexReportText(done: LatexUiState.Done): String = (done.summary + done.lines.map { it.text }).joinToString("\n")

/**
 * "Show folder": opens [done]'s output folder through [openFolder]. A failure is not swallowed: it becomes the
 * banner's [LatexUiState.Done.openError], carrying only the exception's class name (no path or message).
 */
internal fun showLatexFolder(
    done: LatexUiState.Done,
    openFolder: (File) -> Unit,
): LatexUiState.Done =
    try {
        openFolder(done.outDir)
        done.copy(openError = null)
    } catch (e: java.io.IOException) {
        done.copy(openError = e.javaClass.simpleName)
    } catch (e: UnsupportedOperationException) {
        done.copy(openError = e.javaClass.simpleName)
    } catch (e: SecurityException) {
        done.copy(openError = e.javaClass.simpleName)
    } catch (e: IllegalArgumentException) {
        done.copy(openError = e.javaClass.simpleName)
    }
