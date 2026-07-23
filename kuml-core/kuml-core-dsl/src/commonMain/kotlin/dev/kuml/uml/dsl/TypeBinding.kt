package dev.kuml.uml.dsl

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlClassifier
import dev.kuml.uml.UmlTypeRef

// ── Type references ───────────────────────────────────────────────────────────

/**
 * Creates a [UmlTypeRef] from a plain type name string.
 *
 * Use this for external or primitive types that are not modelled as
 * [UmlClassifier] elements (e.g. `"UUID"`, `"String"`, `"Int"`).
 *
 * ```kotlin
 * attribute(name = "id", type = typeRef("UUID"))
 * // or the convenience overload:
 * attribute(name = "id", type = "UUID")
 * ```
 */
fun typeRef(name: String): UmlTypeRef = UmlTypeRef(name = name)

/**
 * Creates a [UmlTypeRef] from a [UmlClassifier] builder handle.
 *
 * The [referencedId][UmlTypeRef.referencedId] is set so that tools can resolve
 * the reference without string matching.
 *
 * ```kotlin
 * val status = enumOf("OrderStatus") { literal("DRAFT"); literal("CONFIRMED") }
 * attribute(name = "status", type = typeRef(status))
 * // or the convenience overload:
 * attribute(name = "status", type = status)
 * ```
 */
fun typeRef(classifier: UmlClassifier): UmlTypeRef = UmlTypeRef(name = classifier.name, referencedId = classifier.id)

/**
 * Creates a [UmlTypeRef] with an explicit [referencedId], for cases where the
 * referenced classifier is not available as a builder handle (e.g. when
 * reconstructing a model from serialized data, such as
 * [dev.kuml.uml.dsl.print.UmlModelDslPrinter]'s round-trip output).
 *
 * ```kotlin
 * attribute(name = "status", type = typeRef("OrderStatus", referencedId = "OrderStatus"))
 * ```
 */
fun typeRef(
    name: String,
    referencedId: String?,
): UmlTypeRef = UmlTypeRef(name = name, referencedId = referencedId)

// ── Multiplicity parsing ──────────────────────────────────────────────────────

/**
 * Parses a multiplicity string into a [Multiplicity].
 *
 * Supported formats:
 * - `"1"` → exactly one
 * - `"0..1"` → optional
 * - `"1..*"` → one or more
 * - `"0..*"` / `"*"` → zero or more
 * - `"m..n"` → explicit lower..upper
 *
 * @throws IllegalArgumentException if the format is not recognised.
 */
fun parseMultiplicity(spec: String): Multiplicity =
    when (val s = spec.trim()) {
        "1" -> Multiplicity(lower = 1, upper = 1)
        "0..1" -> Multiplicity(lower = 0, upper = 1)
        "0..*", "*" -> Multiplicity(lower = 0, upper = null)
        "1..*" -> Multiplicity(lower = 1, upper = null)
        else -> {
            if (s.contains("..")) {
                val parts = s.split("..")
                require(parts.size == 2) { "Invalid multiplicity: $spec" }
                val lower =
                    parts[0].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid multiplicity lower bound: $spec")
                val upper =
                    parts[1].trim().let { u ->
                        if (u == "*") {
                            null
                        } else {
                            u.toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid multiplicity upper bound: $spec")
                        }
                    }
                Multiplicity(lower = lower, upper = upper)
            } else {
                val n =
                    s.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid multiplicity: $spec")
                Multiplicity(lower = n, upper = n)
            }
        }
    }
