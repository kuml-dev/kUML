package dev.kuml.sysml2.projection

import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.EnumerationDefinition
import dev.kuml.sysml2.FlowPropertyUsage
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Model

/**
 * Text lines of one BDD box. Shared by the layout bridge (size estimation) and
 * the SVG renderer, so box height and drawn content can not drift apart.
 *
 * @property featureLines lines of the feature compartment (hidden features removed).
 * @property widthProbe same lines without default expressions (legacy width estimate).
 * @property actions `name()` lines of the `actions` compartment.
 * @property flows `<direction> <name> : <Type>` lines of the `flows` compartment.
 */
data class BddBoxContent(
    val featureLines: List<String>,
    val widthProbe: List<String>,
    val actions: List<String>,
    val flows: List<String>,
)

fun sysml2BddBoxContent(
    model: Sysml2Model,
    def: Sysml2Definition,
    projection: BddProjection?,
): BddBoxContent {
    val hidden = projection?.hiddenFeatureIds ?: emptySet()
    val full = mutableListOf<String>()
    val probe = mutableListOf<String>()
    // Wave 2E-b: the literals of an enumeration are its feature lines.
    if (def is EnumerationDefinition) {
        full += def.literals
        probe += def.literals
    }
    for (f in def.features) {
        if (f.id in hidden) continue
        val usage = model.usages.firstOrNull { it.id == f.id }
        full += formatFeature(f = f, usage = usage, withDefault = true, model = model)
        probe += formatFeature(f = f, usage = usage, withDefault = false, model = model)
    }
    if (def is AttributeDefinition && def.unit != null) {
        full += "unit = ${def.unit}"
        probe += "unit = ${def.unit}"
    }
    val actions = projection?.actionsByDef?.get(def.id)?.map { "$it()" } ?: emptyList()
    val flows = projection?.flowsByDef?.get(def.id) ?: emptyList()
    return BddBoxContent(featureLines = full, widthProbe = probe, actions = actions, flows = flows)
}

private fun dirWord(d: PortDirection?): String =
    when (d) {
        PortDirection.In -> "in "
        PortDirection.Out -> "out "
        PortDirection.InOut -> "inout "
        null -> ""
    }

/** Non-breaking space between a number and its unit (wave 2E-b); display only, never stored. */
const val NBSP: Char = '\u00A0'

private val NUMBER_WITH_UNIT = Regex("""^\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)\s*(?:\[([^\]]*)\])?\s*$""")
private val INTEGRAL_NUMBER = Regex("""^-?[0-9]+\.0+$""")

/**
 * Display form of an attribute default (wave 2E-b): `-5 Pa` for the stored `-5.0` (unit from the attribute type) as well
 * as for the legacy `-5.0[Pa]`. Integral numbers lose the `.0`; the number is never formatted through the platform
 * locale (the decimal separator is always `.`, the minus is ASCII). Text that is not a number (an enumeration literal)
 * is returned unchanged.
 */
fun formatAttributeValue(
    raw: String,
    typeUnit: String?,
): String {
    val m = NUMBER_WITH_UNIT.matchEntire(raw) ?: return raw
    var number = m.groupValues[1]
    if (INTEGRAL_NUMBER.matches(number)) number = number.substringBefore('.')
    val unit = m.groups[2]?.value?.takeIf { it.isNotBlank() } ?: typeUnit?.takeIf { it.isNotBlank() }
    return if (unit == null) number else "$number$NBSP$unit"
}

private fun formatFeature(
    f: KermlFeature,
    usage: Any?,
    withDefault: Boolean,
    model: Sysml2Model,
): String {
    var type = f.typeId ?: "?"
    var prefix = ""
    when (usage) {
        is PortUsage -> {
            prefix = dirWord(usage.direction)
            if (usage.isConjugated) type = "~$type"
        }
        is FlowPropertyUsage -> prefix = dirWord(usage.direction)
        is PartUsage -> if (usage.isReference) prefix = "ref "
    }
    val multSuffix = if (f.multiplicity.toSpecForm() == "1") "" else " [${f.multiplicity.toSpecForm()}]"
    val typeUnit = (model.definitions.firstOrNull { it.id == f.typeId } as? AttributeDefinition)?.unit
    val defaultSuffix =
        if (withDefault) f.defaultExpression?.let { " = ${formatAttributeValue(raw = it, typeUnit = typeUnit)}" } ?: "" else ""
    return "$prefix${f.name} : $type$multSuffix$defaultSuffix"
}

/**
 * Vertical centre (from the top of the box) of the [index]th line of the `actions` compartment of a BDD box (wave 2E-b).
 * Mirrors the line arithmetic of the SVG box renderer (stereotype 16, name +14 +12, divider +14, 13 px per line), so
 * the layout can dock an allocation edge at the row of its action. Lines of the feature compartment come first.
 */
fun bddActionRowCenter(
    content: BddBoxContent,
    index: Int,
): Float {
    var cy = 16f + 14f + 12f
    if (content.featureLines.isNotEmpty()) cy += 14f + 13f * content.featureLines.size
    // actions compartment: divider (+14) -> header baseline, header (+13) -> first line baseline
    val baseline = cy + 14f + 13f * (index + 1)
    return baseline - 4f
}
