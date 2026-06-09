package dev.kuml.cli.validate

import dev.kuml.core.model.KumlDiagram
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlGeneralization

/**
 * A structural violation found during [StructuralValidator.validate].
 *
 * @property id Short machine-readable code for the violation kind.
 * @property severity `"error"` for violations that make the model invalid;
 *   `"warning"` for issues worth reporting but not fatal.
 * @property message Human-readable description.
 * @property location Optional element ID or position where the violation was found.
 * @property category Discriminator for the violation source; always `"structural"` here.
 */
internal data class StructuralViolation(
    val id: String,
    val severity: String,
    val message: String,
    val location: String? = null,
    val category: String = "structural",
)

/**
 * Validates the structural integrity of a [KumlDiagram].
 *
 * Four checks (V2.0.31):
 *
 *  1. **Duplicate IDs** — two elements share the same `id` field → error.
 *  2. **Circular inheritance** — a `UmlGeneralization` chain leads back to its
 *     own starting classifier (A→B→A, A→B→C→A, …) → error.
 *  3. **Dangling references** — a `UmlAssociation` end or `UmlDependency`
 *     client/supplier references an ID that is not present in the diagram → warning.
 *  4. **Missing required stereotype property** — a stereotyped element is missing
 *     a tagged value that the profile's stereotype definition marks required → warning.
 *
 * Checks 1–3 operate purely on the diagram without profile registry access.
 * Check 4 is opportunistic: if the profile registry is populated (e.g. the
 * caller ran [dev.kuml.profile.ProfileRegistry.loadFromClasspath]) the check
 * runs; otherwise it yields no results.
 */
internal object StructuralValidator {
    /**
     * Run all structural checks on [diagram] and return the collected violations.
     *
     * Returns an empty list for an empty diagram.
     */
    internal fun validate(diagram: KumlDiagram): List<StructuralViolation> {
        val violations = mutableListOf<StructuralViolation>()
        val knownIds = diagram.elements.map { it.id }.toSet()

        violations += checkDuplicateIds(diagram)
        violations += checkCircularInheritance(diagram)
        violations += checkDanglingReferences(diagram, knownIds)
        violations += checkMissingRequiredStereotypeProperties(diagram)

        return violations
    }

    // ── Check 1: Duplicate IDs ────────────────────────────────────────────────

    private fun checkDuplicateIds(diagram: KumlDiagram): List<StructuralViolation> {
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        for (element in diagram.elements) {
            if (!seen.add(element.id)) {
                duplicates.add(element.id)
            }
        }
        return duplicates.map { id ->
            StructuralViolation(
                id = "DUPLICATE_ID",
                severity = "error",
                message = "Duplicate element ID '$id' — each element must have a unique ID.",
                location = id,
            )
        }
    }

    // ── Check 2: Circular inheritance ────────────────────────────────────────

    /**
     * Build a parent-map from [UmlGeneralization] edges, then for every
     * classifier walk up the chain looking for a cycle.
     */
    private fun checkCircularInheritance(diagram: KumlDiagram): List<StructuralViolation> {
        // specificId → generalId (child → parent)
        val parentMap = mutableMapOf<String, String>()
        for (element in diagram.elements) {
            if (element is UmlGeneralization) {
                parentMap[element.specificId] = element.generalId
            }
        }

        val violations = mutableListOf<StructuralViolation>()
        val allClassifierIds =
            diagram.elements
                .map { it.id }
                .filter { parentMap.containsKey(it) || parentMap.containsValue(it) }
                .toSet()

        for (startId in allClassifierIds) {
            if (hasCycle(startId, parentMap, mutableSetOf())) {
                violations.add(
                    StructuralViolation(
                        id = "CIRCULAR_INHERITANCE",
                        severity = "error",
                        message =
                            "Circular inheritance detected starting from element '$startId'. " +
                                "Inheritance chains must be acyclic.",
                        location = startId,
                    ),
                )
            }
        }
        // De-duplicate — each cycle is reported once per starting node; collapse to unique messages
        return violations.distinctBy { it.location }
    }

    /**
     * Return `true` if following the parent chain from [current] leads back to
     * any node already in [visited].
     */
    private fun hasCycle(
        current: String,
        parentMap: Map<String, String>,
        visited: MutableSet<String>,
    ): Boolean {
        val parent = parentMap[current] ?: return false
        if (!visited.add(current)) return true
        return hasCycle(parent, parentMap, visited)
    }

    // ── Check 3: Dangling references ─────────────────────────────────────────

    private fun checkDanglingReferences(
        diagram: KumlDiagram,
        knownIds: Set<String>,
    ): List<StructuralViolation> {
        val violations = mutableListOf<StructuralViolation>()
        for (element in diagram.elements) {
            when (element) {
                is UmlAssociation -> {
                    for (end in element.ends) {
                        if (end.typeId !in knownIds) {
                            violations.add(
                                StructuralViolation(
                                    id = "DANGLING_REFERENCE",
                                    severity = "warning",
                                    message =
                                        "Association '${element.id}' references unknown type '${end.typeId}'.",
                                    location = element.id,
                                ),
                            )
                        }
                    }
                }
                is UmlDependency -> {
                    if (element.clientId !in knownIds) {
                        violations.add(
                            StructuralViolation(
                                id = "DANGLING_REFERENCE",
                                severity = "warning",
                                message =
                                    "Dependency '${element.id}' has unknown client '${element.clientId}'.",
                                location = element.id,
                            ),
                        )
                    }
                    if (element.supplierId !in knownIds) {
                        violations.add(
                            StructuralViolation(
                                id = "DANGLING_REFERENCE",
                                severity = "warning",
                                message =
                                    "Dependency '${element.id}' has unknown supplier '${element.supplierId}'.",
                                location = element.id,
                            ),
                        )
                    }
                }
                else -> {}
            }
        }
        return violations
    }

    // ── Check 4: Missing required stereotype properties ───────────────────────

    /**
     * Cross-check stereotyped elements against the profile registry.
     *
     * Opportunistic: silently yields no results if the profile registry is
     * empty (no profiles loaded).
     */
    private fun checkMissingRequiredStereotypeProperties(diagram: KumlDiagram): List<StructuralViolation> {
        val violations = mutableListOf<StructuralViolation>()
        for (element in diagram.elements) {
            if (element !is dev.kuml.uml.Stereotypable) continue
            for (applied in element.appliedStereotypes) {
                val profile =
                    try {
                        dev.kuml.profile.ProfileRegistry
                            .get(applied.profileNamespace)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                val stereotypeDef =
                    profile.stereotypes.firstOrNull { it.name == applied.stereotypeName }
                        ?: continue
                for (prop in stereotypeDef.properties) {
                    if (prop.required && !applied.tags.containsKey(prop.name)) {
                        violations.add(
                            StructuralViolation(
                                id = "MISSING_REQUIRED_STEREOTYPE_PROPERTY",
                                severity = "warning",
                                message =
                                    "Element '${element.id}' is missing required tagged value " +
                                        "'${prop.name}' for stereotype '${applied.stereotypeName}'.",
                                location = element.id,
                            ),
                        )
                    }
                }
            }
        }
        return violations
    }
}
