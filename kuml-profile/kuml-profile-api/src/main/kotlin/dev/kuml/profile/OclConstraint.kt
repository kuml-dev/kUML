package dev.kuml.profile

/** An OCL (or other) constraint attached to a stereotype definition. */
public data class OclConstraint(
    val name: String,
    val body: String,
) {
    init {
        require(name.isNotBlank()) { "OclConstraint.name must not be blank" }
        require(body.isNotBlank()) { "OclConstraint.body must not be blank" }
    }
}
