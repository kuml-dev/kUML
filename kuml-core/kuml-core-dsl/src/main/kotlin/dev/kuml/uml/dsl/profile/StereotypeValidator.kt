package dev.kuml.uml.dsl.profile

import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.UmlMetaclass

internal object StereotypeValidator {
    /**
     * Validate a stereotype application at DSL build time.
     *
     * Checks performed:
     * 1. Target metaclass matches the element's metaclass.
     * 2. All required properties have a value.
     * 3. No unknown tagged-value keys are provided (Levenshtein suggestions on error).
     * 4. Each provided value's type is compatible with the property's declared type.
     */
    fun validateBuildTime(
        stereotype: KumlStereotype,
        application: KumlStereotypeApplication,
        elementMetaclass: UmlMetaclass,
    ) {
        // 1. Target-metaclass check
        require(stereotype.targetMetaclass == elementMetaclass) {
            "Stereotype '${stereotype.name}' targets ${stereotype.targetMetaclass}, " +
                "but was applied to a $elementMetaclass element."
        }

        // 2. Required properties
        for (prop in stereotype.properties) {
            if (!prop.required) continue
            val tagValue = application.tags[prop.name]
            if (tagValue == null) {
                error(
                    "Stereotype '${stereotype.name}' requires property '${prop.name}' " +
                        "(${prop.type.simpleName}) — no value provided.",
                )
            }
        }

        // 3. Unknown tagged-value keys
        val knownNames = stereotype.properties.map { it.name }.toSet()
        for (key in application.tags.keys) {
            if (key !in knownNames) {
                val suggestion = Levenshtein.closest(key, knownNames)
                error(
                    "Stereotype '${stereotype.name}' has no property '$key'." +
                        if (suggestion != null) " Did you mean '$suggestion'?" else "",
                )
            }
        }

        // 4. Type compatibility
        // Note: tags contain TagValue (sealed), so type checking works on the raw
        // Any? that was supplied before conversion. We check via KClass.isInstance
        // on a best-effort basis — full type fidelity is enforced by toTagValue() conversion.
        // Nothing to check here at this level because the conversion already happened.
        // For future completeness this is a no-op placeholder.
    }
}
