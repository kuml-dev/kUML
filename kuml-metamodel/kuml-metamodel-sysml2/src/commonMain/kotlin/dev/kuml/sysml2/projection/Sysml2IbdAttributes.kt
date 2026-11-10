package dev.kuml.sysml2.projection

import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.Sysml2Model

/** One line of the attribute compartment of an IBD part: `name : Type = value` (PAL wave 2C-b2). */
data class IbdAttributeLine(
    val name: String,
    val typeName: String,
    val value: String?,
) {
    /** The text drawn in the compartment; ` = value` only when a value exists. */
    val text: String get() = if (value == null) "$name : $typeName" else "$name : $typeName = $value"
}

/** The attribute compartment of one IBD part: at most [MAX_LINES] lines, the rest only counted. */
data class IbdAttributeCompartment(
    val lines: List<IbdAttributeLine>,
    val hiddenCount: Int,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && hiddenCount == 0

    companion object {
        const val MAX_LINES: Int = 8
        val EMPTY: IbdAttributeCompartment = IbdAttributeCompartment(lines = emptyList(), hiddenCount = 0)
    }
}

/**
 * Attributes of the part [usage]: those of its definition and the redefinitions on the usage itself
 * (`<usage id>::<attribute>`), which override the definition's attribute of the same name and carry their value.
 * Deterministic: definition order, redefinitions replace in place. Single source for layout bridge and SVG renderer.
 */
fun ibdPartAttributes(
    model: Sysml2Model,
    usage: PartUsage,
): IbdAttributeCompartment {
    val defPrefix = "${usage.definitionId}::"
    val usagePrefix = "${usage.id}::"
    val byName = LinkedHashMap<String, IbdAttributeLine>()
    for (a in model.usages.filterIsInstance<AttributeUsage>()) {
        if (!a.id.startsWith(defPrefix) || a.id.removePrefix(defPrefix).contains("::")) continue
        byName[a.name] = line(model = model, a = a)
    }
    for (a in model.usages.filterIsInstance<AttributeUsage>()) {
        if (!a.id.startsWith(usagePrefix) || a.id.removePrefix(usagePrefix).contains("::")) continue
        val own = line(model = model, a = a)
        byName[a.name] = if (own.value == null) own.copy(value = byName[a.name]?.value) else own
    }
    val all = byName.values.toList()
    val shown = all.take(IbdAttributeCompartment.MAX_LINES)
    return IbdAttributeCompartment(lines = shown, hiddenCount = all.size - shown.size)
}

private fun line(
    model: Sysml2Model,
    a: AttributeUsage,
): IbdAttributeLine =
    IbdAttributeLine(
        name = a.name,
        typeName = model.definitions.firstOrNull { it.id == a.definitionId }?.name ?: a.definitionId,
        value =
            a.defaultExpression?.takeIf { it.isNotBlank() }?.let {
                formatAttributeValue(
                    raw = it,
                    typeUnit = (model.definitions.firstOrNull { d -> d.id == a.definitionId } as? AttributeDefinition)?.unit,
                    isLiteral = isDefaultLiteral(metadata = a.metadata),
                )
            },
    )
