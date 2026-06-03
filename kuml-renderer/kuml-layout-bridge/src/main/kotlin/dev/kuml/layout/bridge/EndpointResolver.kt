package dev.kuml.layout.bridge

import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlConnector
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlRelationship

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
        }
}
