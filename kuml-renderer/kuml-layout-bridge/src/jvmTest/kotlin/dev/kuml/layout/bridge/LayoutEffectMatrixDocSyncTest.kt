package dev.kuml.layout.bridge

import dev.kuml.core.model.layout.LayoutOption
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps the handbook table `Keyword by diagram family` (layout.adoc) in sync with [LayoutEffectMatrix].
 *
 * Only the six table columns are compared. `labelMaxWidth`, `showSubjectEdges` and `actorSide` are not in the
 * table and therefore not checked here.
 */
private data class DocColumn(
    val keyword: String,
    val options: List<LayoutOption>,
)

private data class DocRow(
    val family: String,
    val cells: List<String>,
    val line: Int,
)

private val DOC_COLUMNS =
    listOf(
        DocColumn(keyword = "direction", options = listOf(LayoutOption.DIRECTION)),
        DocColumn(keyword = "wrapColumns", options = listOf(LayoutOption.WRAP_COLUMNS)),
        DocColumn(keyword = "sameLayer", options = listOf(LayoutOption.SAME_LAYER)),
        DocColumn(keyword = "first / last", options = listOf(LayoutOption.FIRST, LayoutOption.LAST)),
        DocColumn(keyword = "aspectRatio", options = listOf(LayoutOption.ASPECT_RATIO)),
        DocColumn(keyword = "maxWidth", options = listOf(LayoutOption.MAX_WIDTH)),
    )

private val ALL_KINDS: Set<SpecDiagramKind> = SpecDiagramKind.entries.toSet()

private fun kindsWithPrefix(prefix: String): Set<SpecDiagramKind> = ALL_KINDS.filter { it.name.startsWith(prefix) }.toSet()

/** Doc family name (without the "(see note N)" hint) to the diagram arts the row stands for. */
private val FAMILY_KINDS: Map<String, Set<SpecDiagramKind>> =
    mapOf(
        "UML (all except sequence and timing)" to
            kindsWithPrefix("UML_") - SpecDiagramKind.UML_SEQUENCE - SpecDiagramKind.UML_TIMING,
        "UML sequence, UML timing" to setOf(SpecDiagramKind.UML_SEQUENCE, SpecDiagramKind.UML_TIMING),
        "SysML 2 (all except sequence)" to kindsWithPrefix("SYSML2_") - SpecDiagramKind.SYSML2_SEQ,
        "SysML 2 sequence" to setOf(SpecDiagramKind.SYSML2_SEQ),
        "C4" to setOf(SpecDiagramKind.C4),
        "ERM" to setOf(SpecDiagramKind.ERM),
        "BPMN process, BPMN collaboration" to setOf(SpecDiagramKind.BPMN_PROCESS, SpecDiagramKind.BPMN_COLLABORATION),
        "BPMN choreography, BPMN conversation" to setOf(SpecDiagramKind.BPMN_CHOREOGRAPHY, SpecDiagramKind.BPMN_CONVERSATION),
        "Service Blueprint, Journey" to setOf(SpecDiagramKind.BLUEPRINT, SpecDiagramKind.JOURNEY),
    )

private fun findProjectRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
}

private fun readDoc(): String {
    val file = File(findProjectRoot(), "docs/handbook/modules/reference/pages/layout.adoc")
    if (!file.isFile) fail("handbook page not found: $file")
    return file.readText(Charsets.UTF_8)
}

private val NOTE_HINT = Regex("""\s*\(see note \d+\)""")

private fun parseDocTable(adoc: String): List<DocRow> {
    val lines = adoc.lines()
    val sectionStart = lines.indexOfFirst { it.trim() == "=== Keyword by diagram family" }
    if (sectionStart < 0) fail("section '=== Keyword by diagram family' not found in layout.adoc")
    val tableStart = (sectionStart + 1 until lines.size).firstOrNull { lines[it].trim() == "|===" }
    if (tableStart == null) fail("table of section 'Keyword by diagram family' not found")
    val tableEnd = (tableStart + 1 until lines.size).firstOrNull { lines[it].trim() == "|===" }
    if (tableEnd == null) fail("table of section 'Keyword by diagram family' is not closed")

    val rows = mutableListOf<DocRow>()
    var i = tableStart + 1
    while (i < tableEnd) {
        val text = lines[i].trim()
        if (text.isEmpty() || text.startsWith("| Diagram family")) {
            i++
            continue
        }
        val family =
            text
                .removePrefix("|")
                .replace("`", "")
                .replace(NOTE_HINT, "")
                .trim()
        val cellsLine = lines.getOrNull(i + 1)?.trim().orEmpty()
        val cells = cellsLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (!cellsLine.startsWith("|") || cells.size != DOC_COLUMNS.size || cells.any { it != "acts" && it != "W001" }) {
            fail("layout.adoc line ${i + 2}: expected ${DOC_COLUMNS.size} cells of 'acts' or 'W001' for '$family', got '$cellsLine'")
        }
        rows += DocRow(family = family, cells = cells, line = i + 1)
        i += 2
    }
    return rows
}

class LayoutEffectMatrixDocSyncTest :
    FunSpec({
        test("doc table covers every diagram art exactly once") {
            val seen = mutableSetOf<SpecDiagramKind>()
            FAMILY_KINDS.forEach { (family, kinds) ->
                val overlap = kinds.intersect(seen)
                if (overlap.isNotEmpty()) fail("family '$family' repeats diagram arts already covered: $overlap")
                seen += kinds
            }
            seen shouldBe ALL_KINDS

            val docFamilies = parseDocTable(readDoc()).map { it.family }
            val missing = FAMILY_KINDS.keys - docFamilies.toSet()
            val extra = docFamilies - FAMILY_KINDS.keys
            if (missing.isNotEmpty() || extra.isNotEmpty() || docFamilies.size != docFamilies.toSet().size) {
                fail(
                    "layout.adoc rows differ from the test's family list. " +
                        "Missing in doc: $missing; unknown in doc: $extra; rows: $docFamilies",
                )
            }
        }

        test("doc table matches LayoutEffectMatrix") {
            val diffs = mutableListOf<String>()
            parseDocTable(readDoc()).forEach { row ->
                val kinds = FAMILY_KINDS[row.family] ?: return@forEach // reported by the coverage test
                DOC_COLUMNS.forEachIndexed { index, column ->
                    val doc = row.cells[index]
                    val perKind =
                        kinds.associateWith { kind ->
                            val effective = LayoutEffectMatrix.effectiveFor(kind)
                            val hits = column.options.count { it in effective }
                            when (hits) {
                                column.options.size -> "acts"
                                0 -> "W001"
                                else -> "mixed"
                            }
                        }
                    val distinct = perKind.values.toSet()
                    if (distinct.size > 1 || distinct.single() != doc) {
                        val detail = perKind.entries.joinToString(", ") { "${it.key}=${it.value}" }
                        diffs += "${row.family} / ${column.keyword}: doc says $doc, matrix says $detail (line ${row.line})"
                    }
                }
            }
            if (diffs.isNotEmpty()) {
                fail(
                    "layout.adoc table 'Keyword by diagram family' differs from LayoutEffectMatrix in ${diffs.size} cell(s):\n" +
                        diffs.joinToString("\n") +
                        "\nFix the table in docs/handbook/modules/reference/pages/layout.adoc or LayoutEffectMatrix.kt.",
                )
            }
        }
    })
