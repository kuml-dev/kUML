package dev.kuml.examples.workspace

import dev.kuml.workspace.OkfType
import dev.kuml.workspace.OkfValidator
import dev.kuml.workspace.WorkspaceMode
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

private val loader = object {}::class.java

/** Resolves the bundled `sample-association-charter` demo workspace to a real [File]. */
private fun workspaceRoot(): File {
    val url =
        loader.getResource("/knowledge-workspaces/sample-association-charter")
            ?: error("Workspace resource not found: /knowledge-workspaces/sample-association-charter")
    check(url.protocol == "file") {
        "Expected an exploded 'file:' classpath resource (Gradle test runtime), got: $url"
    }
    return File(url.toURI())
}

/** Reads the committed `_files.txt` manifest — JAR-portable (via [getResourceAsStream]). */
private fun manifestEntries(): List<String> =
    loader
        .getResourceAsStream("/knowledge-workspaces/sample-association-charter/_files.txt")
        ?.bufferedReader()
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?: error("Manifest not found: /knowledge-workspaces/sample-association-charter/_files.txt")

/**
 * V3.6.3 (FT-3) — Rot-guard + render-smoke test for the fictional
 * "sample-association-charter" OKF demo knowledge workspace.
 *
 * The workspace itself (`kuml-examples/src/main/resources/knowledge-workspaces/
 * sample-association-charter/`) is a hand-authored, entirely fictional
 * Muster-Satzung ("Muster-Verein für Offene Zusammenarbeit e.V.") — no real
 * association's actual statute text. It demonstrates the OKF knowledge-workspace
 * shape (ADR-0011): Article/Concept/ConceptCollection/Glossary prose plus
 * UmlClassDiagram/UmlStateMachine/BpmnProcess diagram documents, fully
 * cross-linked, scanning and validating clean under [WorkspaceScanner] /
 * [OkfValidator] in strict-vocabulary mode.
 */
class SampleAssociationWorkspaceTest :
    StringSpec({

        val expectedTypeCounts =
            mapOf(
                OkfType.KUML_WORKSPACE to 1,
                OkfType.ARTICLE to 4,
                OkfType.CONCEPT_COLLECTION to 1,
                OkfType.CONCEPT to 4,
                OkfType.GLOSSARY to 1,
                OkfType.UML_CLASS_DIAGRAM to 1,
                OkfType.UML_STATE_MACHINE to 1,
                OkfType.BPMN_PROCESS to 1,
            )
        val expectedDocumentCount = expectedTypeCounts.values.sum()

        // ── A) Manifest integrity ────────────────────────────────────────────

        "Manifest ist nicht leer und jeder Eintrag existiert als Classpath-Resource" {
            val entries = manifestEntries()
            entries.shouldNotBeEmpty()
            for (entry in entries) {
                val resource = loader.getResource("/knowledge-workspaces/sample-association-charter/$entry")
                withClue("Manifest entry missing on classpath: $entry") {
                    (resource != null) shouldBe true
                }
            }
        }

        // ── B) Scan discovers a KNOWLEDGE-mode workspace with a strict marker ──

        "WorkspaceScanner erkennt KNOWLEDGE-Mode mit striktem Marker" {
            val ws = WorkspaceScanner.scan(workspaceRoot())
            ws.mode shouldBe WorkspaceMode.KNOWLEDGE
            ws.markerFound shouldBe true
            (ws.marker?.name != null) shouldBe true
            ws.marker?.strict shouldBe true
        }

        // ── C) Scanned document inventory matches the committed manifest ───────

        "Gescannte .md-Dateien entsprechen exakt dem _files.txt-Manifest" {
            val ws = WorkspaceScanner.scan(workspaceRoot())
            val scannedPaths = ws.documents.map { it.relativePath }.toSet()
            val manifestMdPaths = manifestEntries().filter { it.endsWith(".md") }.toSet()
            scannedPaths shouldBe manifestMdPaths
            ws.documents.size shouldBe expectedDocumentCount
        }

        // ── D) Type inventory matches the expected OKF-vocabulary distribution ─

        "Typ-Inventar entspricht der erwarteten Verteilung (14 Dokumente, kein unbekannter Typ)" {
            val ws = WorkspaceScanner.scan(workspaceRoot())
            val actualCounts = ws.documents.groupingBy { it.type }.eachCount()
            val unknownOrMissing = ws.documents.count { it.type == null }
            unknownOrMissing shouldBe 0
            for ((type, expectedCount) in expectedTypeCounts) {
                (actualCounts[type] ?: 0) shouldBe expectedCount
            }
            actualCounts.values.sum() shouldBe expectedDocumentCount
        }

        // ── E) Strict OKF validation — zero findings ────────────────────────────

        "OkfValidator.validate liefert keine Findings im strikten Vokabular-Modus" {
            val ws = WorkspaceScanner.scan(workspaceRoot())
            val findings = OkfValidator.validate(ws, strictVocabulary = true)
            withClue(findings.joinToString("\n") { "${it.code} ${it.file}:${it.line} ${it.message}" }) {
                findings.shouldBeEmpty()
            }
        }

        // ── F) All three diagram blocks render to non-empty SVG ────────────────

        "alle drei Diagramm-Bloecke rendern zu gueltigem SVG" {
            val ws = WorkspaceScanner.scan(workspaceRoot())
            val diagramDocs = ws.documents.filter { it.type != null && it.type!!.requiresKumlBlock }
            diagramDocs.size shouldBe 3

            for (doc in diagramDocs) {
                doc.kumlBlocks.size shouldBe 1
                val result = WorkspaceExampleRenderer.render(doc.kumlBlocks.first().source)
                withClue("${doc.relativePath} — render error: ${result.error}") {
                    (result.error == null) shouldBe true
                    (result.svg != null) shouldBe true
                    (result.svg?.contains("<svg") == true) shouldBe true
                }
            }
        }
    })
