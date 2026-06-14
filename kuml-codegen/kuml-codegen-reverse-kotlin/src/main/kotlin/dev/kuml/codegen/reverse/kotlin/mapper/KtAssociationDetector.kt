package dev.kuml.codegen.reverse.kotlin.mapper

import dev.kuml.codegen.reverse.kotlin.support.KtFqnPool
import dev.kuml.uml.AggregationKind
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlInterface

/**
 * Post-mapper pass: detects associations between classifiers.
 *
 * For each attribute in [UmlClass]/[UmlInterface] whose type resolves to another
 * classifier in the model, an [UmlAssociation] is created.
 */
internal class KtAssociationDetector(
    private val pool: KtFqnPool,
) {
    fun detect(
        classes: List<UmlClass>,
        interfaces: List<UmlInterface>,
    ): List<UmlAssociation> {
        // Build a set of all known classifier IDs in the model
        val knownIds = (classes.map { it.id } + interfaces.map { it.id }).toSet()

        val associations = mutableListOf<UmlAssociation>()
        var assocIdx = 0

        // Check class attributes
        for (cls in classes) {
            for (attr in cls.attributes) {
                val targetId = attr.type.referencedId ?: continue
                if (targetId !in knownIds) continue
                if (targetId == cls.id) continue // skip self-references

                associations +=
                    UmlAssociation(
                        id = "kt:assoc.${assocIdx++}",
                        name = attr.name,
                        ends =
                            listOf(
                                UmlAssociationEnd(typeId = cls.id, role = null, multiplicity = Multiplicity(1, 1)),
                                UmlAssociationEnd(typeId = targetId, role = attr.name, multiplicity = attr.multiplicity),
                            ),
                        aggregation = AggregationKind.NONE,
                    )
            }
        }

        // Check interface attributes
        for (iface in interfaces) {
            for (attr in iface.attributes) {
                val targetId = attr.type.referencedId ?: continue
                if (targetId !in knownIds) continue
                if (targetId == iface.id) continue

                associations +=
                    UmlAssociation(
                        id = "kt:assoc.${assocIdx++}",
                        name = attr.name,
                        ends =
                            listOf(
                                UmlAssociationEnd(typeId = iface.id, role = null, multiplicity = Multiplicity(1, 1)),
                                UmlAssociationEnd(typeId = targetId, role = attr.name, multiplicity = attr.multiplicity),
                            ),
                        aggregation = AggregationKind.NONE,
                    )
            }
        }

        return associations
    }
}
