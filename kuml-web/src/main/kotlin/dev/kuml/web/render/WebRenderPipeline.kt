package dev.kuml.web.render

import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptGuard
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.KumlLayoutEngine
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.bridge.bpmn.BpmnLayoutBridge
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import java.util.Base64
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Result type for [WebRenderPipeline.render].
 */
internal sealed class WebRenderResult {
    data class Svg(
        val svg: String,
        val durationMs: Long,
    ) : WebRenderResult()

    data class Png(
        val pngBytes: ByteArray,
        val durationMs: Long,
    ) : WebRenderResult()

    data class Latex(
        val tex: String,
        val durationMs: Long,
    ) : WebRenderResult()

    data class Error(
        val message: String,
    ) : WebRenderResult()
}

/**
 * Orchestrates the kUML render pipeline for the web server.
 *
 * Identical logic to [dev.kuml.cli.RenderPipeline] but returns String/ByteArray
 * instead of writing to a file.
 *
 * Engine and theme registries must be initialised before the first call via
 * [EngineRegistration.ensure].
 */
internal object WebRenderPipeline {
    // ELK is the default engine for all diagram types.
    // Grid layout is available via the layoutOverride = "grid" parameter (opt-in, experimental).

    /**
     * Evaluates [script] and renders to the requested [format] ("svg" or "png").
     *
     * @param script kUML script source code
     * @param format "svg" or "png"
     * @param themeName optional theme name; falls back to "kuml"
     * @param layoutOverride optional engine override: "grid", "elk", or null/"auto"
     * @param widthPx PNG width in pixels (ignored for SVG)
     */
    fun render(
        script: String,
        format: String,
        themeName: String?,
        layoutOverride: String?,
        widthPx: Int = 1024,
        standaloneTex: Boolean = false,
    ): WebRenderResult {
        val startMs = System.currentTimeMillis()
        return try {
            KumlScriptGuard.validate(script)
            val evalResult = KumlScriptHost.eval(script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return WebRenderResult.Error(msg.ifBlank { "Script evaluation failed" })
            }
            val successResult =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return WebRenderResult.Error("Script evaluation did not produce a result")

            val extracted = DiagramExtractor.extractAny(successResult.value.returnValue, java.io.File("inline.kuml.kts"))

            val resolvedThemeName = themeName ?: "kuml"
            val theme: KumlTheme =
                ThemeRegistry.get(resolvedThemeName)
                    ?: return WebRenderResult.Error(
                        "Unknown theme: '$resolvedThemeName'. Available: ${ThemeRegistry.names()}",
                    )

            val durationMs = System.currentTimeMillis() - startMs
            when (extracted) {
                is ExtractedDiagram.Uml -> renderUml(extracted, format, theme, layoutOverride, widthPx, durationMs, standaloneTex)
                is ExtractedDiagram.C4 -> renderC4(extracted, format, theme, widthPx, durationMs, standaloneTex)
                is ExtractedDiagram.Sysml2 -> renderSysml2(extracted, format, theme, widthPx, durationMs, standaloneTex)
                is ExtractedDiagram.Bpmn -> renderBpmn(extracted, format, theme, widthPx, durationMs)
                is ExtractedDiagram.Blueprint -> renderBlueprint(extracted, format, widthPx, durationMs)
            }
        } catch (e: ScriptEvaluationException) {
            WebRenderResult.Error(e.message ?: "Script error")
        } catch (e: Exception) {
            WebRenderResult.Error(e.message ?: "Unexpected error")
        }
    }

    private fun renderUml(
        extracted: ExtractedDiagram.Uml,
        format: String,
        theme: KumlTheme,
        layoutOverride: String?,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult {
        val diagram = extracted.diagram
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val engine = pickEngine(diagram, layoutOverride)
        val layoutResult: LayoutResult = engine.layout(layoutGraph, LayoutHints.DEFAULT)
        return when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(diagram, layoutResult, theme), durationMs)
            "png" -> {
                val bytes = KumlPngRenderer.toPng(diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
                WebRenderResult.Png(bytes, durationMs)
            }
            "latex" -> {
                val tex = KumlLatexRenderer.toLatex(diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex))
                WebRenderResult.Latex(tex, durationMs)
            }
            else -> WebRenderResult.Error("Unsupported format: $format. Use 'svg', 'png', or 'latex'.")
        }
    }

    private fun renderC4(
        extracted: ExtractedDiagram.C4,
        format: String,
        theme: KumlTheme,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult {
        val diagram = extracted.diagram
        val model = extracted.model
        val sizeProvider = C4ContentSizeProvider(model)
        val layoutGraph = C4LayoutBridge.toLayoutGraph(diagram, model, sizeProvider)
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: return WebRenderResult.Error("ELK layout engine not available")
        val layoutResult: LayoutResult = engine.layout(layoutGraph, LayoutHints.DEFAULT)
        return when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(diagram, model, layoutResult, theme), durationMs)
            "png" -> {
                val bytes = KumlPngRenderer.toPng(diagram, model, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
                WebRenderResult.Png(bytes, durationMs)
            }
            "latex" -> {
                val tex = KumlLatexRenderer.toLatex(diagram, model, layoutResult, LatexRenderOptions(standalone = standaloneTex))
                WebRenderResult.Latex(tex, durationMs)
            }
            else -> WebRenderResult.Error("Unsupported format: $format. Use 'svg', 'png', or 'latex'.")
        }
    }

    private fun renderSysml2(
        extracted: ExtractedDiagram.Sysml2,
        format: String,
        theme: KumlTheme,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult {
        val model = extracted.model
        val engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: return WebRenderResult.Error("ELK layout engine not available for SysML 2")
        return when (val diagram = extracted.diagram) {
            is BdDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Bdd(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is IbdDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Ibd(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is UcDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Uc(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is ReqDiagram -> {
                // V2.0.8+: same wider-spacing fix as CLI RenderPipeline — see
                // RenderPipeline.kt ReqDiagram block for the full rationale.
                val reqHints =
                    LayoutHints.DEFAULT.copy(
                        spacing =
                            LayoutHints.DEFAULT.spacing.copy(
                                nodeToNode = 80f,
                                edgeToEdge = 20f,
                                layerToLayer = 100f,
                            ),
                    )
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), reqHints)
                renderSysml2Req(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is StmDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Stm(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is ActDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Act(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is SeqDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Seq(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
            is ParDiagram -> {
                val layoutResult = engine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT)
                renderSysml2Par(model, diagram, layoutResult, theme, format, widthPx, durationMs, standaloneTex)
            }
        }
    }

    private fun renderSysml2Bdd(
        model: Sysml2Model,
        diagram: BdDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Ibd(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Uc(
        model: Sysml2Model,
        diagram: UcDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Req(
        model: Sysml2Model,
        diagram: ReqDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Stm(
        model: Sysml2Model,
        diagram: StmDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" ->
                WebRenderResult.Svg(
                    KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, SvgRenderOptions(paddingPx = 64f)),
                    durationMs,
                )
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Act(
        model: Sysml2Model,
        diagram: ActDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" ->
                WebRenderResult.Svg(
                    KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, SvgRenderOptions(paddingPx = 64f)),
                    durationMs,
                )
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Seq(
        model: Sysml2Model,
        diagram: SeqDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun renderSysml2Par(
        model: Sysml2Model,
        diagram: ParDiagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        format: String,
        widthPx: Int,
        durationMs: Long,
        standaloneTex: Boolean = false,
    ): WebRenderResult =
        when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme), durationMs)
            "png" ->
                WebRenderResult.Png(
                    KumlPngRenderer.toPng(model, diagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx)),
                    durationMs,
                )
            "latex" ->
                WebRenderResult.Latex(
                    KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions(standalone = standaloneTex)),
                    durationMs,
                )
            else -> WebRenderResult.Error("Unsupported format: $format")
        }

    private fun pickEngine(
        diagram: KumlDiagram,
        override: String?,
    ): KumlLayoutEngine {
        // CLI override takes precedence
        if (!override.isNullOrBlank() && override != "auto") {
            val engineId = override.normaliseEngineId()
            return LayoutEngineRegistry.get(engineId)
                ?: error("Layout engine '$override' not found. Available: ${LayoutEngineRegistry.ids().map { it.value }}")
        }
        // DSL metadata
        val dslEngine = (diagram.metadata[LayoutMetadataKeys.ENGINE] as? KumlMetaValue.Text)?.value
        if (dslEngine != null) {
            val engineId = dslEngine.normaliseEngineId()
            return LayoutEngineRegistry.get(engineId)
                ?: error("Layout engine '$dslEngine' (from diagram metadata) not found.")
        }
        // ELK as default for all diagram types (Grid via layoutOverride = "grid", opt-in)
        val kind = diagram.type.toDiagramKind()
        return LayoutEngineRegistry.pickFor(kind, LayoutEngineId("elk.layered"))
            ?: error("No layout engine available for diagram kind $kind.")
    }

    private fun String.normaliseEngineId(): LayoutEngineId =
        LayoutEngineId(
            when (this) {
                "elk" -> "elk.layered"
                "grid" -> "kuml.grid"
                else -> this
            },
        )

    /**
     * BPMN render branch for the web render pipeline (V3.1.6).
     *
     * Supports SVG and PNG output for both [ProcessDiagram] and [CollaborationDiagram].
     * Returns a generic unsupported-format error for other format values.
     */
    private fun renderBpmn(
        extracted: ExtractedDiagram.Bpmn,
        format: String,
        theme: KumlTheme,
        widthPx: Int,
        durationMs: Long,
    ): WebRenderResult {
        val model = extracted.model
        val bpmnDiagram = extracted.diagram
        val bpmnEngine =
            LayoutEngineRegistry.get("elk.layered")
                ?: return WebRenderResult.Error("ELK layout engine not available for BPMN rendering")
        return when (bpmnDiagram) {
            is ProcessDiagram -> {
                val process = model.processes.firstOrNull { it.id == bpmnDiagram.processId }
                val elements: List<dev.kuml.core.model.KumlElement> =
                    process?.renderableElements() ?: emptyList()
                val kumlDiagram =
                    KumlDiagram(
                        name = bpmnDiagram.name,
                        type = DiagramType.BPMN_PROCESS,
                        elements = elements,
                    )
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, bpmnDiagram)
                val layoutResult: LayoutResult = bpmnEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(kumlDiagram, layoutResult, theme), durationMs)
                    "png" -> {
                        val pngBytes = KumlPngRenderer.toPng(kumlDiagram, layoutResult, theme, PngRenderOptions(widthPx = widthPx))
                        WebRenderResult.Png(pngBytes, durationMs)
                    }
                    else -> WebRenderResult.Error("Unsupported format for BPMN: $format (svg, png supported)")
                }
            }
            is CollaborationDiagram -> {
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, bpmnDiagram)
                val layoutResult: LayoutResult = bpmnEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme), durationMs)
                    "png" -> {
                        val svg = KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme)
                        WebRenderResult.Png(KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx)), durationMs)
                    }
                    else -> WebRenderResult.Error("Unsupported format for BPMN: $format (svg, png supported)")
                }
            }
        }
    }

    /**
     * Blueprint / Journey-Map render branch (V3.1.24).
     * No ELK — deterministic grid geometry.
     */
    private fun renderBlueprint(
        extracted: ExtractedDiagram.Blueprint,
        format: String,
        widthPx: Int,
        durationMs: Long,
    ): WebRenderResult {
        val model = extracted.model
        val diagram = extracted.diagram
        return when (format) {
            "svg" -> WebRenderResult.Svg(KumlSvgRenderer.toSvg(model, diagram), durationMs)
            "png" -> {
                val svg = KumlSvgRenderer.toSvg(model, diagram)
                WebRenderResult.Png(KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = widthPx)), durationMs)
            }
            else -> WebRenderResult.Error("Unsupported format for Blueprint: $format (svg, png supported)")
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
}

/** Helper to base64-encode PNG bytes for JSON transport. */
internal fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
