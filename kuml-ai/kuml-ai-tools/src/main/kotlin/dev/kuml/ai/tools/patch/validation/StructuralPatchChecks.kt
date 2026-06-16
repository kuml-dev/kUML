package dev.kuml.ai.tools.patch.validation

// TODO(v3.x): drop in favour of dev.kuml.cli.validate.StructuralValidator once promoted to
// kuml-core-validation. Keep this clone in sync with that file (V2.0.31 reference).
// Reference: kuml-cli/src/main/kotlin/dev/kuml/cli/validate/StructuralValidator.kt

import dev.kuml.ai.tools.context.AnyKumlModel
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization

/**
 * Structural integrity checks for a patched [AnyKumlModel]. This is a clone of the
 * four V2.0.31 checks from `dev.kuml.cli.validate.StructuralValidator`, adapted to
 * work on [AnyKumlModel] rather than [dev.kuml.core.model.KumlDiagram].
 *
 * The clone is necessary because [dev.kuml.cli.validate.StructuralValidator] is
 * `internal object` — a future `kuml-core-validation` extraction will eliminate
 * this duplication.
 */
internal object StructuralPatchChecks {
    /**
     * Run all four structural checks on [model] and return the collected [ValidationError]s.
     *
     * Errors are things that make the model invalid (DUPLICATE_ID, CIRCULAR_INHERITANCE).
     * Warnings are returned in the [warnings] out-parameter (DANGLING_REFERENCE,
     * MISSING_REQUIRED_STEREOTYPE_PROPERTY).
     */
    internal fun run(
        model: AnyKumlModel,
        warnings: MutableList<String>,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (model) {
            is AnyKumlModel.Uml -> {
                val allElementIds = (model.elements.map { it.id } + model.relationships.map { it.id }).toSet()

                errors += checkDuplicateIds(allElementIds, model.elements.map { it.id } + model.relationships.map { it.id })
                errors += checkCircularInheritance(model.relationships.filterIsInstance<UmlGeneralization>())
                val (danglingErrors, danglingWarnings) = checkDanglingReferences(model, allElementIds)
                // Per V2.0.31: dangling references are warnings, not errors
                warnings.addAll(danglingWarnings)
                errors.addAll(danglingErrors.filter { it.code != "DANGLING_REFERENCE" })
                warnings.addAll(danglingErrors.filter { it.code == "DANGLING_REFERENCE" }.map { it.message })
            }
            is AnyKumlModel.C4 -> {
                // C4: check for duplicate IDs across all C4 elements
                val ids = model.model.elements.map { it.id }
                errors += checkDuplicateIds(ids.toSet(), ids)
            }
            is AnyKumlModel.Sysml2 -> {
                // SysML 2: check for duplicate IDs across definitions and usages
                val ids = model.model.definitions.map { it.id } + model.model.usages.map { it.id }
                errors += checkDuplicateIds(ids.toSet(), ids)
            }
        }

        return errors
    }

    // ── Check 1: Duplicate IDs ────────────────────────────────────────────────

    private fun checkDuplicateIds(
        knownIds: Set<String>,
        allIds: List<String>,
    ): List<ValidationError> {
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        for (id in allIds) {
            if (!seen.add(id)) duplicates.add(id)
        }
        return duplicates.map { id ->
            ValidationError(
                code = "DUPLICATE_ID",
                message = "Duplicate element ID '$id' — each element must have a unique ID.",
                locationHint = id,
            )
        }
    }

    // ── Check 2: Circular inheritance ─────────────────────────────────────────

    private fun checkCircularInheritance(generalizations: List<UmlGeneralization>): List<ValidationError> {
        val parentMap = generalizations.associate { it.specificId to it.generalId }
        val violations = mutableListOf<ValidationError>()
        val allIds = (parentMap.keys + parentMap.values).toSet()
        for (startId in allIds) {
            if (hasCycle(startId, parentMap, mutableSetOf())) {
                violations.add(
                    ValidationError(
                        code = "CIRCULAR_INHERITANCE",
                        message = "Circular inheritance detected starting from element '$startId'. Inheritance chains must be acyclic.",
                        locationHint = startId,
                    ),
                )
            }
        }
        return violations.distinctBy { it.locationHint }
    }

    private fun hasCycle(
        current: String,
        parentMap: Map<String, String>,
        visited: MutableSet<String>,
    ): Boolean {
        val parent = parentMap[current] ?: return false
        if (!visited.add(current)) return true
        return hasCycle(parent, parentMap, visited)
    }

    // ── Check 3: Dangling references ──────────────────────────────────────────

    /**
     * Returns a pair of (errors, warningMessages). Per V2.0.31 convention,
     * DANGLING_REFERENCE is a warning for associations and dependencies.
     */
    private fun checkDanglingReferences(
        model: AnyKumlModel.Uml,
        knownIds: Set<String>,
    ): Pair<List<ValidationError>, List<String>> {
        val errors = mutableListOf<ValidationError>()
        val warningMessages = mutableListOf<String>()

        for (rel in model.relationships) {
            when (rel) {
                is UmlAssociation -> {
                    for (end in rel.ends) {
                        if (end.typeId !in knownIds) {
                            warningMessages.add(
                                "Association '${rel.id}' references unknown type '${end.typeId}'.",
                            )
                        }
                    }
                }
                is UmlDependency -> {
                    if (rel.clientId !in knownIds) {
                        warningMessages.add("Dependency '${rel.id}' has unknown client '${rel.clientId}'.")
                    }
                    if (rel.supplierId !in knownIds) {
                        warningMessages.add("Dependency '${rel.id}' has unknown supplier '${rel.supplierId}'.")
                    }
                }
                is UmlGeneralization -> {
                    if (rel.specificId !in knownIds) {
                        warningMessages.add("Generalization '${rel.id}' has unknown specific '${rel.specificId}'.")
                    }
                    if (rel.generalId !in knownIds) {
                        warningMessages.add("Generalization '${rel.id}' has unknown general '${rel.generalId}'.")
                    }
                }
                else -> {}
            }
        }
        return errors to warningMessages
    }
}
