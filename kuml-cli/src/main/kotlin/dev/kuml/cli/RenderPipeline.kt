package dev.kuml.cli

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.constraint.BpmnConstraintChecker
import dev.kuml.bpmn.constraint.ViolationSeverity
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.core.config.KumlConfig
import dev.kuml.core.dsl.layout.LayoutMetadataKeys
import dev.kuml.core.model.ActivityDiagramConfig
import dev.kuml.core.model.ActivityOrientation
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlMetaValue
import dev.kuml.core.model.StateDiagramConfig
import dev.kuml.core.model.StateDiagramOrientation
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.erm.constraint.ErmConstraintChecker
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.io.anim.AnimEncoderException
import dev.kuml.io.anim.AnimFormat
import dev.kuml.io.anim.AnimRenderOptions
import dev.kuml.io.anim.KumlAnimRenderer
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.png.KumlPngRenderer
import dev.kuml.io.png.PngRenderOptions
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SvgRenderOptions
import dev.kuml.io.svg.activity.smil.ActivityAnimationContext
import dev.kuml.io.svg.activity.smil.ActivitySmilRenderer
import dev.kuml.io.svg.bpmn.smil.BpmnAnimationContext
import dev.kuml.io.svg.bpmn.smil.BpmnSmilRenderer
import dev.kuml.io.svg.stm.smil.StmAnimationContext
import dev.kuml.io.svg.stm.smil.StmSmilRenderer
import dev.kuml.io.svg.toSvgFile
import dev.kuml.io.svg.uml.smil.SequenceAnimationContext
import dev.kuml.io.svg.uml.smil.SequenceSmilRenderer
import dev.kuml.layout.DiagramKind
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutEngineId
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.LayoutHints
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Spacing
import dev.kuml.layout.bridge.C4ContentSizeProvider
import dev.kuml.layout.bridge.C4LayoutBridge
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
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import dev.kuml.render.smil.SpeedFactor
import dev.kuml.render.smil.TraceFileLoader
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.ThemeRegistry
import dev.kuml.runtime.TraceEntry
import dev.kuml.runtime.TraceFile
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
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
import dev.kuml.erm.constraint.ViolationSeverity as ErmViolationSeverity

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
            // Register ELK FIRST so pickFor(kind, null) prefers ELK over Grid
            // for diagram kinds that both engines support.
            // Grid layout is still available via --layout=grid (opt-in, experimental).
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
        }
    }

    /**
     * Wählt die Layout-Engine für [diagram] aus.
     *
     * Priorität (höchste zuerst):
     * 1. [cliOverride] — `--layout=grid` oder `--layout=elk` vom CLI-Flag
     * 2. `kuml.layout.engine`-Metadaten im Diagramm (DSL-Ebene)
     * 3. ELK als Default für alle Typen (Grid via `--layout=grid` opt-in)
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
        // 3. ELK als Default für alle Typen
        val kind = diagram.type.toDiagramKind()
        return LayoutEngineRegistry.pickFor(kind, LayoutEngineId("elk.layered"))
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
     *   `"auto"` or `null` → ELK default for all diagram types; `"grid"` opts in to the experimental grid engine.
     * @param latexStandalone When `true` and format is `"latex"`, emit a complete
     *   `\documentclass{standalone}` document instead of a bare tikzpicture snippet.
     *   Only meaningful for LaTeX output — ignored for SVG / PNG.
     * @param animated When `true` and format is `"svg"`, inject SMIL animations (V3.1.31).
     * @param traceFile Optional path to a `kuml.trace.v1` JSON file for trace-driven animation.
     *   When `null` and [animated] is `true`, a demo animation sequence is synthesised.
     * @param speed Playback speed multiplier for animation (default 1.0).
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
        animated: Boolean = false,
        traceFile: File? = null,
        speed: Double = 1.0,
        notation: String? = null,
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

        // 3. Theme — layering: CLI flag > config file > built-in default "kuml"
        if (ThemeRegistry.names().isEmpty()) {
            ThemeRegistry.loadFromClasspath()
        }
        val resolvedThemeName =
            themeName
                ?: config.render.themeName
                ?: "kuml"
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
                    renderUml(extracted, output, format, width, theme, layoutEngineOverride, latexStandalone, animated, traceFile, speed)
                is ExtractedDiagram.C4 -> renderC4(extracted, output, format, width, theme)
                is ExtractedDiagram.Sysml2 -> renderSysml2(extracted, output, format, width, theme, animated, traceFile, speed)
                is ExtractedDiagram.Bpmn -> renderBpmn(extracted, output, format, width, theme, animated, traceFile, speed)
                is ExtractedDiagram.Blueprint -> renderBlueprint(extracted, output, format, width, theme, latexStandalone)
                is ExtractedDiagram.Erm -> renderErm(extracted, output, format, width, theme, notation, layoutEngineOverride)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (e is ScriptEvaluationException) throw e
            if (e is IllegalArgumentException) throw e
            if (e is AnimEncoderException) throw e
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
        animated: Boolean = false,
        traceFile: File? = null,
        speed: Double = 1.0,
    ) {
        val diagram = extracted.diagram
        // V2.x — Connection-aware sizing braucht die Layout-Richtung, damit der
        // Anschluss-Puffer auf der richtigen Seite (Breite bei vertikalem
        // Layout-Fluss, Höhe bei horizontalem) addiert wird.
        //
        // V2.x — Diagramm-seitige Layout-Hints aus `KumlDiagram.metadata`
        // werden in die globalen [LayoutHints] gefaltet, bevor das Layout
        // läuft. Bisher unterstützt: `kuml.layout.mergeEdges` (opt-in für
        // Tree-Trunk-Routing bei großen Fan-Ins).
        val diagramMergeEdges =
            (diagram.metadata[LayoutMetadataKeys.MERGE_EDGES] as? KumlMetaValue.Flag)?.value
        // V2.x — `ActivityDiagramConfig.orientation` / `StateDiagramConfig.orientation`
        // are DSL-level layout hints (LEFT_RIGHT vs. TOP_DOWN) that were previously
        // never consumed anywhere in the pipeline. Fold them into the actual
        // `LayoutHints.direction` so the DSL setting has a real effect on the
        // rendered layout instead of being silently ignored.
        val diagramDirection =
            when (val config = diagram.config) {
                is ActivityDiagramConfig ->
                    if (config.orientation == ActivityOrientation.LEFT_RIGHT) LayoutDirection.LeftToRight else null
                is StateDiagramConfig ->
                    if (config.orientation == StateDiagramOrientation.LEFT_RIGHT) LayoutDirection.LeftToRight else null
                else -> null
            }
        // UML STATE diagrams pack many transition labels around shared states
        // (guard conditions, action labels, composite state entry/exit transitions).
        // The default spacing crowds the states so the transition arrows and their
        // labels (`confirm() [valid]`, `ship() / notifyCustomer()`, …) overlap and
        // become hard to follow. Open everything up generously:
        //  - `nodeToNode = 110f` — horizontal breathing room between sibling states
        //    (e.g. Picking / Packing inside a composite, or a state and its
        //    self-transition loop).
        //  - `edgeToEdge = 36f` — keeps parallel/adjacent transition labels apart.
        //  - `layerToLayer = 130f` — the dominant knob for top-to-bottom state
        //    machines: it widens the vertical gap between successive states so the
        //    inter-state transition labels sit in clear space, not on a box edge.
        //  - `groupPadding = 28f` — extra inset around composite-state frames.
        val baseSpacing =
            if (diagram.type == DiagramType.STATE) {
                Spacing(
                    nodeToNode = 110f,
                    edgeToEdge = 36f,
                    groupPadding = 28f,
                    layerToLayer = 130f,
                )
            } else {
                LayoutHints.DEFAULT.spacing
            }
        // V3.0.x — sequence-diagram lifelines are laid out as edge-less ELK nodes (see
        // UmlLayoutBridge's UmlInteraction branch), so ELK's crossing minimization has no
        // cost signal to keep them in declaration order once their widths differ. UML
        // sequence diagrams are the one diagram type where left-to-right order is
        // semantically meaningful (standard UML convention) — pin it via
        // LayoutHints.preserveNodeOrder. Other diagram types keep free reordering.
        val hints =
            LayoutHints.DEFAULT.copy(
                mergeEdges = diagramMergeEdges ?: LayoutHints.DEFAULT.mergeEdges,
                spacing = baseSpacing,
                direction = diagramDirection ?: LayoutHints.DEFAULT.direction,
                preserveNodeOrder = diagram.type == DiagramType.SEQUENCE,
            )
        val layoutGraph =
            UmlLayoutBridge.toLayoutGraph(diagram, UmlContentSizeProvider(diagram, hints.direction))
        val engine = pickEngine(diagram, layoutEngineOverride)
        val layoutResult: LayoutResult = engine.layout(layoutGraph, hints)
        when (format) {
            "svg", "apng", "webp", "mp4" -> {
                when {
                    animated && diagram.type == DiagramType.STATE -> {
                        val sm = diagram.elements.filterIsInstance<dev.kuml.uml.UmlStateMachine>().firstOrNull()
                        if (sm != null) {
                            val trace = loadOrSynthesiseStmTrace(traceFile, sm)
                            val svgOptions = SvgRenderOptions.DEFAULT
                            val result =
                                StmSmilRenderer.render(
                                    diagram = diagram,
                                    stateMachine = sm,
                                    layoutResult = layoutResult,
                                    theme = theme,
                                    options = svgOptions,
                                    trace = trace,
                                    context = StmAnimationContext(speedFactor = SpeedFactor(speed)),
                                )
                            if (format == "svg") {
                                writeText(output, result.svg)
                            } else {
                                writeAnimated(output, result.svg, result.timeline, format, width)
                            }
                        } else {
                            KumlSvgRenderer.toSvgFile(diagram, layoutResult, output, theme)
                        }
                    }
                    animated && diagram.type == DiagramType.ACTIVITY -> {
                        val actEdges = diagram.elements.filterIsInstance<dev.kuml.uml.UmlActivityEdge>()
                        val trace = loadOrSynthesiseActivityTrace(traceFile, actEdges)
                        val svgOptions = SvgRenderOptions.DEFAULT
                        val result =
                            ActivitySmilRenderer.render(
                                diagram = diagram,
                                activityEdges = actEdges,
                                layoutResult = layoutResult,
                                theme = theme,
                                options = svgOptions,
                                trace = trace,
                                context = ActivityAnimationContext(speedFactor = SpeedFactor(speed)),
                            )
                        if (format == "svg") {
                            writeText(output, result.svg)
                        } else {
                            writeAnimated(output, result.svg, result.timeline, format, width)
                        }
                    }
                    animated && diagram.type == DiagramType.SEQUENCE -> {
                        // Sequence Diagram animated rendering — trace-driven via SequenceSmilRenderer.
                        // Requires a kuml.trace.v1 JSON file with MessageSent entries.
                        // Falls back to static SVG when no trace file is provided.
                        val trace = traceFile?.let { TraceFileLoader.load(it) }
                        if (trace == null) {
                            System.err.println(
                                "[kuml] WARNING: --animated requested for UML Sequence but no --trace file provided. " +
                                    "Sequence animation requires a kuml.trace.v1 JSON file with MessageSent entries. " +
                                    "Falling back to static SVG.",
                            )
                        }
                        val result =
                            SequenceSmilRenderer.render(
                                diagram = diagram,
                                layoutResult = layoutResult,
                                theme = theme,
                                trace = trace,
                                context = SequenceAnimationContext(speedFactor = SpeedFactor(speed)),
                            )
                        if (format == "svg") {
                            writeText(output, result.svg)
                        } else {
                            writeAnimated(output, result.svg, result.timeline, format, width)
                        }
                    }
                    animated && format in setOf("apng", "webp", "mp4") -> {
                        throw ScriptEvaluationException(
                            "Animated ${format.uppercase()} export is not supported for diagram type ${diagram.type}. " +
                                "Supported types: STATE, ACTIVITY, SEQUENCE.",
                        )
                    }
                    else -> KumlSvgRenderer.toSvgFile(diagram, layoutResult, output, theme)
                }
            }
            "png" -> {
                // StaticSnapshotMode.STRIPPED is always correct for PNG: the KumlPngRenderer
                // rasterises the static SVG produced by KumlSvgRenderer directly (no SMIL is
                // injected into that path). If `animated=true` reaches here (bypassing the CLI
                // guard), SMIL stripping is still guaranteed because SMIL is never generated for
                // PNG — we only use the non-SMIL renderer branch. This comment documents the
                // design intent so the guarantee is explicit rather than incidental.
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
     * Loads a [TraceFile] from [file] or synthesises a demo STM trace over [sm]'s vertices.
     * Demo mode emits [TraceEntry.StateEntered] for each vertex in declaration order.
     */
    private fun loadOrSynthesiseStmTrace(
        file: File?,
        sm: dev.kuml.uml.UmlStateMachine,
    ): TraceFile =
        if (file != null) {
            TraceFileLoader.load(file)
        } else {
            // Demo mode: synthesise StateEntered for each vertex
            val entries =
                sm.vertices.mapIndexed { idx, vertex ->
                    TraceEntry.StateEntered(
                        seqNo = idx.toLong(),
                        timestamp = "2026-01-01T00:00:${idx.toString().padStart(2, '0')}Z",
                        vertexId = vertex.id,
                    )
                }
            TraceFile(entries = entries)
        }

    /**
     * Loads a [TraceFile] from [file] or synthesises a demo Activity trace over [edges].
     * Demo mode emits [TraceEntry.TokenPlaced] for each edge source then target in order.
     */
    private fun loadOrSynthesiseActivityTrace(
        file: File?,
        edges: List<dev.kuml.uml.UmlActivityEdge>,
    ): TraceFile =
        if (file != null) {
            TraceFileLoader.load(file)
        } else {
            // Demo mode: synthesise TokenPlaced for each edge sourceId then targetId in order.
            // When `edges` is empty (e.g. an ACTIVITY diagram with only nodes and no
            // UmlActivityEdge elements) there is nothing to animate.  Emit a diagnostic so the
            // user understands why the SVG contains no SMIL animations instead of silently
            // falling back to static output that looks identical to a non-animated render.
            if (edges.isEmpty()) {
                System.err.println(
                    "[kuml] WARNING: --animated requested but no activity edges found in diagram. " +
                        "Demo animation requires at least one edge (control flow or object flow). " +
                        "Rendering static SVG without SMIL animations.",
                )
                return TraceFile(entries = emptyList())
            }
            val nodeIds =
                buildList {
                    for (edge in edges) {
                        if (isEmpty() || last() != edge.sourceId) add(edge.sourceId)
                        add(edge.targetId)
                    }
                }
            val entries =
                nodeIds.mapIndexed { idx, nodeId ->
                    TraceEntry.TokenPlaced(
                        seqNo = idx.toLong(),
                        timestamp = "2026-01-01T00:00:${idx.toString().padStart(2, '0')}Z",
                        nodeId = nodeId,
                        clock = idx.toLong(),
                    )
                }
            TraceFile(entries = entries)
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
        animated: Boolean = false,
        traceFile: File? = null,
        speed: Double = 1.0,
    ) {
        val model = extracted.model
        // SysML 2 uses ELK (same as all other diagram types)
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
                // V2.0.8+: REQ diagrams carry stereotype labels on every edge
                // («satisfy», «verify», «deriveReq», «containment»). With the
                // default 40 px node-to-node spacing these labels overlap adjacent
                // nodes and each other. Increase spacing analogously to what was
                // done for STM (V2.0.44) and ACT (V2.0.45):
                //  - `nodeToNode = 80f` — gives room for label text between
                //    requirement boxes (280 px wide each).
                //  - `layerToLayer = 100f` — edge labels sit at the midpoint of
                //    inter-layer arrows; the wider gap clears them of node borders.
                //  - `edgeToEdge = 20f` — multiple parallel satisfy-edges fan out
                //    from Vehicle; a bit more room reduces ribbon bundling.
                val reqHints =
                    LayoutHints.DEFAULT.copy(
                        spacing =
                            LayoutHints.DEFAULT.spacing.copy(
                                nodeToNode = 80f,
                                edgeToEdge = 20f,
                                layerToLayer = 100f,
                            ),
                    )
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, reqHints)
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
            is StmDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                // V2.0.44: STM diagrams pack many edge labels (`event [guard] / action`)
                // around shared states (e.g. a global Off-state reached from every
                // other state on `powerOff`). Default spacing of 40px node-to-node
                // and 12px edge-to-edge causes label overlap. Increase both so the
                // routing pass has room to place labels without crowding.
                val stmHints =
                    LayoutHints.DEFAULT.copy(
                        spacing = Spacing(nodeToNode = 80f, edgeToEdge = 28f, groupPadding = 24f),
                    )
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, stmHints)
                val stmOptions = SvgRenderOptions(paddingPx = 64f)
                when (format) {
                    "svg", "apng", "webp", "mp4" -> {
                        val baseSvg = KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, stmOptions)
                        val umlSm = Sysml2StateMachineAdapter.toUmlStateMachine(model, diagram)
                        val trace = loadOrSynthesiseStmTrace(traceFile, umlSm)
                        val ctx = StmAnimationContext(speedFactor = SpeedFactor(speed))
                        val result =
                            StmSmilRenderer.renderWithBaseSvg(
                                baseSvg = baseSvg,
                                stateMachine = umlSm,
                                layoutResult = layoutResult,
                                options = stmOptions,
                                trace = trace,
                                context = ctx,
                            )
                        when {
                            format in setOf("apng", "webp", "mp4") ->
                                writeAnimated(output, result.svg, result.timeline, format, width)
                            animated -> writeText(output, result.svg)
                            else -> writeText(output, baseSvg)
                        }
                    }
                    "latex" -> writeText(output, KumlLatexRenderer.toLatex(model, diagram, layoutResult, LatexRenderOptions.DEFAULT))
                    "png" -> writeSysml2Png(model, diagram, layoutResult, theme, width, output)
                    else -> throw ScriptEvaluationException("Unsupported format: $format")
                }
            }
            is ActDiagram -> {
                val layoutGraph = Sysml2LayoutBridge.toLayoutGraph(model, diagram)
                // V2.0.45 — ACT diagrams need roomier spacing than the global
                // default to keep pin labels off neighbouring action boxes and
                // to keep edge labels (`[guard]`, `[ObjectType]`) clear of
                // partition header bars.
                //  - `nodeToNode = 100f` (was 40f) — pin labels are up to ~70 px
                //    wide; two opposing labels need ≥ 140 px between adjacent
                //    actions plus breathing room.
                //  - `layerToLayer = 100f` (was 60f) — edge labels live on the
                //    midpoint of inter-layer arrows; the wider gap pushes them
                //    out of the partition header band (24 px) underneath the
                //    upstream partition's bottom edge.
                val actHints =
                    LayoutHints.DEFAULT.copy(
                        spacing =
                            LayoutHints.DEFAULT.spacing.copy(
                                nodeToNode = 100f,
                                layerToLayer = 100f,
                            ),
                    )
                val layoutResult: LayoutResult = sysml2Engine.layout(layoutGraph, actHints)
                val actOptions = SvgRenderOptions(paddingPx = 64f)
                when (format) {
                    "svg", "apng", "webp", "mp4" -> {
                        val baseSvg = KumlSvgRenderer.toSvg(model, diagram, layoutResult, theme, actOptions)
                        // Build UmlActivityEdge list from model for path resolution
                        val visible = diagram.elementIds.toSet()
                        val actEdges =
                            buildList<dev.kuml.uml.UmlActivityEdge> {
                                for (usage in model.usages) {
                                    when (usage) {
                                        is dev.kuml.sysml2.ControlFlowUsage ->
                                            if (usage.sourceNodeId in visible && usage.targetNodeId in visible) {
                                                add(
                                                    dev.kuml.uml.UmlActivityEdge(
                                                        id = usage.id,
                                                        sourceId = usage.sourceNodeId,
                                                        targetId = usage.targetNodeId,
                                                        isObjectFlow = false,
                                                    ),
                                                )
                                            }
                                        is dev.kuml.sysml2.ObjectFlowUsage ->
                                            if (usage.sourceNodeId in visible && usage.targetNodeId in visible) {
                                                add(
                                                    dev.kuml.uml.UmlActivityEdge(
                                                        id = usage.id,
                                                        sourceId = usage.sourceNodeId,
                                                        targetId = usage.targetNodeId,
                                                        isObjectFlow = true,
                                                    ),
                                                )
                                            }
                                        else -> Unit
                                    }
                                }
                            }
                        val trace = loadOrSynthesiseActivityTrace(traceFile, actEdges)
                        val ctx = ActivityAnimationContext(speedFactor = SpeedFactor(speed))
                        val result =
                            ActivitySmilRenderer.renderWithBaseSvg(
                                baseSvg = baseSvg,
                                activityEdges = actEdges,
                                layoutResult = layoutResult,
                                options = actOptions,
                                trace = trace,
                                context = ctx,
                            )
                        when {
                            format in setOf("apng", "webp", "mp4") ->
                                writeAnimated(output, result.svg, result.timeline, format, width)
                            animated -> writeText(output, result.svg)
                            else -> writeText(output, baseSvg)
                        }
                    }
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
                val layoutGraph =
                    Sysml2LayoutBridge.toLayoutGraph(
                        model,
                        diagram,
                        Sysml2LayoutBridge.parContentAwareSizeProvider(model),
                    )
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
        // V2.0.x: content-aware sizing for C4 — boxes grow to fit header,
        // bold name, and (wrapped) description. Without this, every C4 node
        // got the constant 160×80 fallback and long descriptions overflowed
        // visibly past the box edge (see "Enterprise Banking Landscape").
        val sizeProvider = C4ContentSizeProvider(model)
        val layoutGraph = C4LayoutBridge.toLayoutGraph(diagram, model, sizeProvider)
        ensureEnginesRegistered()
        val c4Engine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for C4 diagrams.")
        // Dynamic-Diagramme tragen typischerweise Request/Response-Paare
        // zwischen denselben Knoten — ELK platziert die beiden Pfeile auf
        // engen Parallelbahnen, sodass die Sequenznummer-Labels (jeweils
        // mit Description + optionalem `[Tech]`-Suffix) sich bei Default-
        // Spacing visuell stapeln. Wir geben dem Layout deutlich mehr Luft,
        // analog zu den Spacing-Tweaks für REQ/STM/ACT in renderSysml2:
        //  - nodeToNode: doppelte Bahn-Distanz, damit das Request/Response-
        //    Bündel zwischen den Knoten breit genug ist
        //  - layerToLayer: längere Pfeile, damit die paar-bewussten ⅓/⅔-
        //    Längsversätze in renderC4Interaction tatsächlich Distanz
        //    schaffen statt nur Phasenshift
        //  - edgeToEdge: parallele Bahnen weiter auseinander
        val hints: LayoutHints =
            if (diagram is dev.kuml.c4.model.DynamicDiagram) {
                LayoutHints.DEFAULT.copy(
                    spacing =
                        LayoutHints.DEFAULT.spacing.copy(
                            nodeToNode = 100f,
                            edgeToEdge = 28f,
                            layerToLayer = 120f,
                        ),
                )
            } else {
                LayoutHints.DEFAULT
            }
        val layoutResult: LayoutResult = c4Engine.layout(layoutGraph, hints)
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
                val tex = KumlLatexRenderer.toLatex(diagram, model, layoutResult, LatexRenderOptions.DEFAULT)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException("Unsupported format: $format")
        }
    }

    /**
     * BPMN render branch (V3.1.6).
     *
     * Dispatches on the concrete [BpmnDiagram] type:
     * - [ProcessDiagram] → `BpmnLayoutBridge.toLayoutGraph` + `KumlSvgRenderer.toSvg(KumlDiagram, …)`
     * - [CollaborationDiagram] → `BpmnLayoutBridge.toLayoutGraph(collab)` + `KumlSvgRenderer.toSvg(model, collab, …)`
     *
     * Before rendering, [BpmnConstraintChecker] is run. ERROR violations are printed as
     * warnings (not thrown) — the model may still be partially renderable and the user
     * should see the diagram even if some constraints are violated. WARNING violations
     * are printed as informational messages.
     */
    private fun renderBpmn(
        extracted: ExtractedDiagram.Bpmn,
        output: Path,
        format: String,
        width: Int,
        theme: dev.kuml.renderer.theme.core.KumlTheme,
        animated: Boolean = false,
        traceFile: File? = null,
        speed: Double = 1.0,
    ) {
        val model = extracted.model
        val bpmnDiagram = extracted.diagram

        // Run constraint checker and report violations
        val violations = BpmnConstraintChecker().check(model)
        violations.forEach { v ->
            val prefix = if (v.severity == ViolationSeverity.ERROR) "BPMN ERROR" else "BPMN WARNING"
            System.err.println("[$prefix] ${v.elementId ?: "model"}: ${v.message}")
        }

        ensureEnginesRegistered()
        val bpmnEngine =
            LayoutEngineRegistry.get("elk.layered")
                ?: error("ELK layout engine not available for BPMN diagrams.")

        when (bpmnDiagram) {
            is ProcessDiagram -> {
                // Build a KumlDiagram view for the SVG renderer:
                // collect all flow nodes + sequence flows of the referenced process.
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
                    "svg", "apng", "webp", "mp4" -> {
                        if (animated) {
                            // BPMN animated rendering — trace-driven via BpmnSmilRenderer.
                            // Unlike STM/Activity, no demo trace is synthesised for BPMN without a
                            // trace file; passing null falls back to static output (same as non-animated).
                            val trace = traceFile?.let { TraceFileLoader.load(it) }
                            if (trace == null) {
                                System.err.println(
                                    "[kuml] WARNING: --animated requested for BPMN but no --trace file provided. " +
                                        "BPMN animation requires a kuml.trace.v1 JSON file. Falling back to static SVG.",
                                )
                            }
                            val ctx = BpmnAnimationContext(speedFactor = SpeedFactor(speed))
                            val result = BpmnSmilRenderer.render(kumlDiagram, layoutResult, theme, trace = trace, context = ctx)
                            if (format == "svg") {
                                writeText(output, result.svg)
                            } else {
                                writeAnimated(output, result.svg, result.timeline, format, width)
                            }
                        } else {
                            writeText(output, KumlSvgRenderer.toSvg(kumlDiagram, layoutResult, theme))
                        }
                    }
                    "png" -> {
                        val pngBytes =
                            KumlPngRenderer.toPng(kumlDiagram, layoutResult, theme, PngRenderOptions(widthPx = width))
                        writeBinary(output, pngBytes)
                    }
                    else -> throw ScriptEvaluationException("Unsupported format for BPMN: $format (supported: svg, png)")
                }
            }
            is CollaborationDiagram -> {
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, bpmnDiagram)
                val layoutResult: LayoutResult = bpmnEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme))
                    "png" -> {
                        val svg = KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme)
                        val pngBytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = width))
                        writeBinary(output, pngBytes)
                    }
                    else -> throw ScriptEvaluationException("Unsupported format for BPMN: $format (supported: svg, png)")
                }
            }
            is ChoreographyDiagram -> {
                // V3.2.2 — Choreography bypasses ELK entirely: deterministic custom grid layout.
                val layoutResult: LayoutResult = ChoreographyGridLayout.layout(model, bpmnDiagram)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme))
                    "png" -> {
                        val svg = KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme)
                        val pngBytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = width))
                        writeBinary(output, pngBytes)
                    }
                    else -> throw ScriptEvaluationException("Unsupported format for BPMN: $format (supported: svg, png)")
                }
            }
            is ConversationDiagram -> {
                val layoutGraph = BpmnLayoutBridge.toLayoutGraph(model, bpmnDiagram)
                val layoutResult: LayoutResult = bpmnEngine.layout(layoutGraph, LayoutHints.DEFAULT)
                when (format) {
                    "svg" -> writeText(output, KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme))
                    "png" -> {
                        val svg = KumlSvgRenderer.toSvg(model, bpmnDiagram, layoutResult, theme)
                        val pngBytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = width))
                        writeBinary(output, pngBytes)
                    }
                    else -> throw ScriptEvaluationException("Unsupported format for BPMN: $format (supported: svg, png)")
                }
            }
        }
    }

    /**
     * Blueprint / Journey-Map render branch (V3.1.24).
     *
     * Blueprint diagrams bypass ELK entirely — the layout is deterministic
     * grid geometry (phases = columns, layers = rows). SVG, PNG and (V3.1.26)
     * LaTeX/TikZ export are supported.
     */
    private fun renderBlueprint(
        extracted: ExtractedDiagram.Blueprint,
        output: Path,
        format: String,
        width: Int,
        @Suppress("UNUSED_PARAMETER") theme: KumlTheme,
        latexStandalone: Boolean = false,
    ) {
        val model: BlueprintModel = extracted.model
        val diagram: BlueprintDiagram = extracted.diagram
        when (format) {
            "svg" -> KumlSvgRenderer.toSvgFile(model, diagram, output)
            "png" -> {
                val svg = KumlSvgRenderer.toSvg(model, diagram)
                val pngBytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = width))
                writeBinary(output, pngBytes)
            }
            "latex" -> {
                val opts = LatexRenderOptions(standalone = latexStandalone)
                val tex = KumlLatexRenderer.toLatex(model, diagram, opts)
                writeText(output, tex)
            }
            else -> throw ScriptEvaluationException(
                "Unsupported format for Blueprint: $format (supported: svg, png, latex)",
            )
        }
    }

    /**
     * ERM render branch (V3.4.2; IDEF1X notation added in V3.4.5).
     *
     * ERM entities/relationships are laid out via ELK — structurally
     * identical to a UML class diagram (see [ErmLayoutBridge]'s KDoc).
     * [ErmConstraintChecker] runs first; like [renderBpmn], violations are
     * printed as warnings/errors to stderr but never block the render — the
     * model may still be partially renderable.
     *
     * [notationOverride] is the raw `--notation` CLI flag value (already
     * validated against the `martin|bachman|chen|idef1x` choice list by
     * Clikt); `null` means "use the notation declared in the DSL script"
     * (`diagram.notation`).
     *
     * [layoutEngineOverride] is the raw `--layout` CLI flag value, same
     * semantics as in [renderUml]/[pickEngine]. ERM has no DSL-level
     * `KumlDiagram.metadata`-style engine override ([ErmDiagram] carries no
     * diagram-level metadata map), so the priority chain is just
     * CLI-override → ELK default — no middle "DSL metadata" tier.
     */
    private fun renderErm(
        extracted: ExtractedDiagram.Erm,
        output: Path,
        format: String,
        width: Int,
        theme: KumlTheme,
        notationOverride: String? = null,
        layoutEngineOverride: String? = null,
    ) {
        val model = extracted.model
        val diagram = extracted.diagram

        val violations = ErmConstraintChecker().check(model)
        violations.forEach { v ->
            val prefix = if (v.severity == ErmViolationSeverity.ERROR) "ERM ERROR" else "ERM WARNING"
            System.err.println("[$prefix] ${v.elementId ?: "model"}: ${v.message}")
        }

        val notation = notationOverride?.let { ErmNotation.valueOf(it.uppercase()) } ?: diagram.notation

        ensureEnginesRegistered()
        // V3.4.x — widened FK-hub spacing; single source of truth lives in
        // ErmLayoutBridge.WIDENED_SPACING_HINTS (see its KDoc for the
        // rationale), shared by every ERM render path.
        val hints = ErmLayoutBridge.WIDENED_SPACING_HINTS
        // Chen expands attributes/relationships into their own layout nodes
        // (see ErmChenLayoutBridge's KDoc) — a structurally different graph
        // from the Martin/Bachman one entity-box-per-entity graph. IDEF1X
        // keeps real entity/view/relationship ids but injects a synthetic
        // category-circle node (see ErmIdef1xLayoutBridge's KDoc). Both get
        // their own bridge + size provider before the shared ELK run.
        val graph =
            when (notation) {
                ErmNotation.CHEN -> ErmChenLayoutBridge.toChenLayoutGraph(model, diagram, ErmChenSizeProvider(model, diagram))
                ErmNotation.IDEF1X ->
                    ErmIdef1xLayoutBridge.toLayoutGraph(model, diagram, ErmContentSizeProvider(model, diagram, hints.direction))
                else -> ErmLayoutBridge.toLayoutGraph(model, diagram, ErmContentSizeProvider(model, diagram, hints.direction))
            }
        val engine =
            if (layoutEngineOverride != null && layoutEngineOverride != "auto") {
                val engineId = layoutEngineOverride.normaliseEngineId()
                LayoutEngineRegistry.get(engineId)
                    ?: error(
                        "Layout engine '$layoutEngineOverride' not found. Available: ${LayoutEngineRegistry.ids().map { it.value }}",
                    )
            } else {
                LayoutEngineRegistry.pickFor(DiagramKind.Generic, LayoutEngineId("elk.layered"))
                    ?: error("No layout engine available for ERM diagrams.")
            }
        val layout: LayoutResult = engine.layout(graph, hints)

        when (format) {
            "svg" -> writeText(output, ermToSvg(model, diagram, layout, theme, notation))
            "png" -> {
                val svg = ermToSvg(model, diagram, layout, theme, notation)
                val pngBytes = KumlPngRenderer.toPng(svg, PngRenderOptions(widthPx = width))
                writeBinary(output, pngBytes)
            }
            "latex" -> throw ScriptEvaluationException(
                "ERM LaTeX export is not yet supported (planned for a post-V3.4 wave).",
            )
            else -> throw ScriptEvaluationException(
                "Unsupported format for ERM: $format (supported: svg, png)",
            )
        }
    }

    /**
     * Renders an ERM diagram to SVG, translating any [IllegalArgumentException]
     * that [KumlSvgRenderer.toSvg] throws into a [ScriptEvaluationException].
     *
     * As of V3.4.5 all four [ErmNotation] values (MARTIN, BACHMAN, CHEN,
     * IDEF1X) are implemented and no longer throw for being unsupported —
     * this wrapper remains as a defensive translation layer for any future
     * `IllegalArgumentException` the renderer might raise.
     *
     * Narrowly scoped to this single renderer call so that unrelated
     * `require(...)`-style precondition failures elsewhere in the render
     * pipeline are not misreported as user-facing script errors — they
     * propagate as-is and crash loudly, as they should.
     */
    private fun ermToSvg(
        model: ErmModel,
        diagram: ErmDiagram,
        layout: LayoutResult,
        theme: KumlTheme,
        notation: ErmNotation,
    ): String =
        try {
            KumlSvgRenderer.toSvg(model, diagram, layout, theme, notation = notation)
        } catch (e: IllegalArgumentException) {
            throw ScriptEvaluationException(e.message ?: "Unsupported ERM notation: $notation", e)
        }

    /**
     * Encode [animatedSvg] + [timeline] to APNG, WebP, or MP4 and write to [output].
     *
     * @param format Must be `"apng"`, `"webp"`, or `"mp4"`.
     * @throws ScriptEvaluationException if the timeline is empty (no animations).
     */
    private fun writeAnimated(
        output: Path,
        animatedSvg: String,
        timeline: dev.kuml.render.smil.SmilTimeline,
        format: String,
        width: Int,
    ) {
        if (!timeline.animations.any()) {
            throw ScriptEvaluationException(
                "Cannot export animated $format: the diagram produced no SMIL animations. " +
                    "Check that the trace file contains relevant entries or that the diagram type supports animation.",
            )
        }
        val animFormat =
            when (format) {
                "apng" -> AnimFormat.APNG
                "webp" -> AnimFormat.WEBP
                "mp4" -> AnimFormat.MP4
                else -> error("Unreachable: unsupported animated format '$format'")
            }
        // MP4/H.264 has no standard alpha channel — force an opaque white background
        // instead of surfacing AnimEncoderException for the common case of a user simply
        // switching --format from webp/apng to mp4 without also flipping --animated options.
        // (KumlAnimRenderer still enforces the invariant defensively for direct API callers.)
        val opts =
            if (animFormat == AnimFormat.MP4) {
                AnimRenderOptions(format = animFormat, widthPx = width, transparent = false)
            } else {
                AnimRenderOptions(format = animFormat, widthPx = width)
            }
        val bytes = KumlAnimRenderer.toAnimated(animatedSvg, timeline, opts)
        writeBinary(output, bytes)
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
