package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.ProfileDiagramConfig
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlComment
import dev.kuml.uml.UmlCommentLink
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.UmlStereotype
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x profile diagram (V1.1).
 *
 * Available builders:
 *  - [stereotype] — declare a stereotype with its extended metaclasses and
 *    tagged-value definitions
 *  - [extension] — `«extension»` dependency from a stereotype to a metaclass
 *    (represented as a [UmlClass] with the `«metaclass»` stereotype)
 *  - [packageOf] — group stereotypes into a profile package
 */
@KumlDsl
public class ProfileDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showMetaclassStereotype: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        when (element) {
            is UmlStereotype, is UmlClass, is UmlPackage -> {}
            else ->
                require(false) {
                    "[$name] ${element::class.simpleName} is not a valid element for a profile diagram."
                }
        }
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        when (relationship) {
            is UmlDependency, is UmlCommentLink -> {}
            else ->
                require(false) {
                    "[$name] ${relationship::class.simpleName} is not a valid relationship for a profile diagram."
                }
        }
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

    public fun stereotype(
        name: String,
        metaclasses: List<String> = emptyList(),
        block: StereotypeScope.() -> Unit = {},
    ): UmlStereotype {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(containerId, name), taken = takenIds)
        val scope = StereotypeScope(parentId = id, takenIds = takenIds)
        scope.apply(block)
        val s =
            UmlStereotype(
                id = id,
                name = name,
                metaclasses = metaclasses,
                tagDefinitions = scope.tags.toList(),
            )
        addNamedElement(s)
        return s
    }

    /** Declares a metaclass placeholder (a [UmlClass] with `«metaclass»` stereotype). */
    public fun metaclass(name: String): UmlClass {
        val id = UmlIds.disambiguate(candidate = "metaclass::$name", taken = takenIds)
        val mc = UmlClass(id = id, name = name, stereotypes = listOf("metaclass"))
        addNamedElement(mc)
        return mc
    }

    public fun extension(
        stereotype: UmlStereotype,
        metaclass: UmlClass,
    ): UmlDependency {
        val id = UmlIds.disambiguate(candidate = "ext::${stereotype.id}-->${metaclass.id}", taken = takenIds)
        val d =
            UmlDependency(
                id = id,
                clientId = stereotype.id,
                supplierId = metaclass.id,
                name = "«extension»",
            )
        addRelationship(d)
        return d
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.PROFILE,
            elements = elements.toList(),
            config = ProfileDiagramConfig(showMetaclassStereotype = showMetaclassStereotype),
        )
}

@KumlDsl
public class StereotypeScope internal constructor(
    private val parentId: String,
    internal val takenIds: MutableSet<String>,
) {
    internal val tags: MutableList<UmlProperty> = mutableListOf()

    public fun tag(
        name: String,
        type: String,
    ): UmlProperty {
        val id = UmlIds.disambiguate(candidate = UmlIds.child(parentId, name), taken = takenIds)
        val p = UmlProperty(id = id, name = name, type = UmlTypeRef(type))
        tags += p
        takenIds += id
        return p
    }
}
