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

/**
 * The direction a port is drawn with (Nacharbeit 48, N-IBD-F e/f). A declared [PortUsage.direction] always wins (it is
 * already the direction "as drawn"). Without one, the direction is derived from the flow properties of the port's
 * definition, with conjugation applied by [effectiveDirection]: all properties in one direction give that direction,
 * properties in different directions give [PortDirection.InOut], no flow property gives `null`.
 */
fun PortUsage.effectivePortDirection(model: Sysml2Model): PortDirection? {
    if (direction != null) return direction
    val directions =
        model.usages
            .filterIsInstance<FlowPropertyUsage>()
            .filter { it.id.startsWith("$definitionId::") }
            .mapNotNull { effectiveDirection(model = model, flowPropertyId = it.id) }
            .toSet()
    return when (directions.size) {
        0 -> null
        1 -> directions.single()
        else -> PortDirection.InOut
    }
}
