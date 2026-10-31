package dev.kuml.desktop.workspace

import dev.kuml.workspace.pdf.ExportMetadata
import dev.kuml.workspace.pdf.ExportScope
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy
import dev.kuml.workspace.pdf.PdfExportKind
import dev.kuml.workspace.pdf.PdfExportOptions
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The three export presets offered at the top of the dialog. */
enum class PdfPreset(
    val kind: PdfExportKind,
) {
    HANDBOOK(kind = PdfExportKind.HANDBOOK),
    PER_DOCUMENT(kind = PdfExportKind.PER_DOCUMENT),
    DIAGRAMS(kind = PdfExportKind.DIAGRAMS),
}

/** Options that can be switched in the dialog; used to explain why one is unavailable. */
enum class PdfOption { COVER, TOC, HEADER_FOOTER, DRAFT, SPLIT, SCOPE_FOLDER, ORIENTATION }

/** An option is either usable or shown disabled together with the key of its explanation (never silently hidden). */
sealed interface PdfOptionAvailability {
    data object Enabled : PdfOptionAvailability

    data class Disabled(
        val reasonKey: PdfReason,
    ) : PdfOptionAvailability
}

enum class PdfReason { TOC_FOR_DIAGRAMS, COVER_FOR_DIAGRAMS, SPLIT_NOT_DIAGRAMS, ORIENTATION_BY_PAGE_STRATEGY }

/** Pure, testable state of the PDF export dialog. */
data class PdfExportFormState(
    val preset: PdfPreset = PdfPreset.HANDBOOK,
    val folderScope: Boolean = false,
    val folder: String = "",
    val page: PageFormat = PageFormat.A4,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val pageStrategy: PageStrategy = PageStrategy.AUTO,
    val tiling: Boolean = true,
    /** Minimum diagram scale in percent, 25..100 in steps of 5. */
    val minScalePercent: Int = DEFAULT_MIN_SCALE_PERCENT,
    val cover: Boolean = true,
    val toc: Boolean = true,
    val headerFooter: Boolean = true,
    val draft: Boolean = false,
    val split: Boolean = false,
    val author: String = "",
    val version: String = "",
    val date: String = "",
    val advancedExpanded: Boolean = false,
) {
    /** Switching the preset applies that preset's sensible defaults (per-document: no cover). */
    fun withPreset(p: PdfPreset): PdfExportFormState =
        copy(preset = p, cover = p == PdfPreset.HANDBOOK, split = if (p == PdfPreset.DIAGRAMS) split else false)

    /** Automatic page size lets kUML choose the orientation per diagram, so the orientation is reset to automatic. */
    fun withPageStrategy(strategy: PageStrategy): PdfExportFormState =
        copy(pageStrategy = strategy, orientation = if (strategy == PageStrategy.AUTO) PageOrientation.AUTO else orientation)

    fun withMinScalePercent(percent: Int): PdfExportFormState =
        copy(minScalePercent = ((percent.coerceIn(MIN_SCALE_PERCENT_RANGE.first, MIN_SCALE_PERCENT_RANGE.last)) / 5) * 5)

    /** Whether [date] is empty or a valid ISO-8601 date. */
    val dateValid: Boolean
        get() = date.isBlank() || runCatching { LocalDate.parse(date.trim()) }.isSuccess

    val folderValid: Boolean get() = !folderScope || (folder.isNotBlank() && !folder.contains(".."))

    val canSubmit: Boolean get() = dateValid && folderValid

    fun availability(option: PdfOption): PdfOptionAvailability =
        when (option) {
            PdfOption.TOC ->
                if (preset ==
                    PdfPreset.DIAGRAMS
                ) {
                    PdfOptionAvailability.Disabled(PdfReason.TOC_FOR_DIAGRAMS)
                } else {
                    PdfOptionAvailability.Enabled
                }
            PdfOption.COVER ->
                if (preset ==
                    PdfPreset.DIAGRAMS
                ) {
                    PdfOptionAvailability.Disabled(PdfReason.COVER_FOR_DIAGRAMS)
                } else {
                    PdfOptionAvailability.Enabled
                }
            PdfOption.SPLIT ->
                if (preset !=
                    PdfPreset.DIAGRAMS
                ) {
                    PdfOptionAvailability.Disabled(PdfReason.SPLIT_NOT_DIAGRAMS)
                } else {
                    PdfOptionAvailability.Enabled
                }
            PdfOption.ORIENTATION ->
                if (pageStrategy == PageStrategy.AUTO) {
                    PdfOptionAvailability.Disabled(PdfReason.ORIENTATION_BY_PAGE_STRATEGY)
                } else {
                    PdfOptionAvailability.Enabled
                }
            PdfOption.HEADER_FOOTER, PdfOption.DRAFT, PdfOption.SCOPE_FOLDER -> PdfOptionAvailability.Enabled
        }

    fun toOptions(producerVersion: String): PdfExportOptions =
        PdfExportOptions(
            kind = preset.kind,
            scope = if (folderScope) ExportScope.Folder(relativePath = folder.trim()) else ExportScope.All,
            page = page,
            orientation = if (pageStrategy == PageStrategy.AUTO) PageOrientation.AUTO else orientation,
            pageStrategy = pageStrategy,
            tiling = tiling,
            minScale = (minScalePercent.coerceIn(MIN_SCALE_PERCENT_RANGE.first, MIN_SCALE_PERCENT_RANGE.last) / 100f),
            cover = cover && availability(PdfOption.COVER) == PdfOptionAvailability.Enabled,
            toc = toc && availability(PdfOption.TOC) == PdfOptionAvailability.Enabled,
            headerFooter = headerFooter,
            draft = draft,
            split = split && preset == PdfPreset.DIAGRAMS,
            metadata =
                ExportMetadata(
                    author = author.trim().ifEmpty { null },
                    version = version.trim().ifEmpty { null },
                    date =
                        try {
                            date.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                        } catch (_: DateTimeParseException) {
                            null
                        },
                ),
            producerVersion = producerVersion,
        )

    companion object {
        const val DEFAULT_MIN_SCALE_PERCENT = 50
        val MIN_SCALE_PERCENT_RANGE = 25..100
    }
}
