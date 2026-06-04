package dev.kuml.core.ocl

import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlStereotype
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.Stereotypable
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlInterface

/**
 * Runtime validator for stereotype applications in a [KumlDiagram].
 *
 * Performs three pass steps for each [KumlStereotypeApplication] found on
 * any [Stereotypable] element in the diagram:
 *
 * 1. **Required-property check**: every `required = true` property must have
 *    a corresponding tag value in the application.
 * 2. **Target-metaclass check** (defensive): the stereotype's [UmlMetaclass]
 *    must match the element type at runtime.
 * 3. **Stereotype OCL check**: for each [dev.kuml.profile.OclConstraint] on
 *    the stereotype, delegates to [OclValidator.validateWithExpressions].
 *
 * Violations from all passes are merged into a single [KumlValidationResult].
 * Uses [ProfileRegistry] to resolve profile namespace → [KumlStereotype].
 */
public object StereotypeValidator {
    /**
     * Validate all stereotype applications in [diagram].
     *
     * Returns a [KumlValidationResult] with all violations found.
     * If [ProfileRegistry] has no profiles loaded, only the structural checks
     * are performed (pass 1 + 2 cannot run without the stereotype definition
     * from the registry).
     */
    public fun validate(diagram: KumlDiagram): KumlValidationResult {
        val violations = mutableListOf<KumlViolation>()

        for (element in diagram.elements) {
            if (element !is Stereotypable) continue
            val stereotypable = element as Stereotypable

            // Determine element id/name and metaclass
            val (elementId, elementName, elementMetaclass) = elementInfo(element) ?: continue

            for (app in stereotypable.appliedStereotypes) {
                if (app !is KumlStereotypeApplication) continue

                // Resolve stereotype definition from registry
                val profile = ProfileRegistry.get(app.profileNamespace)
                val stereotype = profile?.stereotype(app.stereotypeName)

                if (stereotype != null) {
                    // Pass 1: required-property check
                    violations += checkRequiredProperties(app, stereotype, elementId, elementName)

                    // Pass 2: target-metaclass check (defensive)
                    violations += checkTargetMetaclass(app, stereotype, elementId, elementName, elementMetaclass)

                    // Pass 3: stereotype OCL constraints
                    violations += checkOclConstraints(element, app, stereotype, elementId, elementName)
                }
                // If stereotype not found in registry, we skip silently
                // (profile not loaded is not a model error — use kuml profile validate for that)
            }
        }

        return KumlValidationResult(valid = violations.isEmpty(), violations = violations)
    }

    // ── Pass 1: Required properties ───────────────────────────────────────────

    private fun checkRequiredProperties(
        app: KumlStereotypeApplication,
        stereotype: KumlStereotype,
        elementId: String,
        elementName: String,
    ): List<KumlViolation> {
        val violations = mutableListOf<KumlViolation>()
        for (prop in stereotype.properties) {
            if (!prop.required) continue
            val hasValue = app.tags.containsKey(prop.name)
            val hasDefault = prop.default != null
            if (!hasValue && !hasDefault) {
                violations +=
                    KumlViolation(
                        constraintId = "$elementId::stereotype::${stereotype.name}::required::${prop.name}",
                        constraintName = "required-property:${prop.name}",
                        classifierId = elementId,
                        classifierName = elementName,
                        oclExpression = "",
                        message =
                            "Stereotype '${stereotype.name}' on '$elementName' requires property " +
                                "'${prop.name}' — no value provided",
                    )
            }
        }
        return violations
    }

    // ── Pass 2: Target-metaclass defensive check ──────────────────────────────

    private fun checkTargetMetaclass(
        app: KumlStereotypeApplication,
        stereotype: KumlStereotype,
        elementId: String,
        elementName: String,
        elementMetaclass: UmlMetaclass,
    ): List<KumlViolation> {
        if (stereotype.targetMetaclass == elementMetaclass) return emptyList()
        return listOf(
            KumlViolation(
                constraintId = "$elementId::stereotype::${stereotype.name}::metaclass",
                constraintName = "target-metaclass",
                classifierId = elementId,
                classifierName = elementName,
                oclExpression = "",
                message =
                    "Stereotype '${stereotype.name}' targets ${stereotype.targetMetaclass}, " +
                        "but was applied to $elementMetaclass '$elementName'",
            ),
        )
    }

    // ── Pass 3: Stereotype OCL constraints ───────────────────────────────────

    private fun checkOclConstraints(
        element: Any,
        app: KumlStereotypeApplication,
        stereotype: KumlStereotype,
        elementId: String,
        elementName: String,
    ): List<KumlViolation> {
        if (stereotype.constraints.isEmpty()) return emptyList()
        val constraintBodies = stereotype.constraints.associate { it.name to it.body }
        val result =
            OclValidator.validateWithExpressions(
                self = element,
                elementId = elementId,
                elementName = elementName,
                constraintBodies = constraintBodies,
            )
        // Annotate violations with the stereotype context
        return result.violations.map { v ->
            v.copy(
                constraintName = "stereotype:${stereotype.name}:${v.constraintName}",
                message = "[Stereotype '${stereotype.name}' on '${app.profileNamespace}'] ${v.message}",
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns (id, name, metaclass) for a stereotypable element, or null for
     * element types we do not yet know how to map to a [UmlMetaclass].
     */
    private fun elementInfo(element: Any): Triple<String, String, UmlMetaclass>? =
        when (element) {
            is UmlClass -> Triple(element.id, element.name, UmlMetaclass.Class)
            is UmlInterface -> Triple(element.id, element.name, UmlMetaclass.Interface)
            is UmlEnumeration -> Triple(element.id, element.name, UmlMetaclass.Enumeration)
            else -> null
        }
}
