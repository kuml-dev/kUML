package dev.kuml.cli

import dev.kuml.core.model.layout.LayoutSpec
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.diagnostics.ScriptProblemCode
import dev.kuml.core.script.diagnostics.ScriptProblems
import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.SpecDiagramKinds
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.layout.bridge.erm.ErmChenSizeProvider
import dev.kuml.layout.bridge.erm.ErmContentSizeProvider
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.layout.bridge.erm.ErmLayoutBridge
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics

/** One validation finding with the position and the anchor it is about (PAL wave 2B-b). */
public data class Finding(
    val code: String,
    val severity: Severity,
    val line: Int?,
    val column: Int?,
    val diagramId: String?,
    val anchorId: String?,
    val anchorName: String?,
    val message: String,
) {
    /** `Zeile L, Spalte C: [code] message [Anker: Name (id)]`, positions and anchor only when known. */
    public fun format(): String {
        val pos =
            when {
                line != null && column != null -> "Zeile $line, Spalte $column: "
                line != null -> "Zeile $line: "
                else -> ""
            }
        return pos + text()
    }

    /** `[code] message [Anker: Name (id)]` without the position (front ends that show line and column apart from the text). */
    public fun text(): String {
        val anchor =
            when {
                anchorId == null -> ""
                anchorName != null -> " [Anker: $anchorName ($anchorId)]"
                else -> " [Anker: $anchorId]"
            }
        return "[$code] $message$anchor"
    }
}

/** Severity of a [Finding]. */
public enum class Severity { ERROR, WARNING }

/**
 * The one validation entry point shared by `kuml validate` and the desktop app (PAL wave 2B-b): script problems with
 * line and column, note findings, and the static check of the `layout { }` block. Both front ends call it and format
 * the same list, so a script is reported identically everywhere.
 *
 * Model-level checks that need a particular diagram family (OCL, structural, ERM, STM, ...) stay in `ValidateCommand`.
 *
 * Messages name ids, diagram names and limits, never note bodies.
 */
public object ValidationService {
    private val anchorInMessage = Regex("""(?:Anker unbekannt|Anker in diesem Diagramm nicht darstellbar): (\S{1,200})""")

    /** Validates [script] (the source text); [fileName] only shapes messages. Never throws for script errors. */
    public fun validate(
        script: String,
        fileName: String = "script.kuml.kts",
    ): List<Finding> =
        validateEvaluated(result = KumlScriptHost.eval(code = script, fileName = fileName), source = script, fileName = fileName)

    /** Like [validate], for a script the caller has evaluated already (no second compilation). */
    public fun validateEvaluated(
        result: ResultWithDiagnostics<kotlin.script.experimental.api.EvaluationResult>,
        source: String,
        fileName: String = "script.kuml.kts",
    ): List<Finding> {
        val problems = ScriptProblems.describe(result = result, source = source, scriptFileName = fileName)
        if (problems.isNotEmpty()) {
            return problems.map {
                Finding(
                    code = scriptCode(it.code),
                    severity = Severity.ERROR,
                    line = it.line,
                    column = it.column,
                    diagramId = null,
                    anchorId = null,
                    anchorName = null,
                    message = it.message + (it.hint?.let { h -> " Hint: $h" } ?: ""),
                )
            }
        }
        val success =
            result as? ResultWithDiagnostics.Success
                ?: return listOf(error(code = "KUML-SCRIPT-E000", message = "Script evaluation produced no result"))
        val extracted =
            try {
                DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File(fileName))
            } catch (e: Exception) {
                val detail =
                    e.message
                        ?.lines()
                        ?.firstOrNull()
                        ?.take(300) ?: "No renderable diagram"
                return listOf(error(code = "KUML-SCRIPT-E005", message = detail))
            }
        return findingsOf(extracted)
    }

    /** Findings of an already extracted diagram: notes first, then the layout block. */
    public fun findingsOf(extracted: ExtractedDiagram): List<Finding> {
        val diagramId = diagramNameOf(extracted)
        val notes =
            NoteChecks.of(extracted).map { f ->
                val anchor = anchorInMessage.find(f.message)?.groupValues?.get(1)
                Finding(
                    code = f.code,
                    severity = if (f.isError) Severity.ERROR else Severity.WARNING,
                    line = null,
                    column = null,
                    diagramId = diagramId,
                    anchorId = anchor,
                    anchorName = anchor?.let { nameOfAnchor(e = extracted, id = it) },
                    message = f.message,
                )
            }
        val layout =
            layoutOf(extracted).let { (kind, spec) ->
                LayoutSpecRunner
                    .validate(kind = kind, spec = spec, knownIds = layoutNodeIds(extracted), diagramName = diagramId)
                    .map {
                        Finding(
                            code = it.code,
                            severity = if (it.isError) Severity.ERROR else Severity.WARNING,
                            line = null,
                            column = null,
                            diagramId = diagramId,
                            anchorId = null,
                            anchorName = null,
                            message = it.message,
                        )
                    }
            }
        return notes + layout
    }

    private fun error(
        code: String,
        message: String,
    ) = Finding(
        code = code,
        severity = Severity.ERROR,
        line = null,
        column = null,
        diagramId = null,
        anchorId = null,
        anchorName = null,
        message = message,
    )

    private fun scriptCode(code: ScriptProblemCode): String =
        when (code) {
            ScriptProblemCode.DSL_ERROR -> "KUML-SCRIPT-E001"
            ScriptProblemCode.TYPE_MISMATCH -> "KUML-SCRIPT-E002"
            ScriptProblemCode.UNKNOWN_ANCHOR -> "KUML-SCRIPT-E003"
            ScriptProblemCode.RECEIVER_SCOPE -> "KUML-SCRIPT-E004"
            ScriptProblemCode.NO_DIAGRAM -> "KUML-SCRIPT-E005"
        }

    private fun diagramNameOf(e: ExtractedDiagram): String? =
        when (e) {
            is ExtractedDiagram.Uml -> e.diagram.name
            is ExtractedDiagram.C4 -> e.diagram.name
            is ExtractedDiagram.Sysml2 -> e.diagram.name
            is ExtractedDiagram.Erm -> e.diagram.name
            is ExtractedDiagram.Blueprint -> e.diagram.name
            is ExtractedDiagram.Bpmn -> null
        }

    private fun layoutOf(e: ExtractedDiagram): Pair<SpecDiagramKind, LayoutSpec?> =
        when (e) {
            is ExtractedDiagram.Uml -> SpecDiagramKinds.of(e.diagram.type) to e.diagram.layout
            is ExtractedDiagram.C4 -> SpecDiagramKind.C4 to e.diagram.layout
            is ExtractedDiagram.Sysml2 -> SpecDiagramKinds.of(e.diagram) to e.diagram.layout
            is ExtractedDiagram.Erm -> SpecDiagramKind.ERM to e.diagram.layout
            is ExtractedDiagram.Blueprint ->
                (if (e.diagram is dev.kuml.blueprint.model.JourneyDiagram) SpecDiagramKind.JOURNEY else SpecDiagramKind.BLUEPRINT) to
                    e.diagram.layout
            is ExtractedDiagram.Bpmn -> SpecDiagramKind.BPMN_PROCESS to null
        }

    /** Ids the layout of [e] will know; the same graph the render builds, but without running an engine. */
    private fun layoutNodeIds(e: ExtractedDiagram): Set<String>? {
        val graph: LayoutGraph? =
            runCatching {
                when (e) {
                    is ExtractedDiagram.Uml -> UmlLayoutBridge.toLayoutGraph(diagram = e.diagram)
                    is ExtractedDiagram.C4 -> C4LayoutBridge.toLayoutGraph(diagram = e.diagram, model = e.model)
                    is ExtractedDiagram.Erm ->
                        when (e.diagram.notation) {
                            ErmNotation.CHEN ->
                                ErmChenLayoutBridge.toChenLayoutGraph(
                                    model = e.model,
                                    diagram = e.diagram,
                                    sizeProvider = ErmChenSizeProvider(model = e.model, diagram = e.diagram),
                                )
                            ErmNotation.IDEF1X ->
                                ErmIdef1xLayoutBridge.toLayoutGraph(
                                    model = e.model,
                                    diagram = e.diagram,
                                    sizeProvider = ErmContentSizeProvider(model = e.model, diagram = e.diagram),
                                )
                            else ->
                                ErmLayoutBridge.toLayoutGraph(
                                    model = e.model,
                                    diagram = e.diagram,
                                    sizeProvider = ErmContentSizeProvider(model = e.model, diagram = e.diagram),
                                )
                        }
                    is ExtractedDiagram.Sysml2 ->
                        when (val d = e.diagram) {
                            is dev.kuml.sysml2.BdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.IbdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.UcDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.ReqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.StmDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.ActDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.SeqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                            is dev.kuml.sysml2.ParDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = e.model, diagram = d)
                        }
                    else -> null
                }
            }.getOrNull()
        // Unknown graph (unsupported diagram art or bridge failure): do not guess, skip the unknown-id check.
        if (graph == null && e !is ExtractedDiagram.Blueprint) return null
        val fromGraph = graph?.let { g -> g.nodes.map { it.id.value } + g.groups.map { it.id.value } }.orEmpty()
        // Blueprint ids are step ids; the grid has no layout graph.
        val blueprint =
            (e as? ExtractedDiagram.Blueprint)
                ?.model
                ?.steps
                ?.map { it.id }
                .orEmpty()
        return (fromGraph + blueprint).toSet()
    }

    private fun nameOfAnchor(
        e: ExtractedDiagram,
        id: String,
    ): String? =
        when (e) {
            is ExtractedDiagram.Sysml2 ->
                e.model.definitions
                    .firstOrNull { it.id == id }
                    ?.name
                    ?: e.model.definitions.firstNotNullOfOrNull { d -> d.features.firstOrNull { it.id == id }?.name }
                    ?: e.model.usages
                        .firstOrNull { it.id == id }
                        ?.name
            is ExtractedDiagram.C4 ->
                e.model.elements
                    .firstOrNull { it.id == id }
                    ?.name
            is ExtractedDiagram.Blueprint ->
                e.model.steps
                    .firstOrNull { it.id == id }
                    ?.name
            is ExtractedDiagram.Erm ->
                e.model.entities
                    .firstOrNull { it.id == id }
                    ?.name
            else -> null
        }
}
