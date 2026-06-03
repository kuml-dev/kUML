package dev.kuml.uml.dsl.profile

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.UmlMetaclass

internal data class ResolvedStereotype(
    val profile: KumlProfile,
    val stereotype: KumlStereotype,
)

internal object StereotypeResolution {
    /**
     * Resolve [rawName] (possibly qualified as `"prefix:name"`) against [appliedProfiles].
     *
     * Resolution rules (D1):
     * - With prefix: the prefix must match exactly one applied profile by name (case-insensitive)
     *   or by namespace suffix. If none match, error. Then look up the stereotype name in that profile.
     * - Without prefix: search all applied profiles for the stereotype name.
     *   If found in zero profiles → error with Levenshtein suggestion.
     *   If found in more than one profile → error with qualified-form suggestion.
     */
    fun resolve(
        rawName: String,
        appliedProfiles: List<KumlProfile>,
        elementMetaclass: UmlMetaclass,
    ): ResolvedStereotype {
        require(appliedProfiles.isNotEmpty()) {
            "Cannot apply stereotype '$rawName': no applyProfile(...) call in this container. " +
                "Add applyProfile(yourProfile) before using stereotype()."
        }

        val (prefix, name) = parseQualified(rawName)

        val candidates: List<ResolvedStereotype> =
            if (prefix != null) {
                // D1: qualified form — prefix must match exactly one applied profile
                val matchedProfile =
                    appliedProfiles.firstOrNull { p ->
                        p.name.equals(prefix, ignoreCase = true) ||
                            p.namespace.endsWith(".$prefix", ignoreCase = true) ||
                            p.namespace.equals(prefix, ignoreCase = true)
                    } ?: error(
                        "Stereotype '$rawName': profile prefix '$prefix' does not match " +
                            "any applied profile. Applied: ${appliedProfiles.map { it.name }}",
                    )
                listOfNotNull(
                    matchedProfile.stereotype(name)?.let { ResolvedStereotype(matchedProfile, it) },
                )
            } else {
                appliedProfiles.mapNotNull { p ->
                    p.stereotype(name)?.let { ResolvedStereotype(p, it) }
                }
            }

        if (candidates.isEmpty()) {
            val allNames = appliedProfiles.flatMap { p -> p.stereotypes.map { it.name } }
            val suggestion = Levenshtein.closest(name, allNames)
            error(
                "Stereotype '$rawName' not found in applied profiles " +
                    "(${appliedProfiles.map { it.name }})." +
                    if (suggestion != null) " Did you mean '$suggestion'?" else "",
            )
        }

        if (candidates.size > 1) {
            val sources = candidates.joinToString(", ") { "${it.profile.name}:${it.stereotype.name}" }
            error(
                "Stereotype name '$name' is defined in multiple applied profiles: $sources. " +
                    "Use the qualified form, e.g. stereotype(\"${candidates.first().profile.name.lowercase()}:$name\").",
            )
        }

        return candidates.single()
    }

    private fun parseQualified(raw: String): Pair<String?, String> {
        val idx = raw.indexOf(':')
        return if (idx < 0) null to raw else raw.substring(0, idx) to raw.substring(idx + 1)
    }
}
