package dev.kuml.profile

/** A stereotype definition within a [KumlProfile]. */
public data class KumlStereotype(
    val name: String,
    val targetMetaclass: UmlMetaclass,
    val properties: List<StereotypeProperty<*>> = emptyList(),
    val constraints: List<OclConstraint> = emptyList(),
    val icon: String? = null,
    /** Name of a parent stereotype this one specialises (D4). */
    val specializes: String? = null,
) {
    init {
        require(name.isNotBlank()) { "KumlStereotype.name must not be blank" }
        require(properties.map { it.name }.toSet().size == properties.size) {
            "Stereotype '$name' has duplicate property names"
        }
    }
}
