package dev.kuml.profile

/** A UML profile — a named collection of stereotypes that extend the UML metamodel. */
public interface KumlProfile {
    public val name: String
    public val namespace: String
    public val description: String
    public val version: String

    /** Namespaces of profiles this profile extends (for stereotype specialisation). */
    public val extendsProfiles: List<String>

    public val stereotypes: List<KumlStereotype>

    /** Look up a stereotype by name, or `null` if not found. */
    public fun stereotype(name: String): KumlStereotype? = stereotypes.firstOrNull { it.name == name }
}

internal data class KumlProfileImpl(
    override val name: String,
    override val namespace: String,
    override val description: String,
    override val version: String,
    override val extendsProfiles: List<String>,
    override val stereotypes: List<KumlStereotype>,
) : KumlProfile
