package dev.kuml.sysml2.projection

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.kerml.KermlFeature
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.AnalysisCaseDefinition
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.CalcParameter
import dev.kuml.sysml2.CalcParameterDirection
import dev.kuml.sysml2.CalculationDefinition
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.EnumerationDefinition
import dev.kuml.sysml2.FlowPropertyUsage
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortDefinition
import dev.kuml.sysml2.PortDirection
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Definition
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model

/**
 * Text lines of one BDD box. Shared by the layout bridge (size estimation) and
 * the SVG renderer, so box height and drawn content can not drift apart.
 *
 * @property featureLines lines of the feature compartment (hidden features removed).
 * @property actions `name()` lines of the `actions` compartment.
 * @property flows `<direction> <name> : <Type>` lines of the `flows` compartment.
 */
data class BddBoxContent(
    val featureLines: List<String>,
    val actions: List<String>,
    val flows: List<String>,
    /** Further compartments (wave 2I-b: calc / analysis / perform / exhibit), drawn after `actions` and `flows`. */
    val compartments: List<BddCompartment> = emptyList(),
)

/**
 * One extra compartment of a BDD box (wave 2I-b): a [header] line and the [lines] below it. [monospace] lines are drawn
 * in a monospaced font (calculation expressions).
 */
data class BddCompartment(
    val header: String,
    val lines: List<String>,
    val monospace: Boolean = false,
)

/**
 * Vertical geometry of the feature compartment of a BDD-style box (`«part def»` etc.), shared by the SVG renderer and
 * the layout bridge so that an edge can end at the centre of a feature row (parametric diagrams, part attributes).
 * All values in px from the top of the box.
 */
object BddBoxGeometry {
    /** Baseline of the stereotype line. */
    const val STEREOTYPE_BASELINE_Y: Float = 16f

    /** Distance from the stereotype baseline to the name baseline. */
    const val NAME_BASELINE_STEP: Float = 14f

    /** Distance from the name baseline to the divider above the feature compartment. */
    const val DIVIDER_STEP: Float = 12f

    /** Distance from the divider to the first feature baseline. */
    const val FIRST_BASELINE_STEP: Float = 14f

    /** Pitch of the feature baselines. */
    const val FEATURE_LINE_H: Float = 13f

    /** The visual centre of a feature row lies this far above its baseline. */
    const val ROW_CENTRE_ABOVE_BASELINE: Float = 4f

    /** Y of the divider above the feature compartment. */
    const val DIVIDER_Y: Float = STEREOTYPE_BASELINE_Y + NAME_BASELINE_STEP + DIVIDER_STEP

    /** Y of the visual centre of feature row [index] (0-based), measured from the top of the box. */
    fun featureRowCentreY(index: Int): Float = DIVIDER_Y + FIRST_BASELINE_STEP - ROW_CENTRE_ABOVE_BASELINE + index * FEATURE_LINE_H
}

/** Characters per line before the `expression` and `objective` compartments of a BDD box wrap (wave 2I-b). */
const val BDD_WRAP_CHARS: Int = 34

/**
 * Breaks [text] into lines of at most [width] characters, at spaces where possible and inside a word only when the word
 * is longer than [width]. At most [BddProjection.MAX_COMPARTMENT_LINES] lines are returned; when the text is longer, the
 * last line ends with an ellipsis. Pure and deterministic.
 */
fun wrapBddText(
    text: String,
    width: Int = BDD_WRAP_CHARS,
): List<String> {
    val out = mutableListOf<String>()
    var cur = StringBuilder()
    // Hostile input: look at no more than twice what can be drawn; anything beyond counts as truncated.
    val limit = width * BddProjection.MAX_COMPARTMENT_LINES * 2
    val source = if (text.length > limit) text.take(limit) else text
    var truncated = text.length > limit

    fun flush() {
        if (cur.isNotEmpty()) out += cur.toString()
        cur = StringBuilder()
    }
    loop@ for (word in source.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        var rest = word
        while (rest.length > width) {
            flush()
            out += rest.take(width)
            rest = rest.drop(width)
            if (out.size >= BddProjection.MAX_COMPARTMENT_LINES) {
                truncated = true
                break@loop
            }
        }
        if (cur.isNotEmpty() && cur.length + 1 + rest.length > width) flush()
        if (out.size >= BddProjection.MAX_COMPARTMENT_LINES) {
            truncated = true
            break
        }
        if (cur.isNotEmpty()) cur.append(' ')
        cur.append(rest)
    }
    if (!truncated) flush()
    if (truncated && out.size < BddProjection.MAX_COMPARTMENT_LINES) flush()
    val capped = out.take(BddProjection.MAX_COMPARTMENT_LINES).toMutableList()
    if (truncated && capped.isNotEmpty()) capped[capped.lastIndex] = capped.last().take(width - 1) + "…"
    return capped
}

fun sysml2BddBoxContent(
    model: Sysml2Model,
    def: Sysml2Definition,
    projection: BddProjection?,
): BddBoxContent {
    val hidden = projection?.hiddenFeatureIds ?: emptySet()
    val full = mutableListOf<String>()
    // Wave 2E-b: the literals of an enumeration are its feature lines.
    if (def is EnumerationDefinition) {
        full += def.literals
    }
    for (f in def.features) {
        if (f.id in hidden) continue
        val usage = model.usages.firstOrNull { it.id == f.id }
        full += formatFeature(f = f, usage = usage, withDefault = true, model = model, drawnIds = projection?.drawnDefinitionIds)
    }
    if (def is AttributeDefinition && def.unit != null) {
        full += "unit = ${def.unit}"
    }
    val actions = projection?.actionsByDef?.get(def.id)?.map { "$it()" } ?: emptyList()
    val flows = projection?.flowsByDef?.get(def.id) ?: emptyList()
    return BddBoxContent(
        featureLines = full,
        actions = actions,
        flows = flows,
        compartments = analysisCompartments(model = model, def = def, projection = projection),
    )
}

private fun nameOf(
    model: Sysml2Model,
    id: String,
): String = model.definitions.firstOrNull { it.id == id }?.name ?: id

private fun CalcParameter.typed(): String = if (typeName.isEmpty()) name else "$name : $typeName"

/** At most [BddProjection.MAX_COMPARTMENT_LINES] lines, each clipped to [Sysml2Limits.CALC_TEXT] characters (hostile models). */
private fun cap(lines: List<String>): List<String> =
    lines.take(BddProjection.MAX_COMPARTMENT_LINES).map {
        if (it.length > Sysml2Limits.CALC_TEXT) it.take(Sysml2Limits.CALC_TEXT - 1) + "…" else it
    }

/** Extra compartments of calculations, analysis cases and parts with `perform` / `exhibit` (wave 2I-b), in declaration order. */
private fun analysisCompartments(
    model: Sysml2Model,
    def: Sysml2Definition,
    projection: BddProjection?,
): List<BddCompartment> {
    val out = mutableListOf<BddCompartment>()
    when (def) {
        is CalculationDefinition -> {
            if (def.parameters.isNotEmpty()) {
                out +=
                    BddCompartment(
                        header = "parameters",
                        lines =
                            cap(
                                def.parameters.take(BddProjection.MAX_COMPARTMENT_LINES).map {
                                    val dir =
                                        when (it.direction) {
                                            CalcParameterDirection.IN -> "in"
                                            CalcParameterDirection.OUT -> "out"
                                            CalcParameterDirection.INOUT -> "inout"
                                        }
                                    "$dir ${it.typed()}"
                                },
                            ),
                    )
            }
            if (def.expression.isNotBlank()) {
                out += BddCompartment(header = "expression", lines = wrapBddText(text = def.expression), monospace = true)
            }
            def.result?.let { out += BddCompartment(header = "return", lines = listOf(it.typed())) }
        }
        is AnalysisCaseDefinition -> {
            val subject = def.subjectId
            if (subject != null && def.id !in (projection?.drawnSubjects ?: emptySet())) {
                out += BddCompartment(header = "subject", lines = listOf("subject : ${nameOf(model = model, id = subject)}"))
            }
            if (def.objective.isNotBlank()) {
                out += BddCompartment(header = "objective", lines = wrapBddText(text = def.objective))
            }
            val drawn = projection?.drawnCalcs ?: emptySet()
            val calcLines =
                def.calculations
                    .filter { "${def.id}::${it.name}" !in drawn }
                    .take(BddProjection.MAX_COMPARTMENT_LINES)
                    .map { "${it.name} : ${nameOf(model = model, id = it.definitionId)}" }
            if (calcLines.isNotEmpty()) out += BddCompartment(header = "calcs", lines = cap(calcLines))
            def.result?.let { out += BddCompartment(header = "return", lines = listOf(it.typed())) }
        }
        is PartDefinition -> {
            if (def.performs.isNotEmpty()) {
                out +=
                    BddCompartment(
                        header = "perform actions",
                        lines =
                            cap(
                                def.performs.take(BddProjection.MAX_COMPARTMENT_LINES).map {
                                    "perform ${it.name} : ${nameOf(model = model, id = it.definitionId)}"
                                },
                            ),
                    )
            }
            if (def.exhibits.isNotEmpty()) {
                out +=
                    BddCompartment(
                        header = "exhibit states",
                        lines =
                            cap(
                                def.exhibits.take(BddProjection.MAX_COMPARTMENT_LINES).map {
                                    "exhibit ${it.name} : ${nameOf(model = model, id = it.definitionId)}"
                                },
                            ),
                    )
            }
        }
        else -> Unit
    }
    return out
}

private fun dirWord(d: PortDirection?): String =
    when (d) {
        PortDirection.In -> "in "
        PortDirection.Out -> "out "
        PortDirection.InOut -> "inout "
        null -> ""
    }

/**
 * Metadata key marking an attribute default that was declared as a verbatim literal (`defaultLiteral = "..."`). Such a
 * default is displayed unchanged and never run through the number / unit normalisation of [formatAttributeValue].
 */
const val DEFAULT_LITERAL_META_KEY: String = "kuml.defaultLiteral"

/** True when [metadata] carries the [DEFAULT_LITERAL_META_KEY] flag. */
fun isDefaultLiteral(metadata: Map<String, KumlMetaValue>): Boolean = metadata[DEFAULT_LITERAL_META_KEY] == KumlMetaValue.Flag(value = true)

/** Metadata key marking a derived attribute (`derived = true`), displayed with a leading `/`. */
const val DERIVED_META_KEY: String = "kuml.derived"

/** True when [metadata] carries the [DERIVED_META_KEY] flag. */
fun isDerived(metadata: Map<String, KumlMetaValue>): Boolean = metadata[DERIVED_META_KEY] == KumlMetaValue.Flag(value = true)

/** Metadata key marking an external block (`partDef(external = true)`), shown as `«part def, external»`. */
const val EXTERNAL_META_KEY: String = "kuml.external"

/** True when [metadata] carries the [EXTERNAL_META_KEY] flag. */
fun isExternal(metadata: Map<String, KumlMetaValue>): Boolean = metadata[EXTERNAL_META_KEY] == KumlMetaValue.Flag(value = true)

/**
 * Stereotype text of a BDD box (without the guillemets). Shared by the SVG renderer and the layout bridge so that box
 * width and drawn text can not drift apart.
 */
fun bddStereotype(def: Sysml2Definition): String {
    val base =
        when (def) {
            is EnumerationDefinition -> "enumeration"
            is ActionDefinition -> "action def"
            is AttributeDefinition -> "attribute def"
            is PortDefinition -> "port def"
            is ConnectionDefinition -> "connection def"
            is CalculationDefinition -> "calc def"
            is AnalysisCaseDefinition -> "analysis def"
            else -> "part def"
        }
    return if (def is PartDefinition && isExternal(metadata = def.metadata)) "$base, external" else base
}

/** Maximum number of enumeration literals shown in the compact `{A | B | C}` suffix. */
const val BDD_LITERAL_MAX: Int = 4

/** Maximum characters of one literal in the compact suffix (hostile input); longer literals end with an ellipsis. */
const val BDD_LITERAL_CHAR_CAP: Int = 16

/**
 * Compact literal list `" {A | B | C}"` (leading space) for an attribute typed by an enumeration whose own box is not
 * drawn. At most [max] literals, then `| +N` for the rest. Empty string for an empty list.
 */
fun compactLiteralSuffix(
    literals: List<String>,
    max: Int = BDD_LITERAL_MAX,
): String {
    if (literals.isEmpty()) return ""

    fun clip(l: String): String = if (l.length > BDD_LITERAL_CHAR_CAP) l.take(BDD_LITERAL_CHAR_CAP) + "…" else l
    val shown = literals.take(max).map { clip(l = it) }
    val rest = literals.size - shown.size
    val body = if (rest > 0) (shown + "+$rest").joinToString(separator = " | ") else shown.joinToString(separator = " | ")
    return " {$body}"
}

/** Non-breaking space between a number and its unit (wave 2E-b); display only, never stored. */
const val NBSP: Char = '\u00A0'

private val NUMBER_WITH_UNIT = Regex("""^\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)\s*(?:\[([^\]]*)\])?\s*$""")
private val INTEGRAL_NUMBER = Regex("""^-?[0-9]+\.0+$""")

/**
 * Display form of an attribute default (wave 2E-b): `-5 Pa` for the stored `-5.0` (unit from the attribute type) as well
 * as for the legacy `-5.0[Pa]`. Integral numbers lose the `.0`; the number is never formatted through the platform
 * locale (the decimal separator is always `.`, the minus is ASCII). Text that is not a number (an enumeration literal)
 * is returned unchanged. With [isLiteral] (a `defaultLiteral`) the text is always returned verbatim: no `.0` trimming,
 * no unit, no quotes.
 */
fun formatAttributeValue(
    raw: String,
    typeUnit: String?,
    isLiteral: Boolean = false,
): String {
    if (isLiteral) return raw
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
    drawnIds: Set<String>?,
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
        if (withDefault) {
            f.defaultExpression?.let {
                " = ${formatAttributeValue(raw = it, typeUnit = typeUnit, isLiteral = isDefaultLiteral(metadata = f.metadata))}"
            }
                ?: ""
        } else {
            ""
        }
    val enumDef = model.definitions.firstOrNull { it.id == f.typeId } as? EnumerationDefinition
    val literalSuffix =
        if (drawnIds != null && enumDef != null && f.typeId !in drawnIds) compactLiteralSuffix(literals = enumDef.literals) else ""
    val slash = if (isDerived(metadata = f.metadata)) "/" else ""
    return "$prefix$slash${f.name} : $type$literalSuffix$multSuffix$defaultSuffix"
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
