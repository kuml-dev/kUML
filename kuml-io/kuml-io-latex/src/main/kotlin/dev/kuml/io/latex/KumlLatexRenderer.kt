package dev.kuml.io.latex

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.io.latex.sysml2.Sysml2DefLatexRenderer
import dev.kuml.io.latex.sysml2.edge.Sysml2EdgeLatexRenderer
import dev.kuml.io.latex.uml.UmlClassLatexRenderer
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActorDefinition
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.LifelineDefinition
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.RequirementDefinition
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.edge.ActEdgeAdapter
import dev.kuml.sysml2.edge.ParEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2EdgeAdapter
import dev.kuml.sysml2.edge.UcEdgeAdapter
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Renders a kUML diagram + layout result as a LaTeX/TikZ source string.
 *
 * **V2.0.2 MVP scope**: class diagrams in the `plain` theme, snippet by
 * default (or standalone via [LatexRenderOptions.standalone]). Other diagram
 * types render as plain rectangles with a centred label — the structural
 * fidelity for state machines / use cases / C4 lands in a later V2.x wave
 * (see [[kUML V2.0]]).
 *
 * Coordinate system: layout uses pixels with **y down** (CSS convention),
 * TikZ uses cartesian coords with **y up**. We flip on the way out by
 * negating every Y, so a layout point `(100, 50)` becomes
 * `(100pt, -50pt)`. Text labels remain upright because we only flip the
 * coordinate axis on emission, not the picture-wide transform.
 *
 * The output is **layout-faithful**: every node lands at the exact pixel
 * coordinates the kUML layout engine produced, scaled by
 * [LatexRenderOptions.scale]. The TikZ output and the SVG output of the
 * same diagram are pixel-identical (modulo font-metric differences in the
 * rendered PDF).
 *
 * Usage:
 * ```kotlin
 * val tex = KumlLatexRenderer.toLatex(diagram, layout)
 * File("diagram.tex").writeText(tex)
 * ```
 *
 * @see LatexRenderOptions
 */
public object KumlLatexRenderer {
    /**
     * Render the diagram as TikZ source.
     *
     * @param diagram The kUML diagram. Elements not yet supported in the
     *   MVP (state machines, use cases, etc.) fall back to a labelled
     *   rectangle.
     * @param layoutResult Positions and edge routes from a kUML layout engine.
     *   Only nodes/edges present in this result get rendered — any extra
     *   diagram elements are silently skipped (matches the SVG renderer's
     *   behaviour).
     * @param options Tuning knobs; defaults are usually fine.
     * @return A self-contained `\begin{tikzpicture}…\end{tikzpicture}`
     *   block (snippet mode) or a complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)

            // Nodes — index elements by id once, then iterate the layout to
            // preserve the engine's deterministic ordering.
            val nodesById: Map<String, KumlElement> = diagram.elements.associateBy { it.id }

            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = nodesById[nodeId.value] ?: continue
                renderNode(element, nodeId, nodeLayout, options)
            }

            // Edges
            val relationshipsById: Map<String, UmlRelationship> =
                diagram.elements.filterIsInstance<UmlRelationship>().associateBy { it.id }

            for ((edgeId, route) in layoutResult.edges) {
                val rel = relationshipsById[edgeId.value]
                renderEdge(rel, route, options)
            }

            appendPictureClose()

            if (options.standalone) {
                appendStandaloneCoda()
            }
        }

    /**
     * Render a synthetic SysML 2 [KumlDiagram] hull with an adapter-driven
     * edge dispatch (V2.0.13).
     *
     * Nodes follow the same path as [toLatex]. Edges run through a
     * three-way fallback parallel to the SVG renderer:
     *
     *  1. If the synthetic hull has an [UmlRelationship] for the edge id,
     *     the legacy [renderEdge] path styles it (BDD KermlSpecializations
     *     ride this branch).
     *  2. Otherwise, the [Sysml2EdgeAdapter] is asked for metadata. If
     *     present, [Sysml2EdgeLatexRenderer.render] draws the line + dash
     *     + arrow head + stereotype / label.
     *  3. Otherwise, the plain solid line fallback used to be the V2.0.7—12
     *     default behaviour — kept here for safety.
     */
    private fun renderSysml2Synthetic(
        synthetic: KumlDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions,
        sysml2EdgeAdapter: Sysml2EdgeAdapter,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)

            val nodesById: Map<String, KumlElement> = synthetic.elements.associateBy { it.id }
            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = nodesById[nodeId.value] ?: continue
                renderNode(element, nodeId, nodeLayout, options)
            }

            val relationshipsById: Map<String, UmlRelationship> =
                synthetic.elements.filterIsInstance<UmlRelationship>().associateBy { it.id }

            for ((edgeId, route) in layoutResult.edges) {
                val rel = relationshipsById[edgeId.value]
                if (rel != null) {
                    renderEdge(rel, route, options)
                    continue
                }
                val meta = sysml2EdgeAdapter.metadataFor(edgeId.value)
                if (meta != null) {
                    Sysml2EdgeLatexRenderer.render(route, meta, options, this)
                } else {
                    // V2.0.7–12 fallback: plain solid line. Reached only if
                    // the adapter doesn't claim the edge.
                    renderEdge(null, route, options)
                }
            }

            appendPictureClose()

            if (options.standalone) {
                appendStandaloneCoda()
            }
        }

    // ─── Node dispatch ────────────────────────────────────────────────────────

    private fun StringBuilder.renderNode(
        element: KumlElement,
        nodeId: NodeId,
        nodeLayout: dev.kuml.layout.NodeLayout,
        options: LatexRenderOptions,
    ) {
        when (element) {
            is Sysml2Definition -> Sysml2DefLatexRenderer.render(element, nodeId, nodeLayout, options, this)
            // V2.x: full IBD-styled TikZ for usages (stereotype band + content
            // line, matching the SVG renderer's two-line layout). V2.0.6
            // shipped with the rectangle-with-label fallback for usages —
            // every usage still lands at the layout-faithful pixel position
            // with `name : Type` as its label, which is enough for the V2.0.6
            // IBD pipeline to round-trip end-to-end through CLI / docs.
            is PartUsage ->
                UmlClassLatexRenderer.renderFallback(
                    nodeId,
                    nodeLayout,
                    options,
                    this,
                    label = "${element.name} : ${element.definitionId}",
                )
            is UmlClassifier -> UmlClassLatexRenderer.render(element, nodeId, nodeLayout, options, this)
            is UmlNamedElement -> UmlClassLatexRenderer.renderFallback(nodeId, nodeLayout, options, this, label = element.name)
            else -> UmlClassLatexRenderer.renderFallback(nodeId, nodeLayout, options, this, label = element.id)
        }
    }

    /**
     * Render a SysML 2 BDD as TikZ source — V2.0.4 sister-entry to the UML
     * [toLatex] path. Selected definitions become BDD-Boxen,
     * `:>`-specialisations between visible definitions become generalisation
     * arrows. Synthesises a [KumlDiagram] hull so the inner rendering loop
     * stays one path.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: BdDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements = model.definitions.filter { it.id in visible }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return toLatex(synthetic, layoutResult, options)
    }

    /**
     * Rendert ein SysML-2-IBD als TikZ-Quelle (V2.0.6).
     *
     * Wickelt das IBD in ein synthetisches [KumlDiagram] mit den sichtbaren
     * Part-Usages (gemäß `diagram.elementIds` bzw. — wenn leer — *allen*
     * Part-Usages des Owners) als `elements`. Die innere [renderNode]-Dispatch
     * fällt für [PartUsage] auf den Rechteck-mit-Label-Pfad zurück.
     *
     * V2.x-Polish: dedizierter IBD-TikZ-Renderer mit Stereotyp-Band, der die
     * SVG-Variante 1:1 in TikZ-Form spiegelt. Heute ist das Output pixelweise
     * layoutgetreu, aber stilistisch der UML-Fallback — gut genug für die
     * V2.0.6-Wave, die Substanz statt Politur priorisiert.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: IbdDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val ownerPrefix = "${diagram.ownerId}::"
        val ownerPartUsages =
            model.usages
                .filterIsInstance<PartUsage>()
                .filter { it.id.startsWith(ownerPrefix) }
        val filter: Set<String>? = diagram.elementIds.takeIf { it.isNotEmpty() }?.toSet()
        val visible =
            if (filter == null) ownerPartUsages else ownerPartUsages.filter { it.id in filter }

        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = visible,
            )
        return toLatex(synthetic, layoutResult, options)
    }

    /**
     * Render a SysML 2 UC-Diagram as TikZ source (V2.0.7).
     *
     * Wickelt das UC in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ActorDefinition]s + [UseCaseDefinition]s. Beide rendern im V2.0.7-MVP
     * über den UML-Klassenfallback als Rechteck mit Name-Label — der dedizierte
     * TikZ-Stickfigur-/Ellipsen-Renderer ist V2.x-Polish, analog zur
     * BDD/IBD-Geschichte.
     *
     * Edge-Styling: alle drei UC-Edge-Kinds (Association, `«include»`,
     * `«extend»`) rendern als plain solide Linie (Default-Style des
     * Edge-Renderers). Gestricheltes Styling + Stereotyp-Labels sind V2.x.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: UcDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ActorDefinition || it is UseCaseDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, options, UcEdgeAdapter(diagram))
    }

    /**
     * Render a SysML 2 REQ-Diagram as TikZ source (V2.0.8).
     *
     * Wickelt das REQ in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [RequirementDefinition]s plus optional projizierten Satisfier/Verifier-
     * Nodes (Parts/UseCases/Actors). Im V2.0.8-MVP rendert
     * `Sysml2DefLatexRenderer` Anforderungen als Rechteck mit
     * `«requirement»`-Stereotyp-Header — das dreikompartimentige
     * TikZ-Pendant (mit wort-gewrapptem Anforderungstext) ist V2.x-Polish,
     * analog zur BDD/IBD/UC-Geschichte.
     *
     * Edge-Styling: alle vier REQ-Edge-Kinds (Satisfy, Verify, Derive,
     * Contains) rendern als plain solide Linie (Default-Style des
     * Edge-Renderers). Gestricheltes Styling + Stereotyp-Labels sind V2.x.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: ReqDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter {
                    it is RequirementDefinition ||
                        it is PartDefinition ||
                        it is UseCaseDefinition ||
                        it is ActorDefinition
                }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, options, ReqEdgeAdapter(diagram))
    }

    /**
     * Render a SysML 2 STM-Diagram as TikZ source (V2.0.9).
     *
     * Wickelt das STM in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [StateDefinition]s als `elements`. Im V2.0.9-MVP rendert
     * `Sysml2DefLatexRenderer` Zustände als Rechteck mit `«state»`-Header
     * (bzw. `«initial pseudo-state»` / `«final pseudo-state»` für die
     * Pseudo-State-Varianten) — das abgerundet-rechteckige TikZ-Pendant
     * inklusive Action-Compartment landet in V2.x-Polish, analog zur
     * BDD/IBD/UC/REQ-Geschichte im LaTeX-Renderer.
     *
     * Edge-Styling: Transitionen rendern als plain solide Linie (Default-Style
     * des Edge-Renderers). Der `trigger [guard] / effect`-Label und
     * gestricheltes Styling sind V2.x.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: StmDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is StateDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, options, StmEdgeAdapter(model, diagram))
    }

    /**
     * Render a SysML 2 ACT-Diagram as TikZ source (V2.0.10).
     *
     * Wickelt das ACT in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ActionDefinition]s als `elements`. Im V2.0.10-MVP rendert
     * `Sysml2DefLatexRenderer` Action-Knoten als Rechteck mit
     * kind-spezifischem Stereotyp (`«action»` / `«initial node»` /
     * `«final node»` / `«flow final node»` / `«decision node»` /
     * `«merge node»` / `«fork node»` / `«join node»`) — das shape-spezifische
     * TikZ-Pendant (abgerundete Rechtecke, Kreise, Rauten, Bars) landet in
     * V2.x-Polish, analog zur BDD/IBD/UC/REQ/STM-Geschichte im
     * LaTeX-Renderer.
     *
     * Edge-Styling: Control Flows und Object Flows rendern als plain solide
     * Linie (Default-Style des Edge-Renderers). Der `[guard]`-Label (Control
     * Flow) und `[ObjectType]`-Label (Object Flow) sind V2.x.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: ActDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ActionDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, options, ActEdgeAdapter(model, diagram))
    }

    /**
     * Render a SysML 2 SEQ-Diagram as TikZ source (V2.0.11).
     *
     * **Architektur-Divergenz** wie in der SVG-Pipeline: SEQ-Nachrichten sind
     * keine LayoutGraph-Edges. Im V2.0.11-MVP rendert der LaTeX-Renderer die
     * sichtbaren [LifelineDefinition]s als Rechteckte mit `«lifeline»`-Stereotyp;
     * Nachrichten werden bewusst nicht gezeichnet, weil die TikZ-Auto-Routing-
     * Tools (z.B. `pgf-umlsd`) für eine vollwertige SEQ-Darstellung gebraucht
     * werden — Symmetrie zur SVG-Pipeline würde hier separate TikZ-Pfad-
     * Berechnung erfordern und ist auf V2.x verschoben.
     *
     * Edge-Styling: keine Edges im Output (Nachrichten sind nicht in
     * `layoutResult.edges`). Die SEQ-Darstellung als vollwertiges TikZ-
     * Sequence-Diagramm via `pgf-umlsd` ist V2.x-Polish.
     *
     * V2.x: Combined Fragments, Execution Specifications, and Create/Destroy
     * messages are SVG-only in V2.0.15; LaTeX polish is V2.x. The Lifeline-
     * rectangle fallback keeps the LaTeX output structurally consistent with
     * the V2.0.11 baseline while the SVG side delivers the full V2.0.15
     * polish (dashed fragment frames, activation bars, lifecycle stereotypes).
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: SeqDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is LifelineDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return toLatex(synthetic, layoutResult, options)
    }

    /**
     * Render a SysML 2 PAR-Diagram as TikZ source (V2.0.12) — die schließende
     * achte Welle der SysML-2-Diagramm-Typ-Serie.
     *
     * Wickelt das PAR in ein synthetisches [KumlDiagram] mit den sichtbaren
     * [ConstraintDefinition]s und [PartDefinition]s als `elements`. Im
     * V2.0.12-MVP rendert `Sysml2DefLatexRenderer` Constraints als Rechteck mit
     * `«constraint»`-Stereotyp; das dreikompartimentige TikZ-Pendant (Stereotyp
     * + Name + Expression-Body + Parameter-Pin-Liste mit `«in»` / `«out»` /
     * `«inout»`-Stereotyp-Präfix) ist V2.x-Polish, analog zur
     * BDD/IBD/UC/REQ/STM/ACT/SEQ-Geschichte im LaTeX-Renderer.
     *
     * Edge-Styling: Bindings rendern als plain solide Linie (Default-Style des
     * Edge-Renderers). Parameter-Pin-Endpunkt-Anchoring (Bindings docken
     * direkt am Pin statt am Box-Mittelpunkt an) ist V2.x.
     */
    public fun toLatex(
        model: Sysml2Model,
        diagram: ParDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        val visible = diagram.elementIds.toSet()
        val elements =
            model.definitions
                .filter { it.id in visible }
                .filter { it is ConstraintDefinition || it is PartDefinition }
        val synthetic =
            KumlDiagram(
                name = diagram.name,
                type = DiagramType.CLASS,
                elements = elements,
            )
        return renderSysml2Synthetic(synthetic, layoutResult, options, ParEdgeAdapter(model, diagram))
    }

    // ─── Edge dispatch ────────────────────────────────────────────────────────

    private fun StringBuilder.renderEdge(
        relationship: UmlRelationship?,
        route: EdgeRoute,
        options: LatexRenderOptions,
    ) {
        // Style picked per UML relationship kind. Unknown / missing relationships
        // get a neutral solid line — that's still useful diagnostically (the
        // layout said "there's an edge here", we just don't know what UML calls it).
        val style =
            when (relationship) {
                is UmlGeneralization -> UmlEdgeLatexRenderer.Style.GENERALIZATION
                is UmlInterfaceRealization -> UmlEdgeLatexRenderer.Style.REALIZATION
                is UmlDependency -> UmlEdgeLatexRenderer.Style.DEPENDENCY
                is UmlAssociation -> UmlEdgeLatexRenderer.Style.ASSOCIATION
                else -> UmlEdgeLatexRenderer.Style.PLAIN
            }
        UmlEdgeLatexRenderer.render(route, style, options, this)
    }

    // ─── Preamble + skeleton ─────────────────────────────────────────────────

    private fun StringBuilder.appendStandalonePreamble() {
        appendLine("\\documentclass[border=10pt]{standalone}")
        appendLine("\\usepackage{tikz}")
        // `babel` provides `\guillemotleft` / `\guillemotright` reliably in
        // every modern engine; we don't need fontenc for ASCII output.
        appendLine("\\usepackage[T1]{fontenc}")
        appendLine("\\usepackage[english]{babel}")
        appendLine("\\usetikzlibrary{arrows.meta, calc, positioning}")
        appendLine("\\begin{document}")
    }

    private fun StringBuilder.appendStandaloneCoda() {
        appendLine("\\end{document}")
    }

    private fun StringBuilder.appendPictureOpen(scale: Double) {
        // `x=1pt, y=-1pt` would also work for axis-flipping but it inverts
        // arrow heads' implicit "up" direction in some libraries. We instead
        // negate Y at emission time and leave the global transform vanilla
        // except for an optional `scale=…`.
        if (scale == 1.0) {
            appendLine("\\begin{tikzpicture}")
        } else {
            appendLine("\\begin{tikzpicture}[scale=${fmtCoord(scale.toFloat())}, every node/.style={transform shape}]")
        }
    }

    private fun StringBuilder.appendPictureClose() {
        appendLine("\\end{tikzpicture}")
    }

    /**
     * Theme-style block emitted once per picture. The MVP `plain` theme is
     * monochrome: thin black borders, white background, sans-serif content.
     *
     * Users can override these styles in their document preamble (TikZ
     * resolves `/.style` lookups at parse time, so a later definition wins).
     */
    private fun StringBuilder.appendTikzStyles(indent: String) {
        appendLine("$indent% kUML plain theme — override these styles in your preamble to restyle.")
        appendLine("$indent\\tikzset{")
        appendLine("$indent  kuml-class/.style={draw=black, fill=white, line width=0.6pt, rectangle, inner sep=0pt, font=\\sffamily},")
        appendLine("$indent  kuml-interface/.style={kuml-class},")
        appendLine("$indent  kuml-enum/.style={kuml-class},")
        appendLine("$indent  kuml-stereotype/.style={font=\\sffamily\\itshape\\small},")
        appendLine("$indent  kuml-classname/.style={font=\\sffamily\\bfseries},")
        appendLine("$indent  kuml-classname-abstract/.style={font=\\sffamily\\bfseries\\itshape},")
        appendLine("$indent  kuml-feature/.style={font=\\sffamily\\footnotesize},")
        appendLine("$indent  kuml-association/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},")
        appendLine("$indent  kuml-generalization/.style={draw=black, line width=0.6pt, -{Triangle[length=3mm, open]}},")
        appendLine("$indent  kuml-realization/.style={draw=black, line width=0.6pt, dashed, -{Triangle[length=3mm, open]}},")
        appendLine("$indent  kuml-dependency/.style={draw=black, line width=0.5pt, dashed, -{Stealth[length=2mm]}},")
        appendLine("$indent  kuml-edge-plain/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},")
        // V2.0.13 — SysML 2 edge styles for UC / REQ / STM / ACT / PAR
        // dispatched via `Sysml2EdgeLatexRenderer`. Solid + dashed variants
        // cover the four arrow-bearing edge kinds (UC includes / extends,
        // REQ traceability, STM transitions, ACT flows); the `binding`
        // variant is plain solid with no arrow head for PAR bindings.
        appendLine("$indent  kuml-sysml2-edge-solid/.style={draw=black, line width=0.6pt, -{Stealth[length=2.2mm]}},")
        appendLine(
            "$indent  kuml-sysml2-edge-dashed/.style={draw=black, line width=0.6pt, dashed," +
                " -{Stealth[length=2.2mm]}},",
        )
        appendLine(
            "$indent  kuml-sysml2-edge-dashed-noarrow/.style={draw=black, line width=0.6pt, dashed},",
        )
        appendLine("$indent  kuml-sysml2-edge-binding/.style={draw=black, line width=0.6pt},")
        appendLine("$indent}")
    }
}
