package dev.kuml.layout.bridge.label

private var currentState: PortLabelAudit.State? = null

internal actual fun currentAuditState(): PortLabelAudit.State? = currentState

internal actual fun setAuditState(state: PortLabelAudit.State?) {
    currentState = state
}
