package dev.kuml.layout.bridge.note

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.note.isHint
import dev.kuml.layout.LayoutResult
import dev.kuml.uml.Stereotypable
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
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

/**
 * Note placement for UML diagrams (Nacharbeit 99). UML notes are ordinary ELK nodes joined to their anchors by
 * comment-link edges, so ELK decides where they sit. A note that carries a side hint (ABOVE/BELOW/LEFT/RIGHT) is
 * instead taken out of the ELK result and placed by [NotePlacer] against the finished layout, exactly like the C4 and
 * SysML 2 notes; a hint that no free position on the requested side can satisfy falls back to the default rules and
 * is reported as `KUML-NOTE-W004`.
 *
 * Sequence diagrams are skipped here: their notes are placed by [SeqNotePlacer], which evaluates the hint itself
 * (Nacharbeit 102).
 *
 * Since Nacharbeit 120 a hinted note also avoids the (estimated) centre label of every edge: stereotype header and
 * name. Multiplicity and role labels at the edge ends are not yet obstacles, and notes without a hint still ignore
 * labels.
 *
 * Notes without a hint (and NEAR hints), notes without a resolvable anchor and every layout without a hinted note stay
 * byte-identical: [place] returns the layout it was given.
 */
public object UmlNotePlacement {
    /** Places the hinted notes of [diagram] in [layout]; returns [layout] unchanged when there is nothing to do. */
    public fun place(
        diagram: KumlDiagram,
        layout: LayoutResult,
        config: NotePlacementConfig = NotePlacementConfig(),
    ): NotePlacementResult {
        val none = NotePlacementResult(layout = layout, unresolved = emptyList(), truncated = emptyList())
        if (diagram.type == DiagramType.SEQUENCE) return none
        val comments = diagram.elements.filterIsInstance<UmlComment>().filter { it.placement.isHint() }
        if (comments.isEmpty()) return none
        val links = diagram.elements.filterIsInstance<UmlCommentLink>()

        val hinted = comments.filter { it.id in layoutNodeIds(layout) }.map { it.id }.toSet()
        if (hinted.isEmpty()) return none
        val linkIdsOfHinted = links.filter { it.commentId in hinted }.map { it.id }.toSet()

        // The layout without the hinted notes and their anchor lines: what the placer measures against.
        val stripped =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in hinted },
                edges = layout.edges.filterKeys { it.value !in linkIdsOfHinted },
            )
        val resolver = AnchorGeometryResolver(layout = stripped)
        val inputs =
            comments
                .filter { it.id in hinted }
                .mapNotNull { c ->
                    val anchors =
                        links
                            .filter { it.commentId == c.id && resolver.canResolve(it.annotatedElementId) }
                            .map { NoteAnchorRef(linkId = it.id, targetId = it.annotatedElementId) }
                    if (anchors.isEmpty()) {
                        null
                    } else {
                        NotePlacementInput(noteId = c.id, body = c.body, kind = c.kind, placement = c.placement, anchors = anchors)
                    }
                }
        if (inputs.isEmpty()) return none

        // Only the notes the placer takes over leave the ELK result; every other note stays where ELK put it.
        val taken = inputs.map { it.noteId }.toSet()
        val takenLinks = links.filter { it.commentId in taken }.map { it.id }.toSet()
        val base =
            layout.copy(
                nodes = layout.nodes.filterKeys { it.value !in taken },
                edges = layout.edges.filterKeys { it.value !in takenLinks },
            )
        // Nacharbeit 120: the centre label (stereotype header, name) of an edge is an obstacle for the hinted notes.
        val labelLines = centreLabelLines(diagram)
        val obstacles = Sysml2NotePlacement.obstaclesOf(layout = base, edgeLabels = { id -> labelLines[id].orEmpty() })
        val placed =
            NotePlacer.place(
                layout = base,
                notes = inputs,
                resolver = AnchorGeometryResolver(layout = base),
                obstacles = obstacles,
                config = config,
            )
        if (placed.hintNotHonoured.isNotEmpty()) NotePlacementMissCollector.report(placed.hintNotHonoured)
        return placed
    }

    /**
     * The lines of the centre label of every UML edge element by element id, in drawn order: the stereotype header
     * `«a, b»` (if any), then the name (if any). Mirrors the label plan of the SVG renderer (which this module cannot
     * call); the sizes derived from it are estimates, as for the notes. Multiplicity and role labels are not covered.
     */
    internal fun centreLabelLines(diagram: KumlDiagram): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (el in diagram.elements) {
            val lines: List<String> =
                when (el) {
                    is UmlAssociation -> listOfNotNull(header(el), el.name)
                    is UmlAssociationClass -> listOfNotNull(header(el), el.name)
                    is UmlDependency -> listOfNotNull(header(el), el.name)
                    is UmlConnector -> listOfNotNull(header(el), el.name)
                    is UmlCommunicationPath -> listOfNotNull(header(el), el.name)
                    is UmlGeneralization -> listOfNotNull(header(el))
                    is UmlInterfaceRealization -> listOfNotNull(header(el))
                    is UmlInclude -> listOf("«include»")
                    is UmlExtend -> listOf("«extend»")
                    else -> emptyList()
                }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) out[el.id] = lines
        }
        return out
    }

    private fun header(el: Stereotypable): String? {
        val plain =
            when (el) {
                is UmlNamedElement -> el.stereotypes
                is UmlAssociation -> el.stereotypes
                else -> emptyList()
            }
        val names = (el.appliedStereotypes.map { it.stereotypeName } + plain.filter { it.isNotBlank() }).distinct()
        return if (names.isEmpty()) null else "«" + names.joinToString(", ") + "»"
    }

    private fun layoutNodeIds(layout: LayoutResult): Set<String> =
        layout.nodes.keys
            .map { it.value }
            .toSet()
}
