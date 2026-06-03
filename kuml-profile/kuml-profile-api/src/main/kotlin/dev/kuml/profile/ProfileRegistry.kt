package dev.kuml.profile

import java.util.ServiceLoader

/**
 * In-process registry for [KumlProfile] instances.
 *
 * Profiles are keyed by their [KumlProfile.namespace].
 * The registry is populated either by explicit [register] calls (for tests and
 * programmatic use) or by [loadFromClasspath] (for production use via
 * [java.util.ServiceLoader]).
 */
public object ProfileRegistry {
    private val byNamespace = mutableMapOf<String, KumlProfile>()

    /**
     * Register a profile explicitly.
     *
     * Does NOT run closure validation — call [validateClosure] explicitly when
     * all required profiles have been registered.
     */
    public fun register(profile: KumlProfile) {
        byNamespace[profile.namespace] = profile
    }

    /** Look up a profile by its namespace. Returns `null` if not found. */
    public fun get(namespace: String): KumlProfile? = byNamespace[namespace]

    /** All currently registered profiles. */
    public fun all(): List<KumlProfile> = byNamespace.values.toList()

    /** Clear all registered profiles (useful in tests). */
    public fun clear() {
        byNamespace.clear()
    }

    /**
     * Discover and register all profiles provided via [java.util.ServiceLoader].
     *
     * Runs [validateClosure] after loading to enforce D4.
     */
    public fun loadFromClasspath() {
        ServiceLoader
            .load(KumlProfileProvider::class.java)
            .forEach { register(it.profile) }
        validateClosure()
    }

    /**
     * Validate that all `specializes` references in registered profiles can be
     * resolved within the transitive `extends`-closure (D4).
     *
     * @throws IllegalArgumentException if a dangling `specializes` reference is found.
     */
    public fun validateClosure() {
        for (profile in byNamespace.values) {
            val closure = transitiveClosure(profile)
            for (stereotype in profile.stereotypes) {
                val parent = stereotype.specializes ?: continue
                val found =
                    closure.any { p ->
                        p.stereotypes.any { it.name == parent }
                    }
                require(found) {
                    "Profile '${profile.namespace}' stereotype '${stereotype.name}' " +
                        "specializes '$parent', but '$parent' is not found in the " +
                        "extends-closure of this profile. " +
                        "Closure: ${closure.map { it.namespace }}"
                }
            }
        }
    }

    private fun transitiveClosure(profile: KumlProfile): List<KumlProfile> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<KumlProfile>()

        fun visit(p: KumlProfile) {
            if (p.namespace in visited) return
            visited += p.namespace
            result += p
            for (ns in p.extendsProfiles) {
                byNamespace[ns]?.let { visit(it) }
            }
        }

        visit(profile)
        return result
    }
}
