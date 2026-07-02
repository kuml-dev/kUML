package dev.kuml.core.ocl

import dev.kuml.core.model.KumlEvalContext
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlCollaboration
import dev.kuml.uml.UmlComponent
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty

/**
 * Resolves `self.prop` navigations for the OCL evaluator.
 *
 * Resolution order:
 * 1. Hardcoded runtime/structural special cases (unchanged from earlier
 *    versions — [KumlEvalContext], `Map`, SoaML `ownedPort`/`role`).
 * 2. Metamodel-driven attribute lookup (V3.2.21): when [self] is a
 *    [UmlClass] or [UmlInterface], `self.prop` resolves to the declared
 *    [UmlProperty] in `attributes` whose [dev.kuml.uml.UmlNamedElement.name]
 *    equals [prop] — the property object itself is returned (metamodel-level
 *    navigation, consistent with association-end navigation returning
 *    classifier objects rather than runtime instance values).
 * 3. Association-end navigation (V3.2.21): when [self] is a [UmlClassifier]
 *    and [model] contains a [UmlAssociation] with a *navigable* end typed at
 *    `self.id`, `self.prop` resolves to the classifier(s) at the *other* end
 *    — matched by that other end's `role` (or, if unset, by the referenced
 *    classifier's name, OCL-lowercased). An end with multiplicity `upper != 1`
 *    (or multiple matching ends sharing the same role) yields a `List`; a
 *    single navigable end with `upper == 1` yields the bare classifier. An
 *    end with `navigable = false` is not traversable and is skipped, matching
 *    UML/OCL navigability semantics. No matching association end falls
 *    through to the "unknown property" [OclEvaluationException].
 */
internal object UmlPropertyAccessor {
    internal fun get(
        self: Any,
        prop: String,
        model: List<Any> = emptyList(),
    ): Any? =
        try {
            getKnown(self, prop)
        } catch (e: OclEvaluationException) {
            if (self !is UmlClassifier) throw e
            val declared = resolveDeclaredAttribute(self, prop)
            if (declared !== NOT_FOUND) return declared
            val resolved = resolveAssociationEnd(self, prop, model)
            if (resolved === NOT_FOUND) throw e
            resolved
        }

    /**
     * Metamodel-driven lookup of a declared [UmlProperty] on [self] by name,
     * covering the classifier kinds that own attributes ([UmlClass],
     * [UmlInterface]). Returns [NOT_FOUND] rather than `null` so the caller
     * can fall through to association-end navigation when no attribute
     * matches.
     */
    private fun resolveDeclaredAttribute(
        self: UmlClassifier,
        prop: String,
    ): Any {
        val attributes =
            when (self) {
                is UmlClass -> self.attributes
                is UmlInterface -> self.attributes
                else -> return NOT_FOUND
            }
        return attributes.firstOrNull { it.name == prop } ?: NOT_FOUND
    }

    private fun getKnown(
        self: Any,
        prop: String,
    ): Any? =
        when {
            // ── V1.1.5 — Runtime context navigation ─────────────────────────────
            // KumlEvalContext exposes the runtime state needed by state-machine
            // guards. Implemented by StateMachineInstance.
            self is KumlEvalContext && prop == "variables" -> self.variables
            self is KumlEvalContext && prop == "vars" -> self.variables
            self is KumlEvalContext && prop == "currentVertexIds" -> self.currentVertexIds
            self is KumlEvalContext && prop == "isTerminated" -> self.isTerminated
            // Generic Map navigation — used for `event.<key>` lookups when the
            // receiver is a Map (e.g. the event-payload view).
            self is Map<*, *> ->
                @Suppress("UNCHECKED_CAST")
                (self as Map<Any?, Any?>)[prop]
            self is UmlClass && prop == "name" -> self.name
            self is UmlClass && prop == "isAbstract" -> self.isAbstract
            self is UmlClass && prop == "attributes" -> self.attributes
            self is UmlClass && prop == "operations" -> self.operations
            self is UmlClass && prop == "constraints" -> self.constraints
            self is UmlClass && prop == "stereotypes" -> self.stereotypes
            self is UmlInterface && prop == "name" -> self.name
            self is UmlInterface && prop == "attributes" -> self.attributes
            self is UmlInterface && prop == "operations" -> self.operations
            self is UmlInterface && prop == "constraints" -> self.constraints
            self is UmlInterface && prop == "stereotypes" -> self.stereotypes
            self is UmlEnumeration && prop == "name" -> self.name
            self is UmlEnumeration && prop == "literals" -> self.literals
            self is UmlProperty && prop == "name" -> self.name
            self is UmlProperty && prop == "isStatic" -> self.isStatic
            self is UmlProperty && prop == "isReadOnly" -> self.isReadOnly
            self is UmlOperation && prop == "name" -> self.name
            self is UmlOperation && prop == "parameters" -> self.parameters
            self is UmlEnumerationLiteral && prop == "name" -> self.name
            self is UmlParameter && prop == "name" -> self.name
            // ── SoaML / V1.1 additions ──────────────────────────────────────────
            // UmlComponent.ports exposed as "ownedPort" (SoaML Participant constraint)
            self is UmlComponent && prop == "ownedPort" -> self.ports
            self is UmlComponent && prop == "ports" -> self.ports
            self is UmlComponent && prop == "name" -> self.name
            // UmlCollaboration.roles exposed as both "role" and "roles" (SoaML ServiceContract)
            self is UmlCollaboration && prop == "role" -> self.roles
            self is UmlCollaboration && prop == "roles" -> self.roles
            self is UmlCollaboration && prop == "name" -> self.name
            else -> throw OclEvaluationException(
                "Cannot navigate property '$prop' on ${self::class.simpleName}",
            )
        }

    /** Sentinel distinguishing "no matching association end" from a legitimate `null` navigation result. */
    private val NOT_FOUND = Any()

    /**
     * Resolves `self.prop` against [UmlAssociation]s in [model] whose ends
     * reference `self.id`. Returns [NOT_FOUND] when no association end
     * matches [prop] (so the caller can fall through to the "unknown
     * property" error rather than silently returning `null`).
     *
     * KMP note: this is metamodel-driven (walks [UmlAssociation.ends]), not
     * Kotlin-reflection-based — `kotlin-reflect` is unavailable on
     * `js`/`wasmJs`, so any generalisation must stay within the declared
     * metamodel structure.
     */
    private fun resolveAssociationEnd(
        self: UmlClassifier,
        prop: String,
        model: List<Any>,
    ): Any {
        val associations = model.filterIsInstance<UmlAssociation>()
        val elementsById = model.filterIsInstance<UmlClassifier>().associateBy { it.id }

        val matches = mutableListOf<Pair<UmlClassifier, Boolean>>() // (target, isMultiValued)
        for (assoc in associations) {
            if (assoc.ends.size != 2) continue
            for (i in assoc.ends.indices) {
                val ownEnd = assoc.ends[i]
                if (ownEnd.typeId != self.id) continue
                val otherEnd = assoc.ends[1 - i]
                if (!otherEnd.navigable) continue
                val roleMatches =
                    otherEnd.role == prop ||
                        (otherEnd.role == null && elementsById[otherEnd.typeId]?.name?.replaceFirstChar { it.lowercase() } == prop)
                if (!roleMatches) continue
                val target = elementsById[otherEnd.typeId] ?: continue
                matches += target to (otherEnd.multiplicity.upper != 1)
            }
        }
        if (matches.isEmpty()) return NOT_FOUND
        // A single navigable end with upper multiplicity 1 (and no other matching
        // end) resolves to the bare classifier; everything else (multiple ends
        // sharing the same role, or an upper bound > 1 / unbounded) resolves to a
        // List, matching OCL's Set/Bag navigation semantics for multi-valued ends.
        return if (matches.size == 1 && !matches[0].second) matches[0].first else matches.map { it.first }
    }
}
