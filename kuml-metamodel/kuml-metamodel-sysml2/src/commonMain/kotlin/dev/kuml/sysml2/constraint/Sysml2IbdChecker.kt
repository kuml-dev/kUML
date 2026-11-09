package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.ConnectionDefinition
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PortUsage
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.projection.sysml2IbdProjection

/** Severity of an [IbdIssue]. */
public enum class IbdSeverity { Error, Warning }

/** A finding of the internal block diagram checker. */
public data class IbdIssue(
    val code: Int,
    val severity: IbdSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for internal block diagrams (PAL wave 2C-b3).
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 501 | Warning | an end of a connection or flow does not resolve to a part, port or owner port; the edge is not drawn |
 * | 502 | Warning | a port and an attribute of one part definition share a name |
 * | 503 | Warning | connection definitions with the same name are used in the diagram; edges cannot be told apart by label |
 *
 * The rule reuses the projection's `unresolvedEnds`, so the checker and the renderer cannot disagree about which edge is
 * dropped. At most [MAX_ISSUES] findings are reported; the rest is summed up in one message (denial-of-service guard).
 */
public object Sysml2IbdChecker {
    public const val MAX_ISSUES: Int = 256

    public fun check(
        model: Sysml2Model,
        diagram: IbdDiagram,
    ): List<IbdIssue> {
        val projection = sysml2IbdProjection(model = model, diagram = diagram)
        val unresolved = projection.unresolvedEnds
        return unresolvedIssues(model = model, diagram = diagram, unresolved = unresolved) +
            portAttributeClashes(model = model, diagram = diagram, projection = projection) +
            duplicateConnectionDefinitions(model = model, projection = projection)
    }

    private fun portAttributeClashes(
        model: Sysml2Model,
        diagram: IbdDiagram,
        projection: dev.kuml.sysml2.projection.IbdProjection,
    ): List<IbdIssue> {
        val defIds = LinkedHashSet<String>()
        defIds += diagram.ownerId
        projection.partNodes.mapNotNullTo(defIds) { it.typeId }
        val visibleIds = projection.partNodes.map { it.id }.toSet()
        for (path in projection.nestedPartPaths) {
            dev.kuml.sysml2.projection.Sysml2PathResolver
                .definitionOfPath(model = model, path = path, visibleNodeIds = visibleIds)
                ?.let { defIds += it }
        }
        val issues = ArrayList<IbdIssue>()
        for (defId in defIds) {
            val own = model.usages.filter { it.id.startsWith("$defId::") && !it.id.removePrefix("$defId::").contains("::") }
            val attributeIds = own.filterIsInstance<AttributeUsage>().map { it.id }.toSet()
            for (port in own.filterIsInstance<PortUsage>().filter { it.id in attributeIds }.distinctBy { it.id }) {
                if (issues.size >= MAX_ISSUES) return issues
                val name = port.name.take(Sysml2Limits.TEXT)
                issues +=
                    IbdIssue(
                        code = 502,
                        severity = IbdSeverity.Warning,
                        elementId = port.id,
                        message =
                            "Port '$name' and attribute '$name' in '${defId.take(Sysml2Limits.TEXT)}' share a name; " +
                                "rename one to keep them distinguishable.",
                        hint = "Rename the port or the attribute.",
                    )
            }
        }
        return issues
    }

    private fun duplicateConnectionDefinitions(
        model: Sysml2Model,
        projection: dev.kuml.sysml2.projection.IbdProjection,
    ): List<IbdIssue> {
        val used = projection.connections.map { it.usage.definitionId }.toSet()
        val issues = ArrayList<IbdIssue>()
        for ((name, defs) in model.definitions.filterIsInstance<ConnectionDefinition>().groupBy { it.name }) {
            if (defs.size < 2 || defs.none { it.id in used }) continue
            if (issues.size >= MAX_ISSUES) break
            issues +=
                IbdIssue(
                    code = 503,
                    severity = IbdSeverity.Warning,
                    elementId = defs.first().id,
                    message =
                        "Connection definitions '${name.take(Sysml2Limits.TEXT)}' are defined more than once; " +
                            "edges cannot be told apart by label.",
                    hint = "Give the connection definitions different names.",
                )
        }
        return issues
    }

    private fun unresolvedIssues(
        model: Sysml2Model,
        diagram: IbdDiagram,
        unresolved: List<dev.kuml.sysml2.projection.IbdUnresolvedEnd>,
    ): List<IbdIssue> {
        if (unresolved.isEmpty()) return emptyList()
        val owner = model.definitions.firstOrNull { it.id == diagram.ownerId }?.name ?: diagram.ownerId
        val ownerShown = owner.take(Sysml2Limits.TEXT)
        val hint = "Use 'Owner::port', 'part.port' or a bare owner-port name."

        fun issueOf(e: dev.kuml.sysml2.projection.IbdUnresolvedEnd) =
            IbdIssue(
                code = 501,
                severity = IbdSeverity.Warning,
                elementId = e.usageId,
                message =
                    "End '${e.endpointId.take(Sysml2Limits.TEXT)}' of '${e.usageId.take(Sysml2Limits.TEXT)}' does not " +
                        "resolve to a part, port or owner port of '$ownerShown'; the edge is not drawn.",
                hint = hint,
            )
        if (unresolved.size <= MAX_ISSUES) return unresolved.map(::issueOf)
        val rest = unresolved.size - (MAX_ISSUES - 1)
        return unresolved.take(MAX_ISSUES - 1).map(::issueOf) +
            IbdIssue(
                code = 501,
                severity = IbdSeverity.Warning,
                elementId = diagram.ownerId,
                message = "$rest more unresolved ends in '$ownerShown' are not listed.",
                hint = hint,
            )
    }
}
