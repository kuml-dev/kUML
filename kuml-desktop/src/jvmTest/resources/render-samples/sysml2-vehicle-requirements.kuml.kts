import dev.kuml.sysml2.dsl.sysml2Model

sysml2Model(name = "VehicleRequirements") {

    // ── Requirements ────────────────────────────────────────────────────
    val topSpeed = requirementDef(
        name = "TopSpeedRequirement",
        reqId = "R-001",
        text = "The vehicle shall reach at least 180 km/h on flat road",
        subject = "Vehicle",
    )
    val curbWeight = requirementDef(
        name = "CurbWeightRequirement",
        reqId = "R-002",
        text = "The vehicle curb weight shall not exceed 1500 kg",
        subject = "Vehicle",
    )
    val fuelEfficiency = requirementDef(
        name = "FuelEfficiencyRequirement",
        reqId = "R-003",
        text = "The vehicle shall consume less than 4 l/100km combined",
        subject = "Vehicle",
    )
    val emissions = requirementDef(
        name = "EmissionsRequirement",
        reqId = "R-004",
        text = "The vehicle shall comply with Euro 7 emissions standards",
        subject = "Vehicle",
    )
    val nox = requirementDef(
        name = "NOxRequirement",
        reqId = "R-005",
        text = "NOx emissions shall not exceed 30 mg/km",
        subject = "Vehicle",
    )

    // ── Satisfying design element ───────────────────────────────────────
    val vehicle = partDef(name = "Vehicle")

    // ── Verifying use case ──────────────────────────────────────────────
    val verifyTopSpeed = useCaseDef(name = "VerifyTopSpeed")

    // ── Requirement Diagram ─────────────────────────────────────────────
    reqDiagram(name = "Vehicle — top-level requirements") {
        // Nodes
        include(definition = topSpeed)
        include(definition = curbWeight)
        include(definition = fuelEfficiency)
        include(definition = emissions)
        include(definition = nox)
        include(definition = vehicle)
        include(definition = verifyTopSpeed)

        // Satisfy: Vehicle erfüllt R-001 / R-002 / R-003
        satisfy(source = vehicle, requirement = topSpeed)
        satisfy(source = vehicle, requirement = curbWeight)
        satisfy(source = vehicle, requirement = fuelEfficiency)

        // Verify: VerifyTopSpeed prüft R-001
        verify(source = verifyTopSpeed, requirement = topSpeed)

        // Derive: R-001 leitet sich aus R-003 ab
        // (Endgeschwindigkeit vs. Verbrauch ist ein klassischer Trade-off)
        derive(source = topSpeed, target = fuelEfficiency)

        // Contains: R-004 dekomponiert in R-005
        contains(parent = emissions, child = nox)
    }
}
