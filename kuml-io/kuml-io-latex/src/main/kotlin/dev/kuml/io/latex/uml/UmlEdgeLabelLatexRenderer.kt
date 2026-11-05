package dev.kuml.io.latex.uml

import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.io.latex.escapeLatex
import dev.kuml.io.latex.fmtCoord
import dev.kuml.io.latex.routeMidPoint
import dev.kuml.io.latex.stripControl
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationClass
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlCommunicationPath
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlRelationship
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Text labels of one UML edge. Blank strings count as absent (normalised in [of]). */
internal data class UmlEdgeLabels(
    val stereotypes: List<String> = emptyList(),
    val name: String? = null,
    val sourceRole: String? = null,
    val sourceMultiplicity: String? = null,
    val targetRole: String? = null,
    val targetMultiplicity: String? = null,
) {
    val isEmpty: Boolean
        get() =
            listOf(sourceRole, sourceMultiplicity, targetRole, targetMultiplicity, name).all { it == null } &&
                stereotypes.isEmpty()

    companion object {
        val NONE = UmlEdgeLabels()

        /** Builds the labels for [relationship]; [NONE] for null, comment links and kinds without labels. */
        fun of(relationship: UmlRelationship?): UmlEdgeLabels =
            when (relationship) {
                is UmlAssociation ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName } + relationship.stereotypes,
                        name = relationship.name,
                        ends = relationship.ends,
                    )
                // The class box already shows the name of an association class, so the line carries no name.
                is UmlAssociationClass ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName } + relationship.stereotypes,
                        name = null,
                        ends = relationship.ends,
                    )
                is UmlDependency ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName },
                        name = relationship.name,
                        ends = emptyList(),
                    )
                is UmlCommunicationPath ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName },
                        name = relationship.name,
                        ends = emptyList(),
                    )
                is UmlConnector ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName },
                        name = relationship.name,
                        ends = emptyList(),
                    )
                is UmlGeneralization ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName },
                        name = null,
                        ends = emptyList(),
                    )
                is UmlInterfaceRealization ->
                    build(
                        stereotypes = relationship.appliedStereotypes.map { it.stereotypeName },
                        name = null,
                        ends = emptyList(),
                    )
                is UmlInclude -> UmlEdgeLabels(stereotypes = listOf("include"))
                is UmlExtend -> UmlEdgeLabels(stereotypes = listOf("extend"))
                else -> NONE
            }

        private fun build(
            stereotypes: List<String>,
            name: String?,
            ends: List<UmlAssociationEnd>,
        ): UmlEdgeLabels {
            val source = ends.getOrNull(0)
            val target = ends.getOrNull(1)
            return UmlEdgeLabels(
                stereotypes = stereotypes.filter { it.isNotBlank() }.distinct(),
                name = name?.takeIf { it.isNotBlank() },
                sourceRole = source?.role?.takeIf { it.isNotBlank() },
                sourceMultiplicity = source?.let { multiplicityLabel(it.multiplicity) },
                targetRole = target?.role?.takeIf { it.isNotBlank() },
                targetMultiplicity = target?.let { multiplicityLabel(it.multiplicity) },
            )
        }

        /** Same rule as the SVG output: "1" gives null, lower == upper gives "n", unbounded gives "*", else "lower..upper". */
        internal fun multiplicityLabel(m: Multiplicity): String? {
            val upper = if (m.upper == null) "*" else m.upper.toString()
            val label = if (m.lower == m.upper) upper else "${m.lower}..$upper"
            return if (label == "1") null else label
        }
    }
}

/**
 * Draws the text labels of a UML edge as absolute-positioned TikZ nodes: stereotype and name on a white
 * backdrop at the route mid-point, roles and multiplicities beside the line near each end.
 *
 * There is no label planner: placement is deterministic, so labels on dense diagrams may overlap.
 * End labels are pushed past the diamond or open head drawn at that end ([endLabelOffset]); only the
 * first and last non-degenerate segments are considered.
 * Every user-supplied string goes through [esc] exactly once; the node options are constants.
 */
internal object UmlEdgeLabelLatexRenderer {
    /** Opaque white backdrop behind the centre label; kept out of the shared preamble so only labelled UML output changes. */
    private const val EDGE_LABEL_BACKDROP = "fill=white, inner sep=1.5pt"
    private const val CENTER_STYLE = "anchor=center, align=center, font=\\sffamily\\footnotesize, $EDGE_LABEL_BACKDROP"
    private const val END_STYLE_FONT = "font=\\sffamily\\footnotesize, inner sep=1.5pt"

    /** Smallest distance of an end label from the endpoint along the end segment. */
    internal const val END_OFFSET_MIN_PT = 10f

    /** Free space kept between the tip of the line end and the label. */
    internal const val END_LABEL_GAP_PT = 3f
    internal const val END_OFFSET_MAX_FRACTION = 0.4f
    private const val MIN_SEGMENT = 1e-3f

    fun render(
        labels: UmlEdgeLabels,
        route: EdgeRoute,
        options: LatexRenderOptions,
        out: StringBuilder,
        sourceEnd: UmlEdgeLatexRenderer.UmlLineEnd = UmlEdgeLatexRenderer.UmlLineEnd.NONE,
        targetEnd: UmlEdgeLatexRenderer.UmlLineEnd = UmlEdgeLatexRenderer.UmlLineEnd.NONE,
    ) {
        if (labels.isEmpty) return
        renderCenter(labels = labels, route = route, options = options, out = out)
        endSegment(route = route, atSource = true)?.let { (p, q) ->
            renderEnd(
                p = p,
                q = q,
                role = labels.sourceRole,
                multiplicity = labels.sourceMultiplicity,
                end = sourceEnd,
                options = options,
                out = out,
            )
        }
        endSegment(route = route, atSource = false)?.let { (p, q) ->
            renderEnd(
                p = p,
                q = q,
                role = labels.targetRole,
                multiplicity = labels.targetMultiplicity,
                end = targetEnd,
                options = options,
                out = out,
            )
        }
    }

    private fun renderCenter(
        labels: UmlEdgeLabels,
        route: EdgeRoute,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        if (labels.stereotypes.isEmpty() && labels.name == null) return
        val mid = routeMidPoint(route) ?: return
        val content =
            buildString {
                if (labels.stereotypes.isNotEmpty()) {
                    append("\\guillemotleft{}").append(esc(labels.stereotypes.joinToString(", "))).append("\\guillemotright{}")
                }
                if (labels.stereotypes.isNotEmpty() && labels.name != null) append("\\\\")
                labels.name?.let { append(esc(it)) }
            }
        out.appendLine(
            "${options.indent}\\node[$CENTER_STYLE] at (${fmtCoord(mid.first)}pt, ${fmtCoord(-mid.second)}pt) {$content};",
        )
    }

    /**
     * Distance of an end label from the endpoint: `max(10 pt, end length + 3 pt)`, capped at 40 % of the
     * end segment. Segments shorter than 47.5 pt (diamond), 37.5 pt (open head) or 77.5 pt (diamond plus
     * open head) therefore cannot fully clear the tip.
     */
    internal fun endLabelOffset(
        end: UmlEdgeLatexRenderer.UmlLineEnd,
        segmentLength: Float,
    ): Float = min(max(END_OFFSET_MIN_PT, end.lengthPt + END_LABEL_GAP_PT), END_OFFSET_MAX_FRACTION * segmentLength)

    /** Places role and multiplicity beside the end segment [p] (the endpoint) to [q] (the next point inwards). */
    private fun renderEnd(
        p: Point,
        q: Point,
        role: String?,
        multiplicity: String?,
        end: UmlEdgeLatexRenderer.UmlLineEnd,
        options: LatexRenderOptions,
        out: StringBuilder,
    ) {
        if (role == null && multiplicity == null) return
        val dx = q.x - p.x
        val dy = q.y - p.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < MIN_SEGMENT) return
        val ux = dx / len
        val uy = dy / len
        val d = endLabelOffset(end = end, segmentLength = len)
        val ax = p.x + ux * d
        val ay = p.y + uy * d
        val roleAnchor: String
        val multAnchor: String
        if (abs(ux) >= abs(uy)) {
            val h = if (ux > 0f) "west" else "east"
            roleAnchor = "south $h"
            multAnchor = "north $h"
        } else {
            val v = if (uy > 0f) "north" else "south"
            roleAnchor = "$v west"
            multAnchor = "$v east"
        }
        val at = "at (${fmtCoord(ax)}pt, ${fmtCoord(-ay)}pt)"
        role?.let { out.appendLine("${options.indent}\\node[anchor=$roleAnchor, $END_STYLE_FONT] $at {${esc(it)}};") }
        multiplicity?.let { out.appendLine("${options.indent}\\node[anchor=$multAnchor, $END_STYLE_FONT] $at {${esc(it)}};") }
    }

    /**
     * The first (or last) non-degenerate segment of the route as (endpoint, next point inwards), or null when
     * the whole route has no length.
     */
    private fun endSegment(
        route: EdgeRoute,
        atSource: Boolean,
    ): Pair<Point, Point>? {
        val inner: List<Point> =
            when (route) {
                is EdgeRoute.Direct -> emptyList()
                is EdgeRoute.OrthogonalRounded -> route.waypoints
                is EdgeRoute.TreeRounded -> route.waypoints
                is EdgeRoute.Bezier -> route.controlPoints
            }
        val p = if (atSource) route.source else route.target
        val far = if (atSource) route.target else route.source
        val candidates = (if (atSource) inner else inner.asReversed()) + far
        val q = candidates.firstOrNull { distance(a = p, b = it) > MIN_SEGMENT } ?: return null
        return p to q
    }

    private fun distance(
        a: Point,
        b: Point,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Control characters become spaces, then exactly one LaTeX escape. */
    private fun esc(text: String): String = escapeLatex(stripControl(text))
}
