package dev.kuml.sysml2.projection

import dev.kuml.sysml2.FlowPropertyUsage
import dev.kuml.sysml2.PartUsage
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Model

/**
 * One resolved end of a connection, flow or binding inside an IBD.
 *
 * @property nodeId visible part node the end sits on; `null` for a port of the
 *   owner itself (see [ownerPort]).
 * @property ownerPort the owner's boundary port when the end is `Owner::port`.
 * @property steps path segments after the node (or after the owner for an owner port).
 * @property terminalPort port usage reached at the end of [steps], if any.
 * @property terminalFlowProperty flow property reached at the end of [steps], if any.
 */
data class ResolvedEnd(
    val nodeId: String?,
    val ownerPort: PortUsage?,
    val steps: List<String>,
    val terminalPort: PortUsage?,
    val terminalFlowProperty: FlowPropertyUsage?,
)

/**
 * Resolves qualified endpoint ids (`Owner::part::port`, `Owner::port`,
 * `Owner::part::sub::port`) against a [Sysml2Model]. Only the given segments are
 * walked, so resolution terminates even for recursive types.
 */
object Sysml2PathResolver {
    const val MAX_SEGMENTS: Int = 8
    const val MAX_PATH_CHARS: Int = 512

    fun resolve(
        model: Sysml2Model,
        ownerId: String,
        endpointId: String,
        visibleNodeIds: Set<String>,
    ): ResolvedEnd? {
        if (endpointId.length > MAX_PATH_CHARS) return null
        val nodeId =
            visibleNodeIds
                .filter { endpointId == it || endpointId.startsWith("$it::") }
                .maxByOrNull { it.length }
        if (nodeId != null) {
            val steps = splitSteps(endpointId.removePrefix(nodeId).removePrefix("::")) ?: return null
            val start =
                model.usages
                    .filterIsInstance<PartUsage>()
                    .firstOrNull { it.id == nodeId }
                    ?.definitionId
            val (port, fp) = walk(model = model, startDefId = start, steps = steps)
            return ResolvedEnd(nodeId = nodeId, ownerPort = null, steps = steps, terminalPort = port, terminalFlowProperty = fp)
        }
        val prefix = "$ownerId::"
        if (!endpointId.startsWith(prefix)) return null
        val steps = splitSteps(endpointId.removePrefix(prefix)) ?: return null
        if (steps.isEmpty()) return null
        val port =
            model.usages.filterIsInstance<PortUsage>().firstOrNull { it.id == prefix + steps.first() } ?: return null
        val (_, fp) = walkFromPort(model = model, port = port, rest = steps.drop(1))
        return ResolvedEnd(nodeId = null, ownerPort = port, steps = steps.drop(1), terminalPort = port, terminalFlowProperty = fp)
    }

    /** `engine.crank.out` -> `<ownerId>::engine::crank::out`; strings with `::` or without `.` pass through. */
    fun toQualified(
        ownerId: String,
        dotted: String,
    ): String {
        if (dotted.contains("::") || !dotted.contains('.')) return dotted
        require(dotted.length <= MAX_PATH_CHARS) { "path longer than $MAX_PATH_CHARS characters" }
        val segs = dotted.split('.')
        require(segs.size <= MAX_SEGMENTS) { "path has more than $MAX_SEGMENTS segments" }
        require(segs.none { it.isBlank() }) { "path '$dotted' contains an empty segment" }
        require(dotted.none { it.isISOControl() }) { "path contains control characters" }
        return (listOf(ownerId) + segs).joinToString("::")
    }

    private fun splitSteps(rest: String): List<String>? {
        if (rest.isEmpty()) return emptyList()
        val parts = rest.split("::")
        if (parts.size > MAX_SEGMENTS) return null
        return parts
    }

    private fun walk(
        model: Sysml2Model,
        startDefId: String?,
        steps: List<String>,
    ): Pair<PortUsage?, FlowPropertyUsage?> {
        var def = startDefId ?: return null to null
        var i = 0
        while (i < steps.size) {
            val id = "$def::${steps[i]}"
            val usage = model.usages.firstOrNull { it.id == id } ?: return null to null
            when (usage) {
                is PartUsage -> def = usage.definitionId
                is PortUsage -> return walkFromPort(model = model, port = usage, rest = steps.drop(i + 1))
                else -> return null to null
            }
            i++
        }
        return null to null
    }

    private fun walkFromPort(
        model: Sysml2Model,
        port: PortUsage,
        rest: List<String>,
    ): Pair<PortUsage?, FlowPropertyUsage?> {
        if (rest.isEmpty()) return port to null
        val fp =
            model.usages.filterIsInstance<FlowPropertyUsage>().firstOrNull { it.id == "${port.definitionId}::${rest.first()}" }
        return port to fp
    }
}
