package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.PackageDiagramConfig
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlPackage
import dev.kuml.uml.UmlRelationship
import dev.kuml.uml.ids.UmlIds

/**
 * Builder for a UML 2.x package diagram (V1.1).
 *
 * Shows the package structure of a model — packages, their nested members
 * (classes, interfaces, enums and sub-packages), and the dependencies
 * between packages (`«import»`, `«merge»`, `«access»` stereotyped
 * dependencies).
 *
 * Available builders inside the lambda:
 *  - [packageOf] — top-level package
 *  - [packageImport] — `«import»` dependency between two packages
 *  - [packageMerge] — `«merge»` dependency
 *  - inside `packageOf { … }`: [packageOf] (nested), [classOf],
 *    [interfaceOf], [enumOf]
 *
 * Do not instantiate directly — use the [packageDiagram] entry-point function.
 */
@KumlDsl
public class PackageDiagramBuilder(
    private val name: String,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val appliedProfilesList = mutableListOf<KumlProfile>()
    private val elements = mutableListOf<UmlElement>()

    public var showStereotypes: Boolean = true
    public var showFolderTabs: Boolean = true

    override fun addNamedElement(element: UmlNamedElement) {
        requirePackageDiagramElement(element)
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        requirePackageDiagramRelationship(relationship)
        elements += relationship
        takenIds += relationship.id
    }

    /** Convenience: `«import»` dependency between two packages. */
    public fun packageImport(
        client: UmlPackage,
        supplier: UmlPackage,
    ): UmlDependency = stereotypedDependency(client = client, supplier = supplier, stereotype = "import")

    /** Convenience: `«merge»` dependency between two packages. */
    public fun packageMerge(
        client: UmlPackage,
        supplier: UmlPackage,
    ): UmlDependency = stereotypedDependency(client = client, supplier = supplier, stereotype = "merge")

    /** Convenience: `«access»` dependency between two packages. */
    public fun packageAccess(
        client: UmlPackage,
        supplier: UmlPackage,
    ): UmlDependency = stereotypedDependency(client = client, supplier = supplier, stereotype = "access")

    private fun stereotypedDependency(
        client: UmlPackage,
        supplier: UmlPackage,
        stereotype: String,
    ): UmlDependency {
        val id =
            UmlIds.disambiguate(
                candidate = "dep::${client.id}-->${supplier.id}[$stereotype]",
                taken = takenIds,
            )
        val dep =
            UmlDependency(
                id = id,
                clientId = client.id,
                supplierId = supplier.id,
                // UmlDependency has no stereotypes field — encode it in the
                // displayable name. The renderer reads this for the «label».
                name = "«$stereotype»",
            )
        addRelationship(dep)
        return dep
    }

    private fun requirePackageDiagramElement(element: UmlNamedElement) {
        val rejected =
            when (element) {
                is UmlPackage,
                is UmlClass,
                is UmlInterface,
                is UmlEnumeration,
                -> null // ✓
                is dev.kuml.uml.UmlActor -> "UmlActor (use useCaseDiagram { })"
                is dev.kuml.uml.UmlUseCase -> "UmlUseCase (use useCaseDiagram { })"
                is dev.kuml.uml.UmlComponent -> "UmlComponent (use componentDiagram { })"
                is dev.kuml.uml.UmlStateMachine -> "UmlStateMachine (use stateDiagram { })"
                is dev.kuml.uml.UmlInteraction -> "UmlInteraction (use sequenceDiagram { })"
                is dev.kuml.uml.UmlInstanceSpecification -> "UmlInstanceSpecification (use objectDiagram { })"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid element for a package diagram."
        }
    }

    private fun requirePackageDiagramRelationship(rel: UmlRelationship) {
        val rejected =
            when (rel) {
                is UmlDependency -> null // ✓
                is dev.kuml.uml.UmlLink -> "UmlLink (use objectDiagram { })"
                is dev.kuml.uml.UmlInclude -> "UmlInclude (use useCaseDiagram { })"
                is dev.kuml.uml.UmlExtend -> "UmlExtend (use useCaseDiagram { })"
                is dev.kuml.uml.UmlConnector -> "UmlConnector (use componentDiagram { })"
                else -> null
            }
        require(rejected == null) {
            "[$name] $rejected is not a valid relationship for a package diagram."
        }
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    public fun build(): KumlDiagram =
        KumlDiagram(
            name = name,
            type = DiagramType.PACKAGE,
            elements = elements.toList(),
            config =
                PackageDiagramConfig(
                    showStereotypes = showStereotypes,
                    showFolderTabs = showFolderTabs,
                ),
        )
}
