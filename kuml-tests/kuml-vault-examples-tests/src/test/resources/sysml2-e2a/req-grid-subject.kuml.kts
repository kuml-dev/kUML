// Wave 2E-a fixture: four requirement groups in a grid, nested subgroup, block rail, external requirement.
sysml2Model("Platform Requirements") {
    val platform = partDef("Platform")
    val sensor = partDef("SensorUnit")

    val boot = requirementDef("Boot", reqId = "FB-001", text = "The platform shall boot within five seconds.", subjectRef = "Platform")
    val update = requirementDef("Update", reqId = "FB-002", text = "Updates shall install without user interaction.")
    val rollback = requirementDef("Rollback", reqId = "FB-003", text = "A failed update shall roll back automatically.")

    val auth = requirementDef("Auth", reqId = "FB-004", text = "Every request shall be authenticated.", subjectRef = "Platform")
    val audit = requirementDef("Audit", reqId = "FB-005", text = "Security events shall be audited.")
    val failsafe = requirementDef("FailSafe", reqId = "FB-006", text = "A sensor fault shall switch the unit to the safe state.", subjectRef = "SensorUnit")
    val watchdog = requirementDef("Watchdog", reqId = "FB-007", text = "A watchdog shall reset a stalled unit.", subjectRef = "Platform")

    val latency = requirementDef("Latency", reqId = "FB-008", text = "A sensor value shall reach the platform within 20 ms.")
    val scale = requirementDef("Scale", reqId = "FB-009", text = "The platform shall scale to ten thousand users.")
    val rate = requirementDef("SampleRate", reqId = "FB-010", text = "The sensor shall sample at 1 kHz.")

    val logs = requirementDef("Logging", reqId = "FB-011", text = "Logs shall be kept for 30 days.")
    val backup = requirementDef("Backup", reqId = "FB-012", text = "Backups shall run nightly.")
    val localCopy = requirementDef("LocalDeadline", reqId = "PAL-FB-007", text = "Restore shall complete within two hours.")
    val ext = externalRequirement("PAL-FB-007", source = "Dokument 11", text = "Restore shall complete within one hour.")

    comment("Boot time is measured on the reference hardware.", boot)

    reqDiagram("Platform requirements") {
        include(platform)
        include(sensor)
        val a = group("Functional") {
            include(boot)
            include(update)
            include(rollback)
        }
        val b = group("Safety") {
            include(auth)
            include(audit)
            group("Fail-safe") {
                include(failsafe)
                include(watchdog)
            }
        }
        val c = group("Performance") {
            include(latency)
            include(scale)
            include(rate)
        }
        val d = group("Operations") {
            include(logs)
            include(backup)
            include(localCopy)
        }
        include(ext)

        satisfy(platform, boot)
        satisfy(platform, update)
        satisfy(platform, rollback)
        satisfy(platform, auth)
        satisfy(platform, audit)
        satisfy(platform, scale)
        satisfy(platform, logs)
        satisfy(sensor, failsafe)
        satisfy(sensor, watchdog)
        satisfy(sensor, latency)
        satisfy(sensor, rate)
        satisfy(platform, backup)
        satisfy(platform, localCopy)

        contains(boot, update)
        contains(boot, rollback)
        contains(auth, audit)
        contains(latency, rate)
        contains(logs, backup)
        derive(localCopy, ext)
        layout {
            wrapColumns = 2
            showSubjectEdges = true
        }
    }
}
