package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.IbdDiagram
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
        val unresolved = sysml2IbdProjection(model = model, diagram = diagram).unresolvedEnds
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
