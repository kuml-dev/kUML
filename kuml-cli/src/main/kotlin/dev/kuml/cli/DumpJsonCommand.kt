package dev.kuml.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnDiagram
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Model
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Spacing
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnContentSizeProvider
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.layout.bridge.erm.ErmChenSizeProvider
import dev.kuml.layout.bridge.erm.ErmContentSizeProvider
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.layout.bridge.erm.ErmLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.uml.UmlSerializersModule
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Dumps the JVM-computed diagram model + layout for a `.kuml.kts` script as
 * JSON files that the `kuml-wasm-playground` module can decode and render in
 * the browser (there is no `.kuml.kts` parser and no ELK on Kotlin/Wasm, so the
 * JVM does the parsing + layout and ships JSON).
 *
 * **Scope (V0.23.3):** UML, C4, SysML 2, BPMN and Blueprint. The layout is
 * re-derived exactly the way [RenderPipeline] derives it for the same input
 * (same bridge, same size provider, same engine + spacing hints), so a
 * wasm-vs-CLI SVG diff is meaningful.
 *
 * **Output files:**
 *  - `--diagram-out` — the diagram JSON.
 *    - UML: a `KumlDiagram`.
 *    - C4/SysML2/BPMN/Blueprint: the concrete diagram (`C4Diagram`,
 *      `Sysml2Diagram`, `BpmnDiagram`, `BlueprintDiagram`).
 *  - `--layout-out` — the `LayoutResult` JSON. Written for UML/C4/SysML2/BPMN.
 *    Blueprint is geometry-driven and has no `LayoutResult`; for Blueprint this
 *    file is written as an empty object `{}` so downstream tooling can rely on
 *    its presence, and the wasm `renderBlueprintDiagramJson` ignores it.
 *  - `--model-out` — the owning model JSON (`C4Model`, `Sysml2Model`,
 *    `BpmnModel`, `BlueprintModel`). Required for every non-UML metamodel,
 *    because their renderers take a `(model, diagram, layout)` triple. Not
 *    written for UML (a `KumlDiagram` is self-contained); if supplied for a UML
 *    script it is ignored.
 *
 * **Serialization config:** UML uses `serializersModule = UmlSerializersModule`
 * (its `KumlElement` base is an *open* polymorphic type needing an explicit
 * registry). C4/SysML2/BPMN/Blueprint models are `sealed` `@Serializable`
 * hierarchies that need no module. Both configs use `classDiscriminator =
 * "@type"` (the kotlinx default `"type"` collides with `KumlDiagram.type`).
 */
internal class DumpJsonCommand : CliktCommand(name = "dump-json") {
    private val input by argument(name = "SCRIPT", help = "Path to a *.kuml.kts script.")
    private val diagramOut by option("--diagram-out", help = "Output path for the diagram JSON.").required()
    private val layoutOut by option("--layout-out", help = "Output path for the LayoutResult JSON.").required()
    private val modelOut by
        option(
            "--model-out",
            help = "Output path for the owning model JSON (required for C4/SysML2/BPMN/Blueprint; ignored for UML).",
        )

    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "Dumps the parsed diagram, its owning model, and the computed LayoutResult as JSON " +
            "(prerequisite for the wasm playground render path). Supports UML, C4, SysML 2, BPMN, Blueprint."

    /** UML needs the open-polymorphic element registry; sealed metamodels do not. */
    private val umlJson =
        Json {
            serializersModule = UmlSerializersModule
            ignoreUnknownKeys = true
            classDiscriminator = "@type"
        }

    private val modelJson =
        Json {
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

    // ── UML ──────────────────────────────────────────────────────────────
    private fun umlLayout(diagram: KumlDiagram): LayoutResult {
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

    // ── C4 ───────────────────────────────────────────────────────────────
    private fun c4Layout(extracted: ExtractedDiagram.C4): LayoutResult {
        ensureEnginesRegistered()
        val sizeProvider = C4ContentSizeProvider(extracted.model)
        val layoutGraph = C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider)
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for C4 diagrams.")
        val hints =
            if (extracted.diagram is dev.kuml.c4.model.DynamicDiagram) {
                LayoutHints.DEFAULT.copy(
                    spacing = LayoutHints.DEFAULT.spacing.copy(nodeToNode = 100f, edgeToEdge = 28f, layerToLayer = 120f),
                )
            } else {
                LayoutHints.DEFAULT
            }
        return engine.layout(layoutGraph, hints)
    }

    // ── SysML 2 ──────────────────────────────────────────────────────────
    private fun sysml2Layout(extracted: ExtractedDiagram.Sysml2): LayoutResult {
        ensureEnginesRegistered()
        val model = extracted.model
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for SysML 2 diagrams.")
        return when (val diagram = extracted.diagram) {
            is ReqDiagram -> {
                val graph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val hints =
                    LayoutHints.DEFAULT.copy(
                        spacing = LayoutHints.DEFAULT.spacing.copy(nodeToNode = 80f, edgeToEdge = 20f, layerToLayer = 100f),
                    )
                engine.layout(graph, hints)
            }
            is StmDiagram -> {
                val graph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val hints = LayoutHints.DEFAULT.copy(spacing = Spacing(nodeToNode = 80f, edgeToEdge = 28f, groupPadding = 24f))
                engine.layout(graph, hints)
            }
            is ActDiagram -> {
                val graph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val hints =
                    LayoutHints.DEFAULT.copy(
                        spacing = LayoutHints.DEFAULT.spacing.copy(nodeToNode = 100f, layerToLayer = 100f),
                    )
                engine.layout(graph, hints)
            }
            is ParDiagram -> {
                val graph = Sysml2LayoutBridge.toLayoutGraph(model, diagram, Sysml2LayoutBridge.parContentAwareSizeProvider(model))
                engine.layout(graph, LayoutHints.DEFAULT)
            }
            is BdDiagram -> engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
            is IbdDiagram -> engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
            is UcDiagram -> engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
            is SeqDiagram -> engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
        }
    }

    // ── ERM ──────────────────────────────────────────────────────────────
    private fun ermLayout(extracted: ExtractedDiagram.Erm): LayoutResult {
        ensureEnginesRegistered()
        val model = extracted.model
        val diagram = extracted.diagram
        // V3.4.x — shared spacing constant (see ErmLayoutBridge.WIDENED_SPACING_HINTS's
        // KDoc); keeps `--format json` and `--format svg/png` in lock-step so they
        // report/produce the same coordinates for the same script.
        val hints = ErmLayoutBridge.WIDENED_SPACING_HINTS
        // Mirrors the notation dispatch in RenderPipeline.renderErm: Chen expands
        // attributes/relationships into their own layout nodes and IDEF1X injects
        // a synthetic category-circle node, so both need their own bridge + size
        // provider instead of the generic ErmLayoutBridge — otherwise dump-json's
        // coordinates would diverge from the notation actually rendered by
        // `--format svg/png` for the same script.
        val graph =
            when (diagram.notation) {
                ErmNotation.CHEN -> ErmChenLayoutBridge.toChenLayoutGraph(model, diagram, ErmChenSizeProvider(model, diagram))
                ErmNotation.IDEF1X ->
                    ErmIdef1xLayoutBridge.toLayoutGraph(model, diagram, ErmContentSizeProvider(model, diagram, hints.direction))
                else -> ErmLayoutBridge.toLayoutGraph(model, diagram, ErmContentSizeProvider(model, diagram, hints.direction))
            }
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for ERM diagrams.")
        return engine.layout(graph, hints)
    }

    // ── BPMN ─────────────────────────────────────────────────────────────
    private fun bpmnLayout(extracted: ExtractedDiagram.Bpmn): LayoutResult {
        ensureEnginesRegistered()
        val model = extracted.model
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for BPMN diagrams.")
        return when (val diagram = extracted.diagram) {
            is ChoreographyDiagram -> ChoreographyGridLayout.layout(model, diagram)
            is ProcessDiagram ->
                engine.layout(BpmnLayoutBridge.toLayoutGraph(model, diagram, BpmnContentSizeProvider(model)), LayoutHints.DEFAULT)
            is CollaborationDiagram ->
                engine.layout(BpmnLayoutBridge.toLayoutGraph(model, diagram, BpmnContentSizeProvider(model)), LayoutHints.DEFAULT)
            is ConversationDiagram ->
                engine.layout(BpmnLayoutBridge.toLayoutGraph(model, diagram, BpmnContentSizeProvider(model)), LayoutHints.DEFAULT)
        }
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

        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val diagram = extracted.diagram
                val layout = umlLayout(diagram)
                writeDiagram(umlJson.encodeToString(KumlDiagram.serializer(), diagram))
                writeLayout(umlJson.encodeToString(LayoutResult.serializer(), layout))
                // No model file for UML; a KumlDiagram is self-contained.
            }
            is ExtractedDiagram.C4 -> {
                requireModelOut("C4")
                val layout = c4Layout(extracted)
                writeModel(modelJson.encodeToString(C4Model.serializer(), extracted.model))
                writeDiagram(modelJson.encodeToString(C4Diagram.serializer(), extracted.diagram))
                writeLayout(modelJson.encodeToString(LayoutResult.serializer(), layout))
            }
            is ExtractedDiagram.Sysml2 -> {
                requireModelOut("SysML 2")
                val layout = sysml2Layout(extracted)
                writeModel(modelJson.encodeToString(Sysml2Model.serializer(), extracted.model))
                writeDiagram(modelJson.encodeToString(Sysml2Diagram.serializer(), extracted.diagram))
                writeLayout(modelJson.encodeToString(LayoutResult.serializer(), layout))
            }
            is ExtractedDiagram.Bpmn -> {
                requireModelOut("BPMN")
                val layout = bpmnLayout(extracted)
                writeModel(modelJson.encodeToString(BpmnModel.serializer(), extracted.model))
                writeDiagram(modelJson.encodeToString(BpmnDiagram.serializer(), extracted.diagram))
                writeLayout(modelJson.encodeToString(LayoutResult.serializer(), layout))
            }
            is ExtractedDiagram.Blueprint -> {
                requireModelOut("Blueprint")
                writeModel(modelJson.encodeToString(BlueprintModel.serializer(), extracted.model))
                writeDiagram(modelJson.encodeToString(BlueprintDiagram.serializer(), extracted.diagram))
                // Blueprint is geometry-driven — no LayoutResult. Write an empty
                // object so downstream tooling can rely on the file existing.
                writeLayout("{}")
            }
            is ExtractedDiagram.Erm -> {
                requireModelOut("ERM")
                val layout = ermLayout(extracted)
                writeModel(modelJson.encodeToString(ErmModel.serializer(), extracted.model))
                writeDiagram(modelJson.encodeToString(ErmDiagram.serializer(), extracted.diagram))
                writeLayout(modelJson.encodeToString(LayoutResult.serializer(), layout))
            }
        }
    }

    private fun requireModelOut(metamodel: String) {
        if (modelOut == null) {
            throw ScriptEvaluationException(
                "$metamodel diagrams need a --model-out path (their renderers take a model + diagram + layout triple).",
            )
        }
    }

    private fun writeDiagram(json: String) = File(diagramOut).writeText(json)

    private fun writeLayout(json: String) = File(layoutOut).writeText(json)

    private fun writeModel(json: String) {
        modelOut?.let { File(it).writeText(json) }
    }
}
