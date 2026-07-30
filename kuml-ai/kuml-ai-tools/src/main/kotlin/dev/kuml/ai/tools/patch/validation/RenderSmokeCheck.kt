package dev.kuml.ai.tools.patch.validation

// Render-smoke phase (opt-in, off by default — V3.0.25 §4.6).
// Runs the full LayoutBridge → ElkLayoutEngine → KumlSvgRenderer pipeline on
// the patched model. Default off because medium models render in 50–200 ms —
// too costly per tool-call.
// Activated via PatchValidator.desktop(...) for CLI smokes and tests.
// V3.0.24 UI will trigger this via PatchValidator.desktop(...) when
// the user explicitly clicks "Preview".
//
// This is the DEFAULT [RenderSmokeStrategy] implementation used by
// PatchValidator when no other strategy is injected. It is desktop-appropriate
// only: no input-size cap, no timeout, no resource bound of any kind. A
// server-side caller MUST supply its own [RenderSmokeStrategy] rather than
// relying on this default — see [RenderSmokeStrategy]'s KDoc for why and how.

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.sysml2.BdDiagram

/**
 * Attempts a full layout + SVG render of the patched model.
 * Any exception thrown by the pipeline is caught and returned as an [Invalid] result.
 *
 * @return A [PatchValidationResult.Valid] or [PatchValidationResult.Invalid]
 *   with phase [ValidationPhase.RENDER].
 */
internal object RenderSmokeCheck {
    internal fun run(model: AnyKumlModel): PatchValidationResult =
        try {
            val engine = ElkLayoutEngine()
            when (model) {
                is AnyKumlModel.Uml -> {
                    val kumlModel = model.toKumlModel()
                    val diagram = kumlModel.root as dev.kuml.core.model.KumlDiagram
                    val graph = UmlLayoutBridge.toLayoutGraph(diagram)
                    val layoutResult = engine.layout(graph)
                    KumlSvgRenderer.toSvg(diagram, layoutResult)
                }
                is AnyKumlModel.C4 -> {
                    val c4Model = model.model
                    val diagram =
                        dev.kuml.c4.model.SystemContextDiagram(
                            id = "smoke-diagram",
                            name = c4Model.name,
                            elements = c4Model.elements.map { it.id },
                            relationships = c4Model.relationships.map { it.id },
                        )
                    val graph = C4LayoutBridge.toLayoutGraph(diagram, c4Model)
                    val layoutResult = engine.layout(graph)
                    KumlSvgRenderer.toSvg(diagram, c4Model, layoutResult)
                }
                is AnyKumlModel.Sysml2 -> {
                    val sysmlModel = model.model
                    val diagram: BdDiagram =
                        sysmlModel.diagrams.filterIsInstance<BdDiagram>().firstOrNull()
                            ?: BdDiagram(name = sysmlModel.name, elementIds = sysmlModel.definitions.map { it.id })
                    val graph = Sysml2LayoutBridge.toLayoutGraph(sysmlModel, diagram)
                    val layoutResult = engine.layout(graph)
                    KumlSvgRenderer.toSvg(sysmlModel, diagram, layoutResult)
                }
            }
            PatchValidationResult.Valid()
        } catch (e: Exception) {
            PatchValidationResult.Invalid(
                errors =
                    listOf(
                        ValidationError(
                            code = "RENDER_FAILED",
                            message = "Render smoke failed: ${e.message ?: e.javaClass.simpleName}",
                        ),
                    ),
                phase = ValidationPhase.RENDER,
            )
        }
}
