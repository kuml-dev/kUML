package dev.kuml.workspace.pdf

import dev.kuml.workspace.OkfWorkspace
import dev.kuml.workspace.WorkspaceScanner
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files

/** Builds throw-away workspaces and a fake [DiagramSource] so tests never run kUML scripts. */
object TestSupport {
    private fun svg(
        w: Int,
        h: Int,
    ) = """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">
        <rect x="2" y="2" width="${w - 4}" height="${h - 4}" fill="#F8F5F0" stroke="#1D2B4F" stroke-width="2"/>
        <text x="20" y="${h / 2}" font-family="Inter" font-size="16" fill="#111">Ärger ÖÜ äöü ß</text></svg>"""

    val SMALL_SVG: String = svg(w = 400, h = 240)
    val WIDE_SVG: String = svg(w = 2400, h = 200)
    val MEDIUM_SVG: String = svg(w = 1200, h = 300)

    fun workspace(
        dir: File,
        extra: Map<String, String> = emptyMap(),
    ): OkfWorkspace {
        val files =
            mapOf(
                "index.md" to
                    """
                    ---
                    type: KumlWorkspace
                    title: Muster Handbuch
                    subtitle: Ein Test
                    author: Erika Mustermann
                    version: 1.2
                    date: 2026-10-28
                    ---
                    # Muster Handbuch

                    - [Erstes Kapitel](chapters/eins.md)
                    - [Modelle](models/index.md)
                    - [Glossar](glossary/index.md)
                    """.trimIndent(),
                "chapters/eins.md" to
                    """
                    ---
                    type: Article
                    title: Erstes Kapitel
                    ---
                    # Erstes Kapitel

                    Dies ist ein Absatz mit **fettem** und *kursivem* Text, `Code`, einem [externen Link](https://example.com/x)
                    und einem [internen Link](../models/organe.md). Umlaute: ÄÖÜäöüß.

                    ## Unterabschnitt

                    - Punkt eins
                    - Punkt zwei
                      - verschachtelt

                    1. erstens
                    2. zweitens

                    | Spalte A | Spalte B |
                    |---|---|
                    | a1 | b1 |
                    | a2 | b2 |

                    ```kotlin
                    val x = 1
                    ```

                    > [!todo] Das ist noch offen

                    - [ ] Aufgabe offen
                    TODO: Rechtschreibung prüfen

                    [javascript](javascript:alert(1)) und [[Organe]] als Wikilink.
                    """.trimIndent(),
                "models/index.md" to
                    """
                    ---
                    type: ConceptCollection
                    title: Modelle
                    ---
                    # Modelle

                    - [Organe](organe.md)
                    """.trimIndent(),
                "models/organe.md" to
                    """
                    ---
                    type: Model
                    title: Organe
                    ---
                    # Organe

                    Das Diagramm:

                    ```kuml {name="organe"}
                    // wird vom Fake geliefert
                    ```
                    """.trimIndent(),
                "glossary/index.md" to
                    """
                    ---
                    type: Glossary
                    title: Glossar
                    ---
                    # Glossar

                    | Begriff | Bedeutung |
                    |---|---|
                    | Verein | Zusammenschluss |
                    """.trimIndent(),
                "loose/anhang.md" to "---\ntype: Article\ntitle: Anhangsdokument\nstatus: offen\n---\n# Anhangsdokument\n\nText.\n",
            ) + extra
        files.forEach { (rel, text) ->
            val f = File(dir, rel)
            f.parentFile.mkdirs()
            f.writeText(text, Charsets.UTF_8)
        }
        return WorkspaceScanner.scan(root = dir)
    }

    fun source(
        svg: String = SMALL_SVG,
        facts: DiagramFacts? = null,
    ): DiagramSource = DiagramSource { _, _, _ -> DiagramArtifact.Rendered(svg = svg, facts = facts) }

    fun options(
        kind: PdfExportKind = PdfExportKind.HANDBOOK,
        configure: PdfExportOptions.() -> PdfExportOptions = { this },
    ) = PdfExportOptions(
        kind = kind,
        // Fixed language: the default would follow the JVM locale, and CI runs with an English one.
        language = PdfLanguage.DE,
        producerVersion = "kUML test",
        metadata = ExportMetadata(date = java.time.LocalDate.of(2026, 10, 28)),
    ).configure()

    fun tempDir(): File = Files.createTempDirectory("kuml-pdf-test").toFile()

    fun text(
        pdf: PDDocument,
        page: Int,
    ): String =
        PDFTextStripper()
            .also {
                it.startPage = page
                it.endPage = page
            }.getText(pdf)

    fun allText(pdf: PDDocument): String = PDFTextStripper().getText(pdf)

    fun bookmarks(pdf: PDDocument): List<String> {
        val out = mutableListOf<String>()

        fun walk(item: PDOutlineItem?) {
            var cur = item
            while (cur != null) {
                out += cur.title
                walk(cur.firstChild)
                cur = cur.nextSibling
            }
        }
        walk(pdf.documentCatalog.documentOutline?.firstChild)
        return out
    }

    fun export(
        ws: OkfWorkspace,
        options: PdfExportOptions,
        source: DiagramSource = source(),
        target: File,
    ): PdfExportResult =
        WorkspacePdfExporter.write(
            prepared = WorkspacePdfExporter.prepare(workspace = ws, options = options, diagrams = source),
            target = target,
            overwrite = true,
        )

    fun open(f: File): PDDocument = Loader.loadPDF(f)
}
