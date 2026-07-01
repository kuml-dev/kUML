package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.model.KumlModel
import dev.kuml.core.model.ModelLevel
import dev.kuml.core.model.ModelingLanguage
import dev.kuml.profile.KumlProfile
import dev.kuml.uml.UmlElement
import dev.kuml.uml.UmlNamedElement
import dev.kuml.uml.UmlRelationship

/**
 * Builder for a language-root [KumlModel] without a fixed diagram type.
 *
 * Use [umlModel] when creating a model file that captures classifiers and
 * their relationships independently of any particular diagram view.
 *
 * Do not instantiate directly — use the [umlModel] entry-point function.
 */
@KumlDsl
class UmlModelBuilder(
    private val name: String,
    private val level: ModelLevel = ModelLevel.PIM,
) : UmlModelScope {
    override val containerId: String? = null
    override val takenIds: MutableSet<String> = mutableSetOf()

    private val elements = mutableListOf<UmlElement>()
    private val appliedProfilesList = mutableListOf<KumlProfile>()

    override fun addNamedElement(element: UmlNamedElement) {
        elements += element
        takenIds += element.id
    }

    override fun addRelationship(relationship: UmlRelationship) {
        elements += relationship
        takenIds += relationship.id
    }

    override fun addAppliedProfile(profile: KumlProfile) {
        appliedProfilesList += profile
    }

    override fun appliedProfiles(): List<KumlProfile> = appliedProfilesList.toList()

    /** Builds the immutable [KumlModel]. */
    fun build(): KumlModel =
        KumlModel(
            root =
                KumlDiagram(
                    id = name,
                    name = name,
                    type = DiagramType.CLASS,
                    elements = elements.toList(),
                ),
            language = ModelingLanguage.UML,
            level = level,
            name = name,
        )
}

// ── Entry point ───────────────────────────────────────────────────────────────

/**
 * Creates a language-root UML model without a fixed diagram type.
 *
 * All UML element builders (`classOf`, `interfaceOf`, `enumOf`, `` `package` ``,
 * `association`, `generalization`, `realization`, `dependency`) are available
 * inside the [block] lambda.
 *
 * ```kotlin
 * // order-domain.kuml.kts
 * umlModel(name = "Order Domain") {
 *     val status = enumOf(name = "OrderStatus") {
 *         literal(name = "DRAFT"); literal(name = "CONFIRMED"); literal(name = "SHIPPED")
 *     }
 *     val order = classOf(name = "Order") {
 *         attribute(name = "id", type = "UUID")
 *         attribute(name = "status", type = status)
 *     }
 *     val item = classOf(name = "OrderItem") {
 *         attribute(name = "quantity", type = "Int")
 *     }
 *     association(source = order, target = item) {
 *         aggregation = AggregationKind.COMPOSITE
 *         target { multiplicity(spec = "1..*") }
 *     }
 * }
 * ```
 *
 * @param name Human-readable model name.
 * @param level MDA abstraction level (default: [ModelLevel.PIM]).
 * @return The built [KumlModel].
 */
fun umlModel(
    name: String,
    level: ModelLevel = ModelLevel.PIM,
    block: UmlModelBuilder.() -> Unit = {},
): KumlModel = UmlModelBuilder(name = name, level = level).apply(block).build()
