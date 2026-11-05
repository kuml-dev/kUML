package dev.kuml.io.latex

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.bpmn.model.ProcessDiagram
import dev.kuml.c4.model.C4Diagram
import dev.kuml.c4.model.C4Element
import dev.kuml.c4.model.C4Model
import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlElement
import dev.kuml.io.latex.blueprint.BlueprintLatexRenderer
import dev.kuml.io.latex.bpmn.BpmnLatexRenderer
import dev.kuml.io.latex.c4.C4LatexRenderer
import dev.kuml.io.latex.note.NoteTikz
import dev.kuml.io.latex.note.NoteTikzAnchor
import dev.kuml.io.latex.note.NoteTikzSpec
import dev.kuml.io.latex.sysml2.Sysml2DefLatexRenderer
import dev.kuml.io.latex.sysml2.edge.Sysml2EdgeLatexRenderer
import dev.kuml.io.latex.uml.UmlClassLatexRenderer
import dev.kuml.io.latex.uml.UmlEdgeLabelLatexRenderer
import dev.kuml.io.latex.uml.UmlEdgeLabels
import dev.kuml.io.latex.uml.UmlEdgeLatexRenderer
import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.bridge.NoteLayout
import dev.kuml.layout.bridge.note.C4NotePlacement
import dev.kuml.layout.bridge.note.Sysml2NotePlacement
import dev.kuml.layout.bridge.note.WidthLimitedNoteMeasure
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
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import dev.kuml.sysml2.UseCaseDefinition
import dev.kuml.sysml2.edge.ActEdgeAdapter
import dev.kuml.sysml2.edge.BddEdgeAdapter
import dev.kuml.sysml2.edge.IbdEdgeAdapter
import dev.kuml.sysml2.edge.ParEdgeAdapter
import dev.kuml.sysml2.edge.ReqEdgeAdapter
import dev.kuml.sysml2.edge.StmEdgeAdapter
import dev.kuml.sysml2.edge.Sysml2EdgeAdapter
import dev.kuml.sysml2.edge.UcEdgeAdapter
import dev.kuml.sysml2.usesStmStructure
import dev.kuml.sysml2.visibleStateIds
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlCommunicationPath
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.navigability

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
    /** Message of the refusal for package diagrams (wave 2I-a): the LaTeX renderer does not draw frames, tabs or view cards yet. */
    public const val PACKAGE_DIAGRAM_UNSUPPORTED: String = "Package diagrams are not supported by the LaTeX renderer yet (wave 2I-a)"

    /**
     * Package diagrams (wave 2I-a) have no TikZ rendering yet. This always throws [UnsupportedOperationException] with
     * [PACKAGE_DIAGRAM_UNSUPPORTED]; callers turn it into a plain error message instead of a stack trace.
     */
    @Suppress("UNUSED_PARAMETER")
    public fun toLatex(
        model: Sysml2Model,
        diagram: dev.kuml.sysml2.Sysml2PackageDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String = throw UnsupportedOperationException(PACKAGE_DIAGRAM_UNSUPPORTED)

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
    ): String = renderKuml(diagram = diagram, layoutResult = layoutResult, options = options)

    /**
     * Shared body of the UML entry and the SysML 2 sequence diagram.
     *
     * [extraNotes] are notes the caller placed itself (SysML 2 comments), [anchorIds] the ids of their anchor
     * edges in [layoutResult]. UML comments need neither: they are ordinary layout nodes and [UmlCommentLink]
     * relationships.
     */
    private fun renderKuml(
        diagram: KumlDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions,
        extraNotes: List<NoteTikzSpec> = emptyList(),
        anchorIds: Set<String> = emptySet(),
        noteMaxWidth: Float = NoteLayout.MAX_W,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)

            val umlNotes = diagram.elements.filterIsInstance<UmlComment>().filter { NodeId(it.id) in layoutResult.nodes }
            if (umlNotes.isNotEmpty() || extraNotes.isNotEmpty()) {
                NoteTikz.appendNoteStyles(sb = this, indent = options.indent, kinds = umlNotes.map { it.kind } + extraNotes.map { it.kind })
            }

            // Nodes — index elements by id once, then iterate the layout to
            // preserve the engine's deterministic ordering.
            val nodesById: Map<String, KumlElement> = diagram.elements.associateBy { it.id }

            for ((nodeId, nodeLayout) in layoutResult.nodes) {
                val element = nodesById[nodeId.value] ?: continue
                renderNode(element = element, nodeId = nodeId, nodeLayout = nodeLayout, options = options)
            }
            for (note in extraNotes) {
                NoteTikz.renderNote(spec = note, options = options, out = this, maxWidth = noteMaxWidth)
            }

            // Edges
            val relationshipsById: Map<String, UmlRelationship> =
                diagram.elements.filterIsInstance<UmlRelationship>().associateBy { it.id }

            for ((edgeId, route) in layoutResult.edges) {
                if (edgeId.value in anchorIds) {
                    NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = this)
                    continue
                }
                val rel = relationshipsById[edgeId.value]
                renderEdge(relationship = rel, route = route, options = options)
            }
            // A comment link the layout carries no route for still gets a line between the two named nodes.
            for (link in relationshipsById.values.filterIsInstance<UmlCommentLink>()) {
                if (EdgeId(link.id) in layoutResult.edges) continue
                if (NodeId(link.commentId) in layoutResult.nodes && NodeId(link.annotatedElementId) in layoutResult.nodes) {
                    NoteTikz.renderAnchorBetween(noteId = link.commentId, targetId = link.annotatedElementId, options = options, out = this)
                }
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
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        synthetic: KumlDiagram,
        layoutResult: LayoutResult,
        options: LatexRenderOptions,
        sysml2EdgeAdapter: Sysml2EdgeAdapter,
        frames: List<Pair<String, String>> = emptyList(),
        noteMaxWidth: Float = NoteLayout.MAX_W,
    ): String {
        // Notes are placed here (the SVG renderer does the same after the layout); without comments the
        // layout comes back unchanged, so the output stays byte-identical. Everything below uses the placed
        // layout because the placement can shift the whole drawing.
        val placed =
            placeSysml2Notes(
                model = model,
                diagram = diagram,
                layout = layoutResult,
                adapter = sysml2EdgeAdapter,
                noteMaxWidth = noteMaxWidth,
            )
        val notes =
            sysml2NoteSpecs(
                model = model,
                layout = placed,
                taken =
                    layoutResult.nodes.keys
                        .map { it.value }
                        .toSet(),
            )
        val linkIds = model.commentLinks.map { it.id }.toSet()
        return buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)
            if (notes.isNotEmpty()) {
                NoteTikz.appendNoteStyles(sb = this, indent = options.indent, kinds = notes.map { it.kind })
            }

            // Wave C1: group / subject frames (compound groups of the layout) sit behind the nodes.
            appendSysml2Frames(frames = frames, layoutResult = placed, options = options)

            val nodesById: Map<String, KumlElement> = synthetic.elements.associateBy { it.id }
            for ((nodeId, nodeLayout) in placed.nodes) {
                val element = nodesById[nodeId.value] ?: continue
                renderNode(element = element, nodeId = nodeId, nodeLayout = nodeLayout, options = options)
            }
            for (note in notes) {
                NoteTikz.renderNote(spec = note, options = options, out = this, maxWidth = noteMaxWidth)
            }

            val relationshipsById: Map<String, UmlRelationship> =
                synthetic.elements.filterIsInstance<UmlRelationship>().associateBy { it.id }

            for ((edgeId, route) in placed.edges) {
                // Anchor lines of notes first: the adapter fallback below would draw them as solid arrows.
                if (edgeId.value in linkIds) {
                    NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = this)
                    continue
                }
                val rel = relationshipsById[edgeId.value]
                if (rel != null) {
                    renderEdge(relationship = rel, route = route, options = options)
                    continue
                }
                val meta = sysml2EdgeAdapter.metadataFor(edgeId.value)
                if (meta != null) {
                    Sysml2EdgeLatexRenderer.render(route = route, metadata = meta, options = options, out = this)
                } else {
                    // V2.0.7–12 fallback: plain solid line. Reached only if
                    // the adapter doesn't claim the edge.
                    renderEdge(relationship = null, route = route, options = options)
                }
            }

            appendPictureClose()

            if (options.standalone) {
                appendStandaloneCoda()
            }
        }
    }

    /**
     * Places the comments of [diagram]. [Sysml2NotePlacement] returns [layout] itself when the model has no comment
     * the diagram shows. The SVG-only extras (state-machine label planner, sequence stems, port anchors) are not
     * available here, so an anchor on a port or an owner frame stays unresolved and its note is left out.
     */
    private fun placeSysml2Notes(
        model: Sysml2Model,
        diagram: Sysml2Diagram,
        layout: LayoutResult,
        adapter: Sysml2EdgeAdapter?,
        noteMaxWidth: Float,
    ): LayoutResult =
        Sysml2NotePlacement
            .place(
                model = model,
                diagram = diagram,
                layout = layout,
                edgeLabels = { id ->
                    val md = adapter?.metadataFor(id)
                    listOfNotNull(md?.label?.takeIf { it.isNotEmpty() }, md?.endLabel?.takeIf { it.isNotEmpty() })
                },
                measure =
                    if (noteMaxWidth <
                        NoteLayout.MAX_W
                    ) {
                        WidthLimitedNoteMeasure(noteMaxWidth)
                    } else {
                        dev.kuml.layout.bridge.note.DefaultNoteMeasure
                    },
            ).layout

    /** The comments of [model] that [layout] carries as nodes (so were placed), as draw specs. */
    private fun sysml2NoteSpecs(
        model: Sysml2Model,
        layout: LayoutResult,
        taken: Set<String>,
    ): List<NoteTikzSpec> =
        model.comments
            .filter { it.id !in taken }
            .mapNotNull { c ->
                val node = layout.nodes[NodeId(c.id)] ?: return@mapNotNull null
                NoteTikzSpec(id = c.id, body = c.note.body, kind = c.note.kind, bounds = node.bounds)
            }

    /** Draws one rectangle with a bold name label per `(groupId, name)` pair that the layout knows. */
    private fun StringBuilder.appendSysml2Frames(
        frames: List<Pair<String, String>>,
        layoutResult: LayoutResult,
        options: LatexRenderOptions,
    ) {
        for ((groupId, name) in frames) {
            val b = layoutResult.groups[dev.kuml.layout.GroupId(groupId)]?.bounds ?: continue
            val x = b.origin.x
            val y = b.origin.y
            appendLine(
                "${options.indent}\\node[kuml-sysml2-frame, anchor=north west, " +
                    "minimum width=${fmtCoord(b.size.width)}pt, minimum height=${fmtCoord(b.size.height)}pt] " +
                    "at (${fmtCoord(x)}pt, ${fmtCoord(-y)}pt) {};",
            )
            appendLine(
                "${options.indent}\\node[anchor=north west, font=\\sffamily\\bfseries\\small] " +
                    "at (${fmtCoord(x + 6f)}pt, ${fmtCoord(-(y + 4f))}pt) {${escapeLatex(name)}};",
            )
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
            is Sysml2Definition ->
                Sysml2DefLatexRenderer.render(
                    definition = element,
                    nodeId = nodeId,
                    layout = nodeLayout,
                    options = options,
                    out = this,
                )
            // V2.x: full IBD-styled TikZ for usages (stereotype band + content
            // line, matching the SVG renderer's two-line layout). V2.0.6
            // shipped with the rectangle-with-label fallback for usages —
            // every usage still lands at the layout-faithful pixel position
            // with `name : Type` as its label, which is enough for the V2.0.6
            // IBD pipeline to round-trip end-to-end through CLI / docs.
            is PartUsage ->
                UmlClassLatexRenderer.renderFallback(
                    nodeId = nodeId,
                    layout = nodeLayout,
                    options = options,
                    out = this,
                    label = "${element.name} : ${element.definitionId}",
                )
            is UmlClassifier ->
                UmlClassLatexRenderer.render(
                    classifier = element,
                    nodeId = nodeId,
                    layout = nodeLayout,
                    options = options,
                    out = this,
                )
            is UmlNamedElement ->
                UmlClassLatexRenderer.renderFallback(
                    nodeId = nodeId,
                    layout = nodeLayout,
                    options = options,
                    out = this,
                    label = element.name,
                )
            // C4 elements dispatched via the dedicated C4 LaTeX renderer.
            is C4Element ->
                C4LatexRenderer.renderNode(
                    element = element,
                    nodeId = nodeId,
                    nodeLayout = nodeLayout,
                    options = options,
                    out = this,
                )
            // A UML comment is a note, not a labelled rectangle (it is not a UmlNamedElement).
            is UmlComment ->
                NoteTikz.renderNote(
                    spec = NoteTikzSpec(id = element.id, body = element.body, kind = element.kind, bounds = nodeLayout.bounds),
                    options = options,
                    out = this,
                )
            else ->
                UmlClassLatexRenderer.renderFallback(
                    nodeId = nodeId,
                    layout = nodeLayout,
                    options = options,
                    out = this,
                    label = element.id,
                )
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = BddEdgeAdapter(model = model, diagram = diagram),
        )
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = IbdEdgeAdapter(model = model, diagram = diagram),
        )
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = UcEdgeAdapter(diagram),
            frames = diagram.subjects.map { it.id to it.name },
        )
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
                        it is dev.kuml.sysml2.TestCaseDefinition ||
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = ReqEdgeAdapter(diagram),
            frames = diagram.groups.map { it.id to it.name },
        )
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
        val visible = diagram.visibleStateIds(model)
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
        val tikz =
            renderSysml2Synthetic(
                model = model,
                diagram = diagram,
                synthetic = synthetic,
                // Drop the layout-only region-ordering edges ("stm:order:" prefix, see
                // Sysml2LayoutBridge.STM_ORDER_EDGE_PREFIX; this module does not depend on the bridge).
                layoutResult = layoutResult.copy(edges = layoutResult.edges.filterKeys { !it.value.startsWith("stm:order:") }),
                options = options,
                sysml2EdgeAdapter = StmEdgeAdapter(model = model, diagram = diagram),
                noteMaxWidth = NoteLayout.STM_MAX_W,
            )
        // Wave A1: the TikZ backend draws every state as a flat box. Say so in the output
        // instead of silently dropping the structure (this module has no logging dependency).
        return if (model.usesStmStructure(visible)) {
            "% kUML: composite/region nesting is not rendered by the LaTeX backend yet; states are drawn flat.\n$tikz"
        } else {
            tikz
        }
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
     *
     * V2.x: **Partitions and pins are SVG-only in V2.0.16; LaTeX polish is V2.x.**
     * The wave V2.0.16 added Activity Partitions (swimlanes) and typed pins
     * on actions to the metamodel + SVG renderer, but the LaTeX renderer
     * keeps the V2.0.10 baseline visual: an [ActivityPartitionDefinition]
     * falls back to a plain rectangle box with `«activity partition»`-
     * stereotype (see `Sysml2DefLatexRenderer`), and pins are silently
     * omitted from the action box rendering. Achieving the full lane-with-
     * header-bar + side-anchored-pin visual in TikZ needs a dedicated
     * partition-aware rendering pass (analogous to the SVG renderer's
     * `renderActivityPartitionGroup`) which is deferred to V2.x — same
     * polish-deferral pattern as V2.0.15 Combined Fragments / Execution
     * Specs / Create / Destroy.
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = ActEdgeAdapter(model = model, diagram = diagram),
        )
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
        // The comments are placed here and drawn by the shared UML path (SEQ has no edge adapter).
        val placed =
            placeSysml2Notes(model = model, diagram = diagram, layout = layoutResult, adapter = null, noteMaxWidth = NoteLayout.MAX_W)
        return renderKuml(
            diagram = synthetic,
            layoutResult = placed,
            options = options,
            extraNotes =
                sysml2NoteSpecs(
                    model = model,
                    layout = placed,
                    taken =
                        layoutResult.nodes.keys
                            .map { it.value }
                            .toSet(),
                ),
            anchorIds = model.commentLinks.map { it.id }.toSet(),
        )
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
        return renderSysml2Synthetic(
            model = model,
            diagram = diagram,
            synthetic = synthetic,
            layoutResult = layoutResult,
            options = options,
            sysml2EdgeAdapter = ParEdgeAdapter(model = model, diagram = diagram),
        )
    }

    /**
     * Render a C4 diagram as TikZ source.
     *
     * All C4 element types supported by the metamodel are rendered with their
     * dedicated TikZ styles (see [C4LatexRenderer]):
     * - [dev.kuml.c4.model.C4Person] → `kuml-c4-person`
     * - [dev.kuml.c4.model.C4SoftwareSystem] → `kuml-c4-system`
     * - [dev.kuml.c4.model.C4Container] → `kuml-c4-container`
     * - [dev.kuml.c4.model.C4Component] → `kuml-c4-component`
     * - [dev.kuml.c4.model.C4DeploymentNode] → `kuml-c4-node`
     * - [dev.kuml.c4.model.C4CodeElement] → `kuml-c4-code`
     *
     * Relationships are rendered as plain solid arrows. The relationship label
     * is emitted at the mid-point of the route. Technology annotations appear
     * in the stereotype of Container / Component / Node nodes.
     *
     * V2.x polish: grouped system boundaries, Database cylinder shape,
     * technology sub-label, interaction sequence numbers for DynamicDiagram.
     *
     * @param diagram The C4 diagram (view) to render.
     * @param model The parent C4 model for element lookup.
     * @param layoutResult Positions and edge routes from the C4 layout bridge.
     * @param options Tuning knobs; defaults are usually fine.
     * @return A self-contained `\begin{tikzpicture}…\end{tikzpicture}`
     *   block (snippet mode) or a complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        diagram: C4Diagram,
        model: C4Model,
        layoutResult: LayoutResult,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String {
        // Notes are placed here, as the SVG renderer does; without notes the layout comes back unchanged.
        val placed = C4NotePlacement.place(model = model, diagram = diagram, layout = layoutResult).layout
        val original =
            layoutResult.nodes.keys
                .map { it.value }
                .toSet()
        val notes =
            model.notes
                .filter { it.id !in original }
                .mapNotNull { n ->
                    val node = placed.nodes[NodeId(n.id)] ?: return@mapNotNull null
                    NoteTikzSpec(id = n.id, body = n.body, kind = n.kind, bounds = node.bounds)
                }
        val linkIds = model.noteLinks.map { it.id }.toSet()
        return buildString {
            if (options.standalone) {
                appendStandalonePreamble()
            }

            appendPictureOpen(options.scale)
            appendTikzStyles(options.indent)
            if (notes.isNotEmpty()) {
                NoteTikz.appendNoteStyles(sb = this, indent = options.indent, kinds = notes.map { it.kind })
            }

            // Build element index for O(1) lookup by id.
            val elementIndex = model.elements.associateBy { it.id }
            val relationshipIndex = model.relationships.associateBy { it.id }

            // Nodes — iterate in layout order to match the SVG renderer's
            // deterministic ordering.
            for ((nodeId, nodeLayout) in placed.nodes) {
                val element = elementIndex[nodeId.value] ?: continue
                // renderNode dispatches C4Element to C4LatexRenderer via the
                // is C4Element branch added in the renderNode when-expression.
                renderNode(element = element, nodeId = nodeId, nodeLayout = nodeLayout, options = options)
            }
            for (note in notes) {
                NoteTikz.renderNote(spec = note, options = options, out = this)
            }

            // Edges — C4 relationships as plain solid arrows with mid-point labels. Anchor lines of notes are
            // drawn dashed below and must not reach the relationship renderer.
            val visibleRelationships: List<C4Relationship> =
                diagram.relationships.mapNotNull { relId -> relationshipIndex[relId] }
            C4LatexRenderer.renderEdges(
                relationships = visibleRelationships,
                layoutResult = if (linkIds.isEmpty()) placed else placed.copy(edges = placed.edges.filterKeys { it.value !in linkIds }),
                options = options,
                out = this,
            )
            for ((edgeId, route) in placed.edges) {
                if (edgeId.value in linkIds) {
                    NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = this)
                }
            }

            appendPictureClose()

            if (options.standalone) {
                appendStandaloneCoda()
            }
        }
    }

    // ─── Edge dispatch ────────────────────────────────────────────────────────

    private fun StringBuilder.renderEdge(
        relationship: UmlRelationship?,
        route: EdgeRoute,
        options: LatexRenderOptions,
    ) {
        if (relationship is UmlCommentLink) {
            NoteTikz.renderAnchor(route = route, style = NoteTikzAnchor.DASHED, options = options, out = this)
            return
        }
        // Style picked per UML relationship kind. Unknown / missing relationships
        // get a neutral solid line — that's still useful diagnostically (the
        // layout said "there's an edge here", we just don't know what UML calls it).
        var arrowOverride: String? = null
        var sourceEnd = UmlEdgeLatexRenderer.UmlLineEnd.NONE
        var targetEnd = UmlEdgeLatexRenderer.UmlLineEnd.NONE
        val style =
            when (relationship) {
                is UmlGeneralization -> UmlEdgeLatexRenderer.Style.GENERALIZATION
                is UmlInterfaceRealization -> UmlEdgeLatexRenderer.Style.REALIZATION
                is UmlDependency -> UmlEdgeLatexRenderer.Style.DEPENDENCY
                is UmlAssociation -> {
                    arrowOverride =
                        UmlEdgeLatexRenderer.edgeEndsOverride(
                            aggregation = relationship.aggregation,
                            navigability = relationship.navigability(),
                        )
                    UmlEdgeLatexRenderer
                        .lineEnds(aggregation = relationship.aggregation, navigability = relationship.navigability())
                        .let { (s, t) ->
                            sourceEnd = s
                            targetEnd = t
                        }
                    UmlEdgeLatexRenderer.Style.ASSOCIATION
                }
                is UmlInclude, is UmlExtend -> {
                    arrowOverride = "-{${UmlEdgeLatexRenderer.OPEN_HEAD}}"
                    UmlEdgeLatexRenderer.Style.DEPENDENCY
                }
                is UmlConnector, is UmlCommunicationPath -> {
                    arrowOverride = "-"
                    UmlEdgeLatexRenderer.Style.PLAIN
                }
                else -> UmlEdgeLatexRenderer.Style.PLAIN
            }
        UmlEdgeLatexRenderer.render(route = route, style = style, options = options, out = this, arrowOverride = arrowOverride)
        UmlEdgeLabelLatexRenderer.render(
            labels = UmlEdgeLabels.of(relationship),
            route = route,
            options = options,
            out = this,
            sourceEnd = sourceEnd,
            targetEnd = targetEnd,
        )
    }

    // ─── Preamble + skeleton ─────────────────────────────────────────────────

    private fun StringBuilder.appendStandalonePreamble() {
        appendLine("\\documentclass[border=10pt]{standalone}")
        appendLine("\\usepackage{tikz}")
        // `inputenc` enables UTF-8 input so non-ASCII characters (e.g. ×, «, »)
        // compile correctly with pdflatex in ASCII-only documents.
        appendLine("\\usepackage[utf8]{inputenc}")
        // `babel` provides `\guillemotleft` / `\guillemotright` reliably in
        // every modern engine; fontenc T1 enables proper hyphenation + guillemets.
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
        appendLine("$indent  kuml-edge-bidi/.style={draw=black, line width=0.6pt, {Stealth[length=2.2mm]}-{Stealth[length=2.2mm]}},")
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
        // Wave C1: actor generalisation (hollow triangle) and group / subject frames.
        appendLine("$indent  kuml-sysml2-edge-generalization/.style={draw=black, line width=0.6pt, -{Triangle[length=3mm, open]}},")
        appendLine("$indent  kuml-sysml2-frame/.style={draw=black, line width=0.6pt, rectangle, inner sep=0pt, font=\\sffamily},")
        // Welle B: composition / aggregation diamonds sit on the source (owner) end.
        appendLine("$indent  kuml-sysml2-edge-diamond-filled/.style={draw=black, line width=0.6pt, {Diamond[length=3mm]}-},")
        appendLine("$indent  kuml-sysml2-edge-diamond-open/.style={draw=black, line width=0.6pt, {Diamond[open, length=3mm]}-},")
        appendLine("$indent  kuml-sysml2-edge-containment/.style={draw=black, line width=0.6pt, {Circle[open, length=3mm]}-},")
        // C4 node styles — one per C4 element kind.
        appendLine(
            "$indent  kuml-c4-person/.style={draw=black, fill=white, line width=0.6pt, " +
                "rectangle, rounded corners=16pt, inner sep=0pt, font=\\sffamily},",
        )
        appendLine(
            "$indent  kuml-c4-system/.style={draw=black, fill=blue!10, line width=0.6pt, " +
                "rectangle, inner sep=0pt, font=\\sffamily},",
        )
        appendLine(
            "$indent  kuml-c4-container/.style={draw=black, fill=blue!20, line width=0.6pt, " +
                "rectangle, inner sep=0pt, font=\\sffamily},",
        )
        appendLine(
            "$indent  kuml-c4-component/.style={draw=black, fill=white, line width=0.6pt, " +
                "rectangle, inner sep=0pt, font=\\sffamily},",
        )
        appendLine(
            "$indent  kuml-c4-node/.style={draw=black, fill=white, line width=0.6pt, " +
                "rectangle, dashed, inner sep=0pt, font=\\sffamily},",
        )
        appendLine(
            "$indent  kuml-c4-code/.style={draw=black, fill=white, line width=0.6pt, " +
                "rectangle, inner sep=0pt, font=\\sffamily},",
        )
        appendLine("$indent  kuml-c4-label/.style={font=\\sffamily},")
        appendLine("$indent  kuml-c4-edge-label/.style={font=\\sffamily\\small},")
        // V3.1.8 — BPMN TikZ styles (events, gateways, tasks, pools, edges, labels).
        // Injected via BpmnLatexRenderer.appendBpmnTikzStyles to keep the style block
        // co-located with the BPMN renderer.
        appendLine("$indent  % ── BPMN styles ─────────────────────────────────────────────────────────")
        BpmnLatexRenderer.appendBpmnTikzStyles(sb = this, indent = indent)
        // V3.1.26 — Blueprint / Journey TikZ styles (bands, steps, lines, emotion).
        appendLine("$indent  % ── Blueprint styles ────────────────────────────────────────────────────")
        BlueprintLatexRenderer.appendBlueprintTikzStyles(sb = this, indent = indent)
        appendLine("$indent}")
    }

    /**
     * Render a Blueprint / Journey-Map [BlueprintDiagram] as TikZ source
     * (V3.1.26).
     *
     * The blueprint is a strict table (phases = columns, layers = rows); the
     * renderer computes coordinates directly (no layout engine), mirroring the
     * SVG renderer. The emotion curve becomes a `\draw … plot coordinates`, the
     * three separator lines use `solid`/`dashed`/`dotted` styles, and the four
     * swimlane bands are labelled to the left of the grid.
     *
     * @return a `\tikzset{…}` preamble + `\begin{tikzpicture}…\end{tikzpicture}`
     *   block (snippet mode), or a complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        model: BlueprintModel,
        diagram: BlueprintDiagram,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String = renderBlueprint(options = options) { BlueprintLatexRenderer.render(model = model, diagram = diagram, options = options) }

    /**
     * Render a Blueprint model's first diagram as TikZ source (V3.1.26).
     * Returns an empty string if the model has no diagrams.
     */
    public fun toLatex(
        model: BlueprintModel,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String =
        when (val first = model.diagrams.firstOrNull()) {
            null -> ""
            else -> toLatex(model = model, diagram = first, options = options)
        }

    /**
     * Shared wrapper for the Blueprint render overload. Emits the standalone
     * preamble (with the extra `shapes.geometric` + `plotmarks` libraries the
     * blueprint output needs), the shared `\tikzset{…}` style block, then the
     * inner `\begin{tikzpicture}…\end{tikzpicture}` block.
     */
    private fun renderBlueprint(
        options: LatexRenderOptions,
        inner: () -> String,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
                // Blueprint needs extra TikZ libraries beyond the shared preamble.
                appendLine("\\usetikzlibrary{shapes.geometric, plotmarks}")
            }
            appendTikzStyles(options.indent)
            append(inner())
            if (options.standalone) {
                appendStandaloneCoda()
            }
        }

    /**
     * Render a BPMN [ProcessDiagram] as TikZ source (V3.1.8).
     *
     * Flow nodes are linearised left-to-right using `right of=<prevId>` with
     * `node distance=50pt and 80pt`. Sequence flows are rendered as
     * `kuml-bpmn-flow` draw-paths. BPMN-specific TikZ styles (`kuml-bpmn-*`)
     * are emitted in a preceding `\tikzset{…}` block so the output is
     * self-contained and compiles without 'undefined style' errors.
     *
     * @param model The BPMN model containing the processes.
     * @param diagram The process diagram view to render.
     * @param options Tuning knobs; defaults are usually fine.
     * @return A `\tikzset{…}` preamble followed by a
     *   `\begin{tikzpicture}…\end{tikzpicture}` block (snippet mode), or a
     *   complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        model: BpmnModel,
        diagram: ProcessDiagram,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String = renderBpmn(options = options) { BpmnLatexRenderer.render(model = model, diagram = diagram, options = options) }

    /**
     * Render a BPMN [CollaborationDiagram] as TikZ source (V3.1.8).
     *
     * Participant pools are stacked vertically. Message flows are rendered as
     * dashed open-triangle arrows (`kuml-bpmn-msgflow`). BPMN-specific TikZ
     * styles (`kuml-bpmn-*`) are emitted in a preceding `\tikzset{…}` block so
     * the output is self-contained and compiles without 'undefined style' errors.
     *
     * @param model The BPMN model containing the collaborations.
     * @param diagram The collaboration diagram view to render.
     * @param options Tuning knobs; defaults are usually fine.
     * @return A `\tikzset{…}` preamble followed by a
     *   `\begin{tikzpicture}…\end{tikzpicture}` block (snippet mode), or a
     *   complete `.tex` document (standalone mode).
     */
    public fun toLatex(
        model: BpmnModel,
        diagram: CollaborationDiagram,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String = renderBpmn(options = options) { BpmnLatexRenderer.render(model = model, diagram = diagram, options = options) }

    /**
     * Render a BPMN model's first diagram as TikZ source (V3.1.8).
     *
     * Dispatches to [toLatex(BpmnModel, ProcessDiagram, LatexRenderOptions)] or
     * [toLatex(BpmnModel, CollaborationDiagram, LatexRenderOptions)] based on
     * the type of the first diagram in [BpmnModel.diagrams]. Returns an empty
     * string if the model has no diagrams.
     *
     * @param model The BPMN model to render.
     * @param options Tuning knobs; defaults are usually fine.
     * @return A `\tikzset{…}` preamble followed by a
     *   `\begin{tikzpicture}…\end{tikzpicture}` block (snippet mode), or a
     *   complete `.tex` document (standalone mode), or an empty string if the
     *   model has no diagrams.
     */
    public fun toLatex(
        model: BpmnModel,
        options: LatexRenderOptions = LatexRenderOptions.DEFAULT,
    ): String =
        when (val first = model.diagrams.firstOrNull()) {
            is ProcessDiagram -> toLatex(model = model, diagram = first, options = options)
            is CollaborationDiagram -> toLatex(model = model, diagram = first, options = options)
            is ChoreographyDiagram -> "" // LaTeX rendering for choreography diagrams is not yet implemented
            is ConversationDiagram -> "" // LaTeX rendering for conversation diagrams is not yet implemented
            null -> ""
        }

    /**
     * Shared wrapper for the BPMN render overloads.
     *
     * Emits the standalone preamble (if requested), then the `\tikzset{…}`
     * block with all `kuml-bpmn-*` style definitions, then inlines the
     * `\begin{tikzpicture}…\end{tikzpicture}` block produced by [inner].
     * This makes every BPMN output self-contained: callers do not need to
     * include a separate style block, and the output compiles without
     * 'undefined style' errors.
     */
    private fun renderBpmn(
        options: LatexRenderOptions,
        inner: () -> String,
    ): String =
        buildString {
            if (options.standalone) {
                appendStandalonePreamble()
                // The gateway uses the `diamond` shape; without this library a standalone BPMN document does not compile.
                appendLine("\\usetikzlibrary{shapes.geometric}")
            }
            appendTikzStyles(options.indent)
            append(inner())
            if (options.standalone) {
                appendStandaloneCoda()
            }
        }
}
