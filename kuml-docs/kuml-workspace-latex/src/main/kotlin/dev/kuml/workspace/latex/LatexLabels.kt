package dev.kuml.workspace.latex

import dev.kuml.workspace.pdf.PdfLanguage

/** The few fixed texts of the generated document, German and English. */
internal class LatexLabels(
    private val lang: PdfLanguage,
) {
    private fun t(
        de: String,
        en: String,
    ) = if (lang == PdfLanguage.DE) de else en

    val glossary = t(de = "Glossar", en = "Glossary")
    val furtherDocuments = t(de = "Weitere Dokumente", en = "Further documents")
    val babel = if (lang == PdfLanguage.DE) "ngerman" else "english"
    val excluded = t(de = "Diagramm nicht eingebunden (siehe Protokoll)", en = "Diagram not included (see log)")
    val notRendered = t(de = "Diagramm konnte nicht erzeugt werden", en = "Diagram could not be produced")
    val imageMissing = t(de = "Bild nicht eingebunden", en = "Image not included")
}
