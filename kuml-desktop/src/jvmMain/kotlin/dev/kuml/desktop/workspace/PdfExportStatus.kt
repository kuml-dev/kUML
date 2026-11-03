package dev.kuml.desktop.workspace

import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy

/** Visual weight of a status banner. */
internal enum class StatusTone { NEUTRAL, WARNING, ERROR, SUCCESS }

/** Placement summary of a check run: diagrams set on A3 and split diagrams. */
internal data class LayoutNote(
    val a3Diagrams: Int,
    val tiledDiagrams: Int,
    val tilePages: Int,
)

/** What the fixed status area of the PDF dialog shows; pure data, no strings and no Compose. */
internal sealed interface PdfStatusView {
    data object Idle : PdfStatusView

    /** [total] is 0 while the number of documents is not yet known (indeterminate progress). */
    data class Checking(
        val done: Int,
        val total: Int,
    ) : PdfStatusView

    data class Warning(
        val failureCount: Int,
        val scaleCount: Int,
        val failureSample: List<String>,
        val scaleSample: List<String>,
        val hiddenCount: Int,
        val layoutNote: LayoutNote?,
        val offerA3Landscape: Boolean,
        val hintMinScale: Boolean,
    ) : PdfStatusView

    /** [count] is 0 for a single-file target, otherwise the number of existing files in the target directory. */
    data class Overwrite(
        val count: Int,
        val names: List<String>,
    ) : PdfStatusView

    data class Done(
        val path: String,
    ) : PdfStatusView

    /** [message] is null when the renderer is merely busy; such a failure is transient and not an error. */
    data class Failed(
        val message: String?,
        val retryable: Boolean,
    ) : PdfStatusView

    fun tone(): StatusTone =
        when (this) {
            Idle, is Checking -> StatusTone.NEUTRAL
            is Warning -> if (failureCount > 0) StatusTone.ERROR else StatusTone.WARNING
            is Overwrite -> StatusTone.WARNING
            is Done -> StatusTone.SUCCESS
            is Failed -> if (message == null) StatusTone.WARNING else StatusTone.ERROR
        }

    /** Whether the main button must wait because the status asks for a decision first. */
    fun blocksPrimary(): Boolean = this is Warning || this is Overwrite
}

private const val SAMPLE_SIZE = 3
private const val OVERWRITE_SAMPLE_SIZE = 5

internal fun pdfStatusView(
    ui: PdfUiState,
    form: PdfExportFormState,
): PdfStatusView =
    when (ui) {
        PdfUiState.Idle -> PdfStatusView.Idle
        is PdfUiState.Preparing -> PdfStatusView.Checking(done = ui.done, total = ui.total)
        is PdfUiState.Warnings -> warningView(ui = ui, form = form)
        is PdfUiState.ConfirmOverwrite -> PdfStatusView.Overwrite(count = ui.existing.size, names = ui.existing.take(OVERWRITE_SAMPLE_SIZE))
        is PdfUiState.Done -> PdfStatusView.Done(path = ui.path)
        is PdfUiState.Failed -> PdfStatusView.Failed(message = ui.message, retryable = true)
        PdfUiState.Busy -> PdfStatusView.Failed(message = null, retryable = true)
    }

private fun warningView(
    ui: PdfUiState.Warnings,
    form: PdfExportFormState,
): PdfStatusView.Warning {
    val scaleSample =
        ui.warnings.take(SAMPLE_SIZE).mapIndexed { i, w ->
            val ref = "${w.documentPath}#${w.blockIndex + 1}"
            // The explanation is only attached to the first entry to keep the banner short.
            if (i == 0 && w.message.isNotBlank()) "$ref (${w.message})" else ref
        }
    val summary = ui.summary
    val hasScale = ui.warnings.isNotEmpty()
    val isA3Landscape = form.page == PageFormat.A3 && form.orientation == PageOrientation.LANDSCAPE
    return PdfStatusView.Warning(
        failureCount = ui.failures.size,
        scaleCount = ui.warnings.size,
        failureSample = ui.failures.take(SAMPLE_SIZE).map { "${it.file}:${it.line}" },
        scaleSample = scaleSample,
        hiddenCount = (ui.failures.size - SAMPLE_SIZE).coerceAtLeast(0) + (ui.warnings.size - SAMPLE_SIZE).coerceAtLeast(0),
        layoutNote =
            if (summary.a3Diagrams == 0 && summary.tiledDiagrams == 0) {
                null
            } else {
                LayoutNote(a3Diagrams = summary.a3Diagrams, tiledDiagrams = summary.tiledDiagrams, tilePages = summary.tilePages)
            },
        offerA3Landscape = hasScale && form.pageStrategy == PageStrategy.FIXED && !isA3Landscape,
        hintMinScale = hasScale && form.pageStrategy == PageStrategy.AUTO,
    )
}

/** The "use A3 landscape" action: a fixed A3 landscape page. */
internal fun PdfExportFormState.withA3Landscape(): PdfExportFormState =
    copy(page = PageFormat.A3, pageStrategy = PageStrategy.FIXED, orientation = PageOrientation.LANDSCAPE)
