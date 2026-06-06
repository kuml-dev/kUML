@file:Suppress("unused")

import dev.kuml.sysml2.dsl.sysml2Model

/**
 * Vehicle Requirements — SysML 2 Requirement Diagram example (V2.0.8 MVP).
 *
 * Zeigt die V2.0.8-Oberfläche end-to-end:
 *  - `requirement def`-Definitions mit `reqId`, `text` und optional `subject`.
 *  - **Satisfy** (`satisfy(part, req)`) — eine Konstruktion erfüllt eine
 *    Anforderung.
 *  - **Verify** (`verify(useCase, req)`) — ein Verifikations-UseCase prüft
 *    eine Anforderung.
 *  - **Derive** (`derive(child, parent)`) — eine Anforderung wird aus einer
 *    anderen abgeleitet.
 *  - **Contains** (`contains(parent, child)`) — eine Anforderung enthält eine
 *    Unter-Anforderung (Dekomposition).
 *
 * Domain: ein Fahrzeug-Anforderungs-Set mit fünf Requirements:
 *  - R-001 TopSpeedRequirement — Mindest-Endgeschwindigkeit
 *  - R-002 CurbWeightRequirement — Maximalgewicht
 *  - R-003 FuelEfficiencyRequirement — Verbrauchsobergrenze
 *  - R-004 EmissionsRequirement (Parent) — Euro-7-Konformität
 *  - R-005 NOxRequirement (Child von R-004) — NOx-Obergrenze
 *
 * Plus eine `Vehicle`-PartDefinition, die R-001/R-002/R-003 erfüllt, und ein
 * `VerifyTopSpeed`-UseCase, der R-001 verifiziert. R-001 wird aus R-003
 * abgeleitet (Endgeschwindigkeit ↔ Verbrauch sind ein Trade-off).
 *
 * Out of V2.0.8 scope (siehe Wave-Plan):
 *  - Stereotyp-Labels `«satisfy»`/`«verify»`/`«deriveReqt»` an den Kanten
 *    (V2.x SVG/TikZ-Polish)
 *  - Gestricheltes Linien-Styling für REQ-Edges
 *  - Constraint-Expression-Layer (formale OCL/Typed-Expression)
 *  - Subject-Edge-Inferenz aus dem `subject`-Feld
 */
sysml2Model("VehicleRequirements") {

    // ── Requirements ────────────────────────────────────────────────────────
    val topSpeed =
        requirementDef(
            "TopSpeedRequirement",
            reqId = "R-001",
            text = "The vehicle shall reach at least 180 km/h on flat road",
            subject = "Vehicle",
        )
    val curbWeight =
        requirementDef(
            "CurbWeightRequirement",
            reqId = "R-002",
            text = "The vehicle curb weight shall not exceed 1500 kg",
            subject = "Vehicle",
        )
    val fuelEfficiency =
        requirementDef(
            "FuelEfficiencyRequirement",
            reqId = "R-003",
            text = "The vehicle shall consume less than 4 l/100km combined",
            subject = "Vehicle",
        )
    val emissions =
        requirementDef(
            "EmissionsRequirement",
            reqId = "R-004",
            text = "The vehicle shall comply with Euro 7 emissions standards",
            subject = "Vehicle",
        )
    val nox =
        requirementDef(
            "NOxRequirement",
            reqId = "R-005",
            text = "NOx emissions shall not exceed 30 mg/km",
            subject = "Vehicle",
        )

    // ── Satisfying design element ──────────────────────────────────────────
    val vehicle = partDef("Vehicle")

    // ── Verifying use case ─────────────────────────────────────────────────
    val verifyTopSpeed = useCaseDef("VerifyTopSpeed")

    // ── Requirement Diagram ────────────────────────────────────────────────
    reqDiagram("Vehicle — top-level requirements") {
        // Nodes
        include(topSpeed)
        include(curbWeight)
        include(fuelEfficiency)
        include(emissions)
        include(nox)
        include(vehicle)
        include(verifyTopSpeed)

        // Satisfy: Vehicle satisfies R-001 / R-002 / R-003
        satisfy(vehicle, topSpeed)
        satisfy(vehicle, curbWeight)
        satisfy(vehicle, fuelEfficiency)

        // Verify: VerifyTopSpeed verifies R-001
        verify(verifyTopSpeed, topSpeed)

        // Derive: R-001 is derived from R-003 (top speed vs. fuel efficiency
        // is a classic trade-off — picking R-001 follows from the R-003 cap)
        derive(topSpeed, fuelEfficiency)

        // Contains: R-004 decomposes into R-005
        contains(emissions, nox)
    }
}
