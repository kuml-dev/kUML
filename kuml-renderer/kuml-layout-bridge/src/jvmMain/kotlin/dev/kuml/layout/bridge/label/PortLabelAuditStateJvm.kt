package dev.kuml.layout.bridge.label

private val STATE = ThreadLocal<PortLabelAudit.State?>()

internal actual fun currentAuditState(): PortLabelAudit.State? = STATE.get()

internal actual fun setAuditState(state: PortLabelAudit.State?) {
    if (state == null) STATE.remove() else STATE.set(state)
}
