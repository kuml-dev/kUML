package dev.kuml.vaultexamples

import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.LayoutSpecRunner
import dev.kuml.layout.bridge.SpecDiagramKind
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.layout.bridge.erm.ErmChenLayoutBridge
import dev.kuml.layout.bridge.erm.ErmChenSizeProvider
import dev.kuml.layout.bridge.erm.ErmContentSizeProvider
import dev.kuml.layout.bridge.erm.ErmIdef1xLayoutBridge
import dev.kuml.layout.bridge.erm.ErmLayoutBridge
import dev.kuml.layout.bridge.layoutHintsFor
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.UcDiagram
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

data class RenderResult(
    val svg: String?,
    val latex: String?,
    val error: String?,
)

object VaultExampleRenderer {
    /** Lays a SysML 2 diagram out with its `layout { }` block applied, like the CLI pipeline does. */
    private fun sysml2Layout(
        engine: dev.kuml.layout.KumlLayoutEngine,
        model: dev.kuml.sysml2.Sysml2Model,
        diagram: dev.kuml.sysml2.Sysml2Diagram,
    ): dev.kuml.layout.LayoutResult {
        val graph =
            when (diagram) {
                is BdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is IbdDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is UcDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is ReqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is StmDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is ActDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is SeqDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
                is ParDiagram -> Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram)
            }
        val kind =
            when (diagram) {
                is BdDiagram -> SpecDiagramKind.SYSML2_BDD
                is IbdDiagram -> SpecDiagramKind.SYSML2_IBD
                is UcDiagram -> SpecDiagramKind.SYSML2_UC
                is ReqDiagram -> SpecDiagramKind.SYSML2_REQ
                is StmDiagram -> SpecDiagramKind.SYSML2_STM
                is ActDiagram -> SpecDiagramKind.SYSML2_ACT
                is SeqDiagram -> SpecDiagramKind.SYSML2_SEQ
                is ParDiagram -> SpecDiagramKind.SYSML2_PAR
            }
        return LayoutSpecRunner
            .layout(
                engine = engine,
                graph = graph,
                hints = Sysml2LayoutBridge.layoutHintsFor(model = model, diagram = diagram),
                spec = diagram.layout,
                kind = kind,
            ).result
    }

    private fun umlKind(type: DiagramType): SpecDiagramKind =
        when (type) {
            DiagramType.SEQUENCE -> SpecDiagramKind.UML_SEQUENCE
            DiagramType.TIMING -> SpecDiagramKind.UML_TIMING
            else -> SpecDiagramKind.UML_CLASS
        }

    fun render(
        script: String,
        themeName: String = "elegant",
    ): RenderResult {
        ensureInit()
        return try {
            val evalResult = KumlScriptHost.eval(code = script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return RenderResult(svg = null, latex = null, error = msg.ifBlank { "Script-Auswertung fehlgeschlagen" })
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return RenderResult(svg = null, latex = null, error = "Kein Ergebnis aus dem Script")

            val extracted =
                DiagramExtractor.extractAny(
                    returnValue = success.value.returnValue,
                    input = File("inline.kuml.kts"),
                )

            val theme =
                ThemeRegistry.get(themeName)
                    ?: ThemeRegistry.get("plain")
                    ?: return RenderResult(svg = null, latex = null, error = "Theme nicht gefunden")

            val elkEngine =
                LayoutEngineRegistry.get("elk.layered")
                    ?: return RenderResult(svg = null, latex = null, error = "ELK-Layout-Engine nicht verfügbar")

            val paddingOpts = SvgRenderOptions(paddingPx = 64f)

            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    // Content-aware sizing — without this, all nodes fall back to the
                    // 160×80 default and enum literals (e.g. OrderStatus.CANCELLED) overflow
                    // the box.
                    val sizeProvider = UmlContentSizeProvider(diagram = extracted.diagram)
                    val graph = UmlLayoutBridge.toLayoutGraph(diagram = extracted.diagram, sizeProvider = sizeProvider)
                    val layout =
                        LayoutSpecRunner
                            .layout(
                                engine = elkEngine,
                                graph = graph,
                                hints = LayoutHints.DEFAULT,
                                spec = extracted.diagram.layout,
                                kind = umlKind(extracted.diagram.type),
                            ).result
                    val svg = KumlSvgRenderer.toSvg(diagram = extracted.diagram, layoutResult = layout, theme = theme)
                    val latex =
                        runCatching {
                            KumlLatexRenderer.toLatex(diagram = extracted.diagram, layoutResult = layout)
                        }.getOrNull()
                    RenderResult(svg = svg, latex = latex, error = null)
                }

                is ExtractedDiagram.C4 -> {
                    val sizeProvider = C4ContentSizeProvider(model = extracted.model)
                    val graph =
                        C4LayoutBridge.toLayoutGraph(
                            diagram = extracted.diagram,
                            model = extracted.model,
                            sizeProvider = sizeProvider,
                        )
                    val layout =
                        LayoutSpecRunner
                            .layout(
                                engine = elkEngine,
                                graph = graph,
                                hints = LayoutHints.DEFAULT,
                                spec = extracted.diagram.layout,
                                kind = SpecDiagramKind.C4,
                            ).result
                    val svg =
                        KumlSvgRenderer.toSvg(
                            diagram = extracted.diagram,
                            model = extracted.model,
                            layoutResult = layout,
                            theme = theme,
                        )
                    // C4 LaTeX support added in feature/c4-latex-renderer (not yet on master)
                    RenderResult(svg = svg, latex = null, error = null)
                }

                is ExtractedDiagram.Sysml2 -> {
                    val model = extracted.model
                    val svg =
                        when (val diagram = extracted.diagram) {
                            is BdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                            is IbdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                            is UcDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                            is ReqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                            is StmDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                    options = paddingOpts,
                                )
                            is ActDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                    options = paddingOpts,
                                )
                            is SeqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                            is ParDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    theme = theme,
                                )
                        }
                    // SysML2 LaTeX: attempt each overload via runCatching
                    val latex =
                        when (val diagram = extracted.diagram) {
                            is BdDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is IbdDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is UcDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is ReqDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is StmDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is ActDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is SeqDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                            is ParDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model = model,
                                        diagram = diagram,
                                        layoutResult =
                                            sysml2Layout(engine = elkEngine, model = model, diagram = diagram),
                                    )
                                }.getOrNull()
                        }
                    RenderResult(svg = svg, latex = latex, error = null)
                }

                is ExtractedDiagram.Bpmn -> {
                    val svg =
                        when (val diagram = extracted.diagram) {
                            is ProcessDiagram -> {
                                val process =
                                    extracted.model.processes.firstOrNull { it.id == diagram.processId }
                                // renderableElements() flattens expanded SubProcess
                                // children into the element list — without it the inner
                                // flow-nodes are laid out but silently dropped at render
                                // time (mirrors the CLI RenderPipeline.renderBpmn path).
                                val elements =
                                    process?.renderableElements() ?: emptyList()
                                val kumlDiagram =
                                    KumlDiagram(
                                        name = diagram.name,
                                        type = DiagramType.BPMN_PROCESS,
                                        elements = elements,
                                    )
                                val layout =
                                    elkEngine.layout(
                                        graph = BpmnLayoutBridge.toLayoutGraph(model = extracted.model, diagram = diagram),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(diagram = kumlDiagram, layoutResult = layout, theme = theme)
                            }
                            is CollaborationDiagram -> {
                                val layout =
                                    elkEngine.layout(
                                        graph = BpmnLayoutBridge.toLayoutGraph(model = extracted.model, diagram = diagram),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(model = extracted.model, diagram = diagram, layoutResult = layout, theme = theme)
                            }
                            is ChoreographyDiagram -> {
                                // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
                                val layout = ChoreographyGridLayout.layout(model = extracted.model, diagram = diagram)
                                KumlSvgRenderer.toSvg(model = extracted.model, diagram = diagram, layoutResult = layout, theme = theme)
                            }
                            is ConversationDiagram -> {
                                val layout =
                                    elkEngine.layout(
                                        graph = BpmnLayoutBridge.toLayoutGraph(model = extracted.model, diagram = diagram),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(model = extracted.model, diagram = diagram, layoutResult = layout, theme = theme)
                            }
                        }
                    RenderResult(svg = svg, latex = null, error = null)
                }

                // V3.1.24: Blueprint / Journey-Map — no ELK, deterministic grid.
                is ExtractedDiagram.Blueprint -> {
                    val svg = KumlSvgRenderer.toSvg(model = extracted.model, diagram = extracted.diagram)
                    RenderResult(svg = svg, latex = null, error = null)
                }

                // V3.4.2 — ERM/Martin: laid out via ELK, same shape as UML class diagrams.
                // V3.4.5 — Chen and IDEF1X notations need their own layout bridges: Chen
                // expands attributes/relationships into their own layout nodes (diamonds
                // + ovals), IDEF1X injects synthetic category-circle nodes. Using the
                // generic ErmLayoutBridge for these produces a layout graph that the
                // Chen/IDEF1X SVG renderer can't match up with, rendering an empty
                // diagram (only the outer frame). Mirrors the dispatch in
                // RenderPipeline.renderErm.
                is ExtractedDiagram.Erm -> {
                    // V3.4.x — widened FK-hub spacing (nodeToNode/edgeToEdge/layerToLayer)
                    // so dense entities (e.g. Order/Review with 3 FKs each) don't crowd.
                    // Parity achieved (fix/erm-martin-spacing, 2026-07-11): kuml-cli's
                    // RenderPipeline.renderErm/DumpJsonCommand.ermLayout, kuml-web's
                    // WebRenderPipeline, and kuml-docs/kuml-asciidoc's
                    // AsciidocRenderPipeline all now reference the same
                    // ErmLayoutBridge.WIDENED_SPACING_HINTS constant this vault renderer
                    // uses below, instead of the previously CLI-only-missing tuning.
                    // Re-verified visually: vault-example PNGs for example 39 (all four
                    // notations) match `kuml render` CLI output 1:1.
                    val ermHints = ErmLayoutBridge.WIDENED_SPACING_HINTS
                    val notation = extracted.diagram.notation
                    val graph =
                        when (notation) {
                            ErmNotation.CHEN ->
                                ErmChenLayoutBridge.toChenLayoutGraph(
                                    model = extracted.model,
                                    diagram = extracted.diagram,
                                    sizeProvider = ErmChenSizeProvider(model = extracted.model, diagram = extracted.diagram),
                                )
                            ErmNotation.IDEF1X ->
                                ErmIdef1xLayoutBridge.toLayoutGraph(
                                    model = extracted.model,
                                    diagram = extracted.diagram,
                                    sizeProvider =
                                        ErmContentSizeProvider(
                                            model = extracted.model,
                                            diagram = extracted.diagram,
                                            layoutDirection = ermHints.direction,
                                        ),
                                )
                            else ->
                                ErmLayoutBridge.toLayoutGraph(
                                    model = extracted.model,
                                    diagram = extracted.diagram,
                                    sizeProvider =
                                        ErmContentSizeProvider(
                                            model = extracted.model,
                                            diagram = extracted.diagram,
                                            layoutDirection = ermHints.direction,
                                        ),
                                )
                        }
                    val layout =
                        LayoutSpecRunner
                            .layout(
                                engine = elkEngine,
                                graph = graph,
                                hints = ermHints,
                                spec = extracted.diagram.layout,
                                kind = SpecDiagramKind.ERM,
                            ).result
                    val svg =
                        KumlSvgRenderer.toSvg(
                            model = extracted.model,
                            diagram = extracted.diagram,
                            layoutResult = layout,
                            theme = theme,
                        )
                    RenderResult(svg = svg, latex = null, error = null)
                }
            }
        } catch (e: Exception) {
            RenderResult(svg = null, latex = null, error = "Render-Exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Volatile private var initialized = false

    /** Explicit initialisation entry-point — safe to call multiple times. */
    fun init() {
        ensureInit()
    }

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            if (LayoutEngineRegistry.ids().isEmpty()) {
                LayoutEngineRegistry.register(GridLayoutEngineProvider())
                LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            }
            if (ThemeRegistry.names().isEmpty()) {
                ThemeRegistry.loadFromClasspath()
            }
            initialized = true
        }
    }
}

/** UML diagram and its layout, exactly as the vault renderer lays it out (for geometry tests). */
internal fun VaultExampleRenderer.umlLayoutOf(script: String): Pair<KumlDiagram, dev.kuml.layout.LayoutResult>? {
    init()
    val result = KumlScriptHost.eval(code = script)
    val success = result as? ResultWithDiagnostics.Success ?: return null
    val extracted = DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File("inline.kuml.kts"))
    val diagram = (extracted as? ExtractedDiagram.Uml)?.diagram ?: return null
    val engine = LayoutEngineRegistry.get("elk.layered") ?: return null
    val graph = UmlLayoutBridge.toLayoutGraph(diagram = diagram, sizeProvider = UmlContentSizeProvider(diagram = diagram))
    val layout =
        LayoutSpecRunner
            .layout(engine = engine, graph = graph, hints = LayoutHints.DEFAULT, spec = diagram.layout, kind = SpecDiagramKind.UML_CLASS)
            .result
    return diagram to layout
}

/** ERM (Martin) model and its layout, exactly as the vault renderer lays it out (for geometry tests). */
internal fun VaultExampleRenderer.ermLayoutOf(
    script: String,
    notation: ErmNotation = ErmNotation.MARTIN,
): Pair<dev.kuml.erm.model.ErmModel, dev.kuml.layout.LayoutResult>? {
    init()
    val result = KumlScriptHost.eval(code = script)
    val success = result as? ResultWithDiagnostics.Success ?: return null
    val extracted = DiagramExtractor.extractAny(returnValue = success.value.returnValue, input = File("inline.kuml.kts"))
    val erm = extracted as? ExtractedDiagram.Erm ?: return null
    if (erm.diagram.notation != notation) return null
    val engine = LayoutEngineRegistry.get("elk.layered") ?: return null
    val hints = ErmLayoutBridge.WIDENED_SPACING_HINTS
    val graph =
        ErmLayoutBridge.toLayoutGraph(
            model = erm.model,
            diagram = erm.diagram,
            sizeProvider = ErmContentSizeProvider(model = erm.model, diagram = erm.diagram, layoutDirection = hints.direction),
        )
    val layout =
        LayoutSpecRunner.layout(engine = engine, graph = graph, hints = hints, spec = erm.diagram.layout, kind = SpecDiagramKind.ERM).result
    return erm.model to layout
}
