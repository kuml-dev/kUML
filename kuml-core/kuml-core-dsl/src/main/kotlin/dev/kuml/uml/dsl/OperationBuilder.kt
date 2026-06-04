package dev.kuml.uml.dsl

import dev.kuml.core.dsl.KumlDsl
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.ParameterDirection
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlTypeRef
import dev.kuml.uml.Visibility
import dev.kuml.uml.ids.UmlIds

/** Internal data collected for one parameter before the operation ID is known. */
internal data class ParameterSpec(
    val name: String,
    val type: UmlTypeRef,
    val direction: ParameterDirection,
    val defaultValue: String?,
    val appliedStereotypes: List<KumlStereotypeApplication> = emptyList(),
)

// ── ParameterBuilder ──────────────────────────────────────────────────────────

/**
 * Builder for a single [UmlParameter] inside an [OperationBuilder] body.
 *
 * Do not instantiate directly — use the [parameter] extension function inside
 * an [OperationBuilder] block.
 */
@KumlDsl
class ParameterBuilder internal constructor(
    private val name: String,
    private val type: UmlTypeRef,
    override val container: UmlContainerScope,
) : UmlElementScope {
    override val metaclass: UmlMetaclass = UmlMetaclass.Parameter

    var direction: ParameterDirection = ParameterDirection.IN
    var defaultValue: String? = null

    private val appliedStereotypes = mutableListOf<KumlStereotypeApplication>()

    override fun addStereotype(app: KumlStereotypeApplication) {
        appliedStereotypes += app
    }

    internal fun build(): ParameterSpec =
        ParameterSpec(
            name = name,
            type = type,
            direction = direction,
            defaultValue = defaultValue,
            appliedStereotypes = appliedStereotypes.toList(),
        )
}

/**
 * Builder for a [UmlOperation].
 *
 * Do not instantiate directly — use the [operation] extension function on a
 * [UmlClassifierScope].
 */
@KumlDsl
class OperationBuilder internal constructor(
    private val name: String,
    private val ownerId: String,
    private val takenIds: MutableSet<String>,
    private val explicitId: String?,
    override val container: UmlContainerScope,
) : UmlElementScope {
    override val metaclass: UmlMetaclass = UmlMetaclass.Operation

    var visibility: Visibility = Visibility.PUBLIC
    var returnType: UmlTypeRef? = null
    var isAbstract: Boolean = false
    var isStatic: Boolean = false
    val stereotypes: MutableList<String> = mutableListOf()

    private val params = mutableListOf<ParameterSpec>()
    private val appliedStereotypeList = mutableListOf<KumlStereotypeApplication>()

    override fun addStereotype(app: KumlStereotypeApplication) {
        appliedStereotypeList += app
    }

    // ── Return type convenience ───────────────────────────────────────────────

    /** Sets the return type by name string. */
    fun returns(typeName: String) {
        returnType = typeRef(typeName)
    }

    /** Sets the return type via a classifier handle. */
    fun returns(classifier: UmlClassifier) {
        returnType = typeRef(classifier)
    }

    // ── Parameters (flat, backward-compatible) ────────────────────────────────

    /**
     * Adds a parameter to this operation.
     *
     * @param name Parameter name.
     * @param type Type reference.
     * @param direction Parameter direction (default: [ParameterDirection.IN]).
     * @param defaultValue Optional default value expression.
     */
    fun parameter(
        name: String,
        type: UmlTypeRef,
        direction: ParameterDirection = ParameterDirection.IN,
        defaultValue: String? = null,
    ) {
        params += ParameterSpec(name, type, direction, defaultValue)
    }

    /** Convenience overload — type by name string. */
    fun parameter(
        name: String,
        type: String,
        direction: ParameterDirection = ParameterDirection.IN,
        defaultValue: String? = null,
    ) = parameter(name, typeRef(type), direction, defaultValue)

    /** Convenience overload — type by classifier handle. */
    fun parameter(
        name: String,
        type: UmlClassifier,
        direction: ParameterDirection = ParameterDirection.IN,
        defaultValue: String? = null,
    ) = parameter(name, typeRef(type), direction, defaultValue)

    // ── Parameters (block form with stereotype support) ────────────────────────

    /**
     * Adds a parameter with a builder block — enables [stereotype] calls inside.
     *
     * ```kotlin
     * operation("findByEmail") {
     *     parameter("email", "String") {
     *         stereotype("RequestParam")
     *     }
     * }
     * ```
     */
    fun parameter(
        name: String,
        type: UmlTypeRef,
        block: ParameterBuilder.() -> Unit,
    ) {
        val builder = ParameterBuilder(name, type, container)
        builder.block()
        params += builder.build()
    }

    /** Block overload — type by name string. */
    fun parameter(
        name: String,
        type: String,
        block: ParameterBuilder.() -> Unit,
    ) = parameter(name, typeRef(type), block)

    /** Block overload — type by classifier handle. */
    fun parameter(
        name: String,
        type: UmlClassifier,
        block: ParameterBuilder.() -> Unit,
    ) = parameter(name, typeRef(type), block)

    // ── Build ─────────────────────────────────────────────────────────────────

    internal fun build(): UmlOperation {
        val paramTypeNames = params.map { it.type.name }
        val opId =
            explicitId
                ?: UmlIds.disambiguate(
                    candidate = UmlIds.operation(ownerId, name, paramTypeNames),
                    taken = takenIds,
                )
        takenIds += opId

        val builtParams =
            params.map { spec ->
                val paramId =
                    UmlIds.disambiguate(
                        candidate = UmlIds.child(opId, spec.name),
                        taken = takenIds,
                    )
                takenIds += paramId
                UmlParameter(
                    id = paramId,
                    name = spec.name,
                    type = spec.type,
                    direction = spec.direction,
                    defaultValue = spec.defaultValue,
                    appliedStereotypes = spec.appliedStereotypes.toList<AppliedStereotype>(),
                )
            }

        return UmlOperation(
            id = opId,
            name = name,
            visibility = visibility,
            parameters = builtParams,
            returnType = returnType,
            isAbstract = isAbstract,
            isStatic = isStatic,
            stereotypes = stereotypes.toList(),
            appliedStereotypes = appliedStereotypeList.toList<AppliedStereotype>(),
        )
    }
}

// ── Extension functions ───────────────────────────────────────────────────────

/**
 * Adds an operation to the enclosing classifier.
 *
 * ```kotlin
 * classOf("Order") {
 *     operation(name = "confirm") {
 *         visibility = PUBLIC
 *     }
 *     operation(name = "find") {
 *         parameter("id", type = "Long")
 *         returns("Order")
 *     }
 * }
 * ```
 *
 * @param name Operation name.
 * @param id Optional explicit ID override.
 * @return The built [UmlOperation].
 */
fun UmlClassifierScope.operation(
    name: String,
    id: String? = null,
    block: OperationBuilder.() -> Unit = {},
): UmlOperation {
    val builder =
        OperationBuilder(
            name = name,
            ownerId = ownerId,
            takenIds = takenIds,
            explicitId = id,
            container = container,
        )
    builder.block()
    val op = builder.build()
    addOperation(op)
    return op
}

/**
 * Sets the return type of this operation via a multiplicity-less shorthand.
 *
 * Equivalent to setting [OperationBuilder.returnType] directly.
 */
fun OperationBuilder.returns(
    typeName: String,
    multiplicity: Multiplicity? = null,
): Unit = run { returnType = UmlTypeRef(name = typeName) }
