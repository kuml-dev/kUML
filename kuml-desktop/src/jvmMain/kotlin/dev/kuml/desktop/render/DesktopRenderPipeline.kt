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
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnContentSizeProvider
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
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

internal object DesktopRenderPipeline {
    fun render(
        script: String,
        themeName: String,
        watermark: Boolean = false,
    ): DesktopRenderResult {
        DesktopEngineInit.ensure()
        return try {
            val evalResult = KumlScriptHost.eval(code = script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return DesktopRenderResult.Error(msg.ifBlank { "Script-Auswertung fehlgeschlagen" })
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return DesktopRenderResult.Error("Kein Ergebnis aus dem Script")

            val extracted =
                DiagramExtractor.extractAny(
                    returnValue = success.value.returnValue,
                    input = File("inline.kuml.kts"),
                )

            val theme =
                ThemeRegistry.get(themeName)
                    ?: ThemeRegistry.get("kuml")
                    ?: return DesktopRenderResult.Error("Theme '$themeName' nicht gefunden")

            val elkEngine =
                LayoutEngineRegistry.get("elk.layered")
                    ?: return DesktopRenderResult.Error("ELK-Layout-Engine nicht verfügbar")

            // V3.7.4 (design review P9) — opt-in "Powered by kUML" watermark, threaded through
            // every SvgRenderOptions use below so the toggle applies uniformly across diagram
            // types. KNOWN GAP: the Blueprint branch further down does not take an
            // SvgRenderOptions at all (see its own comment) — same limitation the CLI's
            // RenderPipeline.renderBlueprint has documented since V3.1.24.
            val svgOptions = SvgRenderOptions(watermark = watermark)
            val paddingOpts = SvgRenderOptions(paddingPx = 64f, watermark = watermark)

            val svg =
                when (extracted) {
                    is ExtractedDiagram.Uml -> {
                        val graph = UmlLayoutBridge.toLayoutGraph(diagram = extracted.diagram)
                        // V3.0.x — see CLI's RenderPipeline.kt for the full rationale: UML
                        // sequence diagrams are the one diagram type where declaration order
                        // is semantically meaningful, so pin it via LayoutHints.preserveNodeOrder.
                        val hints = LayoutHints.DEFAULT.copy(preserveNodeOrder = extracted.diagram.type == DiagramType.SEQUENCE)
                        val layout = elkEngine.layout(graph = graph, hints = hints)
                        KumlSvgRenderer.toSvg(diagram = extracted.diagram, layoutResult = layout, theme = theme, options = svgOptions)
                    }
                    is ExtractedDiagram.C4 -> {
                        val sizeProvider = C4ContentSizeProvider(model = extracted.model)
                        val graph =
                            C4LayoutBridge.toLayoutGraph(
                                diagram = extracted.diagram,
                                model = extracted.model,
                                sizeProvider = sizeProvider,
                            )
                        val layout = elkEngine.layout(graph = graph, hints = LayoutHints.DEFAULT)
                        KumlSvgRenderer.toSvg(
                            diagram = extracted.diagram,
                            model = extracted.model,
                            layoutResult = layout,
                            theme = theme,
                            options = svgOptions,
                        )
                    }
                    is ExtractedDiagram.Sysml2 -> {
                        val model = extracted.model
                        when (val diagram = extracted.diagram) {
                            is BdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                            is IbdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                            is UcDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                            is ReqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                            is StmDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = paddingOpts,
                                )
                            is ActDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = paddingOpts,
                                )
                            is SeqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                            is ParDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model = model,
                                    diagram = diagram,
                                    layoutResult =
                                        elkEngine.layout(
                                            graph = Sysml2LayoutBridge.toLayoutGraph(model = model, diagram = diagram),
                                            hints = LayoutHints.DEFAULT,
                                        ),
                                    theme = theme,
                                    options = svgOptions,
                                )
                        }
                    }
                    is ExtractedDiagram.Bpmn -> {
                        when (val diagram = extracted.diagram) {
                            is ProcessDiagram -> {
                                val process =
                                    extracted.model.processes.firstOrNull { it.id == diagram.processId }
                                val elements =
                                    if (process != null) {
                                        process.flowNodes + process.sequenceFlows + process.dataObjects
                                    } else {
                                        emptyList()
                                    }
                                val kumlDiagram =
                                    KumlDiagram(
                                        name = diagram.name,
                                        type = DiagramType.BPMN_PROCESS,
                                        elements = elements,
                                    )
                                val sizeProvider = BpmnContentSizeProvider(extracted.model)
                                val layout =
                                    elkEngine.layout(
                                        graph =
                                            BpmnLayoutBridge.toLayoutGraph(
                                                model = extracted.model,
                                                diagram = diagram,
                                                sizeProvider = sizeProvider,
                                            ),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(diagram = kumlDiagram, layoutResult = layout, theme = theme, options = svgOptions)
                            }
                            is CollaborationDiagram -> {
                                val sizeProvider = BpmnContentSizeProvider(extracted.model)
                                val layout =
                                    elkEngine.layout(
                                        graph =
                                            BpmnLayoutBridge.toLayoutGraph(
                                                model = extracted.model,
                                                diagram = diagram,
                                                sizeProvider = sizeProvider,
                                            ),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(
                                    model = extracted.model,
                                    diagram = diagram,
                                    layoutResult = layout,
                                    theme = theme,
                                    options = svgOptions,
                                )
                            }
                            is ChoreographyDiagram -> {
                                // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
                                val layout = ChoreographyGridLayout.layout(model = extracted.model, diagram = diagram)
                                KumlSvgRenderer.toSvg(
                                    model = extracted.model,
                                    diagram = diagram,
                                    layoutResult = layout,
                                    theme = theme,
                                    options = svgOptions,
                                )
                            }
                            is ConversationDiagram -> {
                                val sizeProvider = BpmnContentSizeProvider(extracted.model)
                                val layout =
                                    elkEngine.layout(
                                        graph =
                                            BpmnLayoutBridge.toLayoutGraph(
                                                model = extracted.model,
                                                diagram = diagram,
                                                sizeProvider = sizeProvider,
                                            ),
                                        hints = LayoutHints.DEFAULT,
                                    )
                                KumlSvgRenderer.toSvg(
                                    model = extracted.model,
                                    diagram = diagram,
                                    layoutResult = layout,
                                    theme = theme,
                                    options = svgOptions,
                                )
                            }
                        }
                    }
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
                        KumlSvgRenderer.toSvg(model = extracted.model, diagram = extracted.diagram, theme = theme)
                    // V3.4.1: ERM rendering is out of scope — planned for V3.4.2.
                    is ExtractedDiagram.Erm ->
                        return DesktopRenderResult.Error(
                            "ERM-Rendering wird noch nicht unterstützt — geplant für kUML V3.4.2. " +
                                "V3.4.1 unterstützt für ERM-Skripte nur `kuml validate`.",
                        )
                }
            DesktopRenderResult.Svg(svg)
        } catch (e: ScriptEvaluationException) {
            DesktopRenderResult.Error(e.message ?: "Script-Fehler")
        } catch (e: Exception) {
            DesktopRenderResult.Error(e.message ?: "Unerwarteter Fehler")
        }
    }
}
