package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.CommunicationDiagramConfig
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInstanceValue
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlSlot
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x communication diagram (V1.1).
 *
 * Renders the same actors / objects as a sequence diagram but spatially —
 * lifelines become roles arranged around a graph of links labelled with
 * numbered messages.
 *
 * V1.1 simplification: reuses [UmlInstanceSpecification] for roles and
 * [UmlLink] for connections. Numbered messages are stored as slot-style
 * labels on the link's source role; the [showSequenceNumbers] config
 * controls whether numbers are prefixed.
 */
@KumlDsl
public class CommunicationDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()
    private val messages = mutableListOf<CommunicationMessage>()

    public var showSequenceNumbers: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        require(element is UmlInstanceSpecification) {
            "[$name] ${element::class.simpleName} is not a valid element for a communication diagram."
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        require(relationship is UmlLink) {
            "[$name] ${relationship::class.simpleName} is not a valid relationship for a communication diagram."
        }
        elements += relationship
        takenIds += relationship.id
    }

    /**
     * Declares a participant role. The role is rendered as a small object
     * box `roleName : ClassifierName`.
     */
    public fun role(
        classifierName: String,
        roleName: String = "",
    ): UmlInstanceSpecification {
        val id =
            UmlIds.disambiguate(
                candidate = UmlIds.child(containerId, "${roleName.ifEmpty { "anon" }}@$classifierName"),
                taken = takenIds,
            )
        val r =
            UmlInstanceSpecification(
                id = id,
                name = roleName,
                classifierId = classifierName,
                classifierName = classifierName,
            )
        addNamedElement(r)
        return r
    }

    /**
     * Declares an unlabelled connection between two roles. Subsequent
     * [message] calls reference these roles to attach numbered labels.
     */
    public fun connection(
        from: UmlInstanceSpecification,
        to: UmlInstanceSpecification,
    ): UmlLink {
        val id = UmlIds.disambiguate(candidate = "conn::${from.id}--${to.id}", taken = takenIds)
        val link =
            UmlLink(
                id = id,
                associationId = "",
                sourceInstanceId = from.id,
                targetInstanceId = to.id,
            )
        addRelationship(link)
        return link
    }

    /**
     * Adds a numbered message from one role to another. The sequence number
     * is auto-assigned (1-based). If no connection between the roles exists
     * yet, one is created.
     *
     * @return the [UmlLink] carrying the message label.
     */
    public fun message(
        from: UmlInstanceSpecification,
        to: UmlInstanceSpecification,
        label: String,
    ): UmlLink {
        val seq = messages.size + 1
        messages += CommunicationMessage(seq = seq, sourceId = from.id, targetId = to.id, label = label)
        val existing =
            elements.filterIsInstance<UmlLink>().firstOrNull {
                (it.sourceInstanceId == from.id && it.targetInstanceId == to.id) ||
                    (it.sourceInstanceId == to.id && it.targetInstanceId == from.id)
            }
        if (existing != null) {
            // Append to existing link by replacing its targetRoleName with the
            // running label. We keep it simple: store as `"seq: label\nseq': …"`.
            val combined =
                (existing.targetRoleName?.let { "$it\n" } ?: "") +
                    (if (showSequenceNumbers) "$seq: " else "") + label
            val idx = elements.indexOf(existing)
            elements[idx] = existing.copy(targetRoleName = combined)
            return elements[idx] as UmlLink
        }
        return connectionWithLabel(from = from, to = to, label = if (showSequenceNumbers) "$seq: $label" else label)
    }

    private fun connectionWithLabel(
        from: UmlInstanceSpecification,
        to: UmlInstanceSpecification,
        label: String,
    ): UmlLink {
        val id = UmlIds.disambiguate(candidate = "conn::${from.id}--${to.id}", taken = takenIds)
        val link =
            UmlLink(
                id = id,
                associationId = "",
                sourceInstanceId = from.id,
                targetInstanceId = to.id,
                targetRoleName = label,
            )
        addRelationship(link)
        return link
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.COMMUNICATION,
            elements = elements.toList(),
            config = CommunicationDiagramConfig(showSequenceNumbers = showSequenceNumbers),
        )

    @Suppress("unused") // kept for serialization / future renderer use
    private data class CommunicationMessage(
        val seq: Int,
        val sourceId: String,
        val targetId: String,
        val label: String,
    )

    // Silence unused-import warnings for V1.1 surface that future work will need.
    @Suppress("unused")
    private fun touch(): Pair<UmlSlot?, UmlInstanceValue?> = null to null
}
