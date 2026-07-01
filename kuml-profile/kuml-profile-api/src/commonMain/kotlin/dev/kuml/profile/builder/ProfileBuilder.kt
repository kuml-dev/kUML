package dev.kuml.profile.builder

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlProfileImpl
import dev.kuml.profile.KumlStereotype

/** Builder for [KumlProfile] instances via the [profile] DSL function. */
@KumlProfileDsl
public class ProfileBuilder internal constructor(
    public val name: String,
) {
    public var namespace: String = ""
    public var description: String = ""
    public var version: String = "1.0.0"

    private val extendsList = mutableListOf<String>()
    private val stereotypeBuilders = mutableListOf<StereotypeBuilder>()

    /** Declare that this profile extends another profile (by reference). */
    public fun extends(other: KumlProfile) {
        extendsList += other.namespace
    }

    /** Declare that this profile extends another profile (by namespace string). */
    public fun extends(namespace: String) {
        extendsList += namespace
    }

    /** Add a stereotype to this profile. */
    public fun stereotype(
        name: String,
        block: StereotypeBuilder.() -> Unit,
    ) {
        stereotypeBuilders += StereotypeBuilder(name).apply(block)
    }

    internal fun build(): KumlProfile {
        require(namespace.isNotBlank()) {
            "Profile '$name': namespace must be set (e.g. 'dev.kuml.profiles.javaee')"
        }
        val stereotypes = stereotypeBuilders.map { it.build() }
        require(stereotypes.map { it.name }.toSet().size == stereotypes.size) {
            "Profile '$name' has duplicate stereotype names"
        }
        validateSpecializesClosure(stereotypes, extendsList)
        return KumlProfileImpl(
            name = name,
            namespace = namespace,
            description = description,
            version = version,
            extendsProfiles = extendsList.toList(),
            stereotypes = stereotypes,
        )
    }

    /**
     * D4: each `specializes("X")` reference must resolve either within this
     * profile or in one of the declared `extends` namespaces.
     *
     * Note: the full transitive-closure check happens in [dev.kuml.profile.ProfileRegistry.validateClosure]
     * once all profiles are loaded. Here we only catch the case where `specializes`
     * references an external stereotype but no `extends` is declared at all.
     */
    private fun validateSpecializesClosure(
        stereotypes: List<KumlStereotype>,
        extendsNamespaces: List<String>,
    ) {
        val localNames = stereotypes.map { it.name }.toSet()
        for (s in stereotypes) {
            val parent = s.specializes ?: continue
            if (parent in localNames) continue
            if (extendsNamespaces.isEmpty()) {
                error(
                    "Stereotype '${s.name}' specializes '$parent', " +
                        "but '$parent' is not defined in profile '$name' and " +
                        "no `extends(otherProfile)` is declared. " +
                        "Did you forget to add `extends(...)` to this profile?",
                )
            }
        }
    }
}

/**
 * Build a [KumlProfile] using the profile DSL.
 *
 * ```kotlin
 * val myProfile = profile("MyProfile") {
 *     namespace = "dev.example.myprofile"
 *     stereotype("Entity") { extends(UmlMetaclass.Class) }
 * }
 * ```
 */
public fun profile(
    name: String,
    block: ProfileBuilder.() -> Unit,
): KumlProfile = ProfileBuilder(name).apply(block).build()
