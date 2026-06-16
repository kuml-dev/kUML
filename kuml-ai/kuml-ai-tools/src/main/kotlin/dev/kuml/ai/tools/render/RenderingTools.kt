package dev.kuml.ai.tools.render

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import dev.kuml.ai.tools.context.AgentEditingContext
import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.ai.tools.result.RenderResult
import dev.kuml.ai.tools.result.SimulationResult
import dev.kuml.ai.tools.result.ValidateResult
import dev.kuml.c4.model.SystemContextDiagram
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.sysml2.BdDiagram
import dev.kuml.uml.UmlStateMachine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Tools for rendering, validating, and simulating the current model. NOT for editing.
 *
 * Token-budget discipline: SVG/PNG artifacts are written to temp files. The LLM
 * receives only the file path and a short summary — never the raw bytes/SVG text.
 * V3.0.24 (AI-Panel) reads the path and displays the artifact inline.
 */
@LLMDescription("Tools for rendering, validating, and simulating the current model. NOT for editing.")
public class RenderingTools internal constructor(
    private val ctx: AgentEditingContext,
    private val artifactStore: RenderArtifactStore = RenderArtifactStore.tempDir(),
) : ToolSet {
    /** Public constructor for use outside the module — uses default temp dir store. */
    public constructor(ctx: AgentEditingContext) : this(ctx, RenderArtifactStore.tempDir())

    @Tool(customName = "render_preview")
    @LLMDescription(
        "Renders the current diagram to SVG or PNG and writes it to a temp file. " +
            "Returns the file path and a short summary, NOT the full SVG/PNG bytes " +
            "(those would explode the LLM token budget).",
    )
    public suspend fun renderPreview(
        @LLMDescription("Output format: 'svg' or 'png'. Default 'svg'.") format: String = "svg",
        @LLMDescription("Optional explicit diagram id; defaults to the context's current diagram.") diagramId: String? = null,
    ): RenderResult {
        val model = ctx.resolveModel()
        val artifactId = ModelPatch.newId()

        return try {
            when (model) {
                is AnyKumlModel.Uml -> renderUml(model, format, artifactId)
                is AnyKumlModel.C4 -> renderC4(model, format, artifactId)
                is AnyKumlModel.Sysml2 -> renderSysml2(model, format, artifactId)
            }
        } catch (e: Exception) {
            RenderResult.Failure("Render failed: ${e.message}")
        }
    }

    @Tool(customName = "validate_model")
    @LLMDescription(
        "Runs static validation (same checks as `kuml validate`) over the working model " +
            "and returns a structured pass/fail report.",
    )
    public suspend fun validateModel(): ValidateResult {
        val model = ctx.resolveModel()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        when (model) {
            is AnyKumlModel.Uml -> {
                // Basic structural checks
                val allIds = (model.elements.map { it.id } + model.relationships.map { it.id }).toMutableSet()
                if (allIds.size < model.elements.size + model.relationships.size) {
                    errors += "Duplicate element ids detected"
                }
                // Check that relationship endpoints reference known elements
                model.relationships.forEach { rel ->
                    when (rel) {
                        is dev.kuml.uml.UmlAssociation ->
                            rel.ends.forEach { end ->
                                if (end.typeId !in allIds) {
                                    warnings += "Association endpoint '${end.typeId}' not found in model"
                                }
                            }
                        is dev.kuml.uml.UmlGeneralization -> {
                            if (rel.specificId !in allIds) warnings += "Generalization source '${rel.specificId}' not found"
                            if (rel.generalId !in allIds) warnings += "Generalization target '${rel.generalId}' not found"
                        }
                        else -> Unit
                    }
                }
            }
            is AnyKumlModel.C4 -> {
                // Basic C4 checks: all relationship endpoints exist
                val elementIds =
                    model.model.elements
                        .map { it.id }
                        .toSet()
                model.model.relationships.forEach { rel ->
                    if (rel.source !in elementIds) warnings += "Relationship source '${rel.source}' not found"
                    if (rel.target !in elementIds) warnings += "Relationship target '${rel.target}' not found"
                }
            }
            is AnyKumlModel.Sysml2 -> {
                // Basic SysML2 checks: all definition ids unique
                val defIds = model.model.definitions.map { it.id }
                if (defIds.toSet().size < defIds.size) errors += "Duplicate definition ids detected"
            }
        }

        val totalElements =
            when (model) {
                is AnyKumlModel.Uml -> model.elements.size + model.relationships.size
                is AnyKumlModel.C4 -> model.model.elements.size + model.model.relationships.size
                is AnyKumlModel.Sysml2 -> model.model.definitions.size + model.model.usages.size
            }

        return if (errors.isEmpty() && warnings.isEmpty()) {
            ValidateResult.Ok(checkedElements = totalElements)
        } else {
            ValidateResult.Issues(errors = errors, warnings = warnings)
        }
    }

    @Tool(customName = "simulate_state_machine")
    @LLMDescription(
        "Runs the kuml-runtime-core state machine interpreter against the current " +
            "state machine, feeding the given event sequence and returning the trace.",
    )
    public suspend fun simulateStateMachine(
        @LLMDescription(
            "Event names to fire in order, e.g. ['orderPlaced','paid','shipped'].",
        ) events: List<String>,
        @LLMDescription("Maximum number of steps to run (safety limit). Default 100.") maxSteps: Int = 100,
    ): SimulationResult {
        val model = ctx.resolveModel()
        val uml =
            model as? AnyKumlModel.Uml
                ?: return SimulationResult.Failure("simulate_state_machine requires a UML model")

        // Find the first UmlStateMachine in the elements
        val stm =
            uml.elements.filterIsInstance<UmlStateMachine>().firstOrNull()
                ?: return SimulationResult.Failure(
                    "No UmlStateMachine found in the model. Add states and transitions first.",
                )

        val runtime = StateMachineRuntime()
        val instance = runtime.start(stm)
        val steps = mutableListOf<String>()
        var stepCount = 0

        for (eventName in events) {
            if (stepCount >= maxSteps || instance.isTerminated) break
            val result = runtime.step(instance, Event(eventName))
            steps += "Event '$eventName': $result"
            stepCount++
        }

        val finalStates = instance.currentVertexIds
        val traceId = ModelPatch.newId()
        val traceFile = writeTrace(traceId, stm.id, events, steps, finalStates)

        return SimulationResult.Trace(
            finalStates = finalStates,
            steps = steps.take(20),
            traceFilePath = traceFile.absolutePath,
        )
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun renderUml(
        model: AnyKumlModel.Uml,
        format: String,
        artifactId: String,
    ): RenderResult {
        val kumlModel = model.toKumlModel()
        val diagram = kumlModel.root as dev.kuml.core.model.KumlDiagram
        val engine = ElkLayoutEngine()
        val graph = UmlLayoutBridge.toLayoutGraph(diagram)
        val layoutResult = engine.layout(graph)
        val nodeCount = model.elements.size
        val edgeCount = model.relationships.size
        val summary = "Rendered '${model.name}' with $nodeCount elements, $edgeCount relationships"

        return when (format.lowercase()) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(diagram, layoutResult)
                val file = artifactStore.writeSvg(artifactId, svg)
                RenderResult.Svg(filePath = file.absolutePath, summary = summary)
            }
            "png" -> {
                val png = KumlPngRenderer.toPng(diagram, layoutResult)
                val file = artifactStore.writePng(artifactId, png)
                // Approximate dimensions
                RenderResult.Png(filePath = file.absolutePath, widthPx = 1200, heightPx = 900)
            }
            else -> RenderResult.Failure("Unknown format '$format'. Use 'svg' or 'png'.")
        }
    }

    private fun renderC4(
        model: AnyKumlModel.C4,
        format: String,
        artifactId: String,
    ): RenderResult {
        // Build a minimal SystemContextDiagram from all elements
        val allElementIds = model.model.elements.map { it.id }
        val allRelIds = model.model.relationships.map { it.id }
        val diagram =
            SystemContextDiagram(
                id = "agent-context-diagram",
                name = model.model.name,
                elements = allElementIds,
                relationships = allRelIds,
            )
        val engine = ElkLayoutEngine()
        val graph = C4LayoutBridge.toLayoutGraph(diagram, model.model)
        val layoutResult = engine.layout(graph)
        val summary =
            "Rendered C4 '${model.model.name}' with ${model.model.elements.size} elements, " +
                "${model.model.relationships.size} relationships"

        return when (format.lowercase()) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(diagram, model.model, layoutResult)
                val file = artifactStore.writeSvg(artifactId, svg)
                RenderResult.Svg(filePath = file.absolutePath, summary = summary)
            }
            "png" -> {
                val png = KumlPngRenderer.toPng(diagram, model.model, layoutResult)
                val file = artifactStore.writePng(artifactId, png)
                RenderResult.Png(filePath = file.absolutePath, widthPx = 1200, heightPx = 900)
            }
            else -> RenderResult.Failure("Unknown format '$format'. Use 'svg' or 'png'.")
        }
    }

    private fun renderSysml2(
        model: AnyKumlModel.Sysml2,
        format: String,
        artifactId: String,
    ): RenderResult {
        val sysmlModel = model.model
        // Use the first BdDiagram or create a default one
        val diagram: BdDiagram =
            sysmlModel.diagrams.filterIsInstance<BdDiagram>().firstOrNull()
                ?: BdDiagram(
                    name = sysmlModel.name,
                    elementIds = sysmlModel.definitions.map { it.id },
                )
        val engine = ElkLayoutEngine()
        val graph = Sysml2LayoutBridge.toLayoutGraph(sysmlModel, diagram)
        val layoutResult = engine.layout(graph)
        val summary =
            "Rendered SysML2 '${sysmlModel.name}' with ${sysmlModel.definitions.size} definitions"

        return when (format.lowercase()) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(sysmlModel, diagram, layoutResult)
                val file = artifactStore.writeSvg(artifactId, svg)
                RenderResult.Svg(filePath = file.absolutePath, summary = summary)
            }
            "png" -> {
                val svg = KumlSvgRenderer.toSvg(sysmlModel, diagram, layoutResult)
                val png = KumlPngRenderer.toPng(svg)
                val file = artifactStore.writePng(artifactId, png)
                RenderResult.Png(filePath = file.absolutePath, widthPx = 1200, heightPx = 900)
            }
            else -> RenderResult.Failure("Unknown format '$format'. Use 'svg' or 'png'.")
        }
    }

    private fun writeTrace(
        traceId: String,
        modelId: String,
        events: List<String>,
        steps: List<String>,
        finalStates: List<String>,
    ): File {
        val json =
            buildJsonObject {
                put("traceId", traceId)
                put("modelId", modelId)
            }
        val file = File(System.getProperty("java.io.tmpdir"), "kuml-ai-sim-$traceId.json")
        file.writeText(json.toString())
        return file
    }
}
