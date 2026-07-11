package dev.kuml.io.svg

import dev.kuml.c4.model.C4Relationship
import dev.kuml.core.model.KumlElement
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink

/**
 * Erkennt Self-Loops (Kanten, deren Source und Target derselbe Knoten sind)
 * und ersetzt das vom Layout-Engine gelieferte — typischerweise stark
 * verkrüppelte — Mini-U-Routing durch einen großen, gut lesbaren C-Loop
 * an der rechten Seite des Knotens.
 *
 * ELK liefert für Self-Loops in Praxis Pfade wie
 * `M 625.93 204.67 L 615.93 204.67 L 615.93 237.33 L 625.93 237.33` — eine
 * 10 px breite, 32 px hohe U-Form, die kaum als Loop erkennbar ist und an
 * der die Edge-Label-Logik zwangsläufig in Multiplicity-Labels hineinläuft.
 *
 * Lösung: Wenn das Modell-Element [isSelfLoop] meldet und der Self-Loop-Knoten
 * im Layout gefunden wird, ersetzt [adjust] die Route durch einen
 * [EdgeRoute.OrthogonalRounded] mit drei Wegpunkten, der **rechts** außerhalb
 * der Knotenbox eine deutliche C-Schleife zeichnet:
 *
 * ```
 *   Source: (right,  y + h * 0.30)
 *   WP1:    (right + LOOP_EXTENT_PX, y + h * 0.30)
 *   WP2:    (right + LOOP_EXTENT_PX, y + h * 0.70)
 *   Target: (right,  y + h * 0.70)
 * ```
 *
 * Damit landet der Pfeilkopf am unteren Anker, die Label-Mitte sitzt rechts
 * außerhalb des Knotens und kollidiert nicht mehr mit Multiplicity-Labels.
 */
internal object SelfLoopRouter {
    /** Wie weit der C-Loop seitlich neben den Knoten hinausragt (px). */
    private const val LOOP_EXTENT_PX = 32f

    /** Anteil der Knotenhöhe, an dem oberer/unterer Anker sitzen. */
    private const val UPPER_ANCHOR_FRACTION = 0.30f
    private const val LOWER_ANCHOR_FRACTION = 0.70f

    /** Eck-Radius des Self-Loop-C in px. */
    private const val LOOP_CORNER_RADIUS_PX = 6f

    /**
     * Liefert das gemeinsame Endknoten-ID-Paar zurück, wenn das [element] ein
     * Self-Loop ist (also `sourceNodeId == targetNodeId`), sonst `null`.
     */
    fun selfLoopNodeId(element: KumlElement): String? =
        when (element) {
            is UmlAssociation ->
                element.ends
                    .takeIf { it.size >= 2 }
                    ?.let { if (it[0].typeId == it[1].typeId) it[0].typeId else null }
            is UmlGeneralization ->
                if (element.specificId == element.generalId) element.specificId else null
            is UmlInterfaceRealization ->
                if (element.implementingId == element.interfaceId) element.implementingId else null
            is UmlDependency ->
                if (element.clientId == element.supplierId) element.clientId else null
            is UmlConnector ->
                if (element.end1Id == element.end2Id) element.end1Id else null
            is UmlInclude ->
                if (element.baseId == element.additionId) element.baseId else null
            is UmlExtend ->
                if (element.baseId == element.extensionId) element.baseId else null
            is UmlLink ->
                if (element.sourceInstanceId == element.targetInstanceId) element.sourceInstanceId else null
            is UmlActivityEdge ->
                if (element.sourceId == element.targetId) element.sourceId else null
            is C4Relationship ->
                if (element.source == element.target) element.source else null
            // V3.4.x — ERM self-references (e.g. `Category.parent_id` "subcategory
            // of" self) previously bypassed this router entirely: `renderErm` /
            // `renderErmIdef1x` shift ELK's raw route straight to the SVG without
            // ever calling `adjust`. ELK's cramped ~10px U-shape then collided with
            // the `ERM_ROLE_LABEL_*` offsets in `ErmEdgeLabels`, which were tuned
            // assuming the wide, purely-horizontal C-loop tangent this router
            // produces for every other diagram type. Recognizing the relationship
            // here is the first half of the fix — callers still need to invoke
            // [adjust].
            is ErmRelationship ->
                if (element.sourceEntityId == element.targetEntityId) element.sourceEntityId else null
            else -> null
        }

    /** `true` wenn das Modell-Element einen Self-Loop beschreibt. */
    fun isSelfLoop(element: KumlElement): Boolean = selfLoopNodeId(element) != null

    /**
     * Erzeugt eine ausgeprägte C-förmige Self-Loop-Route auf der rechten Seite
     * der [nodeLayout]-Box. Koordinaten sind im rohen Layout-Raum (vor dem
     * Padding-Shift in [KumlSvgRenderer]).
     */
    fun selfLoopRoute(nodeLayout: NodeLayout): EdgeRoute.OrthogonalRounded {
        val bounds = nodeLayout.bounds
        val right = bounds.origin.x + bounds.size.width
        val top = bounds.origin.y
        val h = bounds.size.height
        val upperY = top + h * UPPER_ANCHOR_FRACTION
        val lowerY = top + h * LOWER_ANCHOR_FRACTION
        val outerX = right + LOOP_EXTENT_PX
        return EdgeRoute.OrthogonalRounded(
            source = Point(right, upperY),
            target = Point(right, lowerY),
            waypoints =
                listOf(
                    Point(outerX, upperY),
                    Point(outerX, lowerY),
                ),
            cornerRadiusPx = LOOP_CORNER_RADIUS_PX,
        )
    }

    /**
     * Wenn [element] ein Self-Loop ist und der Knoten in [nodeLookup] existiert,
     * gibt es eine vergrößerte C-Loop-Route zurück; sonst die unveränderte
     * [originalRoute].
     *
     * Aufrufer liefern eine Lookup-Funktion statt der Layout-Map, damit die
     * NodeId-Konvertierung lokal beim Aufrufer bleibt.
     */
    fun adjust(
        element: KumlElement,
        originalRoute: EdgeRoute,
        nodeLookup: (String) -> NodeLayout?,
    ): EdgeRoute {
        val nodeId = selfLoopNodeId(element) ?: return originalRoute
        val nodeLayout = nodeLookup(nodeId) ?: return originalRoute
        return selfLoopRoute(nodeLayout)
    }
}
