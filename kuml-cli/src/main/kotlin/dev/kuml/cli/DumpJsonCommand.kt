package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Spacing
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.uml.UmlSerializersModule
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Prerequisite (V3.2.11) for the Node/wasm playground engine: dumps the
 * JVM-computed `KumlDiagram` + `LayoutResult` for a UML `.kuml.kts` script
 * as two JSON files, using the exact `Json` configuration that the
 * `kuml-wasm-playground` module's `renderDiagramJson(diagramJson,
 * layoutJson)` entry point expects to decode
 * (`serializersModule = UmlSerializersModule`, `classDiscriminator = "@type"`).
 *
 * Scope: UML only. Non-UML extractions (C4/SysML2/BPMN/Blueprint) are
 * rejected with a clear error, matching the wasm side's decode scope.
 *
 * This intentionally re-derives the diagram + layout the same way
 * [RenderPipeline]'s internal UML path does (same `UmlLayoutBridge` +
 * `UmlContentSizeProvider` + engine selection + spacing hints) so the
 * dumped `LayoutResult` is the same one the CLI SVG render path would use
 * for the identical input — this is what makes a wasm-vs-CLI SVG diff
 * meaningful.
 */
internal class DumpJsonCommand : CliktCommand(name = "dump-json") {
    private val input by argument(name = "SCRIPT", help = "Path to a *.kuml.kts script (UML diagrams only).")
    private val diagramOut by option("--diagram-out", help = "Output path for the KumlDiagram JSON.").required()
    private val layoutOut by option("--layout-out", help = "Output path for the LayoutResult JSON.").required()

    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Dumps the parsed KumlDiagram and computed LayoutResult for a UML script as JSON " +
            "(prerequisite for the wasm playground render path)."

    private val dumpJson =
        Json {
            serializersModule = UmlSerializersModule
            ignoreUnknownKeys = true
            classDiscriminator = "@type"
        }

    private fun ensureEnginesRegistered() {
        if (LayoutEngineRegistry.ids().isEmpty()) {
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
        }
    }

    private fun DiagramType.toDiagramKind(): DiagramKind =
        when (this) {
            DiagramType.CLASS -> DiagramKind.UmlClass
            DiagramType.COMPONENT -> DiagramKind.UmlComponent
            DiagramType.USE_CASE -> DiagramKind.UmlUseCase
            DiagramType.STATE -> DiagramKind.UmlState
            DiagramType.SEQUENCE -> DiagramKind.UmlSequence
            else -> DiagramKind.Generic
        }

    private fun diagramAndLayout(diagram: KumlDiagram): LayoutResult {
        ensureEnginesRegistered()
        val diagramMergeEdges =
            (diagram.metadata[LayoutMetadataKeys.MERGE_EDGES] as? KumlMetaValue.Flag)?.value
        val baseSpacing =
            if (diagram.type == DiagramType.STATE) {
                Spacing(nodeToNode = 110f, edgeToEdge = 36f, groupPadding = 28f, layerToLayer = 130f)
            } else {
                LayoutHints.DEFAULT.spacing
            }
        val hints =
            LayoutHints.DEFAULT.copy(
                mergeEdges = diagramMergeEdges ?: LayoutHints.DEFAULT.mergeEdges,
                spacing = baseSpacing,
            )
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram, UmlContentSizeProvider(diagram, hints.direction))
        val kind = diagram.type.toDiagramKind()
        val engine =
            LayoutEngineRegistry.pickFor(kind, null)
                ?: error("No layout engine registered for kind=$kind")
        return engine.layout(layoutGraph, hints)
    }

    override fun run() {
        val scriptFile = File(input)
        val evalResult = KumlScriptHost.eval(scriptFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            throw ScriptEvaluationException("Script evaluation failed:\n${errors.joinToString("\n") { it.message }}")
        }
        val successResult =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation did not produce a result")
        val extracted = DiagramExtractor.extractAny(successResult.value.returnValue, scriptFile)

        val umlExtracted =
            extracted as? ExtractedDiagram.Uml
                ?: throw ScriptEvaluationException(
                    "dump-json only supports UML diagrams (wasm playground decode scope). " +
                        "Got: ${extracted::class.simpleName}",
                )

        val diagram = umlExtracted.diagram
        val layoutResult = diagramAndLayout(diagram)

        val diagramJson = dumpJson.encodeToString(KumlDiagram.serializer(), diagram)
        val layoutJson = dumpJson.encodeToString(LayoutResult.serializer(), layoutResult)

        File(diagramOut).writeText(diagramJson)
        File(layoutOut).writeText(layoutJson)
    }
}
