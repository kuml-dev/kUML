package dev.kuml.workspace.pdf.labels

import dev.kuml.workspace.pdf.PdfExportKind
import dev.kuml.workspace.pdf.PdfLanguage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fixed texts of generated PDF parts, German and English. */
internal class PdfLabels(
    val lang: PdfLanguage,
) {
    private fun t(
        de: String,
        en: String,
    ) = if (lang == PdfLanguage.DE) de else en

    val contents = t(de = "Inhaltsverzeichnis", en = "Contents")
    val glossary = t(de = "Glossar", en = "Glossary")
    val openItems = t(de = "Offene Punkte", en = "Open items")
    val traceability = t(de = "Rückverfolgbarkeit", en = "Traceability")
    val elements = t(de = "Elemente", en = "Elements")
    val furtherDocuments = t(de = "Weitere Dokumente", en = "Further documents")
    val aiDraft = t(de = "KI-Entwurf", en = "AI draft")
    val draftMark = t(de = "ENTWURF", en = "DRAFT")
    val notInExport = t(de = "nicht im Export", en = "not in export")
    val source = t(de = "Quelle", en = "Source")
    val figure = t(de = "Abbildung", en = "Figure")
    val requirement = t(de = "Anforderung", en = "Requirement")
    val satisfiedBy = t(de = "erfüllt durch (satisfy)", en = "satisfied by (satisfy)")
    val verifiedBy = t(de = "verifiziert durch (verify)", en = "verified by (verify)")
    val derivedFrom = t(de = "abgeleitet von (derive)", en = "derived from (derive)")
    val diagramFailed = t(de = "Diagramm konnte nicht gerendert werden (OKF-E-007)", en = "Diagram could not be rendered (OKF-E-007)")
    val kind = t(de = "Art", en = "Kind")
    val name = t(de = "Name", en = "Name")
    val detail = t(de = "Detail", en = "Detail")
    val truncatedNote = t(de = "Tabelle gekürzt.", en = "Table truncated.")
    val version = t(de = "Version", en = "Version")
    val status = t(de = "Status", en = "Status")

    val author = t(de = "Autor", en = "Author")
    val organization = t(de = "Organisation", en = "Organization")
    val dateLabel = t(de = "Stand", en = "Date")
    val exportedOn = t(de = "Exportiert am", en = "Exported on")
    val documents = t(de = "Dokumente", en = "Documents")
    val enableTiling = t(de = "Kacheln aktivieren", en = "enable tiling")
    val splitDiagram = t(de = "Diagramm aufteilen", en = "split the diagram")

    private val numberLocale: Locale get() = if (lang == PdfLanguage.DE) Locale.GERMAN else Locale.ROOT

    fun createdWith(producer: String) = if (lang == PdfLanguage.DE) "Erstellt mit $producer" else "Created with $producer"

    fun subject(kind: PdfExportKind) =
        when (kind) {
            PdfExportKind.HANDBOOK -> t(de = "Handbuch", en = "Handbook")
            PdfExportKind.PER_DOCUMENT -> t(de = "Dokumente", en = "Documents")
            PdfExportKind.DIAGRAMS -> t(de = "Diagramme", en = "Diagrams")
        }

    fun tileSuffix(
        i: Int,
        n: Int,
        row: Int,
        col: Int,
    ) = if (lang == PdfLanguage.DE) " — Teil $i von $n · Zeile $row, Spalte $col" else " — Part $i of $n · row $row, column $col"

    private fun num(v: Double) = String.format(numberLocale, "%.2f", v)

    fun illegible(
        scale: Double,
        min: Double,
    ) = if (lang == PdfLanguage.DE) "unleserlich (Skala ${num(scale)} < ${num(min)})" else "illegible (scale ${num(scale)} < ${num(min)})"

    fun formatDate(d: LocalDate): String =
        if (lang == PdfLanguage.DE) {
            DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN).format(d)
        } else {
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(d)
        }

    fun page(
        n: Int,
        total: Int,
    ) = if (lang == PdfLanguage.DE) "Seite $n von $total" else "Page $n of $total"

    fun openItemsLine(
        count: Int,
        page: Int,
    ) = if (lang == PdfLanguage.DE) "Offene Punkte: $count (Seite $page)" else "Open items: $count (page $page)"
}
