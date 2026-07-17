package dev.kuml.layout.bridge

import dev.kuml.uml.UmlActivityEdge
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Aufgelöste Endpunkte einer [UmlRelationship] für den Layout-Graphen.
 *
 * Für [UmlConnector]s zwischen Component-Ports werden die qualifizierten
 * Endpunkt-IDs (`"<componentId>::<portName>"`) in Node-ID und Port-ID
 * aufgesplittet — die Port-Namen werden dabei gegen die Port-Liste der
 * jeweiligen Komponente validiert.
 *
 * @property sourceNodeId Node-ID des Quell-Endpunkts.
 * @property sourcePortId Optionale Port-ID des Quell-Endpunkts (für Connector-Endpoints).
 * @property targetNodeId Node-ID des Ziel-Endpunkts.
 * @property targetPortId Optionale Port-ID des Ziel-Endpunkts (für Connector-Endpoints).
 */
internal data class ResolvedEndpoints(
    val sourceNodeId: String,
    val sourcePortId: String? = null,
    val targetNodeId: String,
    val targetPortId: String? = null,
)

/**
 * Reduziert eine [UmlRelationship] auf ein `(sourceId, targetId)`-Paar.
 *
 * Gibt `null` zurück wenn die Beziehung nicht aufgelöst werden kann
 * (z.B. [UmlAssociation] mit ≠ 2 Ends).
 */
internal object EndpointResolver {
    /**
     * Löst eine [UmlRelationship] zu einem (sourceId, targetId)-Paar auf.
     *
     * @return Pair(sourceId, targetId) oder null, wenn die Beziehung übersprungen werden soll.
     */
    fun resolve(relationship: UmlRelationship): Pair<String, String>? =
        when (relationship) {
            is UmlAssociation -> {
                if (relationship.ends.size == 2) {
                    relationship.ends[0].typeId to relationship.ends[1].typeId
                } else {
                    // UmlAssociation with != 2 ends → skip (spec requirement)
                    null
                }
            }
            is UmlGeneralization -> relationship.specificId to relationship.generalId
            is UmlInterfaceRealization -> relationship.implementingId to relationship.interfaceId
            is UmlDependency -> relationship.clientId to relationship.supplierId
            is UmlConnector -> relationship.end1Id to relationship.end2Id
            is UmlInclude -> relationship.baseId to relationship.additionId
            is UmlExtend -> relationship.baseId to relationship.extensionId
            is UmlLink -> relationship.sourceInstanceId to relationship.targetInstanceId
            is UmlActivityEdge -> relationship.sourceId to relationship.targetId
            is UmlCommentLink -> relationship.commentId to relationship.annotatedElementId
        }

    /**
     * Löst eine [UmlRelationship] zu [ResolvedEndpoints] auf und teilt für
     * [UmlConnector] die qualifizierten Port-IDs (`"<componentId>::<portName>"`) in
     * Node + Port auf — sofern [componentPorts] die jeweilige Kombination
     * tatsächlich kennt.
     *
     * Für alle anderen Relationships ist das Resultat semantisch identisch zu
     * [resolve], nur in [ResolvedEndpoints] verpackt (ohne Port-IDs).
     *
     * @param componentPorts Map componentId → Set der dort definierten Port-Namen.
     */
    fun resolveWithPorts(
        relationship: UmlRelationship,
        componentPorts: Map<String, Set<String>>,
    ): ResolvedEndpoints? {
        val raw = resolve(relationship) ?: return null
        if (relationship !is UmlConnector) {
            return ResolvedEndpoints(sourceNodeId = raw.first, targetNodeId = raw.second)
        }
        val (sNode, sPort) = splitPortId(raw.first, componentPorts)
        val (tNode, tPort) = splitPortId(raw.second, componentPorts)
        return ResolvedEndpoints(
            sourceNodeId = sNode,
            sourcePortId = sPort,
            targetNodeId = tNode,
            targetPortId = tPort,
        )
    }

    /**
     * Wenn [endId] die Form `"<componentId>::<portName>"` hat UND
     * [componentPorts] die Kombination kennt, wird der Endpunkt aufgesplittet.
     * Andernfalls bleibt es eine reine Node-Referenz (Port = null).
     */
    private fun splitPortId(
        endId: String,
        componentPorts: Map<String, Set<String>>,
    ): Pair<String, String?> {
        val sepIdx = endId.lastIndexOf(UmlIds.SEP)
        if (sepIdx <= 0) return endId to null
        val candidateNode = endId.substring(0, sepIdx)
        val candidatePort = endId.substring(sepIdx + UmlIds.SEP.length)
        if (candidatePort.isEmpty()) return endId to null
        val ports = componentPorts[candidateNode] ?: return endId to null
        if (candidatePort !in ports) return endId to null
        return candidateNode to candidatePort
    }
}
