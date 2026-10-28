import dev.kuml.sysml2.RequirementPriority
import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model("Platform Requirements") {
    val reqs = listOf(
        "Boot" to "The platform shall boot within 5 seconds.",
        "Update" to "Updates shall install without user interaction.",
        "Rollback" to "A failed update shall roll back automatically.",
        "Telemetry" to "Telemetry shall be opt-in.",
        "Logging" to "Logs shall be kept for 30 days.",
        "Auth" to "Every request shall be authenticated.",
        "Audit" to "Security events shall be audited.",
        "Encrypt" to "Data at rest shall be encrypted.",
        "Backup" to "Backups shall run nightly.",
        "Restore" to "A restore shall complete within one hour.",
        "Scale" to "The platform shall scale to 10 000 users.",
        "Monitor" to "Health shall be monitored continuously.",
    ).mapIndexed { i, (name, text) ->
        requirementDef(name, reqId = "R-${i + 1}", text = text, priority = RequirementPriority.MEDIUM)
    }
    reqDiagram("Platform requirements") {
        reqs.forEach { include(it) }
        layout {
            wrapColumns = 4
        }
    }
}
