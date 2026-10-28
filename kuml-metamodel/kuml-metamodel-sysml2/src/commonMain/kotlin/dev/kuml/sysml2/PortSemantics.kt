package dev.kuml.sysml2

/**
 * Effective direction of the flow property [flowPropertyId] as seen through
 * this port. Conjugation (`~PortDef`) swaps [PortDirection.In] and
 * [PortDirection.Out]; [PortDirection.InOut] is unaffected.
 *
 * The flow property is looked up among the usages of the port's definition
 * (id = `<portDefId>::<name>` or a bare name). Returns `null` if the property
 * is unknown.
 */
fun PortUsage.effectiveDirection(
    model: Sysml2Model,
    flowPropertyId: String,
): PortDirection? {
    val fp =
        model.usages.filterIsInstance<FlowPropertyUsage>().firstOrNull {
            it.id == flowPropertyId ||
                (it.id.startsWith("$definitionId::") && it.name == flowPropertyId)
        } ?: return null
    return if (!isConjugated) {
        fp.direction
    } else {
        when (fp.direction) {
            PortDirection.In -> PortDirection.Out
            PortDirection.Out -> PortDirection.In
            PortDirection.InOut -> PortDirection.InOut
        }
    }
}
