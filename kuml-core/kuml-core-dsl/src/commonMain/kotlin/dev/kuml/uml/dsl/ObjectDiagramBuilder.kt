package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.ObjectDiagramConfig
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlInstanceSpecification
import dev.kuml.uml.UmlInstanceValue
import dev.kuml.uml.UmlLink
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x object diagram (V1.1).
 *
 * An object diagram is a snapshot — it shows *instances* of classifiers and the
 * *links* between them rather than the type structure. Typical use cases:
 *
 *  - illustrating a specific configuration the class diagram is meant to permit,
 *  - capturing test fixtures pictorially,
 *  - documenting examples in API references.
 *
 * Available builders inside the lambda:
 *  - [instanceOf] (creates a [UmlInstanceSpecification]),
 *  - [UmlInstanceSpecificationBuilder.slot] (inside `instanceOf { … }`),
 *  - [link] (creates a binary [UmlLink] between two instances).
 *
 * Example:
 *
 * ```kotlin
 * val customer = classOf(name = "Customer") {
 *     attribute(name = "id",   type = "UUID")
 *     attribute(name = "name", type = "String")
 * }
 *
 * objectDiagram(name = "Order #42 snapshot") {
 *     val alice = instanceOf(classifier = customer, name = "alice") {
 *         slot(feature = "id",   value = literal("c0ffee42"))
 *         slot(feature = "name", value = literal("Alice"))
 *     }
 *     val order = instanceOf(classifier = order, name = "order42") {
 *         slot(feature = "id",     value = literal("ord-42"))
 *         slot(feature = "amount", value = literal("19.95"))
 *     }
 *     link(from = alice, to = order, association = customerOrder)
 * }
 * ```
 *
 * Do not instantiate directly — use the [objectDiagram] entry-point function.
 */
@KumlDsl
public class ObjectDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    // ── Display options ───────────────────────────────────────────────────────

    public var showClassifierType: Boolean = true
    public var showSlotCompartment: Boolean = true
    public var showNullSlots: Boolean = true

    // ── UmlModelScope ─────────────────────────────────────────────────────────

    override fun addNamedElement(element: UmlNamedElement) {
        requireObjectDiagramElement(element)
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        requireObjectDiagramRelationship(relationship)
        elements += relationship
        takenIds += relationship.id
    }

    /**
     * Adds a [UmlComment] (UML note) to this diagram.
     *
     * Comment/Note support (V0.23.1+) is available for all UML diagram types
     * — see `UmlModelScope.addComment` KDoc.
     */
    override fun addComment(comment: UmlComment) {
        elements += comment
        takenIds += comment.id
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    /**
     * Creates an instance of [classifier] and adds it to this diagram.
     *
     * @param classifier The classifier the instance realises (typically a [dev.kuml.uml.UmlClass]).
     * @param name Instance name. Empty for anonymous instances (`: User`).
     * @param id Optional explicit ID. Default: derived from name + classifier.
     * @param block Configuration block; see [UmlInstanceSpecificationBuilder.slot].
     * @return The built [UmlInstanceSpecification] — capture it in a `val` to
     *   pass to [link].
     */
    public fun instanceOf(
        classifier: UmlClassifier,
        name: String = "",
        id: String? = null,
        block: UmlInstanceSpecificationBuilder.() -> Unit = {},
    ): UmlInstanceSpecification {
        val instanceId =
            id ?: UmlIds.disambiguate(
                candidate = UmlIds.child(containerId, "${name.ifEmpty { "anon" }}@${classifier.name}"),
                taken = takenIds,
            )
        val builder = UmlInstanceSpecificationBuilder(classifier = classifier)
        builder.apply(block)
        val instance =
            UmlInstanceSpecification(
                id = instanceId,
                name = name,
                classifierId = classifier.id,
                classifierName = classifier.name,
                slots = builder.buildSlots(),
            )
        addNamedElement(instance)
        return instance
    }

    /**
     * Creates a binary [UmlLink] between two instances.
     *
     * @param from Source instance.
     * @param to Target instance.
     * @param association Optional typing [UmlAssociation]. Pass `null` for a
     *   bare link not derived from a named association.
     * @param sourceRole Optional role label rendered near the source end.
     * @param targetRole Optional role label rendered near the target end.
     */
    public fun link(
        from: UmlInstanceSpecification,
        to: UmlInstanceSpecification,
        association: UmlAssociation? = null,
        sourceRole: String? = null,
        targetRole: String? = null,
    ): UmlLink {
        val linkId =
            UmlIds.disambiguate(
                candidate = "link::${from.id}-->${to.id}",
                taken = takenIds,
            )
        val link =
            UmlLink(
                id = linkId,
                associationId = association?.id ?: "",
                sourceInstanceId = from.id,
                targetInstanceId = to.id,
                sourceRoleName = sourceRole,
                targetRoleName = targetRole,
            )
        addRelationship(link)
        return link
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private fun requireObjectDiagramElement(element: UmlNamedElement) {
        val rejected =
            when (element) {
                is UmlInstanceSpecification -> null // ✓
                is dev.kuml.uml.UmlClass -> "UmlClass (object diagrams contain instances, not classifiers)"
                is dev.kuml.uml.UmlInterface -> "UmlInterface (use classDiagram { })"
                is dev.kuml.uml.UmlEnumeration -> "UmlEnumeration (use classDiagram { })"
                is dev.kuml.uml.UmlStateMachine -> "UmlStateMachine (use stateDiagram { })"
                is dev.kuml.uml.UmlInteraction -> "UmlInteraction (use sequenceDiagram { })"
                is dev.kuml.uml.UmlActor -> "UmlActor (use useCaseDiagram { })"
                is dev.kuml.uml.UmlUseCase -> "UmlUseCase (use useCaseDiagram { })"
                is dev.kuml.uml.UmlComponent -> "UmlComponent (use componentDiagram { })"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid element for an object diagram."
        }
    }

    private fun requireObjectDiagramRelationship(rel: UmlRelationship) {
        val rejected =
            when (rel) {
                is UmlLink -> null // ✓
                is UmlCommentLink -> null // ✓
                is dev.kuml.uml.UmlInclude -> "UmlInclude (use useCaseDiagram { })"
                is dev.kuml.uml.UmlExtend -> "UmlExtend (use useCaseDiagram { })"
                is dev.kuml.uml.UmlConnector -> "UmlConnector (use componentDiagram { })"
                is dev.kuml.uml.UmlAssociation,
                is dev.kuml.uml.UmlGeneralization,
                is dev.kuml.uml.UmlInterfaceRealization,
                is dev.kuml.uml.UmlDependency,
                -> "${rel::class.simpleName} (object diagrams hold UmlLink, not type-level relationships)"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid relationship for an object diagram."
        }
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /** Builds the immutable [KumlDiagram] with [ObjectDiagramConfig] attached. */
    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.OBJECT,
            elements = elements.toList(),
            config =
                ObjectDiagramConfig(
                    showClassifierType = showClassifierType,
                    showSlotCompartment = showSlotCompartment,
                    showNullSlots = showNullSlots,
                ),
        )
}

/**
 * Scope inside an `instanceOf(…) { }` block — only [slot] declarations are
 * permitted.
 */
@KumlDsl
public class UmlInstanceSpecificationBuilder internal constructor(
    private val classifier: UmlClassifier,
) {
    private val slots = mutableListOf<dev.kuml.uml.UmlSlot>()

    /**
     * Adds a slot for a feature by *name*. The feature is looked up among the
     * classifier's attributes — if no match is found, the slot is still
     * recorded with an empty `definingFeatureId` (the renderer falls back to
     * the supplied [feature] string for the label).
     */
    public fun slot(
        feature: String,
        value: UmlInstanceValue,
    ) {
        val definingFeature: UmlProperty? = lookupFeature(feature)
        slots +=
            dev.kuml.uml.UmlSlot(
                definingFeatureId = definingFeature?.id ?: "",
                featureName = feature,
                value = value,
            )
    }

    /** Adds a slot for a feature given as a typed [UmlProperty]. */
    public fun slot(
        feature: UmlProperty,
        value: UmlInstanceValue,
    ) {
        slots +=
            dev.kuml.uml.UmlSlot(
                definingFeatureId = feature.id,
                featureName = feature.name,
                value = value,
            )
    }

    private fun lookupFeature(name: String): UmlProperty? =
        when (val c = classifier) {
            is dev.kuml.uml.UmlClass -> c.attributes.firstOrNull { it.name == name }
            is dev.kuml.uml.UmlInterface -> c.attributes.firstOrNull { it.name == name }
            else -> null
        }

    internal fun buildSlots(): List<dev.kuml.uml.UmlSlot> = slots.toList()
}

// ── Value helpers ────────────────────────────────────────────────────────────

/** Convenience: literal slot value from a raw string. */
public fun literal(text: String): UmlInstanceValue = UmlInstanceValue.Literal(text)

/** Convenience: literal slot value from any toString()-friendly value. */
public fun literal(value: Number): UmlInstanceValue = UmlInstanceValue.Literal(value.toString())

/** Convenience: literal slot value from a boolean. */
public fun literal(value: Boolean): UmlInstanceValue = UmlInstanceValue.Literal(value.toString())

/** Convenience: instance-reference slot value pointing at another instance. */
public fun ref(instance: UmlInstanceSpecification): UmlInstanceValue = UmlInstanceValue.InstanceRef(instance.id)

/** Convenience: explicit null slot value. */
public val nullValue: UmlInstanceValue = UmlInstanceValue.Null
