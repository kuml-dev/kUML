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
)

/**
 * Berechnete Position und optionale Port-Koordinaten eines einzelnen Knotens.
 *
 * [bounds] enthält die absolute Position und die tatsächliche Größe nach dem Layout.
 * [ports] enthält absolute Koordinaten der Ports, falls der Knoten Ports besitzt.
 */
@Serializable
public data class NodeLayout(
    val bounds: Rect,
    val ports: Map<PortId, Point> = emptyMap(),
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
