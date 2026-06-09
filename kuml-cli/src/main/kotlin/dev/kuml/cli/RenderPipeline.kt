package dev.kuml.cli

import dev.kuml.core.config.KumlConfig
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.bridge.C4LayoutBridge
import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.layout.bridge.UmlLayoutBridge
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.renderer.theme.core.KumlTheme
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
import java.io.IOException
import java.nio.file.Path
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Maps a [DiagramType] to the default [DiagramKind] used for engine selection.
 *
 * UML diagram types that have dedicated [DiagramKind] values map explicitly;
 * all others fall back to [DiagramKind.Generic].
 */
private fun DiagramType.toDiagramKind(): DiagramKind =
    when (this) {
        DiagramType.CLASS -> DiagramKind.UmlClass
        DiagramType.COMPONENT -> DiagramKind.UmlComponent
        DiagramType.USE_CASE -> DiagramKind.UmlUseCase
        DiagramType.STATE -> DiagramKind.UmlState
        DiagramType.SEQUENCE -> DiagramKind.UmlSequence
        else -> DiagramKind.Generic
    }

/**
 * Grid-first default: diagrams that prefer `kuml.grid` when no explicit engine override is set.
 */
private val GRID_DEFAULT_KINDS =
    setOf(
        DiagramKind.UmlClass,
        DiagramKind.UmlComponent,
        DiagramKind.UmlUseCase,
        // UmlState intentionally excluded: state machines need ELK's hierarchical
        // routing to produce compact vertical layouts with curved back-edges.
    )

/**
 * Orchestrates the kUML render pipeline: Script → Layout → Renderer → File.
 *
 * Branches on UML vs C4 at the extraction step. The downstream stages
 * (layout, renderer, file write) are dispatched on [ExtractedDiagram].
 *
 * This object is the internal glue between all pipeline stages. It is not part
 * of the public API; all user-facing interaction goes through [RenderCommand].
 */
internal object RenderPipeline {
    // Registry is initialised lazily on first use; tests can pre-populate it.
    private fun ensureEnginesRegistered() {
        if (LayoutEngineRegistry.ids().isEmpty()) {
            // Register grid FIRST so pickFor(kind, null) prefers grid over elk
            // for diagram kinds that both engines support.
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }
    }

    /**
     * Wählt die Layout-Engine für [diagram] aus.
     *
     * Priorität (höchste zuerst):
     * 1. [cliOverride] — `--layout=grid` oder `--layout=elk` vom CLI-Flag
     * 2. `kuml.layout.engine`-Metadaten im Diagramm (DSL-Ebene)
     * 3. Grid als Default für CLASS / COMPONENT / USE_CASE / STATE
     * 4. ELK als Default für alle anderen Typen
     */
    private fun pickEngine(
        diagram: KumlDiagram,
        cliOverride: String?,
    ): dev.kuml.layout.KumlLayoutEngine {
        ensureEnginesRegistered()
        // 1. CLI-Flag gewinnt immer
        if (cliOverride != null && cliOverride != "auto") {
            val engineId = cliOverride.normaliseEngineId()
            return LayoutEngineRegistry.get(engineId)
                ?: error(
                    "Layout engine '$cliOverride' not found. Available: ${LayoutEngineRegistry.ids().map { it.value }}",
                )
        }
        // 2. DSL-Metadaten
        val dslEngine = (diagram.metadata[LayoutMetadataKeys.ENGINE] as? KumlMetaValue.Text)?.value
        if (dslEngine != null) {
            val engineId = dslEngine.normaliseEngineId()
            return LayoutEngineRegistry.get(engineId)
                ?: error("Layout engine '$dslEngine' (from diagram metadata) not found.")
        }
        // 3+4. Typ-basierter Default
        val kind = diagram.type.toDiagramKind()
        val preferredId =
            if (kind in GRID_DEFAULT_KINDS) LayoutEngineId("kuml.grid") else LayoutEngineId("elk.layered")
        return LayoutEngineRegistry.pickFor(kind, preferredId)
            ?: error("No layout engine available for diagram kind $kind.")
    }

    /**
     * Normalisiert Kurzformen auf vollständige Engine-IDs:
     * - `"elk"` → `"elk.layered"`
     * - `"grid"` → `"kuml.grid"`
     * - Alle anderen Werte bleiben unverändert.
     */
    private fun String.normaliseEngineId(): LayoutEngineId =
        LayoutEngineId(
            when (this) {
                "elk" -> "elk.layered"
                "grid" -> "kuml.grid"
                else -> this
            },
        )

    /**
     * Runs the full render pipeline for the given input script.
     *
     * @param input Path to the `*.kuml.kts` script file.
     * @param output Destination path for the rendered output file.
     * @param format Output format: `"svg"`, `"png"`, or `"latex"`.
     * @param width Width in pixels (used only for PNG output).
     * @param themeName Optional theme name from CLI; takes precedence over config.
     * @param config Loaded `kuml.config.kts` configuration (or [KumlConfig.DEFAULT]).
     * @param layoutEngineOverride Optional CLI `--layout` flag value (`"auto"`, `"grid"`, `"elk"`).
     *   `"auto"` or `null` → per-diagram-type default (grid for class/component/use-case/state).
     * @param latexStandalone When `true` and format is `"latex"`, emit a complete
     *   `\documentclass{standalone}` document instead of a bare tikzpicture snippet.
     *   Only meaningful for LaTeX output — ignored for SVG / PNG.
     * @throws ScriptEvaluationException if the script fails to compile or evaluate.
     * @throws IOException if the output file cannot be written.
     */
    internal fun run(
        input: File,
        output: Path,
        format: String,
        width: Int,
        themeName: String?,
        config: KumlConfig = KumlConfig.DEFAULT,
        layoutEngineOverride: String? = null,
        latexStandalone: Boolean = false,
    ) {
        // 1. Evaluate script
        val evalResult = KumlScriptHost.eval(input)

        // Check for compilation/evaluation errors
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        if (errors.isNotEmpty() || evalResult is ResultWithDiagnostics.Failure) {
            val message = errors.joinToString("\n") { it.message }
            throw ScriptEvaluationException("Script evaluation failed:\n$message")
        }

        // 2. Extract diagram — UML or C4
        val successResult =
            evalResult as? ResultWithDiagnostics.Success
                ?: throw ScriptEvaluationException("Script evaluation did not produce a result")

        val extracted = DiagramExtractor.extractAny(successResult.value.returnValue, input)

        // 3. Theme — layering: CLI flag > config file > built-in default "plain"
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
        val resolvedThemeName =
            themeName
                ?: config.render.themeName
                ?: "plain"
        val baseTheme: KumlTheme =
            ThemeRegistry.get(resolvedThemeName)
                ?: throw ScriptEvaluationException(
                    "Unknown theme: '$resolvedThemeName'. " +
                        "Registered themes: ${ThemeRegistry.names()}",
                )
        val theme: KumlTheme =
            config.render.stereotypeOverrides
                ?.let { patch -> baseTheme.copy(stereotypes = patch.applyTo(baseTheme.stereotypes)) }
                ?: baseTheme

        // 4–6. Branch on diagram kind: layout → render → write
        try {
            when (extracted) {
                is ExtractedDiagram.Uml ->
                    renderUml(extracted, output, format, width, theme, layoutEngineOverride, latexStandalone)
                is ExtractedDiagram.C4 -> renderC4(extracted, output, format, width, theme)
                is ExtractedDiagram.Sysml2 -> renderSysml2(extracted, output, format, width, theme)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (e is ScriptEvaluationException) throw e
            throw IOException("Failed to write output file: ${e.message}", e)
        }
    }

    private fun renderUml(
        extracted: ExtractedDiagram.Uml,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
        layoutEngineOverride: String? = null,
        latexStandalone: Boolean = false,
    ) {
        val diagram = extracted.diagram
        val layoutGraph = UmlLayoutBridge.toLayoutGraph(diagram)
        val engine = pickEngine(diagram, layoutEngineOverride)
        val layoutResult: LayoutResult = engine.layout(layoutGraph, LayoutHints.DEFAULT)
        when (format) {
            "svg" -> KumlSvgRenderer.toSvgFile(diagram, layoutResult, output, theme)
            "png" -> {
                val pngBytes =
                    KumlPngRenderer.toPng(diagram, layoutResult, theme, PngRenderOptions(widthPx = width))
                writeBinary(output, pngBytes)
            }
            "latex" -> {
                val opts = LatexRenderOptions(standalone = latexStandalone)
                val tex = KumlLatexRenderer.toLatex(diagram, layoutResult, opts)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    /**
     * SysML 2 render-Branch (V2.0.4 BDD, V2.0.6 IBD, V2.0.7 UC, V2.0.8 REQ,
     * V2.0.9 STM, V2.0.10 ACT, V2.0.11 SEQ, V2.0.12 PAR).
     *
     * Bridge → ELK Layout → Render (SVG / LaTeX / PNG). Dispatch in zwei
     * Stufen:
     *  1. auf den Diagrammtyp (BDD/IBD/UC/REQ/STM/ACT/SEQ/PAR) — Bridge-Aufruf
     *     + Renderer-Wahl.
     *  2. auf das Ausgabeformat (svg/latex/png).
     *
     * Seit V2.0.14 unterstützen alle acht Diagramm-Typen den PNG-Export. Der
     * SVG-Pfad ist seit V2.0.13 vollständig (Stereotyp-Labels, gestrichelte
     * Linien, Pfeilköpfe), Batik rasterisiert den SVG-String unverändert zu
     * PNG. Es gibt keine SysML-2-spezifischen Render-Optionen für PNG — die
     * Stereotyp-Styles sind im SVG bereits vorhanden.
     *
     * Die acht Diagramm-Typ-Branches haben weitgehend dieselbe Form
     * (Bridge → Layout → Format-Dispatch); das `width` für PNG wird
     * durchgereicht.
     */
    private fun renderSysml2(
        extracted: ExtractedDiagram.Sysml2,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
    ) {
        val model = extracted.model
        // SysML 2 always uses ELK (no grid-default change for SysML 2 in V2.0.26)
        ensureEnginesRegistered()
        val sysml2Engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for SysML 2 diagrams.")
        when (val diagram = extracted.diagram) {
            is BdDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is IbdDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is UcDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is ReqDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is StmDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" ->
                        writeText(
                            output,
                            KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, SvgRenderOptions(paddingPx = 64f)),
                        )
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is ActDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" ->
                        writeText(
                            output,
                            KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, SvgRenderOptions(paddingPx = 64f)),
                        )
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is SeqDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is ParDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme))
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
        }
    }

    /**
     * Gemeinsamer PNG-Helfer für alle acht SysML-2-Diagrammtypen (V2.0.14).
     * Nutzt den generischen `toPng(Sysml2Model, Sysml2Diagram, …)`-Overload
     * von [KumlPngRenderer], der intern auf den versiegelten Subtyp dispatcht.
     */
    private fun writeSysml2Png(
        model: dev.kuml.sysml2.Sysml2Model,
        diagram: dev.kuml.sysml2.Sysml2Diagram,
        layoutResult: LayoutResult,
        theme: KumlTheme,
        width: Int,
        output: Path,
    ) {
        val pngBytes =
            KumlPngRenderer.toPng(
                model,
                diagram,
                layoutResult,
                theme,
                PngRenderOptions(widthPx = width),
            )
        writeBinary(output, pngBytes)
    }

    private fun renderC4(
        extracted: ExtractedDiagram.C4,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
    ) {
        val diagram = extracted.diagram
        val model = extracted.model
        val layoutGraph = C4LayoutBridge.toLayoutGraph(diagram, model)
        ensureEnginesRegistered()
        val c4Engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for C4 diagrams.")
        val layoutResult: LayoutResult = c4Engine.layout(layoutGraph, LayoutHints.DEFAULT)
        when (format) {
            "svg" -> {
                val svg = KumlSvgRenderer.toSvg(diagram, model, layoutResult, theme)
                writeText(output, svg)
            }
            "png" -> {
                val pngBytes =
                    KumlPngRenderer.toPng(
                        diagram,
                        model,
                        layoutResult,
                        theme,
                        PngRenderOptions(widthPx = width),
                    )
                writeBinary(output, pngBytes)
            }
            "latex" -> {
                // C4 LaTeX export reuses the UML class-renderer fallback — each
                // C4 element gets a labelled rectangle. Full C4-style boxes
                // (Person / SoftwareSystem / Container with role labels) land
                // in a later V2.x wave; for V2.0.2 the MVP focus is class
                // diagrams.
                val placeholderDiagram = KumlDiagram(name = diagram.name)
                val tex = KumlLatexRenderer.toLatex(placeholderDiagram, layoutResult, LatexRenderOptions.DEFAULT)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    private fun writeBinary(
        out: Path,
        bytes: ByteArray,
    ) {
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun writeText(
        out: Path,
        text: String,
    ) {
        val file = out.toFile()
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
