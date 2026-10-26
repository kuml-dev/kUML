package dev.kuml.desktop.render

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.desktop.simulation.isSimulatable
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnContentSizeProvider
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.UcDiagram
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/** Result of [DesktopRenderPipeline.prepareSimulation] — see its KDoc. */
internal sealed interface SimulationPrepareResult {
    data class Ready(
        val stateMachine: UmlStateMachine,
        val svg: String,
    ) : SimulationPrepareResult

    data class Unsupported(
        val message: String,
    ) : SimulationPrepareResult

    data class Failed(
        val message: String,
    ) : SimulationPrepareResult
}

/** One diagnostic of [DesktopRenderPipeline.validate]; [line]/[column] are 1-based when known. */
internal data class DesktopDiagnostic(
    val severity: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

/** Outcome of [DesktopRenderPipeline.validate]. */
internal data class DesktopValidationResult(
    val ok: Boolean,
    val diagnostics: List<DesktopDiagnostic>,
)

internal object DesktopRenderPipeline {
    /** Canvas padding of SysML-2 STM/ACT renders and of the simulation preview -- they must match. */
    private const val DIAGRAM_PADDING_PX = 64f

    private const val ERM_NOT_SUPPORTED_MESSAGE =
        "ERM-Rendering wird noch nicht unterstützt — geplant für kUML V3.4.2. " +
            "V3.4.1 unterstützt für ERM-Skripte nur `kuml validate`."

    /**
     * Evaluates [script] and extracts its diagram -- no layout, no SVG, no state change. Backs
     * the MCP `validate` tool; shares the evaluation entry point ([KumlScriptHost.eval]) with
     * [render], so a script is accepted here exactly when it would evaluate there.
     */
    fun validate(script: String): DesktopValidationResult =
        try {
            DesktopEngineInit.ensure()
            val evalResult = KumlScriptHost.eval(code = script)
            val diagnostics =
                evalResult.reports
                    .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
                    .map { report ->
                        DesktopDiagnostic(
                            severity = report.severity.name,
                            message = report.message,
                            line = report.location?.start?.line,
                            column = report.location?.start?.col,
                        )
                    }
            val hasErrors = diagnostics.any { it.severity == ScriptDiagnostic.Severity.ERROR.name }
            val success = evalResult as? ResultWithDiagnostics.Success
            if (hasErrors || success == null) {
                DesktopValidationResult(
                    ok = false,
                    diagnostics =
                        diagnostics.ifEmpty {
                            listOf(DesktopDiagnostic(severity = "ERROR", message = "Script-Auswertung fehlgeschlagen"))
                        },
                )
            } else {
                DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File("inline.kuml.kts"))
                DesktopValidationResult(ok = true, diagnostics = diagnostics)
            }
        } catch (e: ScriptEvaluationException) {
            DesktopValidationResult(
                ok = false,
                diagnostics = listOf(DesktopDiagnostic(severity = "ERROR", message = e.message ?: "Script-Fehler")),
            )
        } catch (e: Exception) {
            DesktopValidationResult(
                ok = false,
                diagnostics = listOf(DesktopDiagnostic(severity = "ERROR", message = e.message ?: "Unerwarteter Fehler")),
            )
        }

    /** Shared eval → extract → theme → ELK-engine front for [render] and [prepareSimulation]. */
    private sealed interface PipelineContext {
        data class Ready(
            val extracted: ExtractedDiagram,
            val theme: KumlTheme,
            val elkEngine: KumlLayoutEngine,
        ) : PipelineContext

        data class Failure(
            val message: String,
        ) : PipelineContext
    }

    /**
     * Evaluates [script], extracts its diagram, and resolves [themeName] + the ELK engine —
     * the front half every render (and simulation start) needs, factored out once so [render]
     * and [prepareSimulation] never duplicate the eval/extract/theme-lookup logic (Rams:
     * "nicht zweimal fast gleich").
     */
    private fun prepare(
        script: String,
        themeName: String,
    ): PipelineContext {
        DesktopEngineInit.ensure()
        val evalResult = KumlScriptHost.eval(code = script)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val msg = errors.joinToString("\n") { it.message }
            return PipelineContext.Failure(msg.ifBlank { "Script-Auswertung fehlgeschlagen" })
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: return PipelineContext.Failure("Kein Ergebnis aus dem Script")

        val extracted =
            DiagramExtractor.extractAny(
                returnValue = success.value.returnValue,
                input = File("inline.kuml.kts"),
            )

        val theme =
            ThemeRegistry.get(themeName)
                ?: ThemeRegistry.get("kuml")
                ?: return PipelineContext.Failure("Theme '$themeName' nicht gefunden")

        val elkEngine =
            LayoutEngineRegistry.get("elk.layered")
                ?: return PipelineContext.Failure("ELK-Layout-Engine nicht verfügbar")

        return PipelineContext.Ready(extracted = extracted, theme = theme, elkEngine = elkEngine)
    }

    /**
     * Everything a diagram branch of [render] needs besides the diagram itself: theme, ELK engine
     * and the two [SvgRenderOptions] flavours, so every branch applies the watermark toggle
     * uniformly.
     */
    private class RenderEnv(
        val theme: KumlTheme,
        val elkEngine: KumlLayoutEngine,
        watermark: Boolean,
    ) {
        /** Default options. */
        val svgOptions = SvgRenderOptions(watermark = watermark)

        /**
         * Wider canvas padding for SysML-2 STM and ACT. Must stay identical to the padding of the
         * simulation preview ([DIAGRAM_PADDING_PX]), otherwise the preview jumps when a simulation
         * starts.
         */
        val paddingOptions = SvgRenderOptions(paddingPx = DIAGRAM_PADDING_PX, watermark = watermark)

        /** Lays [graph] out with ELK and passes the result to [render] -- the step every ELK-based branch repeats. */
        inline fun layoutThenRender(
            graph: LayoutGraph,
            hints: LayoutHints = LayoutHints.DEFAULT,
            render: (LayoutResult) -> String,
        ): String = render(elkEngine.layout(graph = graph, hints = hints))
    }

    fun render(
        script: String,
        themeName: String,
        watermark: Boolean = false,
    ): DesktopRenderResult {
        return try {
            val ctx =
                when (val prepared = prepare(script = script, themeName = themeName)) {
                    is PipelineContext.Failure -> return DesktopRenderResult.Error(prepared.message)
                    is PipelineContext.Ready -> prepared
                }
            val env = RenderEnv(theme = ctx.theme, elkEngine = ctx.elkEngine, watermark = watermark)
            val svg =
                renderSvg(extracted = ctx.extracted, env = env)
                    ?: return DesktopRenderResult.Error(ERM_NOT_SUPPORTED_MESSAGE)
            DesktopRenderResult.Svg(svg = svg, simulatable = isSimulatable(ctx.extracted))
        } catch (e: ScriptEvaluationException) {
            DesktopRenderResult.Error(e.message ?: "Script-Fehler")
        } catch (e: Exception) {
            DesktopRenderResult.Error(e.message ?: "Unerwarteter Fehler")
        }
    }

    /** SVG of [extracted], or `null` for a diagram family the desktop cannot render (ERM). */
    private fun renderSvg(
        extracted: ExtractedDiagram,
        env: RenderEnv,
    ): String? =
        when (extracted) {
            is ExtractedDiagram.Uml -> renderUml(extracted = extracted, env = env)
            is ExtractedDiagram.C4 -> renderC4(extracted = extracted, env = env)
            is ExtractedDiagram.Sysml2 -> renderSysml2(extracted = extracted, env = env)
            is ExtractedDiagram.Bpmn -> renderBpmn(extracted = extracted, env = env)
            // V3.1.24: Blueprint / Journey-Map — no ELK, deterministic grid.
            //
            // KNOWN GAP (V3.7.4, design review P9): this overload of KumlSvgRenderer.toSvg
            // does not accept an SvgRenderOptions at all -- BlueprintGridSvg builds its
            // own <svg> root directly instead of going through SvgDocument.render, so the
            // watermark cannot be threaded through here. Same limitation the CLI's
            // RenderPipeline.renderBlueprint has documented since V3.1.24. `theme` IS now
            // passed through, though -- it was previously silently dropped (falling back
            // to PlainTheme()) even though a theme change elsewhere in this file already
            // re-triggers this render path; see P6's KDoc.
            is ExtractedDiagram.Blueprint ->
                KumlSvgRenderer.toSvg(model = extracted.model, diagram = extracted.diagram, theme = env.theme)
            // V3.4.1: ERM rendering is out of scope — planned for V3.4.2.
            is ExtractedDiagram.Erm -> null
        }

    private fun renderUml(
        extracted: ExtractedDiagram.Uml,
        env: RenderEnv,
    ): String {
        // V3.0.x — see CLI's RenderPipeline.kt for the full rationale: UML
        // sequence diagrams are the one diagram type where declaration order
        // is semantically meaningful, so pin it via LayoutHints.preserveNodeOrder.
        val hints = LayoutHints.DEFAULT.copy(preserveNodeOrder = extracted.diagram.type == DiagramType.SEQUENCE)
        return env.layoutThenRender(graph = UmlLayoutBridge.toLayoutGraph(diagram = extracted.diagram), hints = hints) { layout ->
            KumlSvgRenderer.toSvg(diagram = extracted.diagram, layoutResult = layout, theme = env.theme, options = env.svgOptions)
        }
    }

    private fun renderC4(
        extracted: ExtractedDiagram.C4,
        env: RenderEnv,
    ): String {
        val graph =
            C4LayoutBridge.toLayoutGraph(
                diagram = extracted.diagram,
                model = extracted.model,
                sizeProvider = C4ContentSizeProvider(model = extracted.model),
            )
        return env.layoutThenRender(graph = graph) { layout ->
            KumlSvgRenderer.toSvg(
                diagram = extracted.diagram,
                model = extracted.model,
                layoutResult = layout,
                theme = env.theme,
                options = env.svgOptions,
            )
        }
    }

    /**
     * The `Sysml2LayoutBridge.toLayoutGraph` and `KumlSvgRenderer.toSvg` overloads are per diagram
     * type, so each branch has to name its own type; everything else (ELK call, theme, options)
     * is shared. STM and ACT use [RenderEnv.paddingOptions], all others [RenderEnv.svgOptions].
     */
    private fun renderSysml2(
        extracted: ExtractedDiagram.Sysml2,
        env: RenderEnv,
    ): String {
        val model = extracted.model
        return when (val diagram = extracted.diagram) {
            is BdDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
            is IbdDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
            is UcDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
            is ReqDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
            is StmDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(
                        model = model,
                        diagram = diagram,
                        layoutResult = it,
                        theme = env.theme,
                        options = env.paddingOptions,
                    )
                }
            is ActDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(
                        model = model,
                        diagram = diagram,
                        layoutResult = it,
                        theme = env.theme,
                        options = env.paddingOptions,
                    )
                }
            is SeqDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
            is ParDiagram ->
                env.layoutThenRender(graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)) {
                    KumlSvgRenderer.toSvg(model = model, diagram = diagram, layoutResult = it, theme = env.theme, options = env.svgOptions)
                }
        }
    }

    private fun renderBpmn(
        extracted: ExtractedDiagram.Bpmn,
        env: RenderEnv,
    ): String {
        val model = extracted.model
        // Only the ELK-based branches need it; the choreography grid layout does not.
        val sizeProvider by lazy { BpmnContentSizeProvider(model) }
        return when (val diagram = extracted.diagram) {
            is ProcessDiagram -> {
                // A process diagram renders as a plain KumlDiagram of the process' own elements.
                val process = model.processes.firstOrNull { it.id == diagram.processId }
                val elements =
                    if (process != null) {
                        process.flowNodes + process.sequenceFlows + process.dataObjects
                    } else {
                        emptyList()
                    }
                val kumlDiagram = KumlDiagram(name = diagram.name, type = DiagramType.BPMN_PROCESS, elements = elements)
                env.layoutThenRender(
                    graph = BpmnLayoutBridge.toLayoutGraph(model = model, diagram = diagram, sizeProvider = sizeProvider),
                ) { layout ->
                    KumlSvgRenderer.toSvg(diagram = kumlDiagram, layoutResult = layout, theme = env.theme, options = env.svgOptions)
                }
            }
            is CollaborationDiagram ->
                env.layoutThenRender(
                    graph = BpmnLayoutBridge.toLayoutGraph(model = model, diagram = diagram, sizeProvider = sizeProvider),
                ) { layout ->
                    KumlSvgRenderer.toSvg(
                        model = model,
                        diagram = diagram,
                        layoutResult = layout,
                        theme = env.theme,
                        options = env.svgOptions,
                    )
                }
            // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
            is ChoreographyDiagram ->
                KumlSvgRenderer.toSvg(
                    model = model,
                    diagram = diagram,
                    layoutResult = ChoreographyGridLayout.layout(model = model, diagram = diagram),
                    theme = env.theme,
                    options = env.svgOptions,
                )
            is ConversationDiagram ->
                env.layoutThenRender(
                    graph = BpmnLayoutBridge.toLayoutGraph(model = model, diagram = diagram, sizeProvider = sizeProvider),
                ) { layout ->
                    KumlSvgRenderer.toSvg(
                        model = model,
                        diagram = diagram,
                        layoutResult = layout,
                        theme = env.theme,
                        options = env.svgOptions,
                    )
                }
        }
    }

    /**
     * Prepares a live simulation session for [script]: evaluates it, extracts a
     * [UmlStateMachine] (UML STATE diagram, or SysML-2 STM translated via
     * [Sysml2StateMachineAdapter]), lays it out exactly like [render] would, and renders one
     * SVG with EVERY vertex's highlight ring present but hidden
     * ([SvgRenderOptions.preparedHighlightVertexIds]) — the desktop then flips rings
     * visible/invisible per simulation step via a DOM patch instead of re-rendering.
     *
     * `paddingPx = DIAGRAM_PADDING_PX` for the SysML-2 STM path is INTENTIONAL and must stay identical to the
     * [RenderEnv.paddingOptions] used by the regular STM branch of [render] — otherwise the preview jumps when a
     * simulation starts (see the render pipeline's own `paddingOpts` comment).
     *
     * `watermark` is always `false` here regardless of the app's watermark setting — a
     * simulation session is a working view, not an export (see plan §7.2).
     */
    fun prepareSimulation(
        script: String,
        themeName: String,
    ): SimulationPrepareResult =
        try {
            when (val ctx = prepare(script = script, themeName = themeName)) {
                is PipelineContext.Failure -> SimulationPrepareResult.Failed(ctx.message)
                is PipelineContext.Ready -> prepareSimulationFromContext(ctx)
            }
        } catch (e: ScriptEvaluationException) {
            SimulationPrepareResult.Failed(e.message ?: "Script-Fehler")
        } catch (e: Exception) {
            SimulationPrepareResult.Failed(e.message ?: "Unerwarteter Fehler")
        }

    private fun prepareSimulationFromContext(ctx: PipelineContext.Ready): SimulationPrepareResult {
        val theme = ctx.theme
        val elkEngine = ctx.elkEngine
        return when (val extracted = ctx.extracted) {
            is ExtractedDiagram.Uml -> {
                if (extracted.diagram.type != DiagramType.STATE) {
                    return SimulationPrepareResult.Unsupported("diagram type is not simulatable")
                }
                val sm =
                    extracted.diagram.elements
                        .filterIsInstance<UmlStateMachine>()
                        .firstOrNull()
                        ?: return SimulationPrepareResult.Failed("STATE diagram has no UmlStateMachine element")
                val graph = UmlLayoutBridge.toLayoutGraph(diagram = extracted.diagram)
                val layout = elkEngine.layout(graph = graph, hints = LayoutHints.DEFAULT)
                val svg =
                    KumlSvgRenderer.toSvg(
                        diagram = extracted.diagram,
                        layoutResult = layout,
                        theme = theme,
                        options =
                            SvgRenderOptions(
                                watermark = false,
                                preparedHighlightVertexIds = allVertexIds(sm),
                                preparedHighlightEdgeIds = sm.transitions.map { it.id }.toSet(),
                            ),
                    )
                SimulationPrepareResult.Ready(stateMachine = sm, svg = svg)
            }
            is ExtractedDiagram.Sysml2 -> {
                val diagram = extracted.diagram
                if (diagram !is StmDiagram) return SimulationPrepareResult.Unsupported("diagram type is not simulatable")
                val model = extracted.model
                val sm = Sysml2StateMachineAdapter.toUmlStateMachine(model = model, diagram = diagram)
                val layout =
                    elkEngine.layout(
                        graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                        hints = LayoutHints.DEFAULT,
                    )
                val svg =
                    KumlSvgRenderer.toSvg(
                        model = model,
                        diagram = diagram,
                        layoutResult = layout,
                        theme = theme,
                        options =
                            SvgRenderOptions(
                                paddingPx = DIAGRAM_PADDING_PX,
                                watermark = false,
                                preparedHighlightVertexIds = allVertexIds(sm),
                                preparedHighlightEdgeIds = sm.transitions.map { it.id }.toSet(),
                            ),
                    )
                SimulationPrepareResult.Ready(stateMachine = sm, svg = svg)
            }
            else -> SimulationPrepareResult.Unsupported("diagram type is not simulatable")
        }
    }

    /** Recursively collects every vertex ID in [sm], including nested [UmlState.substates]. */
    private fun allVertexIds(sm: UmlStateMachine): Set<String> {
        val ids = mutableSetOf<String>()

        fun collect(vertices: List<UmlVertex>) {
            for (v in vertices) {
                ids += v.id
                if (v is UmlState && v.substates.isNotEmpty()) collect(v.substates)
            }
        }
        collect(sm.vertices)
        return ids
    }
}
