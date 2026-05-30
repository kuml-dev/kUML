package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.uml.UmlActor
import dev.kuml.uml.UmlExtend
import dev.kuml.uml.UmlInclude
import dev.kuml.uml.UmlUseCase
import dev.kuml.uml.UmlUseCaseSubject
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

// ── Actor ─────────────────────────────────────────────────────────────────────

/**
 * Adds a [UmlActor] to the enclosing use-case diagram or model.
 *
 * ```kotlin
 * useCaseDiagram("Checkout") {
 *     val customer = actor("Customer")
 *     val payment  = actor("Payment Service") { stereotypes += "external" }
 * }
 * ```
 *
 * @param name Actor name (typically a noun referring to an external role).
 * @param id Optional explicit ID override.
 */
fun UmlContainerScope.actor(
    name: String,
    id: String? = null,
    block: ActorBuilder.() -> Unit = {},
): UmlActor {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(containerId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val builder = ActorBuilder().apply(block)
    val actor =
        UmlActor(
            id = resolvedId,
            name = name,
            visibility = builder.visibility,
            stereotypes = builder.stereotypes.toList(),
        )
    addNamedElement(actor)
    return actor
}

// ── Use Case ──────────────────────────────────────────────────────────────────

/**
 * Adds a [UmlUseCase] to the enclosing use-case diagram or model.
 *
 * ```kotlin
 * useCaseDiagram("Checkout") {
 *     val placeOrder    = useCase("Place Order")
 *     val applyDiscount = useCase("Apply Discount")
 * }
 * ```
 *
 * @param name Use-case name.
 * @param id Optional explicit ID override.
 */
fun UmlContainerScope.useCase(
    name: String,
    id: String? = null,
    block: UseCaseBuilder.() -> Unit = {},
): UmlUseCase {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(containerId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val builder = UseCaseBuilder().apply(block)
    val useCase =
        UmlUseCase(
            id = resolvedId,
            name = name,
            visibility = builder.visibility,
            stereotypes = builder.stereotypes.toList(),
        )
    addNamedElement(useCase)
    return useCase
}

// ── Subject (System Boundary) ─────────────────────────────────────────────────

/**
 * Declares the subject (system boundary) of a use-case diagram.
 *
 * All use cases passed in [containedUseCases] are visually enclosed by
 * the subject boundary at render time.
 *
 * ```kotlin
 * useCaseDiagram("Checkout") {
 *     val placeOrder = useCase("Place Order")
 *     val validate   = useCase("Validate Cart")
 *     subject("Online Shop", placeOrder, validate)
 * }
 * ```
 *
 * @param name Subject name (the system or subsystem label).
 * @param containedUseCases Use cases enclosed within this subject boundary.
 * @param id Optional explicit ID override.
 */
fun UmlContainerScope.subject(
    name: String,
    vararg containedUseCases: UmlUseCase,
    id: String? = null,
): UmlUseCaseSubject {
    val resolvedId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.child(containerId, name),
            taken = takenIds,
        )
    takenIds += resolvedId
    val subject =
        UmlUseCaseSubject(
            id = resolvedId,
            name = name,
            useCaseIds = containedUseCases.map { it.id },
        )
    addNamedElement(subject)
    return subject
}

// ── Include ───────────────────────────────────────────────────────────────────

/**
 * Creates a `<<include>>` relationship between two use cases.
 *
 * The [base] use case unconditionally includes the behaviour of [addition].
 *
 * ```kotlin
 * useCaseDiagram("Checkout") {
 *     val placeOrder = useCase("Place Order")
 *     val validate   = useCase("Validate Cart")
 *     include(base = placeOrder, addition = validate)
 * }
 * ```
 *
 * @param base The including (base) use case.
 * @param addition The included use case.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.include(
    base: UmlUseCase,
    addition: UmlUseCase,
    id: String? = null,
): UmlInclude = includeById(base.id, addition.id, id)

/**
 * Creates a `<<include>>` relationship between two use cases identified by ID.
 *
 * @param baseId ID of the including (base) use case.
 * @param additionId ID of the included use case.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.includeById(
    baseId: String,
    additionId: String,
    id: String? = null,
): UmlInclude {
    val relId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.include(baseId, additionId),
            taken = takenIds,
        )
    takenIds += relId
    val rel = UmlInclude(id = relId, baseId = baseId, additionId = additionId)
    addRelationship(rel)
    return rel
}

// ── Extend ────────────────────────────────────────────────────────────────────

/**
 * Creates an `<<extend>>` relationship between two use cases.
 *
 * The [extension] use case conditionally extends [base] at [at].
 *
 * ```kotlin
 * extend(base = placeOrder, extension = applyDiscount, at = "PaymentChosen")
 * ```
 *
 * @param base The extended (base) use case.
 * @param extension The extending use case.
 * @param at Name of the extension point in the base use case, if specified.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.extend(
    base: UmlUseCase,
    extension: UmlUseCase,
    at: String? = null,
    id: String? = null,
): UmlExtend = extendById(base.id, extension.id, at, id)

/**
 * Creates an `<<extend>>` relationship between two use cases identified by ID.
 *
 * @param baseId ID of the extended (base) use case.
 * @param extensionId ID of the extending use case.
 * @param extensionPoint Name of the extension point in the base use case, if specified.
 * @param id Optional explicit ID override.
 */
fun UmlModelScope.extendById(
    baseId: String,
    extensionId: String,
    extensionPoint: String? = null,
    id: String? = null,
): UmlExtend {
    val relId =
        id ?: UmlIds.disambiguate(
            candidate = UmlIds.extend(baseId, extensionId),
            taken = takenIds,
        )
    takenIds += relId
    val rel = UmlExtend(id = relId, baseId = baseId, extensionId = extensionId, extensionPoint = extensionPoint)
    addRelationship(rel)
    return rel
}

// ── Builders ──────────────────────────────────────────────────────────────────

/**
 * Builder for inline display options of an [UmlActor].
 */
@KumlDsl
class ActorBuilder internal constructor() {
    var visibility: Visibility = Visibility.PUBLIC
    val stereotypes: MutableList<String> = mutableListOf()
}

/**
 * Builder for inline display options of a [UmlUseCase].
 */
@KumlDsl
class UseCaseBuilder internal constructor() {
    var visibility: Visibility = Visibility.PUBLIC
    val stereotypes: MutableList<String> = mutableListOf()
}
