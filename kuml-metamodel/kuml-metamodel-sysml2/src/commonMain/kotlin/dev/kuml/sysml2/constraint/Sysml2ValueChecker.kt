package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.EnumerationDefinition
import dev.kuml.sysml2.Sysml2Model

/** A finding of [Sysml2ValueChecker]. */
public data class ValueIssue(
    val code: Int,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Checks the attribute defaults of a model against the type of the attribute (wave 2E-b).
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 501 | Error | A default carries a unit (`-5.0[Pa]`) that differs from the unit of the attribute type |
 * | 502 | Error | The default of an enumeration-typed attribute is not one of the literals |
 * | 503 | Error | A numeric default is given for an enumeration-typed attribute, or a literal for a unit-typed one |
 */
public object Sysml2ValueChecker {
    private val WITH_UNIT = Regex("""^\s*-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?\s*\[([^\]]*)\]\s*$""")
    private val NUMBER = Regex("""^\s*-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?\s*$""")

    public fun check(model: Sysml2Model): List<ValueIssue> {
        val defs = model.definitions.associateBy { it.id }
        val out = ArrayList<ValueIssue>()
        for (usage in model.usages.filterIsInstance<dev.kuml.sysml2.AttributeUsage>()) {
            val raw = usage.defaultExpression ?: continue
            when (val type = defs[usage.definitionId]) {
                is EnumerationDefinition -> {
                    if (NUMBER.matches(raw) || WITH_UNIT.matches(raw)) {
                        out +=
                            ValueIssue(
                                code = 503,
                                elementId = usage.id,
                                message = "'${usage.name}' is an enumeration, but its default is a number.",
                                hint = "Use one of ${type.literals}.",
                            )
                    } else if (raw.trim() !in type.literals) {
                        out +=
                            ValueIssue(
                                code = 502,
                                elementId = usage.id,
                                message = "Default '${raw.take(40)}' of '${usage.name}' is not a literal of ${type.name}.",
                                hint = "Use one of ${type.literals.take(10)}.",
                            )
                    }
                }
                is AttributeDefinition -> {
                    val typeUnit = type.unit
                    val m = WITH_UNIT.matchEntire(raw)
                    if (m != null && typeUnit != null && m.groupValues[1] != typeUnit) {
                        out +=
                            ValueIssue(
                                code = 501,
                                elementId = usage.id,
                                message =
                                    "Default of '${usage.name}' is in '${m.groupValues[1]}', " +
                                        "but ${type.name} is measured in '$typeUnit'.",
                                hint = "Write the default in $typeUnit or change the unit of ${type.name}.",
                            )
                    }
                }
                else -> Unit
            }
        }
        return out
    }
}
