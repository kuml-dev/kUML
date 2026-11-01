package dev.kuml.layout

import kotlinx.serialization.Serializable

/**
 * Vollständiges Ergebnis eines Layout-Laufs, bereit zur Übergabe an den Renderer.
 *
 * Ist vollständig serialisierbar ([kotlinx.serialization]) — Ergebnisse können gecacht,
 * gedifft und als Golden Files getestet werden.
 *
 * @property engineId Die Engine, die dieses Ergebnis produziert hat.
 * @property seed Der verwendete Seed für deterministische Engines; null wenn nicht-deterministisch.
 * @property canvas Gesamtgröße des berechneten Canvas.
 * @property nodes Absolute Bounds und Port-Positionen pro Knoten.
 * @property edges Routing-Pfade pro Kante.
 * @property groups Bounds der Gruppen.
 * @property warnings Nicht-fatale Hinweise der Engine (z.B. ignorierte Hints, Budget-Überschreitungen).
 */
@Serializable
public data class LayoutResult(
    val engineId: LayoutEngineId,
    val seed: Long?,
    val canvas: Size,
    val nodes: Map<NodeId, NodeLayout>,
    val edges: Map<EdgeId, EdgeRoute>,
    val groups: Map<GroupId, GroupLayout>,
    val warnings: List<LayoutWarning> = emptyList(),
    /** SysML-2-IBD only: the nested part frames inside the visible part boxes (PAL wave 2C-b2b); empty for every other diagram. */
    val ibdNestedFrames: List<IbdNestedFrame> = emptyList(),
)

/**
 * One nested part frame of an IBD (box in box, PAL wave 2C-b2b). The visible part box itself is level 1 and is a
 * [NodeLayout]; the parts nested inside it are frames of level 2, 3, ...
 *
 * [bounds] is **local to the visible part box** [nodeId] (origin at the box's top-left corner), like the port positions
 * of an IBD node, so a translation of the node moves its frames with it. [headerHeight] is the single-line header
 * (`name : Type`, 22 px); below it the [attributeLines] compartment follows when it is not empty.
 */
@Serializable
public data class IbdNestedFrame(
    val path: String,
    val level: Int,
    val bounds: Rect,
    val headerHeight: Float,
    val parentPath: String?,
    val nodeId: String = "",
    val title: String = "",
    val attributeLines: List<String> = emptyList(),
)

/**
 * Berechnete Position und optionale Port-Koordinaten eines einzelnen Knotens.
 *
 * [bounds] enthält die absolute Position und die tatsächliche Größe nach dem Layout.
 * [ports] enthält absolute Port-Mittelpunkte, falls der Knoten Ports besitzt.
 */
@Serializable
public data class NodeLayout(
    val bounds: Rect,
    val ports: Map<PortId, Point> = emptyMap(),
    /**
     * Von der Engine platzierte Labels. Schlüssel `"node"` (externes Knotenlabel) bzw.
     * `"port:<portId>"`. Leer, wenn der Knoten keine Label-Reservierung angefordert hat.
     */
    val labels: Map<String, Rect> = emptyMap(),
)

/**
 * Berechnete absolute Bounds einer Gruppe nach dem Layout.
 *
 * Die Engine garantiert, dass alle Kindknoten innerhalb dieser Bounds liegen.
 */
@Serializable
public data class GroupLayout(
    val bounds: Rect,
)
