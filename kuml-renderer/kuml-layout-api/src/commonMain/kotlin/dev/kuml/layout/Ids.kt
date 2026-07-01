package dev.kuml.layout

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Stabile, typsichere ID eines Knoten im Layout-Graphen.
 *
 * Wird vom Diagramm-Builder vergeben und referenziert einen [LayoutNode]
 * eindeutig innerhalb eines [LayoutGraph].
 */
@Serializable
@JvmInline
public value class NodeId(
    public val value: String,
)

/**
 * Stabile, typsichere ID einer Kante im Layout-Graphen.
 *
 * Referenziert eine [LayoutEdge] eindeutig innerhalb eines [LayoutGraph].
 */
@Serializable
@JvmInline
public value class EdgeId(
    public val value: String,
)

/**
 * Stabile, typsichere ID einer Gruppe (Container, Paket, Subgraph) im Layout-Graphen.
 *
 * Referenziert eine [LayoutGroup] eindeutig innerhalb eines [LayoutGraph].
 */
@Serializable
@JvmInline
public value class GroupId(
    public val value: String,
)

/**
 * Stabile, typsichere ID eines Ports an einem Knoten.
 *
 * Ports sind optionale Anschlusspunkte für Kanten; fehlen sie, ist das
 * Routing knotengebunden.
 */
@Serializable
@JvmInline
public value class PortId(
    public val value: String,
)

/**
 * Stabile, maschinenlesbare ID einer Layout-Engine, z.B. `"elk.layered"` oder `"kuml.grid"`.
 *
 * Wird in [LayoutResult.engineId] und [KumlLayoutEngine.id] verwendet,
 * um Ergebnisse ihrer Herkunft zuzuordnen.
 */
@Serializable
@JvmInline
public value class LayoutEngineId(
    public val value: String,
)
