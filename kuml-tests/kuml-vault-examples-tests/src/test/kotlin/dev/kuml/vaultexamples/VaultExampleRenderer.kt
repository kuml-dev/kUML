package dev.kuml.vaultexamples

import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlContentSizeProvider
import dev.kuml.layout.bridge.UmlLayoutBridge
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
    fun render(
        script: String,
        themeName: String = "elegant",
    ): RenderResult {
        ensureInit()
        return try {
            val evalResult = KumlScriptHost.eval(script)
            val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
                val msg = errors.joinToString("\n") { it.message }
                return RenderResult(null, null, msg.ifBlank { "Script-Auswertung fehlgeschlagen" })
            }
            val success =
                evalResult as? ResultWithDiagnostics.Success
                    ?: return RenderResult(null, null, "Kein Ergebnis aus dem Script")

            val extracted =
                DiagramExtractor.extractAny(
                    success.value.returnValue,
                    File("inline.kuml.kts"),
                )

            val theme =
                ThemeRegistry.get(themeName)
                    ?: ThemeRegistry.get("plain")
                    ?: return RenderResult(null, null, "Theme nicht gefunden")

            val elkEngine =
                LayoutEngineRegistry.get("elk.layered")
                    ?: return RenderResult(null, null, "ELK-Layout-Engine nicht verfügbar")

            val paddingOpts = SvgRenderOptions(paddingPx = 64f)

            when (extracted) {
                is ExtractedDiagram.Uml -> {
                    // Content-aware sizing — without this, all nodes fall back to the
                    // 160×80 default and enum literals (e.g. OrderStatus.CANCELLED) overflow
                    // the box.
                    val sizeProvider = UmlContentSizeProvider(extracted.diagram)
                    val graph = UmlLayoutBridge.toLayoutGraph(extracted.diagram, sizeProvider)
                    val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
                    val svg = KumlSvgRenderer.toSvg(extracted.diagram, layout, theme)
                    val latex =
                        runCatching {
                            KumlLatexRenderer.toLatex(extracted.diagram, layout)
                        }.getOrNull()
                    RenderResult(svg, latex, null)
                }

                is ExtractedDiagram.C4 -> {
                    val sizeProvider = C4ContentSizeProvider(extracted.model)
                    val graph = C4LayoutBridge.toLayoutGraph(extracted.diagram, extracted.model, sizeProvider)
                    val layout = elkEngine.layout(graph, LayoutHints.DEFAULT)
                    val svg = KumlSvgRenderer.toSvg(extracted.diagram, extracted.model, layout, theme)
                    // C4 LaTeX support added in feature/c4-latex-renderer (not yet on master)
                    RenderResult(svg, null, null)
                }

                is ExtractedDiagram.Sysml2 -> {
                    val model = extracted.model
                    val svg =
                        when (val diagram = extracted.diagram) {
                            is BdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                            is IbdDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                            is UcDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                            is ReqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                            is StmDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                    paddingOpts,
                                )
                            is ActDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                    paddingOpts,
                                )
                            is SeqDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                            is ParDiagram ->
                                KumlSvgRenderer.toSvg(
                                    model,
                                    diagram,
                                    elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    theme,
                                )
                        }
                    // SysML2 LaTeX: attempt each overload via runCatching
                    val latex =
                        when (val diagram = extracted.diagram) {
                            is BdDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is IbdDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is UcDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is ReqDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is StmDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is ActDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is SeqDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                            is ParDiagram ->
                                runCatching {
                                    KumlLatexRenderer.toLatex(
                                        model,
                                        diagram,
                                        elkEngine.layout(Sysml2LayoutBridge.toLayoutGraph(model, diagram), LayoutHints.DEFAULT),
                                    )
                                }.getOrNull()
                        }
                    RenderResult(svg, latex, null)
                }
            }
        } catch (e: Exception) {
            RenderResult(null, null, "Render-Exception: ${e.javaClass.simpleName}: ${e.message}")
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
