package dev.kuml.asciidoc

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
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.layout.bridge.bpmn.ChoreographyGridLayout
import dev.kuml.layout.elk.ElkLayoutEngine
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
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

/**
 * Internal glue: evaluate a kUML script string and render to SVG/PNG bytes.
 *
 * Spiegelt `MarkdownRenderPipeline` aus `kuml-docs/kuml-markdown` — gleiche
 * Layout-/Theme-Auswahl, gleiche API.
 *
 * **Coverage** (V0.23.3): all five diagram families render here —
 * [ExtractedDiagram.Uml], [ExtractedDiagram.C4], [ExtractedDiagram.Sysml2],
 * [ExtractedDiagram.Bpmn], and [ExtractedDiagram.Blueprint]. The dispatch
 * mirrors `kuml-cli`'s `RenderPipeline` (same bridges, same ELK engine, same
 * per-kind spacing tweaks are intentionally *not* duplicated here — Antora
 * docs favour the plain default spacing over the CLI's diagram-specific
 * hand-tuned hints).
 *
 * Theme resolution: defaults to [PlainTheme] (undecorated, documentation-friendly),
 * but a per-block `theme="..."` AsciiDoc attribute can select any theme registered
 * in [ThemeRegistry] — see [AsciidocProcessor].
 */
internal object AsciidocRenderPipeline {
    private val layoutEngine = ElkLayoutEngine()
    private val defaultTheme: KumlTheme = PlainTheme()

    init {
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
    }

    /** Resolves a theme by name from [ThemeRegistry], falling back to [PlainTheme] when `null`. */
    internal fun resolveTheme(themeName: String?): KumlTheme {
        if (themeName == null) return defaultTheme
        return ThemeRegistry.get(themeName)
            ?: throw ScriptEvaluationException(
                "Unknown theme: '$themeName'. Registered themes: ${ThemeRegistry.names()}",
            )
    }

    internal fun evaluate(
        source: String,
        virtualName: String,
    ): ExtractedDiagram {
        val evalResult = KumlScriptHost.eval(source, virtualName)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed in '$virtualName':\n$message")
        }
        val success =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script '$virtualName' produced no result")
        return DiagramExtractor.extractAny(success.value.returnValue, File(virtualName))
    }

    internal fun renderSvg(
        extracted: ExtractedDiagram,
        theme: KumlTheme = defaultTheme,
    ): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layoutGraph = UmlLayoutBridge.toLayoutGraph(extracted.diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(extracted.diagram, layoutResult, theme)
            }
            is ExtractedDiagram.Blueprint -> KumlSvgRenderer.toSvg(extracted.model, extracted.diagram, theme)
            is ExtractedDiagram.C4 -> {
                val sizeProvider = C4ContentSizeProvider(extracted.model)
                val layoutGraph = C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(extracted.diagram, extracted.model, layoutResult, theme)
            }
            is ExtractedDiagram.Sysml2 -> renderSysml2Svg(extracted, theme)
            is ExtractedDiagram.Bpmn -> renderBpmnSvg(extracted, theme)
            is ExtractedDiagram.Erm -> throw ermRenderingNotYetSupported()
        }

    internal fun renderPng(
        extracted: ExtractedDiagram,
        widthPx: Int,
        theme: KumlTheme = defaultTheme,
    ): ByteArray =
        when (extracted) {
            is ExtractedDiagram.Uml -> {
                val layoutGraph = UmlLayoutBridge.toLayoutGraph(extracted.diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlPngRenderer.toPng(extracted.diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.Blueprint -> {
                val svg = KumlSvgRenderer.toSvg(extracted.model, extracted.diagram, theme)
                KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.C4 -> {
                val sizeProvider = C4ContentSizeProvider(extracted.model)
                val layoutGraph = C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlPngRenderer.toPng(extracted.diagram, extracted.model, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.Sysml2 -> {
                val svg = renderSysml2Svg(extracted, theme)
                KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.Bpmn -> {
                val svg = renderBpmnSvg(extracted, theme)
                KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx))
            }
            is ExtractedDiagram.Erm -> throw ermRenderingNotYetSupported()
        }

    /** Display name used for `image::…[alt]` alt text and similar. */
    internal fun diagramName(extracted: ExtractedDiagram): String =
        when (extracted) {
            is ExtractedDiagram.Uml -> extracted.diagram.name
            is ExtractedDiagram.Blueprint -> extracted.diagram.name
            is ExtractedDiagram.C4 -> extracted.diagram.name
            is ExtractedDiagram.Sysml2 -> extracted.diagram.name
            is ExtractedDiagram.Bpmn -> extracted.diagram.name
            is ExtractedDiagram.Erm -> extracted.diagram.name
        }

    /**
     * ERM rendering is out of scope for V3.4.1 — the metamodel, DSL and
     * `kuml validate` wiring land here, the renderer (Martin/crow's-foot
     * notation first) comes in V3.4.2.
     */
    private fun ermRenderingNotYetSupported(): ScriptEvaluationException =
        ScriptEvaluationException(
            "ERM rendering is not yet supported — planned for kUML V3.4.2. " +
                "V3.4.1 only supports `kuml validate` for ERM scripts.",
        )

    /**
     * SysML 2 dispatches per concrete diagram type — there is no generic
     * `toSvg(Sysml2Model, Sysml2Diagram, …)` overload, each of the eight
     * diagram kinds has its own `toSvg` overload (mirrors `RenderPipeline.renderSysml2`).
     */
    private fun renderSysml2Svg(
        extracted: ExtractedDiagram.Sysml2,
        theme: KumlTheme,
    ): String {
        val model = extracted.model
        return when (val diagram = extracted.diagram) {
            is BdDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is IbdDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is UcDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is ReqDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is StmDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is ActDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is SeqDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is ParDiagram -> {
                val layoutGraph =
                    Sysml2LayoutBridge.toLayoutGraph(
                        model,
                        diagram,
                        Sysml2LayoutBridge.parContentAwareSizeProvider(model),
                    )
                val layoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
        }
    }

    /**
     * BPMN dispatch, mirroring `kuml-cli`'s `RenderPipeline.renderBpmn`:
     * - [ProcessDiagram] projects onto a plain [KumlDiagram] (its flow nodes +
     *   sequence flows) and uses the generic `toSvg(KumlDiagram, …)` overload —
     *   there is no dedicated `toSvg(BpmnModel, ProcessDiagram, …)` overload.
     * - [CollaborationDiagram] / [ConversationDiagram] go through the shared ELK
     *   layout engine via [BpmnLayoutBridge].
     * - [ChoreographyDiagram] bypasses ELK entirely (deterministic grid layout).
     */
    private fun renderBpmnSvg(
        extracted: ExtractedDiagram.Bpmn,
        theme: KumlTheme,
    ): String {
        val model = extracted.model
        return when (val diagram = extracted.diagram) {
            is ProcessDiagram -> {
                val process = model.processes.firstOrNull { it.id == diagram.processId }
                val elements = process?.renderableElements() ?: emptyList()
                val kumlDiagram = KumlDiagram(name = diagram.name, type = DiagramType.BPMN_PROCESS, elements = elements)
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(kumlDiagram, layoutResult, theme)
            }
            is CollaborationDiagram -> {
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is ConversationDiagram -> {
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = layoutEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
            is ChoreographyDiagram -> {
                val layoutResult: LayoutResult = ChoreographyGridLayout.layout(model, diagram)
                KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme)
            }
        }
    }
}
