package dev.kuml.sysml2.projection

import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.AttributeDefinition
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
    for (f in def.features) {
        if (f.id in hidden) continue
        val usage = model.usages.firstOrNull { it.id == f.id }
        full += formatFeature(f = f, usage = usage, withDefault = true)
        probe += formatFeature(f = f, usage = usage, withDefault = false)
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

private fun formatFeature(
    f: KermlFeature,
    usage: Any?,
    withDefault: Boolean,
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
    val defaultSuffix = if (withDefault) f.defaultExpression?.let { " = $it" } ?: "" else ""
    return "$prefix${f.name} : $type$multSuffix$defaultSuffix"
}
