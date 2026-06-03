package dev.kuml.profile.builder

import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.OclConstraint
import dev.kuml.profile.StereotypeProperty
import dev.kuml.profile.UmlMetaclass

/** Builder for [KumlStereotype] instances within the profile DSL. */
@KumlProfileDsl
public class StereotypeBuilder internal constructor(
    public val name: String,
) {
    private var target: UmlMetaclass? = null
    private val props = mutableListOf<StereotypeProperty<*>>()
    private val cons = mutableListOf<OclConstraint>()

    public var icon: String? = null
    public var specializes: String? = null

    /** Set the target UML metaclass this stereotype extends. */
    public fun extends(metaclass: UmlMetaclass) {
        target = metaclass
    }

    /** Add a typed property to this stereotype. */
    public inline fun <reified T : Any> property(
        name: String,
        block: PropertyBuilder<T>.() -> Unit = {},
    ) {
        addProperty(PropertyBuilder(name, T::class).apply(block).build())
    }

    @PublishedApi
    internal fun <T : Any> addProperty(prop: StereotypeProperty<T>) {
        props += prop
    }

    /** Add an OCL constraint to this stereotype. */
    public fun constraint(
        name: String,
        block: ConstraintBuilder.() -> Unit,
    ) {
        cons += ConstraintBuilder(name).apply(block).build()
    }

    internal fun build(): KumlStereotype {
        val t =
            target ?: error(
                "Stereotype '$name' must declare extends(UmlMetaclass.X)",
            )
        return KumlStereotype(name, t, props.toList(), cons.toList(), icon, specializes)
    }
}
